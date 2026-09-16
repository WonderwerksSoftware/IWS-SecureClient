#!/usr/bin/python3 -I
"""Fixed IWS Linux launcher and enrollment controller; never accepts a command/URL."""
from datetime import datetime, timezone
import ctypes
import fcntl
import hashlib
import json
import os
from pathlib import Path
import pwd
import re
import shutil
import subprocess
import sys
import time
import urllib.request

LIB = Path('/usr/lib/iws-client')
STATE = Path('/var/lib/iws-client')
RUN = Path('/run/iws-client-v1')
BOOT = Path('/usr/share/iws-client/bootstrap')
NS = '/run/netns/iws-client-v1'
CA = LIB / 'iws-root-ca.crt'
SOCKET = 'unix:///run/iws-client-v1/transport.sock'
PORTAL = 'https://portal.iws.internal/'
TRANSPORT_HASH = '4eb4d7a2f5fe1a224362c68f6c8129502247635711db1970cc85ed921896780d'


class EnrollmentTransient(RuntimeError):
    """Enrollment may be retried with the same still-valid one-use material."""


class EnrollmentRejected(RuntimeError):
    """The controller rejected the one-use material; it must not be reused."""


class SetupPaths:
    """Fixed filesystem boundary for package preparation and privileged setup."""

    def __init__(self, lib=LIB, state=STATE, run=RUN, boot=BOOT,
                 pending=Path('/var/lib/iws-client-bootstrap'),
                 archives=Path('/var/lib/iws-client-recovery'),
                 public_status=Path('/var/lib/iws-client-setup.json')):
        self.lib = Path(lib)
        self.state = Path(state)
        self.run = Path(run)
        self.boot = Path(boot)
        self.pending = Path(pending)
        self.archives = Path(archives)
        self.public_status = Path(public_status)
        self.ca = self.lib / 'iws-root-ca.crt'
        self.transport_binary = self.lib / 'iws-transport'
        self.boot_manifest = self.boot / 'device.json'
        self.boot_key = self.boot / 'one-use.key'
        self.record = self.state / 'device.json'
        self.transport = self.state / 'transport'
        self.enrolling = self.state / 'enrolling'
        self.selected = self.pending / 'selected.json'
        self.transaction = self.pending / 'transaction.json'
        self.lock = self.pending / 'setup.lock'

    @classmethod
    def for_test(cls, root):
        root = Path(root)
        return cls(root / 'usr/lib/iws-client', root / 'var/lib/iws-client',
                   root / 'run/iws-client-v1', root / 'usr/share/iws-client/bootstrap',
                   root / 'var/lib/iws-client-bootstrap',
                   root / 'var/lib/iws-client-recovery',
                   root / 'var/lib/iws-client-setup.json')

    def pending_dir(self, device, generation):
        return self.pending / f'{device}-g{generation}'

    def pending_manifest(self, device, generation):
        return self.pending_dir(device, generation) / 'device.json'

    def pending_key(self, device, generation):
        return self.pending_dir(device, generation) / 'one-use.key'

    def pending_marker(self, device, generation, marker):
        return self.pending_dir(device, generation) / marker


REAL_PATHS = SetupPaths()


def _atomic_write(path, content, mode):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    temporary = path.with_name(path.name + f'.tmp-{os.getpid()}')
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    descriptor = os.open(temporary, flags, mode)
    try:
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(content if isinstance(content, bytes) else content.encode())
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary, mode)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def _write_json(path, value, mode=0o600):
    _atomic_write(path, json.dumps(value, sort_keys=True).encode(), mode)


def _read_json(path):
    try:
        return json.loads(path.read_text())
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return None


def _manifest_shape(value):
    if (not isinstance(value, dict) or
        not re.fullmatch(r'[a-z0-9]{1,40}', value.get('deviceId', '')) or
        type(value.get('generation')) is not int or value['generation'] < 1 or
        value.get('platform') not in ('LINUX_DEBIAN', 'LINUX_FEDORA') or
        value.get('clientHostname') != f"iws-{value['deviceId']}-g{value['generation']}" or
        not isinstance(value.get('clientCheckpoint'), str) or not value['clientCheckpoint']):
        raise ValueError('IWS_INSTALLER_INVALID')
    try:
        expiration = datetime.fromisoformat(value['expiresAt'].replace('Z', '+00:00'))
        if expiration.tzinfo is None:
            raise ValueError
    except (KeyError, TypeError, ValueError, AttributeError):
        raise ValueError('IWS_INSTALLER_INVALID') from None
    return value, expiration


def _record_shape(value):
    """Existing enrollment remains valid after its installer has expired."""
    try:
        return _manifest_shape(value)[0]
    except ValueError:
        return None

def validate_manifest(value, now=None):
    now = now or datetime.now(timezone.utc)
    value, expiration = _manifest_shape(value)
    if expiration <= now:
        raise ValueError('IWS_INSTALLER_EXPIRED')
    return value

def generation_action(previous, incoming):
    if previous is None:
        return 'fresh'
    if previous['deviceId'] != incoming['deviceId'] or previous['generation'] > incoming['generation']:
        raise ValueError('IWS_DEVICE_GENERATION_CONFLICT')
    return 'reuse' if previous['generation'] == incoming['generation'] else 'replace'


def _secure_directory(path, expected_uid):
    if path.exists() and (path.is_symlink() or not path.is_dir() or
                          path.stat().st_uid != expected_uid or path.stat().st_mode & 0o077):
        raise ValueError('IWS_STATE_PERMISSIONS_INVALID')
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    path.chmod(0o700)


def _secure_input(path, expected_uid):
    info = path.lstat()
    if not path.is_file() or path.is_symlink() or info.st_uid != expected_uid or info.st_mode & 0o077:
        raise ValueError('IWS_BOOTSTRAP_INVALID')


def _validate_control_state(paths, expected_uid):
    _secure_directory(paths.pending, expected_uid)
    _secure_directory(paths.archives, expected_uid)
    if paths.state.exists():
        _secure_directory(paths.state, expected_uid)
    for path in (paths.selected, paths.transaction, paths.record):
        if path.exists():
            _secure_input(path, expected_uid)
    incoming = _selected_manifest(paths)
    if incoming:
        manifest = paths.pending_manifest(incoming['deviceId'], incoming['generation'])
        _secure_input(manifest, expected_uid)
        key = paths.pending_key(incoming['deviceId'], incoming['generation'])
        if key.exists():
            _secure_input(key, expected_uid)
    if paths.transport.exists():
        _secure_directory(paths.transport, expected_uid)


def _selected_manifest(paths):
    selected = _read_json(paths.selected)
    if not isinstance(selected, dict):
        return None
    try:
        selected, _expiration = _manifest_shape(selected)
    except ValueError:
        return None
    stored = _read_json(paths.pending_manifest(selected['deviceId'], selected['generation']))
    return selected if stored == selected else None


def _transaction(paths):
    value = _read_json(paths.transaction)
    if not isinstance(value, dict):
        return None
    if value.get('phase') not in ('prepared', 'enrollment-attempted', 'enrolled', 'completed'):
        return None
    if not re.fullmatch(r'[a-z0-9]{1,40}', value.get('deviceId', '')):
        return None
    if type(value.get('generation')) is not int or value['generation'] < 1:
        return None
    return value


def evaluate_setup(paths=REAL_PATHS, now=None):
    now = now or datetime.now(timezone.utc)
    current_raw = _read_json(paths.record)
    current = _record_shape(current_raw) if current_raw is not None else None
    incoming = _selected_manifest(paths)
    transaction = _transaction(paths)
    unknown = current_raw is not None and current is None
    if current is None and paths.transport.exists():
        retry = (transaction and incoming and
                 transaction['deviceId'] == incoming['deviceId'] and
                 transaction['generation'] == incoming['generation'] and
                 transaction['phase'] == 'enrollment-attempted')
        if not retry:
            unknown = True
    mode = 'ready' if current else 'missing-installer'
    if unknown:
        mode = 'unknown'
    elif incoming:
        _value, expiration = _manifest_shape(incoming)
        directory = paths.pending_dir(incoming['deviceId'], incoming['generation'])
        consumed = (directory / 'consumed').exists()
        unusable = ((directory / 'expired').exists() or (directory / 'rejected').exists() or
                    consumed or expiration <= now)
        if consumed and current and current['deviceId'] == incoming['deviceId'] and current['generation'] == incoming['generation']:
            mode = ('repair' if transaction and transaction.get('phase') == 'enrolled' and
                    transaction.get('deviceId') == incoming['deviceId'] and
                    transaction.get('generation') == incoming['generation'] else 'ready')
        elif unusable:
            mode = 'repair' if current and current['deviceId'] == incoming['deviceId'] and current['generation'] == incoming['generation'] else 'expired'
        elif transaction and not current and transaction.get('phase') == 'enrollment-attempted' and \
                transaction.get('deviceId') == incoming['deviceId'] and transaction.get('generation') == incoming['generation']:
            mode = 'replace-retry' if transaction.get('archive') else 'enroll-retry'
        elif current is None:
            mode = 'fresh'
        elif current['deviceId'] == incoming['deviceId'] and current['generation'] == incoming['generation']:
            mode = 'repair'
        elif current['deviceId'] == incoming['deviceId'] and current['generation'] > incoming['generation']:
            mode = 'conflict'
        else:
            mode = 'replace'
    return {
        'schemaVersion': 1,
        'mode': mode,
        'existingAvailable': current is not None,
        'incomingGeneration': incoming['generation'] if incoming else None,
        'clientCheckpoint': incoming.get('clientCheckpoint') if incoming else None,
    }


def _publish_status(paths, now=None, error=None):
    status = evaluate_setup(paths, now)
    if error:
        status['lastError'] = error if re.fullmatch(r'IWS_[A-Z_]+', error) else 'IWS_SETUP_FAILED'
    _write_json(paths.public_status, status, 0o644)
    return status


def prepare_package(paths=REAL_PATHS, now=None, expected_uid=0, trust_installer=None):
    """Offline-only package configuration: trust validation plus protected staging."""
    now = now or datetime.now(timezone.utc)
    trust_installer = trust_installer or install_trust
    _secure_directory(paths.pending, expected_uid)
    _secure_directory(paths.archives, expected_uid)
    if paths.state.exists():
        _secure_directory(paths.state, expected_uid)
    if not paths.boot_manifest.exists() and not paths.boot_key.exists():
        return _publish_status(paths, now)
    if not paths.boot_manifest.exists() or not paths.boot_key.exists():
        raise ValueError('IWS_BOOTSTRAP_INVALID')
    _secure_input(paths.boot_manifest, expected_uid)
    _secure_input(paths.boot_key, expected_uid)
    incoming, expiration = _manifest_shape(json.loads(paths.boot_manifest.read_text()))
    trust_installer(incoming['platform'])
    target = paths.pending_dir(incoming['deviceId'], incoming['generation'])
    _secure_directory(target, expected_uid)
    manifest_path = paths.pending_manifest(incoming['deviceId'], incoming['generation'])
    existing = _read_json(manifest_path)
    if existing is not None and existing != incoming:
        recovery = paths.archives / f"bootstrap-{incoming['deviceId']}-g{incoming['generation']}-{time.time_ns()}"
        os.replace(target, recovery)
        _secure_directory(target, expected_uid)
        manifest_path = paths.pending_manifest(incoming['deviceId'], incoming['generation'])
        existing = None
    if existing is None:
        _write_json(manifest_path, incoming)
    key_path = paths.pending_key(incoming['deviceId'], incoming['generation'])
    marked = any(paths.pending_marker(incoming['deviceId'], incoming['generation'], marker).exists()
                 for marker in ('expired', 'rejected', 'consumed'))
    if expiration <= now:
        key_path.unlink(missing_ok=True)
        _atomic_write(target / 'expired', b'', 0o600)
    elif not marked:
        contents = paths.boot_key.read_bytes()
        if key_path.exists() and key_path.read_bytes() != contents:
            raise ValueError('IWS_BOOTSTRAP_CONFLICT')
        if not key_path.exists():
            _atomic_write(key_path, contents, 0o600)
    _write_json(paths.selected, incoming)
    paths.boot_key.unlink()
    paths.boot_manifest.unlink()
    return _publish_status(paths, now)

def run(*args, timeout=60, **kwargs):
    return subprocess.run(args, check=True, text=True, capture_output=True,
                          timeout=timeout, **kwargs).stdout

def clean_environment():
    return {'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'LANG': 'C.UTF-8',
            'HOME': '/var/lib/iws-client', 'NB_STATE_DIR': str(STATE / 'transport'),
            'DBUS_SYSTEM_BUS_ADDRESS': 'unix:path=/run/iws-client-v1/no-system-bus'}

def transport_args():
    return [str(LIB / 'iws-transport'), '--config', str(STATE / 'transport/default.json'),
            '--daemon-addr', SOCKET, '--log-file', str(STATE / 'transport/client.log'),
            '--log-level', 'error']

def enter_namespace(browser=False):
    libc = ctypes.CDLL(None, use_errno=True)
    if libc.unshare(0x00020000) != 0:  # CLONE_NEWNS; supports Python 3.11 too
        raise ValueError('IWS_MOUNT_NAMESPACE_FAILED')
    run('mount', '--make-rprivate', '/')
    with open(NS) as net:
        if libc.setns(net.fileno(), 0x40000000) != 0:  # CLONE_NEWNET
            raise ValueError('IWS_NETWORK_NAMESPACE_FAILED')
    resolver = RUN / ('browser-resolv.conf' if browser else 'control-resolv.conf')
    run('mount', '--bind', str(resolver), '/etc/resolv.conf')
    run('mount', '--bind', str(RUN / 'nsswitch.conf'), '/etc/nsswitch.conf')
    if browser:
        run('mount', '--bind', str(RUN / 'browser-hosts'), '/etc/hosts')
    # Filesystem Unix sockets are not isolated by a network namespace.
    # Do not let the private transport/browser configure host services over D-Bus.
    for target in ('/run/dbus/system_bus_socket', '/run/systemd/resolve/io.systemd.Resolve'):
        if Path(target).exists():
            run('mount', '--bind', '/dev/null', target)

def service_flags(enrolled):
    return ['--disable-profiles', '--disable-networks'] + (['--disable-update-settings'] if enrolled else [])

def serve():
    if hashlib.sha256((LIB / 'iws-transport').read_bytes()).hexdigest() != TRANSPORT_HASH:
        raise ValueError('IWS_TRANSPORT_CHECKSUM_INVALID')
    enter_namespace()
    args = transport_args() + ['service', 'run'] + service_flags(
        (STATE / 'device.json').is_file() and not (STATE / 'enrolling').exists())
    os.execve(args[0], args, clean_environment())

def install_trust(platform):
    release = {}
    for line in Path('/etc/os-release').read_text().splitlines():
        if '=' in line:
            key, value = line.split('=', 1)
            release[key] = value.strip('"')
    family = 'LINUX_FEDORA' if release.get('ID') in ('fedora', 'nobara') else (
        'LINUX_DEBIAN' if release.get('ID') in ('debian', 'ubuntu', 'linuxmint')
        or 'debian' in release.get('ID_LIKE', '').split() else None)
    if platform != family:
        raise ValueError('IWS_PLATFORM_MISMATCH')
    certificate = subprocess.run(['openssl', 'x509', '-in', str(CA), '-outform', 'DER'],
                                 check=True, capture_output=True).stdout
    if hashlib.sha256(certificate).hexdigest() != '3976d486cf804696206b98fccb56c2315f29a6690a0272891c562fac2b48a781':
        raise ValueError('IWS_CA_INVALID')
    destination = Path('/etc/pki/ca-trust/source/anchors/iws-private-root.crt' if family == 'LINUX_FEDORA'
                       else '/usr/local/share/ca-certificates/iws-private-root.crt')
    if destination.is_symlink() or (destination.exists() and destination.read_bytes() != CA.read_bytes()):
        raise ValueError('IWS_EXISTING_CA_CONFLICT')
    if not destination.exists():
        shutil.copyfile(CA, destination)
        destination.chmod(0o644)
    run('update-ca-trust' if family == 'LINUX_FEDORA' else 'update-ca-certificates')

def _tree_digest(root):
    digest = hashlib.sha256()
    if not root.exists():
        return digest.hexdigest()
    for entry in sorted(root.rglob('*')):
        relative = entry.relative_to(root).as_posix().encode()
        if entry.is_symlink():
            raise ValueError('IWS_STATE_PERMISSIONS_INVALID')
        digest.update(relative + b'\0' + str(entry.stat().st_mode & 0o7777).encode() + b'\0')
        if entry.is_file():
            digest.update(entry.read_bytes())
    return digest.hexdigest()


def _archive_state(paths):
    paths.archives.mkdir(parents=True, exist_ok=True, mode=0o700)
    archive = paths.archives / f'state-{time.time_ns()}'
    temporary = archive.with_name(archive.name + '.tmp')
    try:
        shutil.copytree(paths.state, temporary, symlinks=False)
        if _tree_digest(paths.state) != _tree_digest(temporary):
            raise ValueError('IWS_RECOVERY_COPY_INVALID')
        os.replace(temporary, archive)
    except Exception:
        shutil.rmtree(temporary, ignore_errors=True)
        raise
    return archive


def _restore_archive(paths, archive):
    archive = Path(archive)
    if (archive.parent != paths.archives or
            not re.fullmatch(r'state-[0-9]+', archive.name) or
            not archive.is_dir() or archive.is_symlink()):
        raise ValueError('IWS_RECOVERY_COPY_INVALID')
    replacement = paths.state.with_name(paths.state.name + f'.restore-{os.getpid()}')
    shutil.rmtree(replacement, ignore_errors=True)
    shutil.copytree(archive, replacement, symlinks=False)
    if _tree_digest(archive) != _tree_digest(replacement):
        shutil.rmtree(replacement, ignore_errors=True)
        raise ValueError('IWS_RECOVERY_COPY_INVALID')
    shutil.rmtree(paths.state, ignore_errors=True)
    os.replace(replacement, paths.state)


class RealBoundary:
    def run(self, *args, **kwargs):
        try:
            return run(*args, **kwargs)
        except subprocess.TimeoutExpired as error:
            raise EnrollmentTransient('IWS_ENROLLMENT_UNCERTAIN') from error
        except subprocess.CalledProcessError as error:
            detail = ((error.stderr or '') + ' ' + (error.stdout or '')).lower()
            if any(word in detail for word in ('invalid setup key', 'expired setup key', 'revoked', 'unauthorized')):
                raise EnrollmentRejected('IWS_ENROLLMENT_REJECTED') from error
            raise EnrollmentTransient('IWS_CONNECTION_UNAVAILABLE') from error

    def wait_for_socket(self, socket):
        deadline = time.monotonic() + 15
        while not Path(socket).exists():
            if time.monotonic() >= deadline:
                raise EnrollmentTransient('IWS_CONNECTION_UNAVAILABLE')
            time.sleep(0.2)


def _transport_args(paths):
    return [str(paths.transport_binary), '--config', str(paths.transport / 'default.json'),
            '--daemon-addr', SOCKET, '--log-file', str(paths.transport / 'client.log'),
            '--log-level', 'error']


def _mark_unusable(paths, incoming, marker):
    paths.pending_key(incoming['deviceId'], incoming['generation']).unlink(missing_ok=True)
    _atomic_write(paths.pending_marker(incoming['deviceId'], incoming['generation'], marker), b'', 0o600)


def _start_enrollment(paths, boundary, incoming, key):
    args = _transport_args(paths) + ['up', '--setup-key-file', str(key),
        '--hostname', incoming['clientHostname'], '--management-url', 'https://api.netbird.io:443',
        '--interface-name', 'IwsPrivate', '--disable-client-routes', '--disable-server-routes',
        '--disable-dns', '--disable-ipv6', '--block-inbound', '--block-lan-access', '--no-browser']
    # The setup key is passed only to the root-private local CLI. Child output is
    # captured and classified in memory; it is never copied to UI or logs.
    boundary.run(*args, timeout=120, env=clean_environment())


def perform_setup(operation, paths=REAL_PATHS, boundary=None, now=None, expected_uid=None):
    """Execute one fixed privileged operation after re-reading all root state."""
    now = now or datetime.now(timezone.utc)
    boundary = boundary or RealBoundary()
    expected_uid = os.geteuid() if expected_uid is None else expected_uid
    paths.pending.mkdir(parents=True, exist_ok=True, mode=0o700)
    with open(paths.lock, 'a+b') as lock:
        os.chmod(paths.lock, 0o600)
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError('IWS_SETUP_BUSY') from None
        _validate_control_state(paths, expected_uid)
        status = evaluate_setup(paths, now)
        if operation == 'repair':
            if not status['existingAvailable'] or status['mode'] not in ('ready', 'repair', 'expired', 'conflict'):
                raise ValueError('IWS_SETUP_ACTION_INVALID')
            boundary.run('systemctl', 'start', 'iws-client.service')
            incoming = _selected_manifest(paths)
            if incoming and status['mode'] == 'repair':
                _mark_unusable(paths, incoming, 'consumed')
                transaction = _transaction(paths)
                if (transaction and transaction.get('phase') == 'enrolled' and
                        transaction.get('deviceId') == incoming['deviceId'] and
                        transaction.get('generation') == incoming['generation']):
                    _write_json(paths.transaction, {**transaction, 'phase': 'completed'})
            return _publish_status(paths, now)
        expected_modes = {'enroll': ('fresh', 'enroll-retry'), 'replace': ('replace', 'replace-retry')}
        if operation not in expected_modes or status['mode'] not in expected_modes[operation]:
            if status['mode'] == 'unknown':
                raise ValueError('IWS_IDENTITY_UNKNOWN')
            if operation in expected_modes and status['mode'] == 'expired':
                raise ValueError('IWS_BOOTSTRAP_UNUSABLE')
            raise ValueError('IWS_SETUP_ACTION_INVALID')
        incoming = _selected_manifest(paths)
        if incoming is None:
            raise ValueError('IWS_BOOTSTRAP_UNUSABLE')
        try:
            validate_manifest(incoming, now)
        except ValueError as error:
            if str(error) == 'IWS_INSTALLER_EXPIRED':
                _mark_unusable(paths, incoming, 'expired')
                _publish_status(paths, now, 'IWS_INSTALLER_EXPIRED')
                raise ValueError('IWS_BOOTSTRAP_UNUSABLE') from None
            raise
        key = paths.pending_key(incoming['deviceId'], incoming['generation'])
        if (not key.is_file() or key.is_symlink() or key.stat().st_mode & 0o077 or
                any(paths.pending_marker(incoming['deviceId'], incoming['generation'], marker).exists()
                    for marker in ('expired', 'rejected', 'consumed'))):
            raise ValueError('IWS_BOOTSTRAP_UNUSABLE')
        transaction = _transaction(paths)
        retry = status['mode'].endswith('-retry')
        archive = None
        attempted = retry
        if retry:
            archive = transaction.get('archive') if transaction else None
        try:
            if retry:
                boundary.run('systemctl', 'start', 'iws-client.service')
                boundary.wait_for_socket(paths.run / 'transport.sock')
            else:
                if operation == 'replace':
                    archive = _archive_state(paths)
                    boundary.run('systemctl', 'stop', 'iws-client.service')
                _write_json(paths.transaction, {
                    'phase': 'prepared', 'deviceId': incoming['deviceId'],
                    'generation': incoming['generation'], 'archive': str(archive) if archive else None,
                })
                if operation == 'replace':
                    shutil.rmtree(paths.state)
                paths.state.mkdir(parents=True, exist_ok=True, mode=0o700)
                paths.transport.mkdir(parents=True, exist_ok=True, mode=0o700)
                paths.enrolling.touch(mode=0o600, exist_ok=True)
                boundary.run('systemctl', 'enable', '--now', 'iws-client.service')
                boundary.wait_for_socket(paths.run / 'transport.sock')
            _write_json(paths.transaction, {
                'phase': 'enrollment-attempted', 'deviceId': incoming['deviceId'],
                'generation': incoming['generation'], 'archive': str(archive) if archive else None,
            })
            attempted = True
            _start_enrollment(paths, boundary, incoming, key)
        except EnrollmentRejected:
            _mark_unusable(paths, incoming, 'rejected')
            _publish_status(paths, now, 'IWS_ENROLLMENT_REJECTED')
            raise
        except Exception as error:
            if operation == 'replace' and not attempted and archive:
                _restore_archive(paths, archive)
                paths.transaction.unlink(missing_ok=True)
                try:
                    boundary.run('systemctl', 'start', 'iws-client.service')
                except Exception:
                    pass
            elif operation == 'enroll' and not attempted:
                shutil.rmtree(paths.state, ignore_errors=True)
                paths.transaction.unlink(missing_ok=True)
            _publish_status(paths, now, str(error))
            raise
        # This record is the durable proof used by reruns. Write it before the
        # settings-lock restart so a downstream failure is key-free repairable.
        _write_json(paths.record, incoming)
        _mark_unusable(paths, incoming, 'consumed')
        paths.enrolling.unlink(missing_ok=True)
        _write_json(paths.transaction, {
            'phase': 'enrolled', 'deviceId': incoming['deviceId'],
            'generation': incoming['generation'], 'archive': str(archive) if archive else None,
        })
        try:
            boundary.run('systemctl', 'restart', 'iws-client.service')
        except Exception as error:
            _publish_status(paths, now, str(error))
            raise
        _write_json(paths.transaction, {
            'phase': 'completed', 'deviceId': incoming['deviceId'],
            'generation': incoming['generation'], 'archive': str(archive) if archive else None,
        })
        return _publish_status(paths, now)


def dispatch_helper(argv, paths=REAL_PATHS, boundary=None, now=None):
    if len(argv) != 2 or argv[1] not in ('enroll', 'repair', 'replace'):
        raise ValueError('IWS_INVOCATION_INVALID')
    return perform_setup(argv[1], paths, boundary, now)

def launch():
    uid = int(os.environ.get('SUDO_UID', '-1'))
    if uid < 1000:
        raise ValueError('IWS_LAUNCH_USER_INVALID')
    user = pwd.getpwuid(uid)
    shell = LIB / 'shell.py'
    if not shell.is_file():
        raise ValueError('IWS_SHELL_UNAVAILABLE')
    display = {k: os.environ[k] for k in ('DISPLAY', 'WAYLAND_DISPLAY', 'XAUTHORITY') if k in os.environ}
    run('systemctl', 'start', 'iws-client.service')
    enter_namespace(browser=True)
    os.initgroups(user.pw_name, user.pw_gid)
    os.setgid(user.pw_gid)
    os.setuid(uid)
    if ctypes.CDLL(None).prctl(38, 1, 0, 0, 0) != 0:  # PR_SET_NO_NEW_PRIVS
        raise ValueError('IWS_PRIVILEGE_DROP_FAILED')
    home = Path(user.pw_dir) / '.local/share/iws-client'
    home.mkdir(mode=0o700, parents=True, exist_ok=True)
    nss = home / '.pki/nssdb'
    nss.mkdir(mode=0o700, parents=True, exist_ok=True)
    if not (nss / 'cert9.db').exists():
        run('certutil', '-N', '--empty-password', '-d', 'sql:' + str(nss))
    run('certutil', '-A', '-d', 'sql:' + str(nss), '-n', 'IWS Private Root',
        '-t', 'C,,', '-i', str(CA))
    environment = {'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8', 'HOME': str(home),
                   'USER': user.pw_name, 'LOGNAME': user.pw_name,
                   'XDG_RUNTIME_DIR': f'/run/user/{uid}',
                   'IWS_NAMESPACE_INODE': str(os.stat('/proc/self/ns/net').st_ino), **display}
    os.execve('/usr/bin/python3', ['/usr/bin/python3', '-I', str(shell)], environment)

def wait_for_portal():
    # Private resolver and native CA validation; no hosts override or HTTP fallback.
    deadline = time.monotonic() + 45
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            return None
    while True:
        try:
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
            with opener.open(PORTAL + 'api/health', timeout=min(5, max(0.1, deadline-time.monotonic()))) as response:
                if response.status == 200:
                    break
        except Exception as error:
            import ssl
            if isinstance(getattr(error, 'reason', error), ssl.SSLCertVerificationError):
                raise ValueError('IWS_CERTIFICATE_INVALID') from None
        if time.monotonic() >= deadline:
            raise ValueError('IWS_UNAVAILABLE')
        time.sleep(min(1, deadline-time.monotonic()))

if __name__ == '__main__':
    try:
        if os.geteuid() != 0 or len(sys.argv) != 2 or sys.argv[1] not in ('prepare', 'serve', 'launch-existing'):
            raise ValueError('IWS_INVOCATION_INVALID')
        os.umask(0o077)
        {'prepare': prepare_package, 'serve': serve, 'launch-existing': launch}[sys.argv[1]]()
    except Exception as error:
        code = str(error)
        print(code if re.fullmatch(r'IWS_[A-Z_]+', code) else 'IWS_OPERATION_FAILED', file=sys.stderr)
        sys.exit(1)

#!/usr/bin/python3 -I
"""Fixed IWS Linux launcher and enrollment controller; never accepts a command/URL."""
from datetime import datetime, timezone
import ctypes
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

def validate_manifest(value, now=None):
    now = now or datetime.now(timezone.utc)
    if (not isinstance(value, dict) or
        not re.fullmatch(r'[a-z0-9]{1,40}', value.get('deviceId', '')) or
        type(value.get('generation')) is not int or value['generation'] < 1 or
        value.get('platform') not in ('LINUX_DEBIAN', 'LINUX_FEDORA') or
        value.get('clientHostname') != f"iws-{value['deviceId']}-g{value['generation']}" or
        not isinstance(value.get('clientCheckpoint'), str) or not value['clientCheckpoint']):
        raise ValueError('IWS_INSTALLER_INVALID')
    try:
        expiration = datetime.fromisoformat(value['expiresAt'].replace('Z', '+00:00'))
        if expiration.tzinfo is None or expiration <= now:
            raise ValueError('IWS_INSTALLER_EXPIRED')
    except (KeyError, TypeError, ValueError):
        raise ValueError('IWS_INSTALLER_EXPIRED') from None
    return value

def generation_action(previous, incoming):
    if previous is None:
        return 'fresh'
    if previous['deviceId'] != incoming['deviceId'] or previous['generation'] > incoming['generation']:
        raise ValueError('IWS_DEVICE_GENERATION_CONFLICT')
    return 'reuse' if previous['generation'] == incoming['generation'] else 'replace'

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
    # Filesystem Unix sockets are not isolated by a network namespace.
    # Do not let the private transport/browser configure host services over D-Bus.
    for target in ('/run/dbus/system_bus_socket', '/run/systemd/resolve/io.systemd.Resolve'):
        if Path(target).exists():
            run('mount', '--bind', '/dev/null', target)

def serve():
    if hashlib.sha256((LIB / 'iws-transport').read_bytes()).hexdigest() != TRANSPORT_HASH:
        raise ValueError('IWS_TRANSPORT_CHECKSUM_INVALID')
    enter_namespace()
    args = transport_args() + ['service', 'run', '--disable-profiles', '--disable-update-settings',
                               '--disable-networks']
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

def install():
    key = BOOT / 'one-use.key'
    manifest_path = BOOT / 'device.json'
    try:
        incoming = validate_manifest(json.loads(manifest_path.read_text()))
        install_trust(incoming['platform'])
        STATE.mkdir(mode=0o700, exist_ok=True)
        if STATE.is_symlink() or STATE.stat().st_uid != 0 or STATE.stat().st_mode & 0o077:
            raise ValueError('IWS_STATE_PERMISSIONS_INVALID')
        record = STATE / 'device.json'
        previous = json.loads(record.read_text()) if record.exists() else None
        action = generation_action(previous, incoming)
        if action == 'reuse':
            run('systemctl', 'enable', '--now', 'iws-client.service')
            return
        if not key.is_file() or key.is_symlink() or key.stat().st_mode & 0o077:
            raise ValueError('IWS_BOOTSTRAP_INVALID')
        if action == 'replace':
            run('systemctl', 'stop', 'iws-client.service')
            # Only the separately provisioned IWS peer's own state is replaced.
            transport = STATE / 'transport'
            if transport.is_symlink():
                raise ValueError('IWS_STATE_PERMISSIONS_INVALID')
            if transport.exists():
                shutil.rmtree(transport)
        (STATE / 'transport').mkdir(mode=0o700, exist_ok=True)
        run('systemctl', 'daemon-reload')
        run('systemctl', 'enable', '--now', 'iws-client.service')
        deadline = time.monotonic() + 15
        while not (RUN / 'transport.sock').exists():
            if time.monotonic() >= deadline:
                raise ValueError('IWS_CONNECTION_UNAVAILABLE')
            time.sleep(0.2)
        args = transport_args() + ['up', '--setup-key-file', str(key),
            '--hostname', incoming['clientHostname'], '--management-url', 'https://api.netbird.io:443',
            '--interface-name', 'IwsPrivate', '--disable-client-routes', '--disable-server-routes',
            '--disable-dns', '--disable-ipv6', '--block-inbound', '--block-lan-access', '--no-browser']
        # CLI communicates only with the root-private daemon socket. Its stdout
        # and stderr never become installer output or journald credential output.
        run(*args, timeout=120, env=clean_environment())
        record.write_text(json.dumps(incoming))
        record.chmod(0o600)
        print('IWS setup completed. Run iws to open IWS.')
    finally:
        key.unlink(missing_ok=True)
        manifest_path.unlink(missing_ok=True)

def launch():
    uid = int(os.environ.get('SUDO_UID', '-1'))
    if uid < 1000:
        raise ValueError('IWS_LAUNCH_USER_INVALID')
    user = pwd.getpwuid(uid)
    # Fixed executable selection, never caller-provided command or URL.
    browser = next((p for p in ('/usr/bin/google-chrome-stable', '/usr/bin/google-chrome',
                               '/usr/bin/chromium-browser', '/usr/bin/chromium') if Path(p).is_file()), None)
    if browser is None:
        raise ValueError('IWS_BROWSER_UNAVAILABLE')
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
                   'XDG_RUNTIME_DIR': f'/run/user/{uid}', **display}
    # Private resolver and native CA validation; no hosts override or HTTP fallback.
    print('Connecting to IWS...', flush=True)
    deadline = time.monotonic() + 45
    while True:
        try:
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
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
    argv = [browser, '--user-data-dir=' + str(home / 'profile'), '--no-first-run',
            '--no-default-browser-check', '--app=' + PORTAL]
    os.execve(browser, argv, environment)

if __name__ == '__main__':
    try:
        if os.geteuid() != 0 or len(sys.argv) != 2 or sys.argv[1] not in ('install', 'serve', 'launch'):
            raise ValueError('IWS_INVOCATION_INVALID')
        os.umask(0o077)
        {'install': install, 'serve': serve, 'launch': launch}[sys.argv[1]]()
    except Exception as error:
        code = str(error)
        print(code if re.fullmatch(r'IWS_[A-Z_]+', code) else 'IWS_OPERATION_FAILED', file=sys.stderr)
        sys.exit(1)

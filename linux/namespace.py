#!/usr/bin/python3
"""The shared Linux IWS network boundary. Never changes host routes/firewall/DNS."""
import ipaddress
import json
import os
from pathlib import Path
import signal
import re
import subprocess
import sys
import time

NAME = 'iws-client-v1'
STATE = Path('/run/iws-client-v1')
NETNS = Path('/run/netns') / NAME
DNS = Path('/etc/netns') / NAME
ENDPOINT = '100.83.75.124'

def command(*args, **kwargs):
    return subprocess.run(args, check=True, text=True, capture_output=True,
                          timeout=30, **kwargs).stdout

def uplink():
    routes = json.loads(command('ip', '-j', '-4', 'route', 'show', 'default'))
    routes.sort(key=lambda item: item.get('metric', 0))
    if not routes:
        raise RuntimeError('IWS_UPLINK_UNAVAILABLE')
    interface = routes[0].get('dev', '')
    if not interface or interface.startswith(('wt', 'tailscale', 'Iws', 'tun')):
        raise RuntimeError('IWS_UPLINK_NOT_UNDERLAY')
    # Ask the host manager for the active interface's upstream, not a loopback
    # stub that cannot exist in this separate namespace. No host setting changes.
    sources = []
    for args in [('resolvectl', 'dns', interface),
                 ('nmcli', '-g', 'IP4.DNS', 'device', 'show', interface)]:
        try:
            sources.extend(command(*args).split())
        except (OSError, subprocess.SubprocessError):
            pass
    if not sources:
        for line in Path('/etc/resolv.conf').read_text().splitlines():
            if line.startswith('nameserver '):
                sources.append(line.split()[1])
    servers = []
    for value in sources:
        try:
            address = ipaddress.IPv4Address(value)
            if not address.is_loopback and not address.is_unspecified and not address.is_multicast:
                if str(address) not in servers:
                    servers.append(str(address))
        except ValueError:
            pass
    if not servers:
        raise RuntimeError('IWS_UPLINK_DNS_UNAVAILABLE')
    return interface, servers

def prepare():
    if STATE.exists() or NETNS.exists():
        raise RuntimeError('IWS_NAMESPACE_ALREADY_EXISTS')
    if DNS.exists() and (DNS.is_symlink() or not (DNS / '.iws-owned').is_file()
                         or (DNS / '.iws-owned').read_text() != NAME):
        raise RuntimeError('IWS_RESOLVER_OWNERSHIP_INVALID')
    interface, servers = uplink()
    STATE.mkdir(mode=0o700)
    (STATE / 'owned').write_text(NAME)
    created = False
    try:
        DNS.mkdir(mode=0o700, parents=True, exist_ok=True)
        (DNS / '.iws-owned').write_text(NAME)
        (DNS / 'resolv.conf').write_text(''.join('nameserver ' + ip + '\n' for ip in servers)
                                       + 'options timeout:2 attempts:2\n')
        (STATE / 'control-resolv.conf').write_bytes((DNS / 'resolv.conf').read_bytes())
        (STATE / 'browser-resolv.conf').write_text('nameserver ' + ENDPOINT + '\noptions timeout:2 attempts:2\n')
        (STATE / 'browser-resolv.conf').chmod(0o444)
        nss = re.sub(r'^hosts:.*$', 'hosts: files dns', Path('/etc/nsswitch.conf').read_text(), flags=re.M)
        (STATE / 'nsswitch.conf').write_text(nss)
        (STATE / 'nsswitch.conf').chmod(0o444)
        command('ip', 'netns', 'add', NAME)
        created = True
        command('ip', '-n', NAME, 'link', 'set', 'lo', 'up')
        command('ip', 'netns', 'exec', NAME, 'sysctl', '-q', '-w',
                'net.ipv6.conf.all.disable_ipv6=1', 'net.ipv4.ip_forward=0')
        # This table exists ONLY in the private namespace. Root is the trusted
        # transport controller. Unprivileged browser sockets have no underlay
        # escape, even when the private interface or connection is absent.
        rules = f'''table inet iws_client_boundary {{
 chain output {{ type filter hook output priority -150; policy drop;
  meta nfproto ipv6 drop
  meta skuid 0 accept
  oifname "IwsPrivate" ip daddr {ENDPOINT} tcp dport {{ 443, 53 }} accept
  oifname "IwsPrivate" ip daddr {ENDPOINT} udp dport 53 accept
 }}
 chain forward {{ type filter hook forward priority -150; policy drop; }}
}}
'''
        command('ip', 'netns', 'exec', NAME, 'nft', '-f', '-', input=rules)
        argv = ['pasta', '--foreground', '--quiet', '--runas', '0', '--ipv4-only', '--config-net',
                '--no-map-gw', '--no-dhcp', '--no-dhcpv6',
                '--no-ndp', '--no-ra', '--no-icmp', '--mtu', '1500',
                '--interface', interface, '--outbound-if4', interface,
                '--ns-ifname', 'iwsuplink', '-t', 'none', '-u', 'none',
                '-T', 'none', '-U', 'none', '--netns', str(NETNS)]
        with (STATE / 'uplink.log').open('xb') as log:
            child = subprocess.Popen(argv, stdin=subprocess.DEVNULL,
                                     stdout=log, stderr=log, start_new_session=True)
        (STATE / 'uplink.pid').write_text(str(child.pid))
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if child.poll() is not None:
                # Uplink diagnostics contain no enrollment material. Preserve
                # privately outside the repo before removing this failed netns.
                diagnostic = Path('/run/iws-client-v1-uplink-error.log')
                diagnostic.write_bytes((STATE / 'uplink.log').read_bytes())
                diagnostic.chmod(0o600)
                raise RuntimeError('IWS_UPLINK_START_FAILED')
            routes = json.loads(command('ip', '-n', NAME, '-j', '-4', 'route', 'show', 'default'))
            if routes:
                print('IWS_NAMESPACE_READY')
                return
            time.sleep(0.1)
        raise RuntimeError('IWS_UPLINK_START_TIMEOUT')
    except Exception:
        if created:
            remove()
        else:
            for filename in ('resolv.conf', '.iws-owned'):
                (DNS / filename).unlink(missing_ok=True)
            if DNS.exists():
                DNS.rmdir()
            (STATE / 'owned').unlink()
            for filename in ('control-resolv.conf', 'browser-resolv.conf', 'nsswitch.conf'):
                (STATE / filename).unlink(missing_ok=True)
            STATE.rmdir()
        raise

def remove():
    if not STATE.is_dir() or STATE.is_symlink() or (STATE / 'owned').read_text() != NAME:
        raise RuntimeError('IWS_NAMESPACE_OWNERSHIP_INVALID')
    pidfile = STATE / 'uplink.pid'
    if pidfile.exists():
        pid = int(pidfile.read_text())
        try:
            argv = Path(f'/proc/{pid}/cmdline').read_bytes().split(b'\0')
            if str(NETNS).encode() not in argv or b'--netns' not in argv:
                raise RuntimeError('IWS_UPLINK_PROCESS_MISMATCH')
            os.kill(pid, signal.SIGTERM)
        except FileNotFoundError:
            pass
    if NETNS.exists():
        command('ip', 'netns', 'delete', NAME)
    for filename in ('resolv.conf', '.iws-owned'):
        (DNS / filename).unlink(missing_ok=True)
    if DNS.exists():
        DNS.rmdir()
    for filename in ('uplink.pid', 'uplink.log', 'owned', 'control-resolv.conf', 'browser-resolv.conf', 'nsswitch.conf'):
        (STATE / filename).unlink(missing_ok=True)
    STATE.rmdir()
    print('IWS_NAMESPACE_REMOVED')

if __name__ == '__main__':
    try:
        if os.geteuid() != 0 or len(sys.argv) != 2 or sys.argv[1] not in ('prepare', 'remove'):
            raise RuntimeError('IWS_NAMESPACE_INVOCATION_INVALID')
        os.umask(0o077)
        {'prepare': prepare, 'remove': remove}[sys.argv[1]]()
    except Exception as error:
        message = str(error)
        print(message if message.startswith('IWS_') and message.replace('_', '').isalnum()
              else 'IWS_NAMESPACE_FAILED', file=sys.stderr)
        sys.exit(1)

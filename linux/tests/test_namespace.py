"""Physical, privileged tests: a failure never counts as positive isolation."""
import json
import os
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]
NS = 'iws-client-v1'

def run(*args):
    return subprocess.run(args, text=True, capture_output=True, timeout=35)

class NamespaceBoundary(unittest.TestCase):
    def test_outbound_transport_and_denied_browser_without_host_routes(self):
        self.assertEqual(os.geteuid(), 0, 'Run only on the authorized physical development host')
        before = run('ip', '-j', '-4', 'route', 'show', 'table', 'main').stdout
        dns_before = Path('/etc/resolv.conf').read_bytes()
        prepared = run('/usr/bin/python3', str(ROOT / 'namespace.py'), 'prepare')
        self.assertEqual(prepared.returncode, 0, prepared.stderr)
        try:
            self.assertNotEqual(os.stat('/run/netns/' + NS).st_ino,
                                os.stat('/proc/self/ns/net').st_ino)
            # No forced hostname mapping: ordinary DNS and validated HTTPS.
            positive = run('ip', 'netns', 'exec', NS, 'curl', '--noproxy', '*',
                           '--max-time', '20', '-fsS', '-o', '/dev/null',
                           'https://api.netbird.io/')
            # Management root may return 404, which still establishes HTTPS.
            self.assertIn(positive.returncode, (0, 22), positive.stderr)
            negative = run('ip', 'netns', 'exec', NS, 'setpriv', '--reuid=65534',
                           '--regid=65534', '--clear-groups', 'curl', '--noproxy', '*',
                           '--max-time', '3', '-sS', '-o', '/dev/null',
                           'https://1.1.1.1/')
            self.assertEqual(negative.returncode, 28, negative.stderr)
            # The kernel WireGuard transport has no userspace socket UID. Its
            # pinned privileged mark must not be forgeable by the browser UID.
            forged = run('ip', 'netns', 'exec', NS, 'setpriv', '--reuid=65534',
                         '--regid=65534', '--clear-groups', 'python3', '-c',
                         'import socket,errno; s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM)\n'
                         'try: s.setsockopt(socket.SOL_SOCKET,36,0x1bd00)\n'
                         'except OSError as e: raise SystemExit(0 if e.errno==errno.EPERM else 2)\n'
                         'raise SystemExit(1)')
            self.assertEqual(forged.returncode, 0, 'browser UID can forge transport mark')
            self.assertEqual(json.loads(run('ip', '-j', '-4', 'route', 'show', 'table', 'main').stdout),
                             json.loads(before))
            self.assertEqual(Path('/etc/resolv.conf').read_bytes(), dns_before)
        finally:
            cleaned = run('/usr/bin/python3', str(ROOT / 'namespace.py'), 'remove')
            self.assertEqual(cleaned.returncode, 0, cleaned.stderr)

if __name__ == '__main__':
    unittest.main()

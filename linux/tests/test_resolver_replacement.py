"""Root-only isolated mount regression; never alters host DNS or live IWS."""
import ctypes
import importlib.util
import os
from pathlib import Path
import select
import subprocess
import tempfile
import unittest

RUNTIME = Path(__file__).resolve().parents[1] / 'runtime.py'

@unittest.skipUnless(os.geteuid() == 0, 'requires root for disposable mount namespaces')
class ResolverReplacement(unittest.TestCase):
    def exercise(self, browser, symlink):
        with tempfile.TemporaryDirectory(prefix='iws-resolver-test-') as folder:
            root = Path(folder)
            etc, state, host = (root / name for name in ('etc', 'run', 'host'))
            for directory in (etc, state, host):
                directory.mkdir()
            host_resolver = host / 'stub-resolv.conf'
            host_resolver.write_text('nameserver 127.0.0.53\n')
            if symlink:
                (etc / 'resolv.conf').symlink_to(host_resolver)
            else:
                (etc / 'resolv.conf').write_text(host_resolver.read_text())
            (etc / 'nsswitch.conf').write_text('hosts: resolve files dns\n')
            (etc / 'hosts').write_text('127.0.0.1 localhost host-only\n')
            (etc / 'unrelated').mkdir()
            (etc / 'unrelated' / 'marker').write_text('preserved')
            (etc / 'link').symlink_to('unrelated/marker')
            for role, address in [('control', '192.0.2.53'), ('browser', '198.51.100.53')]:
                (state / (role + '-resolv.conf')).write_text('nameserver ' + address + '\n')
            (state / 'nsswitch.conf').write_text('hosts: files dns\n')
            (state / 'browser-hosts').write_text('127.0.0.1 localhost\n')
            spec = importlib.util.spec_from_file_location('runtime', RUNTIME)
            runtime = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(runtime)
            runtime.RUN = state
            runtime.NS = '/proc/self/ns/net'
            ready_r, ready_w = os.pipe()
            go_r, go_w = os.pipe()
            pid = os.fork()
            if pid == 0:
                os.close(ready_r)
                os.close(go_w)
                try:
                    libc = ctypes.CDLL(None, use_errno=True)
                    assert libc.unshare(0x00020000) == 0
                    subprocess.run(['mount', '--make-rprivate', '/'], check=True)
                    subprocess.run(['mount', '--bind', str(etc), '/etc'], check=True)
                    runtime.enter_namespace(browser=browser)
                    expected = (state / ('browser-resolv.conf' if browser else 'control-resolv.conf')).read_text()
                    assert Path('/etc/resolv.conf').read_text() == expected
                    os.write(ready_w, b'1')
                    assert select.select([go_r], [], [], 15)[0], 'parent timed out'
                    assert os.read(go_r, 1) == b'1'
                    assert Path('/etc/resolv.conf').read_text() == expected, 'private DNS lost after external replacement'
                    assert Path('/etc/nsswitch.conf').read_text() == 'hosts: files dns\n'
                    assert Path('/etc/link').read_text() == 'preserved'
                    expected_hosts = (state / 'browser-hosts' if browser else etc / 'hosts').read_text()
                    assert Path('/etc/hosts').read_text() == expected_hosts
                    os._exit(0)
                except BaseException as error:
                    print(str(error), flush=True)
                    os._exit(1)
            os.close(ready_w)
            os.close(go_r)
            try:
                self.assertTrue(select.select([ready_r], [], [], 15)[0], 'child setup timed out')
                self.assertEqual(os.read(ready_r, 1), b'1', 'child setup failed')
                # External updates to both the symlink target and /etc entry.
                for destination in (host_resolver, etc / 'resolv.conf', etc / 'nsswitch.conf'):
                    temporary = destination.with_name(destination.name + '.replacement')
                    temporary.write_text('host replacement\n')
                    os.replace(temporary, destination)
                os.write(go_w, b'1')
                _, status = os.waitpid(pid, 0)
                pid = None
                self.assertEqual(os.waitstatus_to_exitcode(status), 0, 'private resolver did not survive host update')
                self.assertEqual((etc / 'resolv.conf').read_text(), 'host replacement\n')
            finally:
                os.close(ready_r)
                os.close(go_w)
                if pid is not None:
                    os.kill(pid, 9)
                    os.waitpid(pid, 0)

    def test_control_symlink(self):
        self.exercise(browser=False, symlink=True)

    def test_browser_symlink(self):
        self.exercise(browser=True, symlink=True)

    def test_control_regular_file(self):
        self.exercise(browser=False, symlink=False)

    def test_browser_regular_file(self):
        self.exercise(browser=True, symlink=False)

if __name__ == '__main__':
    unittest.main()

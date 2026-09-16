import importlib.util
from pathlib import Path
import unittest

class ShellPolicy(unittest.TestCase):
    def policy(self):
        source = Path(__file__).resolve().parents[1] / 'shell_policy.py'
        self.assertTrue(source.is_file(), 'native shell navigation policy is missing')
        spec = importlib.util.spec_from_file_location('shell_policy', source)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_only_private_https_origin_is_navigable(self):
        p = self.policy()
        for url in ['https://portal.iws.internal/', 'https://portal.iws.internal/build/?x=1',
                    'https://portal.iws.internal:443/inventory/#items']:
            self.assertTrue(p.allowed_navigation(url), url)
        for url in ['http://portal.iws.internal/', 'https://example.com/',
                    'https://portal.iws.internal.evil.test/', 'file:///etc/passwd',
                    'javascript:alert(1)', 'https://user@portal.iws.internal/',
                    'https://portal.iws.internal:444/', 'https://100.83.75.124/']:
            self.assertFalse(p.allowed_navigation(url), url)

    def test_failed_load_cannot_be_relabelled_success_by_its_finished_callback(self):
        p = self.policy()
        state = p.LoadState()
        state.started()
        state.failed(tls=True)
        state.finished()
        self.assertEqual(state.state, 'certificate-error')
        state.started()
        state.finished()
        self.assertEqual(state.state, 'ready')

if __name__ == '__main__': unittest.main()

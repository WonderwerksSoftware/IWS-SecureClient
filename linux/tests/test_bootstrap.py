import importlib.util
import tempfile
from pathlib import Path
import json
import unittest
from datetime import datetime, timezone

RUNTIME = Path(__file__).resolve().parents[1] / 'runtime.py'

class Bootstrap(unittest.TestCase):
    def load(self):
        self.assertTrue(RUNTIME.is_file(), 'shared Linux runtime is not implemented')
        spec = importlib.util.spec_from_file_location('runtime', RUNTIME)
        runtime = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(runtime)
        return runtime

    def test_bootstrap_rejects_expiry_and_manifest_identity_mismatch(self):
        runtime = self.load()
        manifest = {'deviceId': 'device123', 'generation': 2, 'platform': 'LINUX_DEBIAN',
                    'clientHostname': 'iws-device123-g2', 'clientCheckpoint': 'abc123',
                    'expiresAt': '2026-09-12T12:00:00Z'}
        now = datetime(2026, 9, 11, tzinfo=timezone.utc)
        for platform in ['LINUX_DEBIAN', 'LINUX_FEDORA']:
            self.assertEqual(runtime.validate_manifest({**manifest, 'platform': platform}, now)['generation'], 2)
        for override in [{'expiresAt': '2026-09-10T12:00:00Z'}, {'clientHostname': 'OleClankyV2'},
                         {'platform': 'LINUX_ARCH'}, {'generation': 0}, {'deviceId': '../../netbird'},
                         {'expiresAt': 'garbage'}]:
            with self.assertRaises(ValueError):
                runtime.validate_manifest({**manifest, **override}, now)

    def test_generation_does_not_silently_replace_another_device_or_downgrade(self):
        runtime = self.load()
        old = {'deviceId': 'device123', 'generation': 2}
        self.assertEqual(runtime.generation_action(old, old), 'reuse')
        self.assertEqual(runtime.generation_action(old, {**old, 'generation': 3}), 'replace')
        self.assertEqual(runtime.generation_action(None, old), 'fresh')
        for new in [{'deviceId': 'other', 'generation': 3}, {**old, 'generation': 1}]:
            with self.assertRaises(ValueError):
                runtime.generation_action(old, new)

    def test_first_enrollment_is_not_blocked_by_the_post_enrollment_settings_lock(self):
        runtime = self.load()
        self.assertNotIn('--disable-update-settings', runtime.service_flags(enrolled=False))
        self.assertIn('--disable-update-settings', runtime.service_flags(enrolled=True))

if __name__ == '__main__':
    unittest.main()

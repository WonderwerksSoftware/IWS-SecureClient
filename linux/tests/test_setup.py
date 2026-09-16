import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from datetime import datetime, timezone


RUNTIME = Path(__file__).resolve().parents[1] / "runtime.py"
SETUP_UI = Path(__file__).resolve().parents[1] / "setup_ui.py"
NOW = datetime(2026, 9, 15, 12, 0, tzinfo=timezone.utc)


class Boundary:
    def __init__(self, fail=None):
        self.calls = []
        self.fail = fail or {}

    def run(self, *args, **kwargs):
        self.calls.append(args)
        marker = args[:2]
        failure = self.fail.get(marker)
        if failure:
            raise failure
        return ""

    def wait_for_socket(self, _path):
        self.calls.append(("wait-for-socket",))
        failure = self.fail.get(("wait-for-socket",))
        if failure:
            raise failure


class LinuxSetup(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location("runtime_setup", RUNTIME)
        self.runtime = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.runtime)
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.paths = self.runtime.SetupPaths.for_test(root)
        self.paths.lib.mkdir(parents=True)
        self.paths.boot.mkdir(parents=True, mode=0o700)
        self.paths.ca.write_text("fixture-ca")

    def tearDown(self):
        self.temp.cleanup()

    def manifest(self, device="newdevice", generation=1, expires="2026-09-16T12:00:00Z"):
        return {
            "deviceId": device,
            "generation": generation,
            "platform": "LINUX_DEBIAN",
            "clientHostname": f"iws-{device}-g{generation}",
            "clientCheckpoint": "secure-client-v1.0.2",
            "expiresAt": expires,
        }

    def incoming(self, manifest=None, key="one-use-fixture"):
        manifest = manifest or self.manifest()
        self.paths.boot.mkdir(parents=True, exist_ok=True)
        self.paths.boot_manifest.write_text(json.dumps(manifest))
        self.paths.boot_key.write_text(key)
        self.paths.boot_manifest.chmod(0o600)
        self.paths.boot_key.chmod(0o600)
        return manifest

    def current(self, manifest=None, transport="old-identity"):
        manifest = manifest or self.manifest("olddevice", 3, "2026-09-10T12:00:00Z")
        self.paths.state.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.paths.record.write_text(json.dumps(manifest))
        self.paths.record.chmod(0o600)
        self.paths.transport.mkdir(mode=0o700)
        (self.paths.transport / "identity.json").write_text(transport)
        return manifest

    def prepare(self):
        return self.runtime.prepare_package(
            self.paths,
            now=NOW,
            expected_uid=os.getuid(),
            trust_installer=lambda _platform: None,
        )

    def test_package_preparation_is_offline_idempotent_and_preserves_conflicting_identity(self):
        old = self.current()
        self.incoming(self.manifest("newdevice", 1))
        first = self.prepare()
        second = self.prepare()
        self.assertEqual(first["mode"], "replace")
        self.assertEqual(second["mode"], "replace")
        self.assertEqual(json.loads(self.paths.record.read_text())["deviceId"], old["deviceId"])
        self.assertEqual((self.paths.transport / "identity.json").read_text(), "old-identity")
        self.assertTrue(self.paths.pending_key("newdevice", 1).is_file())
        self.assertFalse(self.paths.boot_key.exists())

    def test_expired_package_preparation_succeeds_but_makes_key_unusable(self):
        self.incoming(self.manifest(expires="2026-09-14T12:00:00Z"))
        status = self.prepare()
        self.assertEqual(status["mode"], "expired")
        self.assertFalse(self.paths.pending_key("newdevice", 1).exists())
        self.assertTrue(self.paths.pending_marker("newdevice", 1, "expired").is_file())

    def test_renewed_same_generation_package_preserves_unused_previous_material(self):
        first = self.incoming(self.manifest(expires="2026-09-16T12:00:00Z"), key="first-key")
        self.prepare()
        second = self.incoming(self.manifest(expires="2026-09-17T12:00:00Z"), key="second-key")
        status = self.prepare()
        self.assertEqual(status["mode"], "fresh")
        self.assertEqual(self.paths.pending_key("newdevice", 1).read_text(), "second-key")
        recovered = [entry for entry in self.paths.archives.iterdir() if entry.name.startswith("bootstrap-")]
        self.assertEqual(len(recovered), 1)
        self.assertEqual((recovered[0] / "one-use.key").read_text(), "first-key")
        self.assertNotEqual(first["expiresAt"], second["expiresAt"])

    def test_fresh_enrollment_records_identity_before_downstream_restart(self):
        incoming = self.incoming()
        self.prepare()
        boundary = Boundary({("systemctl", "restart"): RuntimeError("restart failed")})
        with self.assertRaisesRegex(RuntimeError, "restart failed"):
            self.runtime.perform_setup("enroll", self.paths, boundary, now=NOW)
        self.assertEqual(json.loads(self.paths.record.read_text())["deviceId"], incoming["deviceId"])
        self.assertFalse(self.paths.pending_key("newdevice", 1).exists())
        self.assertEqual(self.runtime.evaluate_setup(self.paths, now=NOW)["mode"], "repair")
        repaired = self.runtime.perform_setup("repair", self.paths, Boundary(), now=NOW)
        self.assertEqual(repaired["mode"], "ready")

    def test_same_generation_repair_only_starts_existing_service(self):
        current = self.current(self.manifest("deviceone", 2))
        self.incoming(current)
        self.prepare()
        boundary = Boundary()
        result = self.runtime.perform_setup("repair", self.paths, boundary, now=NOW)
        self.assertEqual(result["mode"], "ready")
        self.assertEqual(boundary.calls, [("systemctl", "start", "iws-client.service")])
        self.assertEqual((self.paths.transport / "identity.json").read_text(), "old-identity")

    def test_higher_generation_requires_explicit_replace_operation(self):
        self.current(self.manifest("deviceone", 1))
        self.incoming(self.manifest("deviceone", 2))
        self.prepare()
        self.assertEqual(self.runtime.evaluate_setup(self.paths, now=NOW)["mode"], "replace")
        boundary = Boundary()
        with self.assertRaisesRegex(ValueError, "IWS_SETUP_ACTION_INVALID"):
            self.runtime.perform_setup("enroll", self.paths, boundary, now=NOW)
        self.assertEqual(boundary.calls, [])
        self.assertEqual(json.loads(self.paths.record.read_text())["generation"], 1)

    def test_pre_enrollment_replacement_failure_restores_original_and_keeps_key(self):
        self.current()
        self.incoming()
        self.prepare()
        boundary = Boundary({("wait-for-socket",): RuntimeError("offline")})
        with self.assertRaisesRegex(RuntimeError, "offline"):
            self.runtime.perform_setup("replace", self.paths, boundary, now=NOW)
        self.assertEqual(json.loads(self.paths.record.read_text())["deviceId"], "olddevice")
        self.assertEqual((self.paths.transport / "identity.json").read_text(), "old-identity")
        self.assertTrue(self.paths.pending_key("newdevice", 1).is_file())
        self.assertTrue(any(self.paths.archives.iterdir()))

    def test_transient_enrollment_failure_keeps_new_state_key_and_recoverable_archive(self):
        self.current()
        self.incoming()
        self.prepare()
        transient = self.runtime.EnrollmentTransient("network unavailable")
        boundary = Boundary({(str(self.paths.transport_binary), "--config"): transient})
        with self.assertRaises(self.runtime.EnrollmentTransient):
            self.runtime.perform_setup("replace", self.paths, boundary, now=NOW)
        self.assertFalse(self.paths.record.exists())
        self.assertTrue(self.paths.transport.exists())
        self.assertTrue(self.paths.pending_key("newdevice", 1).is_file())
        self.assertTrue(any(self.paths.archives.iterdir()))
        self.assertEqual(self.runtime.evaluate_setup(self.paths, now=NOW)["mode"], "replace-retry")

    def test_rejected_key_is_not_reused_and_old_archive_remains(self):
        self.current()
        self.incoming()
        self.prepare()
        rejected = self.runtime.EnrollmentRejected("invalid setup key")
        boundary = Boundary({(str(self.paths.transport_binary), "--config"): rejected})
        with self.assertRaises(self.runtime.EnrollmentRejected):
            self.runtime.perform_setup("replace", self.paths, boundary, now=NOW)
        self.assertFalse(self.paths.pending_key("newdevice", 1).exists())
        self.assertTrue(self.paths.pending_marker("newdevice", 1, "rejected").is_file())
        self.assertTrue(any(self.paths.archives.iterdir()))
        with self.assertRaisesRegex(ValueError, "IWS_BOOTSTRAP_UNUSABLE"):
            self.runtime.perform_setup("replace", self.paths, Boundary(), now=NOW)

    def test_unknown_transport_state_cannot_be_treated_as_fresh(self):
        self.paths.state.mkdir(parents=True, mode=0o700)
        self.paths.transport.mkdir(mode=0o700)
        self.incoming()
        self.prepare()
        self.assertEqual(self.runtime.evaluate_setup(self.paths, now=NOW)["mode"], "unknown")
        with self.assertRaisesRegex(ValueError, "IWS_IDENTITY_UNKNOWN"):
            self.runtime.perform_setup("enroll", self.paths, Boundary(), now=NOW)

    def test_helper_accepts_only_fixed_verbs_and_revalidates_after_authorization(self):
        self.current()
        self.incoming()
        self.prepare()
        with self.assertRaisesRegex(ValueError, "IWS_INVOCATION_INVALID"):
            self.runtime.dispatch_helper(["runtime.py", "../../bin/sh"], self.paths, Boundary(), now=NOW)
        with self.assertRaisesRegex(ValueError, "IWS_SETUP_ACTION_INVALID"):
            self.runtime.dispatch_helper(["runtime.py", "enroll"], self.paths, Boundary(), now=NOW)

    def test_unprivileged_controller_exposes_only_fixed_setup_choices(self):
        spec = importlib.util.spec_from_file_location("setup_ui", SETUP_UI)
        ui = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(ui)
        self.assertEqual(ui.view_for({"mode": "fresh"})["action"], "enroll")
        self.assertEqual(ui.view_for({"mode": "repair"})["action"], "repair")
        replacement = ui.view_for({"mode": "replace", "existingAvailable": True})
        self.assertEqual(replacement["action"], "replace")
        self.assertTrue(replacement["confirm"])
        expired = ui.view_for({"mode": "expired", "existingAvailable": True})
        self.assertIsNone(expired["action"])
        self.assertTrue(expired["canOpen"])
        self.assertEqual(
            ui.helper_command("replace"),
            ["/usr/bin/pkexec", "/usr/lib/iws-client/iws-setup-helper", "replace"],
        )
        with self.assertRaises(ValueError):
            ui.helper_command("/bin/sh")


if __name__ == "__main__":
    unittest.main()

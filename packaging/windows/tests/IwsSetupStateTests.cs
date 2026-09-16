using System;
using System.IO;
using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;

internal static class IwsSetupStateTests {
    private static int failures;

    private static void Assert(bool condition, string message) {
        if (!condition) {
            Console.Error.WriteLine("FAIL: " + message);
            failures += 1;
        }
    }

    private static IwsSetupEvidence Evidence() {
        return new IwsSetupEvidence {
            ArtifactDeviceId = "device-a",
            ArtifactGeneration = 2,
            ArtifactExpiresUtc = new DateTime(2026, 9, 16, 0, 0, 0, DateTimeKind.Utc),
            NowUtc = new DateTime(2026, 9, 15, 0, 0, 0, DateTimeKind.Utc)
        };
    }

    private static void Main() {
        const string validManifest = "{\"deviceId\":\"devicea\",\"generation\":2," +
            "\"platform\":\"WINDOWS\",\"clientHostname\":\"iws-devicea-g2\"," +
            "\"clientCheckpoint\":\"secure-client-v1.0.1\",\"expiresAt\":\"2026-09-16T00:00:00Z\"," +
            "\"management_server\":\"https://api.netbird.io:443\"," +
            "\"iws_entrypoint\":\"https://portal.iws.internal/\"," +
            "\"releaseProvenance\":{\"releaseTag\":\"secure-client-v1.0.1\"," +
            "\"sourceCommit\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"," +
            "\"tagObject\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\"}}";
        IwsSetupManifest manifest = IwsSetupMetadata.ParseManifest(validManifest);
        Assert(manifest.DeviceId == "devicea" && manifest.Generation == 2,
            "valid public manifest identity was not preserved");
        Assert(manifest.ExpiresUtc == new DateTime(2026, 9, 16, 0, 0, 0, DateTimeKind.Utc),
            "valid manifest expiry was not parsed as UTC");
        try {
            IwsSetupMetadata.ParseManifest(validManifest.Replace("secure-client-v1.0.1", "wrong-release"));
            Assert(false, "wrong release provenance was accepted");
        }
        catch (InvalidDataException) { }
        try {
            IwsSetupMetadata.ParseManifest(validManifest.Replace(
                "https://portal.iws.internal/", "http://portal.iws.internal/"));
            Assert(false, "HTTP portal fallback was accepted");
        }
        catch (InvalidDataException) { }

        IwsInstallationReceipt receipt = IwsSetupMetadata.ParseReceipt(
            "{\"schemaVersion\":1,\"deviceId\":\"devicea\",\"generation\":2," +
            "\"clientCheckpoint\":\"secure-client-v1.0.1\"}");
        Assert(receipt.DeviceId == "devicea" && receipt.Generation == 2,
            "valid protected receipt identity was not preserved");
        const string establishedEvidence = "{\"schemaVersion\":1,\"status\":\"ESTABLISHED\"," +
            "\"deviceId\":\"devicea\",\"generation\":2," +
            "\"clientCheckpoint\":\"secure-client-v1.0.1\"}";
        Assert(IwsSetupMetadata.IsEnrollmentMaterialUnavailable(establishedEvidence,
            "devicea", 2, "secure-client-v1.0.1"),
            "established generation evidence was not recognized");
        Assert(!IwsSetupMetadata.IsEnrollmentMaterialUnavailable(establishedEvidence,
            "devicea", 3, "secure-client-v1.0.1"),
            "different generation was incorrectly marked consumed");

        string retryRoot = Path.Combine(Path.GetTempPath(), "iws-retry-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(retryRoot);
        try {
            byte[] keyBytes = Encoding.ASCII.GetBytes("credential-free-fixture-key");
            string archivePath = Path.Combine(retryRoot, "payload.zip");
            using (ZipArchive archive = ZipFile.Open(archivePath, ZipArchiveMode.Create)) {
                ZipArchiveEntry entry = archive.CreateEntry("one-use.key");
                using (Stream stream = entry.Open()) stream.Write(keyBytes, 0, keyBytes.Length);
            }
            string keyHash;
            using (SHA256 sha = SHA256.Create())
                keyHash = BitConverter.ToString(sha.ComputeHash(keyBytes)).Replace("-", "").ToLowerInvariant();
            File.WriteAllText(Path.Combine(retryRoot, "BUNDLE-MANIFEST.sha256"),
                keyHash + "  one-use.key\n", Encoding.ASCII);
            IwsSetupEvidence retryEvidence = Evidence();
            retryEvidence.ServicePresent = true;
            retryEvidence.ServiceOwned = true;
            retryEvidence.NativeIdentityStatus = IwsNativeIdentityStatus.NeedsLogin;
            IwsSetupDecision retryDecision = IwsSetupStateMachine.Evaluate(retryEvidence);
            Assert(IwsSetupRecovery.RestoreEnrollmentKeyForRetry(retryRoot,
                IwsSetupFailure.EnrollmentTransient, retryDecision),
                "eligible transient retry did not restore verified embedded key");
            Assert(File.ReadAllText(Path.Combine(retryRoot, "one-use.key")) ==
                "credential-free-fixture-key", "retry restored wrong key bytes");
            IwsSetupRecovery.DeleteTemporaryEnrollmentKey(retryRoot);
            Assert(!File.Exists(Path.Combine(retryRoot, "one-use.key")),
                "terminal retry cleanup retained temporary enrollment material");
            Assert(!IwsSetupRecovery.RestoreEnrollmentKeyForRetry(retryRoot,
                IwsSetupFailure.EnrollmentCredentialRejected, retryDecision),
                "known-rejected enrollment material was restored");
            Assert(!File.Exists(Path.Combine(retryRoot, "one-use.key")),
                "known-rejected retry recreated key material");
            var retryRecords = new System.Collections.Generic.List<string>();
            IwsSetupDiagnostics retryDiagnostics = new IwsSetupDiagnostics(
                delegate(string record) { retryRecords.Add(record); });
            retryDiagnostics.AcceptChildOutput("IWS_SETUP_ERROR=ENROLLMENT_TRANSIENT");
            IwsSetupEvidence laterNeedsLogin = Evidence();
            laterNeedsLogin.ServicePresent = true;
            laterNeedsLogin.ServiceOwned = true;
            laterNeedsLogin.NativeIdentityStatus = IwsNativeIdentityStatus.NeedsLogin;
            IwsSetupDecision preparedRetry = IwsSetupRecovery.PrepareRetry(
                retryRoot, retryDiagnostics.CurrentFailure, laterNeedsLogin);
            Assert(preparedRetry != null && preparedRetry.State == IwsDetectedState.PartialNeedsLogin &&
                preparedRetry.UseEnrollmentKey,
                "actual Setup retry path did not select eligible NeedsLogin enrollment");
            Assert(File.Exists(Path.Combine(retryRoot, "one-use.key")),
                "actual Setup retry path did not restore verified temporary key");
            IwsSetupRecovery.DeleteTemporaryEnrollmentKey(retryRoot);
            IwsSetupEvidence enrolledEvidence = Evidence();
            enrolledEvidence.ServicePresent = true;
            enrolledEvidence.ServiceOwned = true;
            enrolledEvidence.ReceiptPresent = true;
            enrolledEvidence.ReceiptDeviceId = "device-a";
            enrolledEvidence.ReceiptGeneration = 2;
            IwsSetupDecision enrolledDecision = IwsSetupStateMachine.Evaluate(enrolledEvidence);
            Assert(!IwsSetupRecovery.RestoreEnrollmentKeyForRetry(retryRoot,
                IwsSetupFailure.EnrollmentTransient, enrolledDecision),
                "enrolled identity retry restored enrollment material");
        }
        finally { Directory.Delete(retryRoot, true); }

        IwsSetupEvidence fresh = Evidence();
        IwsSetupDecision freshDecision = IwsSetupStateMachine.Evaluate(fresh);
        Assert(freshDecision.State == IwsDetectedState.Fresh, "fresh host was not classified as fresh");
        Assert(freshDecision.DefaultAction == IwsSetupAction.Install, "fresh host did not default to Install");
        Assert(freshDecision.UseEnrollmentKey, "fresh install did not select enrollment");

        IwsSetupEvidence partial = Evidence();
        partial.ServicePresent = true;
        partial.ServiceOwned = true;
        partial.NativeIdentityStatus = IwsNativeIdentityStatus.NeedsLogin;
        IwsSetupDecision partialDecision = IwsSetupStateMachine.Evaluate(partial);
        Assert(partialDecision.State == IwsDetectedState.PartialNeedsLogin,
            "owned service-only host was not classified as resumable NeedsLogin");
        Assert(partialDecision.DefaultAction == IwsSetupAction.Repair,
            "partial host did not default to Repair");
        Assert(partialDecision.UseEnrollmentKey,
            "partial NeedsLogin repair did not select enrollment");
        Assert(partialDecision.CanCleanReinstall,
            "fresh eligible partial install did not expose explicit clean reinstall");

        IwsSetupEvidence rejectedPartial = Evidence();
        rejectedPartial.ServicePresent = true;
        rejectedPartial.ServiceOwned = true;
        rejectedPartial.NativeIdentityStatus = IwsNativeIdentityStatus.NeedsLogin;
        rejectedPartial.ArtifactEnrollmentMaterialUnavailable = true;
        IwsSetupDecision rejectedPartialDecision = IwsSetupStateMachine.Evaluate(rejectedPartial);
        Assert(rejectedPartialDecision.DefaultAction == IwsSetupAction.Blocked,
            "known-used or rejected artifact remained eligible for enrollment");
        Assert(!rejectedPartialDecision.UseEnrollmentKey && !rejectedPartialDecision.CanCleanReinstall,
            "known-used or rejected artifact exposed key reuse or destructive clean");

        IwsSetupEvidence stoppedPartial = Evidence();
        stoppedPartial.ServicePresent = true;
        stoppedPartial.ServiceOwned = true;
        stoppedPartial.ServiceRunning = false;
        stoppedPartial.NativeIdentityStatus = IwsNativeIdentityStatus.Unknown;
        IwsSetupDecision stoppedDecision = IwsSetupStateMachine.Evaluate(stoppedPartial);
        Assert(stoppedDecision.State == IwsDetectedState.UnknownIdentity,
            "stopped owned service was treated as proven NeedsLogin");
        Assert(!stoppedDecision.UseEnrollmentKey,
            "stopped owned service attempted enrollment without explicit NeedsLogin evidence");
        Assert(stoppedDecision.RequiresReassignmentConfirmation,
            "unknown stopped identity allowed ordinary clean without reassignment confirmation");

        IwsSetupEvidence existing = Evidence();
        existing.ServicePresent = true;
        existing.ServiceOwned = true;
        existing.ServiceRunning = true;
        existing.NativeIdentityStatus = IwsNativeIdentityStatus.Registered;
        existing.ReceiptPresent = true;
        existing.ReceiptDeviceId = "device-a";
        existing.ReceiptGeneration = 2;
        existing.ShellAvailable = false;
        IwsSetupDecision existingDecision = IwsSetupStateMachine.Evaluate(existing);
        Assert(existingDecision.State == IwsDetectedState.ExistingIdentity,
            "matching registered identity was not classified for repair");
        Assert(existingDecision.DefaultAction == IwsSetupAction.Repair,
            "registered identity did not default to Repair");
        Assert(!existingDecision.UseEnrollmentKey,
            "registered identity repair attempted to reuse enrollment material");

        IwsSetupEvidence resumed = Evidence();
        resumed.ServicePresent = true;
        resumed.ServiceOwned = true;
        resumed.NativeIdentityStatus = IwsNativeIdentityStatus.Registered;
        resumed.ReceiptPresent = true;
        resumed.ReceiptDeviceId = "device-a";
        resumed.ReceiptGeneration = 2;
        IwsSetupDecision resumedDecision = IwsSetupStateMachine.Evaluate(resumed);
        Assert(!resumedDecision.UseEnrollmentKey,
            "post-enrollment rerun did not resume as a key-free repair");

        IwsSetupEvidence legacy = Evidence();
        legacy.ServicePresent = true;
        legacy.ServiceOwned = true;
        legacy.NativeIdentityStatus = IwsNativeIdentityStatus.Registered;
        IwsSetupDecision legacyDecision = IwsSetupStateMachine.Evaluate(legacy);
        Assert(legacyDecision.State == IwsDetectedState.LegacyIdentity,
            "native enrolled identity without receipt was not preserved as legacy");
        Assert(!legacyDecision.UseEnrollmentKey, "legacy identity was selected for automatic rebinding");
        Assert(legacyDecision.CanCleanReinstall, "fresh artifact did not expose explicit legacy reassignment");
        Assert(legacyDecision.RequiresReassignmentConfirmation,
            "legacy reassignment did not require stronger confirmation");

        IwsSetupEvidence wrongDevice = Evidence();
        wrongDevice.ServicePresent = true;
        wrongDevice.ServiceOwned = true;
        wrongDevice.NativeIdentityStatus = IwsNativeIdentityStatus.Registered;
        wrongDevice.ReceiptPresent = true;
        wrongDevice.ReceiptDeviceId = "device-b";
        wrongDevice.ReceiptGeneration = 1;
        IwsSetupDecision wrongDeviceDecision = IwsSetupStateMachine.Evaluate(wrongDevice);
        Assert(wrongDeviceDecision.State == IwsDetectedState.DifferentDevice,
            "wrong-device artifact was not blocked");
        Assert(wrongDeviceDecision.DefaultAction == IwsSetupAction.Blocked,
            "wrong-device artifact exposed a mutating default action");
        Assert(!wrongDeviceDecision.UseEnrollmentKey, "wrong-device artifact selected silent rebinding");
        Assert(wrongDeviceDecision.CanCleanReinstall,
            "fresh wrong-device artifact did not expose explicit reassignment");
        Assert(wrongDeviceDecision.RequiresReassignmentConfirmation,
            "wrong-device reassignment did not require stronger confirmation");
        Assert(!IwsSetupStateMachine.CanBeginClean(wrongDeviceDecision, true, false),
            "wrong-device clean began without reassignment confirmation");
        Assert(IwsSetupStateMachine.CanBeginClean(wrongDeviceDecision, true, true),
            "fully confirmed fresh reassignment was rejected");

        IwsSetupEvidence expired = Evidence();
        expired.ServicePresent = true;
        expired.ServiceOwned = true;
        expired.NativeIdentityStatus = IwsNativeIdentityStatus.Registered;
        expired.ReceiptPresent = true;
        expired.ReceiptDeviceId = "device-a";
        expired.ReceiptGeneration = 1;
        expired.ArtifactExpiresUtc = expired.NowUtc.AddMinutes(-1);
        IwsSetupDecision expiredDecision = IwsSetupStateMachine.Evaluate(expired);
        Assert(!expiredDecision.CanCleanReinstall, "expired artifact allowed destructive clean reinstall");
        Assert(!expiredDecision.UseEnrollmentKey, "expired artifact selected enrollment");

        IwsSetupEvidence eligibleClean = Evidence();
        eligibleClean.ServicePresent = true;
        eligibleClean.ServiceOwned = true;
        eligibleClean.NativeIdentityStatus = IwsNativeIdentityStatus.Registered;
        eligibleClean.ReceiptPresent = true;
        eligibleClean.ReceiptDeviceId = "device-a";
        eligibleClean.ReceiptGeneration = 1;
        IwsSetupDecision cleanDecision = IwsSetupStateMachine.Evaluate(eligibleClean);
        Assert(cleanDecision.CanCleanReinstall, "newer same-device artifact did not allow clean reinstall");
        Assert(!IwsSetupStateMachine.CanBeginClean(cleanDecision, false, false),
            "clean reinstall began without explicit confirmation");
        Assert(IwsSetupStateMachine.CanBeginClean(cleanDecision, true, false),
            "confirmed eligible clean reinstall was rejected");

        IwsSetupEvidence unowned = Evidence();
        unowned.ServicePresent = true;
        unowned.ServiceOwned = false;
        IwsSetupDecision unownedDecision = IwsSetupStateMachine.Evaluate(unowned);
        Assert(unownedDecision.State == IwsDetectedState.OwnershipConflict,
            "unowned same-name service was not blocked");
        Assert(unownedDecision.DefaultAction == IwsSetupAction.Blocked,
            "unowned service exposed a mutating action");

        Assert(IwsSetupStateMachine.GetFailureAction(IwsSetupFailure.EnrollmentCredentialRejected) == IwsFailureAction.FreshInstaller,
            "enrollment rejection did not request a fresh installer");
        Assert(IwsSetupStateMachine.GetFailureAction(IwsSetupFailure.IdentityNeedsFreshInstaller) == IwsFailureAction.FreshInstaller,
            "lost registered identity did not request a fresh installer");
        Assert(IwsSetupStateMachine.GetFailureAction(IwsSetupFailure.EnrollmentTransient) == IwsFailureAction.RetryRepair,
            "transient enrollment failure did not allow retry/resume");
        Assert(IwsSetupStateMachine.GetFailureAction(IwsSetupFailure.General) == IwsFailureAction.RetryRepair,
            "post-enrollment shell failure was not repairable");

        if (failures != 0) Environment.Exit(1);
        Console.WriteLine("IWS_SETUP_STATE_TESTS=pass");
    }
}

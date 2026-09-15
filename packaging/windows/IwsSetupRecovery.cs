using System;
using System.IO;
using System.IO.Compression;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;

internal static class IwsSetupRecovery {
    internal static IwsSetupDecision PrepareRetry(string workspace, IwsSetupFailure failure,
        IwsSetupEvidence evidence) {
        IwsSetupDecision decision = IwsSetupStateMachine.Evaluate(evidence);
        if (decision.DefaultAction != IwsSetupAction.Install &&
            decision.DefaultAction != IwsSetupAction.Repair) return null;
        if (failure == IwsSetupFailure.EnrollmentTransient &&
            !RestoreEnrollmentKeyForRetry(workspace, failure, decision)) return null;
        return decision;
    }

    internal static void DeleteTemporaryEnrollmentKey(string workspace) {
        string keyPath = Path.Combine(Path.GetFullPath(workspace), "one-use.key");
        if (File.Exists(keyPath)) File.Delete(keyPath);
    }

    internal static bool RestoreEnrollmentKeyForRetry(string workspace, IwsSetupFailure failure,
        IwsSetupDecision decision) {
        if (decision == null) throw new ArgumentNullException("decision");
        if (failure != IwsSetupFailure.EnrollmentTransient ||
            decision.State != IwsDetectedState.PartialNeedsLogin || !decision.UseEnrollmentKey ||
            decision.ArtifactExpired || decision.ArtifactEnrollmentMaterialUnavailable) return false;
        string root = Path.GetFullPath(workspace).TrimEnd(Path.DirectorySeparatorChar);
        string keyPath = Path.Combine(root, "one-use.key");
        string expected = ReadExpectedKeyHash(Path.Combine(root, "BUNDLE-MANIFEST.sha256"));
        byte[] key = ReadKeyFromArchive(Path.Combine(root, "payload.zip"));
        using (SHA256 sha = SHA256.Create()) {
            string actual = BitConverter.ToString(sha.ComputeHash(key)).Replace("-", "").ToLowerInvariant();
            if (!String.Equals(actual, expected, StringComparison.Ordinal)) throw new InvalidDataException();
        }
        if (File.Exists(keyPath)) {
            using (SHA256 sha = SHA256.Create()) {
                string actual = BitConverter.ToString(sha.ComputeHash(File.ReadAllBytes(keyPath)))
                    .Replace("-", "").ToLowerInvariant();
                if (!String.Equals(actual, expected, StringComparison.Ordinal)) throw new InvalidDataException();
            }
        }
        else {
            using (FileStream file = new FileStream(keyPath, FileMode.CreateNew, FileAccess.Write, FileShare.None))
                file.Write(key, 0, key.Length);
        }
        ProtectKeyWhenInProgramData(keyPath);
        return true;
    }

    private static string ReadExpectedKeyHash(string manifestPath) {
        string expected = null;
        foreach (string line in File.ReadAllLines(manifestPath)) {
            string[] parts = line.Split(new[] { "  " }, 2, StringSplitOptions.None);
            if (parts.Length != 2) throw new InvalidDataException();
            string member = parts[1].Replace('\\', '/');
            if (String.Equals(member, "one-use.key", StringComparison.OrdinalIgnoreCase) ||
                String.Equals(member, "./one-use.key", StringComparison.OrdinalIgnoreCase)) {
                if (expected != null || parts[0].Length != 64) throw new InvalidDataException();
                expected = parts[0].ToLowerInvariant();
            }
        }
        if (expected == null) throw new InvalidDataException();
        return expected;
    }

    private static byte[] ReadKeyFromArchive(string archivePath) {
        byte[] key = null;
        using (ZipArchive archive = ZipFile.OpenRead(archivePath)) {
            foreach (ZipArchiveEntry entry in archive.Entries) {
                string member = entry.FullName.Replace('\\', '/');
                if (!String.Equals(member, "one-use.key", StringComparison.OrdinalIgnoreCase) &&
                    !String.Equals(member, "./one-use.key", StringComparison.OrdinalIgnoreCase)) continue;
                if (key != null || entry.Length < 1 || entry.Length > 4096) throw new InvalidDataException();
                using (Stream input = entry.Open())
                using (MemoryStream output = new MemoryStream()) {
                    input.CopyTo(output); key = output.ToArray();
                }
            }
        }
        if (key == null) throw new InvalidDataException();
        return key;
    }

    private static void ProtectKeyWhenInProgramData(string keyPath) {
        string protectedRoot = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),
            "IWS", "Provisioning") + Path.DirectorySeparatorChar;
        if (!Path.GetFullPath(keyPath).StartsWith(protectedRoot, StringComparison.OrdinalIgnoreCase)) return;
        FileSecurity security = new FileSecurity(); security.SetAccessRuleProtection(true, false);
        security.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null),
            FileSystemRights.FullControl, AccessControlType.Allow));
        security.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null),
            FileSystemRights.FullControl, AccessControlType.Allow));
        File.SetAccessControl(keyPath, security);
    }
}

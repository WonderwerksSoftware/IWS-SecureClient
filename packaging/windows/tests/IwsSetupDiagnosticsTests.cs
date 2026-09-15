using System;
using System.Collections.Generic;

internal static class IwsSetupDiagnosticsTests {
    private static int failures;

    private static void Assert(bool condition, string message) {
        if (!condition) {
            Console.Error.WriteLine("FAIL: " + message);
            failures += 1;
        }
    }

    private static void Main() {
        var expected = new Dictionary<string, IwsSetupPhase> {
            {"IWS_SETUP_PHASE=TRANSPORT_INSTALLATION", IwsSetupPhase.TransportInstallation},
            {"IWS_SETUP_PHASE=ENROLLMENT", IwsSetupPhase.Enrollment},
            {"IWS_SETUP_PHASE=TRANSPORT_READY", IwsSetupPhase.TransportReady},
            {"IWS_SETUP_PHASE=TRUST_INSTALLATION", IwsSetupPhase.TrustInstallation},
            {"IWS_SETUP_PHASE=WEBVIEW_SHELL_INSTALLATION", IwsSetupPhase.WebViewShellInstallation},
            {"IWS_SETUP_PHASE=FIREWALL_BOUNDARY_INSTALLATION", IwsSetupPhase.FirewallBoundaryInstallation}
        };

        foreach (KeyValuePair<string, IwsSetupPhase> item in expected) {
            IwsSetupPhase parsed;
            Assert(IwsSetupDiagnostics.TryParsePhaseMarker(item.Key, out parsed),
                "exact phase marker was rejected: " + item.Key);
            Assert(parsed == item.Value, "phase marker was misclassified: " + item.Key);
        }

        foreach (string unsafeLine in new[] {
            null,
            "",
            " IWS_SETUP_PHASE=ENROLLMENT",
            "IWS_SETUP_PHASE=ENROLLMENT ",
            "IWS_SETUP_PHASE=UNKNOWN",
            "setup_key=one-use-secret-canary",
            "ghp_personal-access-token-canary",
            "{\"peerCredentials\":\"peer-secret-canary\"}",
            "System.Exception: arbitrary-exception-canary"
        }) {
            IwsSetupPhase ignored;
            Assert(!IwsSetupDiagnostics.TryParsePhaseMarker(unsafeLine, out ignored),
                "non-whitelisted child output was accepted");
        }

        IwsSetupFailure failure;
        Assert(IwsSetupDiagnostics.TryParseFailureMarker(
            "IWS_SETUP_ERROR=ENROLLMENT_CREDENTIAL_REJECTED", out failure),
            "credential rejection marker was rejected");
        Assert(failure == IwsSetupFailure.EnrollmentCredentialRejected,
            "credential rejection marker was misclassified");
        Assert(IwsSetupDiagnostics.TryParseFailureMarker(
            "IWS_SETUP_ERROR=ENROLLMENT_TRANSIENT", out failure),
            "transient enrollment marker was rejected");
        Assert(failure == IwsSetupFailure.EnrollmentTransient,
            "transient enrollment marker was misclassified");
        Assert(IwsSetupDiagnostics.TryParseFailureMarker(
            "IWS_SETUP_ERROR=IDENTITY_NEEDS_FRESH_INSTALLER", out failure),
            "identity replacement guidance marker was rejected");
        Assert(failure == IwsSetupFailure.IdentityNeedsFreshInstaller,
            "identity replacement guidance marker was misclassified");
        foreach (string unsafeError in new[] {
            null, "", " IWS_SETUP_ERROR=ENROLLMENT_TRANSIENT",
            "IWS_SETUP_ERROR=ENROLLMENT_TRANSIENT ", "IWS_SETUP_ERROR=one-use-secret-canary"
        }) Assert(!IwsSetupDiagnostics.TryParseFailureMarker(unsafeError, out failure),
            "non-whitelisted failure marker was accepted");

        var records = new List<string>();
        var diagnostics = new IwsSetupDiagnostics(delegate(string record) { records.Add(record); });
        diagnostics.AcceptChildOutput("setup_key=one-use-secret-canary");
        diagnostics.AcceptChildOutput("ghp_personal-access-token-canary");
        diagnostics.AcceptChildOutput("{\"payload\":\"payload-json-canary\"}");
        diagnostics.AcceptChildOutput("System.Exception: arbitrary-exception-canary");
        diagnostics.AcceptChildOutput("IWS_SETUP_PHASE=ENROLLMENT");
        diagnostics.AcceptChildOutput("IWS_SETUP_ERROR=ENROLLMENT_TRANSIENT");

        string joined = String.Join("\n", records.ToArray());
        Assert(records.Count == 2, "discarded child output reached the safe log sink");
        Assert(joined.Contains("PHASE|ENROLLMENT|IWS-WIN-003"),
            "accepted phase did not produce its fixed safe phase/code record");
        Assert(joined.Contains("FAILURE|ENROLLMENT_TRANSIENT|IWS-WIN-003"),
            "accepted safe failure did not produce a fixed record");
        Assert(diagnostics.CurrentFailure == IwsSetupFailure.EnrollmentTransient,
            "safe failure state was not retained for retry guidance");
        foreach (string canary in new[] {
            "one-use-secret-canary", "personal-access-token-canary", "payload-json-canary",
            "arbitrary-exception-canary"
        }) Assert(!joined.Contains(canary), "safe log contains redaction canary: " + canary);

        diagnostics.SetPhase(IwsSetupPhase.ClientLaunch);
        Assert(diagnostics.SafePhaseName == "CLIENT_LAUNCH", "client-launch phase name changed");
        Assert(diagnostics.SafeCode == "IWS-WIN-008", "client-launch safe code changed");

        if (failures != 0) Environment.Exit(1);
        Console.WriteLine("IWS_SETUP_DIAGNOSTICS_TESTS=pass");
    }
}

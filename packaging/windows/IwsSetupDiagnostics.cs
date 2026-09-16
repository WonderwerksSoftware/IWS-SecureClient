using System;
using System.Globalization;

internal enum IwsSetupPhase {
    OverlayArchiveVerification,
    TransportInstallation,
    Enrollment,
    EnrollmentEstablished,
    TransportReady,
    TrustInstallation,
    WebViewShellInstallation,
    FirewallBoundaryInstallation,
    ClientLaunch
}

internal sealed class IwsSetupDiagnostics {
    private readonly Action<string> safeLog;

    internal IwsSetupDiagnostics(Action<string> safeLog) {
        if (safeLog == null) throw new ArgumentNullException("safeLog");
        this.safeLog = safeLog;
        CurrentPhase = IwsSetupPhase.OverlayArchiveVerification;
        CurrentFailure = IwsSetupFailure.General;
    }

    internal IwsSetupPhase CurrentPhase { get; private set; }
    internal IwsSetupFailure CurrentFailure { get; private set; }
    internal bool EnrollmentEstablished { get; private set; }

    internal void BeginAttempt() { CurrentFailure = IwsSetupFailure.General; }
    internal string SafePhaseName { get { return GetSafePhaseName(CurrentPhase); } }
    internal string SafeCode { get { return GetSafeCode(CurrentPhase); } }

    internal void SetPhase(IwsSetupPhase phase) {
        CurrentPhase = phase;
        if (phase == IwsSetupPhase.EnrollmentEstablished) EnrollmentEstablished = true;
        safeLog(BuildRecord("PHASE", GetSafePhaseName(phase), GetSafeCode(phase)));
    }

    internal void AcceptChildOutput(string line) {
        IwsSetupPhase phase;
        IwsSetupFailure failure;
        if (TryParsePhaseMarker(line, out phase)) SetPhase(phase);
        else if (TryParseFailureMarker(line, out failure)) {
            CurrentFailure = failure;
            safeLog(BuildRecord("FAILURE", GetSafeFailureName(failure), SafeCode));
        }
    }

    internal void RecordFailure() {
        safeLog(BuildRecord("FAIL", SafePhaseName, SafeCode));
    }

    internal static bool TryParsePhaseMarker(string line, out IwsSetupPhase phase) {
        switch (line) {
            case "IWS_SETUP_PHASE=TRANSPORT_INSTALLATION":
                phase = IwsSetupPhase.TransportInstallation; return true;
            case "IWS_SETUP_PHASE=ENROLLMENT":
                phase = IwsSetupPhase.Enrollment; return true;
            case "IWS_SETUP_PHASE=ENROLLMENT_ESTABLISHED":
                phase = IwsSetupPhase.EnrollmentEstablished; return true;
            case "IWS_SETUP_PHASE=TRANSPORT_READY":
                phase = IwsSetupPhase.TransportReady; return true;
            case "IWS_SETUP_PHASE=TRUST_INSTALLATION":
                phase = IwsSetupPhase.TrustInstallation; return true;
            case "IWS_SETUP_PHASE=WEBVIEW_SHELL_INSTALLATION":
                phase = IwsSetupPhase.WebViewShellInstallation; return true;
            case "IWS_SETUP_PHASE=FIREWALL_BOUNDARY_INSTALLATION":
                phase = IwsSetupPhase.FirewallBoundaryInstallation; return true;
            default:
                phase = IwsSetupPhase.OverlayArchiveVerification; return false;
        }
    }

    internal static bool TryParseFailureMarker(string line, out IwsSetupFailure failure) {
        switch (line) {
            case "IWS_SETUP_ERROR=ENROLLMENT_CREDENTIAL_REJECTED":
                failure = IwsSetupFailure.EnrollmentCredentialRejected; return true;
            case "IWS_SETUP_ERROR=ENROLLMENT_TRANSIENT":
                failure = IwsSetupFailure.EnrollmentTransient; return true;
            case "IWS_SETUP_ERROR=IDENTITY_NEEDS_FRESH_INSTALLER":
                failure = IwsSetupFailure.IdentityNeedsFreshInstaller; return true;
            default:
                failure = IwsSetupFailure.General; return false;
        }
    }

    private static string GetSafeFailureName(IwsSetupFailure failure) {
        switch (failure) {
            case IwsSetupFailure.EnrollmentCredentialRejected: return "ENROLLMENT_CREDENTIAL_REJECTED";
            case IwsSetupFailure.EnrollmentTransient: return "ENROLLMENT_TRANSIENT";
            case IwsSetupFailure.IdentityNeedsFreshInstaller: return "IDENTITY_NEEDS_FRESH_INSTALLER";
            default: return "GENERAL";
        }
    }

    internal static string GetSafePhaseName(IwsSetupPhase phase) {
        switch (phase) {
            case IwsSetupPhase.OverlayArchiveVerification: return "OVERLAY_ARCHIVE_VERIFICATION";
            case IwsSetupPhase.TransportInstallation: return "TRANSPORT_INSTALLATION";
            case IwsSetupPhase.Enrollment: return "ENROLLMENT";
            case IwsSetupPhase.EnrollmentEstablished: return "ENROLLMENT_ESTABLISHED";
            case IwsSetupPhase.TransportReady: return "TRANSPORT_READY";
            case IwsSetupPhase.TrustInstallation: return "TRUST_INSTALLATION";
            case IwsSetupPhase.WebViewShellInstallation: return "WEBVIEW_SHELL_INSTALLATION";
            case IwsSetupPhase.FirewallBoundaryInstallation: return "FIREWALL_BOUNDARY_INSTALLATION";
            case IwsSetupPhase.ClientLaunch: return "CLIENT_LAUNCH";
            default: return "OVERLAY_ARCHIVE_VERIFICATION";
        }
    }

    internal static string GetSafeCode(IwsSetupPhase phase) {
        switch (phase) {
            case IwsSetupPhase.OverlayArchiveVerification: return "IWS-WIN-001";
            case IwsSetupPhase.TransportInstallation: return "IWS-WIN-002";
            case IwsSetupPhase.Enrollment: return "IWS-WIN-003";
            case IwsSetupPhase.EnrollmentEstablished: return "IWS-WIN-003";
            case IwsSetupPhase.TransportReady: return "IWS-WIN-004";
            case IwsSetupPhase.TrustInstallation: return "IWS-WIN-005";
            case IwsSetupPhase.WebViewShellInstallation: return "IWS-WIN-006";
            case IwsSetupPhase.FirewallBoundaryInstallation: return "IWS-WIN-007";
            case IwsSetupPhase.ClientLaunch: return "IWS-WIN-008";
            default: return "IWS-WIN-001";
        }
    }

    private static string BuildRecord(string kind, string phase, string code) {
        return DateTime.UtcNow.ToString("o", CultureInfo.InvariantCulture) + "|" + kind + "|" + phase + "|" + code;
    }
}

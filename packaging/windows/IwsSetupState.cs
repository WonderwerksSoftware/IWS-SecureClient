using System;

internal enum IwsNativeIdentityStatus {
    Unknown,
    NeedsLogin,
    Registered
}

internal enum IwsDetectedState {
    Fresh,
    PartialNeedsLogin,
    ExistingIdentity,
    LegacyIdentity,
    UnknownIdentity,
    DifferentDevice,
    OwnershipConflict
}

internal enum IwsSetupAction {
    Install,
    Repair,
    CleanReinstall,
    OpenIws,
    Blocked,
    Cancel
}

internal enum IwsSetupFailure {
    General,
    EnrollmentTransient,
    EnrollmentCredentialRejected,
    IdentityNeedsFreshInstaller
}

internal enum IwsFailureAction {
    RetryRepair,
    FreshInstaller
}

internal sealed class IwsSetupEvidence {
    internal bool ServicePresent { get; set; }
    internal bool ServiceOwned { get; set; }
    internal bool ServiceRunning { get; set; }
    internal bool StatePresent { get; set; }
    internal IwsNativeIdentityStatus NativeIdentityStatus { get; set; }
    internal bool ReceiptPresent { get; set; }
    internal string ReceiptDeviceId { get; set; }
    internal int ReceiptGeneration { get; set; }
    internal bool ShellAvailable { get; set; }
    internal bool ArtifactEnrollmentMaterialUnavailable { get; set; }
    internal string ArtifactDeviceId { get; set; }
    internal int ArtifactGeneration { get; set; }
    internal DateTime ArtifactExpiresUtc { get; set; }
    internal DateTime NowUtc { get; set; }
}

internal sealed class IwsSetupDecision {
    internal IwsDetectedState State { get; set; }
    internal IwsSetupAction DefaultAction { get; set; }
    internal bool UseEnrollmentKey { get; set; }
    internal bool CanCleanReinstall { get; set; }
    internal bool RequiresReassignmentConfirmation { get; set; }
    internal bool CanOpenIws { get; set; }
    internal bool ArtifactExpired { get; set; }
    internal bool ArtifactEnrollmentMaterialUnavailable { get; set; }
}

internal static class IwsSetupStateMachine {
    internal static IwsSetupDecision Evaluate(IwsSetupEvidence evidence) {
        if (evidence == null) throw new ArgumentNullException("evidence");
        bool expired = evidence.ArtifactExpiresUtc <= evidence.NowUtc;
        IwsSetupDecision decision = new IwsSetupDecision {
            DefaultAction = IwsSetupAction.Blocked,
            ArtifactExpired = expired,
            ArtifactEnrollmentMaterialUnavailable = evidence.ArtifactEnrollmentMaterialUnavailable,
            CanOpenIws = evidence.ShellAvailable
        };

        if (evidence.ServicePresent && !evidence.ServiceOwned) {
            decision.State = IwsDetectedState.OwnershipConflict;
            return decision;
        }

        if (evidence.ReceiptPresent) {
            if (!String.Equals(evidence.ReceiptDeviceId, evidence.ArtifactDeviceId,
                    StringComparison.Ordinal)) {
                decision.State = IwsDetectedState.DifferentDevice;
                decision.CanCleanReinstall = !expired && !evidence.ArtifactEnrollmentMaterialUnavailable;
                decision.RequiresReassignmentConfirmation = true;
                return decision;
            }
            decision.State = IwsDetectedState.ExistingIdentity;
            decision.DefaultAction = IwsSetupAction.Repair;
            decision.CanCleanReinstall = !expired && !evidence.ArtifactEnrollmentMaterialUnavailable &&
                evidence.ArtifactGeneration > evidence.ReceiptGeneration;
            return decision;
        }

        if (evidence.NativeIdentityStatus == IwsNativeIdentityStatus.Registered) {
            decision.State = IwsDetectedState.LegacyIdentity;
            decision.DefaultAction = IwsSetupAction.Repair;
            decision.CanCleanReinstall = !expired && !evidence.ArtifactEnrollmentMaterialUnavailable;
            decision.RequiresReassignmentConfirmation = true;
            return decision;
        }

        if (evidence.NativeIdentityStatus == IwsNativeIdentityStatus.NeedsLogin &&
            evidence.ServicePresent) {
            decision.State = IwsDetectedState.PartialNeedsLogin;
            if (!expired && !evidence.ArtifactEnrollmentMaterialUnavailable) {
                decision.DefaultAction = IwsSetupAction.Repair;
                decision.UseEnrollmentKey = true;
                decision.CanCleanReinstall = true;
            }
            return decision;
        }

        if (evidence.ServicePresent || evidence.StatePresent) {
            decision.State = IwsDetectedState.UnknownIdentity;
            decision.DefaultAction = IwsSetupAction.Repair;
            decision.CanCleanReinstall = !expired && !evidence.ArtifactEnrollmentMaterialUnavailable;
            decision.RequiresReassignmentConfirmation = true;
            return decision;
        }

        decision.State = IwsDetectedState.Fresh;
        if (!expired && !evidence.ArtifactEnrollmentMaterialUnavailable) {
            decision.DefaultAction = IwsSetupAction.Install;
            decision.UseEnrollmentKey = true;
        }
        return decision;
    }

    internal static bool CanBeginClean(IwsSetupDecision decision, bool explicitlyConfirmed,
        bool reassignmentConfirmed) {
        if (decision == null) throw new ArgumentNullException("decision");
        return decision.CanCleanReinstall && explicitlyConfirmed &&
            (!decision.RequiresReassignmentConfirmation || reassignmentConfirmed);
    }

    internal static IwsFailureAction GetFailureAction(IwsSetupFailure failure) {
        return failure == IwsSetupFailure.EnrollmentCredentialRejected ||
            failure == IwsSetupFailure.IdentityNeedsFreshInstaller
            ? IwsFailureAction.FreshInstaller
            : IwsFailureAction.RetryRepair;
    }
}

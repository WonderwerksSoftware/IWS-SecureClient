package com.impactwiring.iwsconnectpoc;

/** Applies current-document observations to the readiness state machine. */
final class PortalDocumentCoordinator {
    private final PortalReadinessCoordinator readiness;
    private final PortalLoadRecovery loadRecovery = new PortalLoadRecovery();
    private final MainFrameNavigationGuard navigation = new MainFrameNavigationGuard();

    PortalDocumentCoordinator(PortalReadinessCoordinator readiness) {
        this.readiness = readiness;
    }

    void expect(long navigationIdentity, String url) {
        loadRecovery.onMainFrameLoadRequested();
        navigation.expect(navigationIdentity, url);
    }

    boolean expects(String url) {
        return navigation.expects(url);
    }

    void onStarted(String url, String currentUrl) {
        loadRecovery.onMainFrameLoadStarted();
        navigation.onStarted(url, currentUrl);
    }

    long pendingIdentityForCurrentUrl(String currentUrl) {
        return navigation.pendingIdentityForCurrentUrl(currentUrl);
    }

    void onObservation(
            long navigationIdentity,
            CurrentDocumentObservation observation,
            String currentUrl,
            boolean allowedObservedUrl) {
        if (!observation.complete) {
            return;
        }
        boolean expectedLocation = allowedObservedUrl && navigation.acceptsObservation(
                navigationIdentity, observation.url, currentUrl);
        CurrentDocumentDecision.Outcome outcome =
                CurrentDocumentDecision.decide(expectedLocation, observation);
        switch (outcome) {
            case SUCCESS:
                navigation.complete(navigationIdentity, observation.url);
                if (loadRecovery.mayRevealPortal(true)) {
                    readiness.onMainFrameSucceeded(navigationIdentity);
                }
                return;
            case RETRYABLE_FAILURE:
                fail(navigationIdentity, PortalReadinessResult.RETRYABLE);
                return;
            case PERMANENT_FAILURE:
            case UNSUPPORTED:
                fail(navigationIdentity, PortalReadinessResult.PERMANENT);
                return;
            case WAIT:
            default:
                return;
        }
    }

    void invalidatePending() {
        navigation.invalidatePending();
    }

    void onTlsError(String currentUrl) {
        if (!navigation.hasCurrentDocument(currentUrl)) {
            return;
        }
        navigation.invalidatePending();
        loadRecovery.onMainFrameLoadFailed();
        readiness.onDocumentTlsFailure();
    }

    private void fail(long navigationIdentity, PortalReadinessResult failure) {
        navigation.fail(navigationIdentity);
        loadRecovery.onMainFrameLoadFailed();
        readiness.onMainFrameFailed(navigationIdentity, failure);
    }
}

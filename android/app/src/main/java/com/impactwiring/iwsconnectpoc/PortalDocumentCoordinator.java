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

    void expectReplacing(long navigationIdentity, String url, String baselineEpoch) {
        loadRecovery.onMainFrameLoadRequested();
        navigation.expectReplacing(navigationIdentity, url, baselineEpoch);
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
        if (!navigation.isCurrentPending(navigationIdentity, currentUrl)) {
            return;
        }
        if (!observation.complete) {
            return;
        }
        if (!navigation.acceptsEpoch(
                navigationIdentity, observation.documentEpoch, currentUrl)) {
            return;
        }
        boolean expectedLocation = allowedObservedUrl && navigation.acceptsObservation(
                navigationIdentity,
                observation.url,
                observation.documentEpoch,
                currentUrl);
        CurrentDocumentDecision.Outcome outcome =
                CurrentDocumentDecision.decide(expectedLocation, observation);
        switch (outcome) {
            case SUCCESS:
                navigation.complete(
                        navigationIdentity, observation.url, observation.documentEpoch);
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

    void onPreparationFailed(long navigationIdentity) {
        loadRecovery.onMainFrameLoadFailed();
        readiness.onMainFrameFailed(
                navigationIdentity, PortalReadinessResult.PERMANENT);
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

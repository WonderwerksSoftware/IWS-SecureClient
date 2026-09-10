package com.impactwiring.iwsconnectpoc;

/** Coordinates one bounded portal-readiness episode independently of Android UI classes. */
final class PortalReadinessCoordinator {
    static final long NO_NAVIGATION = 0L;

    interface Clock {
        long nowMillis();
    }

    interface Driver {
        void showConnecting();

        void showUnavailable(boolean tlsFailure);

        void revealPortal();

        void navigateToPortalRoot(long navigationIdentity);

        boolean startProbe(long probeIdentity, long remainingMillis);

        void scheduleRetry(long episode, long delayMillis);

        void scheduleDeadline(long episode, long delayMillis);

        void cancelScheduledWork();
    }

    private static final long EPISODE_BUDGET_MILLIS = 45_000L;
    private static final long INITIAL_RETRY_MILLIS = 250L;
    private static final long MAX_RETRY_MILLIS = 4_000L;

    private final Clock clock;
    private final Driver driver;

    private boolean started;
    private boolean transportConnected;
    private boolean episodeActive;
    private boolean readinessConfirmed;
    private boolean activeAllowedPage;
    private boolean rootNavigationPending;
    private boolean terminalFailure;
    private boolean terminalTlsFailure;
    private boolean probeInFlight;
    private boolean probeResultEligible;
    private boolean waitingForProbeSlot;
    private long probeIdentitySequence;
    private long activeProbeIdentity;
    private long activeProbeEpisode;
    private long navigationIdentitySequence;
    private long expectedNavigationIdentity;
    private long episode;
    private long deadlineMillis;
    private int retryNumber;

    PortalReadinessCoordinator(Clock clock, Driver driver) {
        this.clock = clock;
        this.driver = driver;
    }

    void onStart() {
        started = true;
    }

    void onStop() {
        started = false;
        transportConnected = false;
        invalidateProbeResult();
        invalidateNavigation();
        cancelEpisode();
    }

    void onTransportConnecting() {
        if (!started) {
            return;
        }
        if (transportConnected) {
            transportConnected = false;
            readinessConfirmed = false;
            invalidateProbeResult();
            invalidateNavigation();
        }
        if (terminalFailure) {
            driver.showUnavailable(terminalTlsFailure);
            return;
        }
        if (!episodeActive) {
            beginEpisode();
        } else {
            driver.showConnecting();
        }
    }

    void onTransportDisconnected() {
        if (!started) {
            return;
        }
        boolean connectionWasActive = transportConnected;
        if (connectionWasActive) {
            transportConnected = false;
            readinessConfirmed = false;
            invalidateProbeResult();
            invalidateNavigation();
        }
        if (terminalFailure) {
            driver.showUnavailable(terminalTlsFailure);
            return;
        }
        if (episodeActive) {
            driver.showConnecting();
        } else if (connectionWasActive || activeAllowedPage) {
            beginEpisode();
        } else {
            driver.showUnavailable(false);
        }
    }

    void onTransportConnected() {
        if (!started || transportConnected) {
            return;
        }
        transportConnected = true;
        if (terminalFailure) {
            driver.showUnavailable(terminalTlsFailure);
            return;
        }
        if (!episodeActive) {
            beginEpisode();
        } else {
            driver.showConnecting();
            maybeStartProbe();
        }
    }

    void onManualRetry() {
        if (!started) {
            return;
        }
        terminalFailure = false;
        terminalTlsFailure = false;
        beginEpisode();
    }

    void onPortalRootRequested() {
        if (!started) {
            return;
        }
        rootNavigationPending = true;
        invalidateNavigation();
        if (terminalFailure) {
            driver.showUnavailable(terminalTlsFailure);
            return;
        }
        if (transportConnected && readinessConfirmed && !episodeActive) {
            navigateToPortalRoot();
            return;
        }
        if (!episodeActive) {
            beginEpisode();
        } else {
            driver.showConnecting();
            maybeStartProbe();
        }
    }

    long onMainFrameLoadRequested() {
        expectedNavigationIdentity = ++navigationIdentitySequence;
        return expectedNavigationIdentity;
    }

    void onRetryDue(long retryEpisode) {
        if (!isCurrentActiveEpisode(retryEpisode)) {
            return;
        }
        if (isExpired()) {
            expireEpisode();
            return;
        }
        maybeStartProbe();
    }

    void onDeadline(long deadlineEpisode) {
        if (!isCurrentActiveEpisode(deadlineEpisode)) {
            return;
        }
        long remaining = deadlineMillis - clock.nowMillis();
        if (remaining > 0L) {
            driver.scheduleDeadline(episode, remaining);
            return;
        }
        expireEpisode();
    }

    void onProbeSlotAvailable() {
        if (!waitingForProbeSlot) {
            return;
        }
        waitingForProbeSlot = false;
        maybeStartProbe();
    }

    void onProbeCompleted(long completedProbeIdentity, PortalReadinessResult result) {
        if (!probeInFlight || completedProbeIdentity != activeProbeIdentity) {
            return;
        }
        probeInFlight = false;
        boolean mayAcceptResult = probeResultEligible
                && activeProbeEpisode == episode
                && isCurrentActiveEpisode(activeProbeEpisode)
                && transportConnected;
        probeResultEligible = false;
        if (!mayAcceptResult) {
            maybeStartProbe();
            return;
        }
        if (isExpired()) {
            expireEpisode();
            return;
        }
        switch (result) {
            case READY:
                readinessConfirmed = true;
                if (rootNavigationPending || !activeAllowedPage) {
                    navigateToPortalRoot();
                } else {
                    finishReadyEpisode();
                }
                return;
            case FATAL_TLS:
                failEpisode(true);
                return;
            case PERMANENT:
                failEpisode(false);
                return;
            case RETRYABLE:
            default:
                scheduleRetry();
        }
    }

    void onMainFrameSucceeded(long navigationIdentity) {
        if (navigationIdentity == NO_NAVIGATION
                || navigationIdentity != expectedNavigationIdentity) {
            return;
        }
        expectedNavigationIdentity = NO_NAVIGATION;
        if (episodeActive && isExpired()) {
            expireEpisode();
            return;
        }
        activeAllowedPage = true;
        rootNavigationPending = false;
        if (!started || !transportConnected || !readinessConfirmed) {
            return;
        }
        if (episodeActive) {
            finishReadyEpisode();
        } else {
            driver.revealPortal();
        }
    }

    void onMainFrameFailed(long navigationIdentity, PortalReadinessResult failure) {
        if (navigationIdentity == NO_NAVIGATION
                || navigationIdentity != expectedNavigationIdentity) {
            return;
        }
        expectedNavigationIdentity = NO_NAVIGATION;
        if (!started || terminalFailure || !transportConnected) {
            return;
        }
        activeAllowedPage = false;
        if (failure == PortalReadinessResult.FATAL_TLS) {
            failEpisode(true);
            return;
        }
        if (failure == PortalReadinessResult.PERMANENT) {
            failEpisode(false);
            return;
        }
        if (!episodeActive) {
            beginEpisode();
            return;
        }
        if (isExpired()) {
            expireEpisode();
            return;
        }
        readinessConfirmed = false;
        driver.showConnecting();
        maybeStartProbe();
    }

    private void beginEpisode() {
        invalidateProbeResult();
        invalidateNavigation();
        episode++;
        episodeActive = true;
        readinessConfirmed = false;
        waitingForProbeSlot = false;
        retryNumber = 0;
        deadlineMillis = clock.nowMillis() + EPISODE_BUDGET_MILLIS;
        driver.cancelScheduledWork();
        driver.showConnecting();
        driver.scheduleDeadline(episode, EPISODE_BUDGET_MILLIS);
        maybeStartProbe();
    }

    private void maybeStartProbe() {
        if (!started || !transportConnected || !episodeActive
                || probeInFlight || waitingForProbeSlot) {
            return;
        }
        long remaining = deadlineMillis - clock.nowMillis();
        if (remaining <= 0L) {
            expireEpisode();
            return;
        }
        long probeIdentity = ++probeIdentitySequence;
        probeInFlight = true;
        probeResultEligible = true;
        activeProbeIdentity = probeIdentity;
        activeProbeEpisode = episode;
        if (!driver.startProbe(probeIdentity, remaining)) {
            probeInFlight = false;
            probeResultEligible = false;
            waitingForProbeSlot = true;
        }
    }

    private void scheduleRetry() {
        long delay = INITIAL_RETRY_MILLIS << Math.min(retryNumber, 4);
        retryNumber++;
        delay = Math.min(delay, MAX_RETRY_MILLIS);
        if (clock.nowMillis() + delay >= deadlineMillis) {
            return;
        }
        driver.scheduleRetry(episode, delay);
    }

    private void navigateToPortalRoot() {
        long navigationIdentity = onMainFrameLoadRequested();
        driver.navigateToPortalRoot(navigationIdentity);
    }

    private void finishReadyEpisode() {
        episodeActive = false;
        driver.cancelScheduledWork();
        driver.revealPortal();
    }

    private boolean isExpired() {
        return clock.nowMillis() >= deadlineMillis;
    }

    private void expireEpisode() {
        failEpisode(false);
    }

    private void failEpisode(boolean tlsFailure) {
        episodeActive = false;
        readinessConfirmed = false;
        waitingForProbeSlot = false;
        terminalFailure = true;
        terminalTlsFailure = tlsFailure;
        invalidateNavigation();
        driver.cancelScheduledWork();
        driver.showUnavailable(tlsFailure);
    }

    private void cancelEpisode() {
        episode++;
        episodeActive = false;
        readinessConfirmed = false;
        waitingForProbeSlot = false;
        driver.cancelScheduledWork();
    }

    private void invalidateProbeResult() {
        probeResultEligible = false;
    }

    private void invalidateNavigation() {
        expectedNavigationIdentity = NO_NAVIGATION;
    }

    private boolean isCurrentActiveEpisode(long candidateEpisode) {
        return started && episodeActive && candidateEpisode == episode;
    }
}

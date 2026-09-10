package com.impactwiring.iwsconnectpoc;

/** Coordinates one bounded portal-readiness episode independently of Android UI classes. */
final class PortalReadinessCoordinator {
    interface Clock {
        long nowMillis();
    }

    interface Driver {
        void showConnecting();

        void showUnavailable(boolean tlsFailure);

        void revealPortal();

        void navigateToPortalRoot();

        boolean startProbe(long episode, long remainingMillis);

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
    private boolean terminalFailure;
    private boolean terminalTlsFailure;
    private boolean probeInFlight;
    private long probeEpisode;
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
        cancelEpisode();
    }

    void onTransportConnecting() {
        if (!started) {
            return;
        }
        if (transportConnected) {
            transportConnected = false;
            cancelEpisode();
        }
        if (terminalFailure) {
            driver.showUnavailable(terminalTlsFailure);
        } else {
            driver.showConnecting();
        }
    }

    void onTransportDisconnected() {
        if (!started) {
            return;
        }
        transportConnected = false;
        cancelEpisode();
        driver.showUnavailable(terminalFailure && terminalTlsFailure);
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
        if (episodeActive) {
            maybeStartProbe();
        } else {
            beginEpisode();
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
        if (terminalFailure) {
            driver.showUnavailable(terminalTlsFailure);
            return;
        }
        if (transportConnected && readinessConfirmed && !episodeActive) {
            driver.navigateToPortalRoot();
            return;
        }
        beginEpisode();
    }

    void onRetryDue(long retryEpisode) {
        if (!isCurrentActiveEpisode(retryEpisode)) {
            return;
        }
        if (clock.nowMillis() >= deadlineMillis) {
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

    void onProbeCompleted(long completedEpisode, PortalReadinessResult result) {
        if (!probeInFlight || completedEpisode != probeEpisode) {
            return;
        }
        probeInFlight = false;
        if (!isCurrentActiveEpisode(completedEpisode)) {
            maybeStartProbe();
            return;
        }
        if (clock.nowMillis() >= deadlineMillis) {
            expireEpisode();
            return;
        }
        switch (result) {
            case READY:
                readinessConfirmed = true;
                if (activeAllowedPage) {
                    finishReadyEpisode();
                } else {
                    driver.navigateToPortalRoot();
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

    void onMainFrameSucceeded() {
        activeAllowedPage = true;
        if (!started || !transportConnected || !readinessConfirmed) {
            return;
        }
        if (episodeActive) {
            finishReadyEpisode();
        } else {
            driver.revealPortal();
        }
    }

    void onMainFrameFailed(PortalReadinessResult failure) {
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
        readinessConfirmed = false;
        driver.showConnecting();
        maybeStartProbe();
    }

    private void beginEpisode() {
        episode++;
        episodeActive = true;
        readinessConfirmed = false;
        retryNumber = 0;
        deadlineMillis = clock.nowMillis() + EPISODE_BUDGET_MILLIS;
        driver.cancelScheduledWork();
        driver.showConnecting();
        driver.scheduleDeadline(episode, EPISODE_BUDGET_MILLIS);
        maybeStartProbe();
    }

    private void maybeStartProbe() {
        if (!started || !transportConnected || !episodeActive || probeInFlight) {
            return;
        }
        long remaining = deadlineMillis - clock.nowMillis();
        if (remaining <= 0L) {
            expireEpisode();
            return;
        }
        probeInFlight = true;
        probeEpisode = episode;
        if (!driver.startProbe(episode, remaining)) {
            probeInFlight = false;
            scheduleRetry();
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

    private void finishReadyEpisode() {
        episodeActive = false;
        driver.cancelScheduledWork();
        driver.revealPortal();
    }

    private void expireEpisode() {
        failEpisode(false);
    }

    private void failEpisode(boolean tlsFailure) {
        episodeActive = false;
        readinessConfirmed = false;
        terminalFailure = true;
        terminalTlsFailure = tlsFailure;
        driver.cancelScheduledWork();
        driver.showUnavailable(tlsFailure);
    }

    private void cancelEpisode() {
        episode++;
        episodeActive = false;
        readinessConfirmed = false;
        driver.cancelScheduledWork();
    }

    private boolean isCurrentActiveEpisode(long candidateEpisode) {
        return started && episodeActive && candidateEpisode == episode;
    }
}

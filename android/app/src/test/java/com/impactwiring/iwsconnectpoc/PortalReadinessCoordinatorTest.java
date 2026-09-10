package com.impactwiring.iwsconnectpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class PortalReadinessCoordinatorTest {
    @Test
    public void connectedTransportDoesNotNavigateBeforeHttpsReadiness() {
        Fixture fixture = new Fixture();

        fixture.coordinator.onTransportConnected();

        assertEquals(1, fixture.driver.probes.size());
        assertEquals(0, fixture.driver.navigationCount);
        assertTrue(fixture.driver.connectingVisible);
    }

    @Test
    public void transientProbeFailureRetriesAfterCompletionThenNavigatesOnSuccess() {
        Fixture fixture = new Fixture();
        fixture.coordinator.onTransportConnected();
        long episode = fixture.driver.lastProbeEpisode();

        fixture.coordinator.onProbeCompleted(episode, PortalReadinessResult.RETRYABLE);

        assertEquals(1, fixture.driver.probes.size());
        assertEquals(1, fixture.driver.retries.size());
        assertEquals(250L, fixture.driver.retries.get(0).delayMillis);

        fixture.coordinator.onRetryDue(episode);
        fixture.coordinator.onProbeCompleted(
                fixture.driver.lastProbeEpisode(), PortalReadinessResult.READY);

        assertEquals(2, fixture.driver.probes.size());
        assertEquals(1, fixture.driver.navigationCount);
        assertTrue(fixture.driver.connectingVisible);
    }

    @Test
    public void manualRetryCannotOverlapAnUncancellableOldProbe() {
        Fixture fixture = new Fixture();
        fixture.coordinator.onTransportConnected();
        long oldEpisode = fixture.driver.lastProbeEpisode();

        fixture.coordinator.onManualRetry();
        long newEpisode = fixture.driver.lastDeadlineEpisode();

        assertEquals(1, fixture.driver.probes.size());
        fixture.coordinator.onProbeCompleted(oldEpisode, PortalReadinessResult.READY);

        assertEquals(2, fixture.driver.probes.size());
        assertEquals(newEpisode, fixture.driver.lastProbeEpisode());
        assertEquals(0, fixture.driver.navigationCount);
    }

    @Test
    public void recreatedOwnerWaitsForProcessProbeSlotWithoutBackoffPolling() {
        Fixture fixture = new Fixture();
        fixture.driver.probeSlotAvailable = false;

        fixture.coordinator.onTransportConnected();

        assertEquals(1, fixture.driver.probeStartRequests);
        assertEquals(0, fixture.driver.probes.size());
        assertEquals(0, fixture.driver.retries.size());

        fixture.driver.probeSlotAvailable = true;
        fixture.coordinator.onProbeSlotAvailable();

        assertEquals(2, fixture.driver.probeStartRequests);
        assertEquals(1, fixture.driver.probes.size());
    }

    @Test
    public void duplicateConnectedCallbackDoesNotResetBudgetOrStartAnotherProbe() {
        Fixture fixture = new Fixture();
        fixture.coordinator.onTransportConnected();
        long episode = fixture.driver.lastProbeEpisode();
        fixture.clock.nowMillis = 44_000L;

        fixture.coordinator.onTransportConnected();

        assertEquals(1, fixture.driver.probes.size());
        assertEquals(1, fixture.driver.deadlines.size());
        assertEquals(episode, fixture.driver.lastDeadlineEpisode());
    }

    @Test
    public void episodeExpiresOnceAndLateProbeSuccessCannotNavigate() {
        Fixture fixture = new Fixture();
        fixture.coordinator.onTransportConnected();
        long episode = fixture.driver.lastProbeEpisode();
        fixture.clock.nowMillis = 45_000L;

        fixture.coordinator.onDeadline(episode);
        fixture.coordinator.onProbeCompleted(episode, PortalReadinessResult.READY);

        assertEquals(1, fixture.driver.unavailableCount);
        assertEquals(0, fixture.driver.navigationCount);
        assertEquals(1, fixture.driver.probes.size());
        assertFalse(fixture.driver.connectingVisible);
    }

    @Test
    public void fatalTlsFailureIsTerminalWithoutRetry() {
        Fixture fixture = new Fixture();
        fixture.coordinator.onTransportConnected();
        long episode = fixture.driver.lastProbeEpisode();

        fixture.coordinator.onProbeCompleted(episode, PortalReadinessResult.FATAL_TLS);

        assertEquals(1, fixture.driver.tlsUnavailableCount);
        assertEquals(0, fixture.driver.retries.size());
        assertEquals(0, fixture.driver.navigationCount);
    }

    @Test
    public void staleCompletionAfterStopCannotNavigateOrRevealPortal() {
        Fixture fixture = new Fixture();
        fixture.coordinator.onTransportConnected();
        long episode = fixture.driver.lastProbeEpisode();

        fixture.coordinator.onStop();
        fixture.coordinator.onProbeCompleted(episode, PortalReadinessResult.READY);

        assertEquals(0, fixture.driver.navigationCount);
        assertEquals(0, fixture.driver.portalRevealCount);
    }

    @Test
    public void reconnectReadinessPreservesLoadedRouteInsteadOfLoadingRoot() {
        Fixture fixture = new Fixture();
        fixture.coordinator.onTransportConnected();
        long firstEpisode = fixture.driver.lastProbeEpisode();
        fixture.coordinator.onProbeCompleted(firstEpisode, PortalReadinessResult.READY);
        fixture.completeLastNavigationSuccessfully();
        assertEquals(1, fixture.driver.navigationCount);

        fixture.coordinator.onTransportDisconnected();
        fixture.coordinator.onTransportConnected();
        long reconnectEpisode = fixture.driver.lastProbeEpisode();
        fixture.coordinator.onProbeCompleted(reconnectEpisode, PortalReadinessResult.READY);

        assertEquals(1, fixture.driver.navigationCount);
        assertEquals(2, fixture.driver.portalRevealCount);
    }

    @Test
    public void activeWorkflowIsNotReloadedByRepeatedConnectedCallbacks() {
        Fixture fixture = new Fixture();
        fixture.coordinator.onTransportConnected();
        long episode = fixture.driver.lastProbeEpisode();
        fixture.coordinator.onProbeCompleted(episode, PortalReadinessResult.READY);
        fixture.completeLastNavigationSuccessfully();

        fixture.coordinator.onTransportConnected();
        fixture.coordinator.onTransportConnected();

        assertEquals(1, fixture.driver.navigationCount);
        assertEquals(1, fixture.driver.probes.size());
    }

    @Test
    public void firstNavigationErrorResumesReadinessWithinOriginalEpisode() {
        Fixture fixture = new Fixture();
        fixture.coordinator.onTransportConnected();
        long episode = fixture.driver.lastProbeEpisode();
        fixture.coordinator.onProbeCompleted(episode, PortalReadinessResult.READY);
        assertEquals(1, fixture.driver.navigationCount);
        fixture.clock.nowMillis = 10_000L;

        fixture.failLastNavigation(PortalReadinessResult.RETRYABLE);

        assertEquals(2, fixture.driver.probes.size());
        assertEquals(1, fixture.driver.deadlines.size());
        fixture.coordinator.onProbeCompleted(
                fixture.driver.lastProbeEpisode(), PortalReadinessResult.READY);
        assertEquals(2, fixture.driver.navigationCount);
    }

    @Test
    public void fatalTlsNavigationFailureNeverResumesReadiness() {
        Fixture fixture = new Fixture();
        fixture.coordinator.onTransportConnected();
        long episode = fixture.driver.lastProbeEpisode();
        fixture.coordinator.onProbeCompleted(episode, PortalReadinessResult.READY);

        fixture.failLastNavigation(PortalReadinessResult.FATAL_TLS);

        assertEquals(1, fixture.driver.probes.size());
        assertEquals(1, fixture.driver.tlsUnavailableCount);
        assertEquals(0, fixture.driver.retries.size());
    }

    @Test
    public void fatalTlsNavigationFailureCannotBeOverwrittenByLaterErrorCallback() {
        Fixture fixture = new Fixture();
        fixture.coordinator.onTransportConnected();
        long episode = fixture.driver.lastProbeEpisode();
        fixture.coordinator.onProbeCompleted(episode, PortalReadinessResult.READY);

        long failedNavigation = fixture.driver.lastNavigationIdentity;
        fixture.coordinator.onMainFrameFailed(
                failedNavigation, PortalReadinessResult.FATAL_TLS);
        fixture.coordinator.onMainFrameFailed(
                failedNavigation, PortalReadinessResult.RETRYABLE);

        assertEquals(1, fixture.driver.probes.size());
        assertEquals(1, fixture.driver.tlsUnavailableCount);
        assertFalse(fixture.driver.connectingVisible);
    }

    @Test
    public void fatalTlsPresentationCannotBeOverwrittenByTransportCallbacks() {
        Fixture fixture = new Fixture();
        fixture.coordinator.onTransportConnected();
        long episode = fixture.driver.lastProbeEpisode();

        fixture.coordinator.onProbeCompleted(episode, PortalReadinessResult.FATAL_TLS);
        fixture.coordinator.onTransportDisconnected();
        assertTrue(fixture.driver.lastUnavailableWasTls);
        fixture.coordinator.onTransportConnecting();
        assertTrue(fixture.driver.lastUnavailableWasTls);
        fixture.coordinator.onTransportConnected();

        assertTrue(fixture.driver.lastUnavailableWasTls);
        assertEquals(1, fixture.driver.probes.size());
        assertFalse(fixture.driver.connectingVisible);
    }

    @Test
    public void staleNavigationFailureAfterDisconnectCannotCauseReconnectReload() {
        Fixture fixture = new Fixture();
        fixture.coordinator.onTransportConnected();
        long firstEpisode = fixture.driver.lastProbeEpisode();
        fixture.coordinator.onProbeCompleted(firstEpisode, PortalReadinessResult.READY);
        long staleNavigation = fixture.driver.lastNavigationIdentity;
        fixture.completeLastNavigationSuccessfully();

        fixture.coordinator.onTransportDisconnected();
        fixture.coordinator.onMainFrameFailed(
                staleNavigation, PortalReadinessResult.RETRYABLE);
        fixture.coordinator.onTransportConnected();
        long reconnectEpisode = fixture.driver.lastProbeEpisode();
        fixture.coordinator.onProbeCompleted(reconnectEpisode, PortalReadinessResult.READY);

        assertEquals(1, fixture.driver.navigationCount);
        assertEquals(2, fixture.driver.portalRevealCount);
    }

    @Test
    public void retryBackoffIsCappedAndCannotSchedulePastDeadline() {
        Fixture fixture = new Fixture();
        fixture.coordinator.onTransportConnected();
        long episode = fixture.driver.lastProbeEpisode();

        long[] expectedDelays = {250L, 500L, 1_000L, 2_000L, 4_000L, 4_000L};
        for (long expectedDelay : expectedDelays) {
            fixture.coordinator.onProbeCompleted(
                    fixture.driver.lastProbeEpisode(), PortalReadinessResult.RETRYABLE);
            assertEquals(expectedDelay,
                    fixture.driver.retries.get(fixture.driver.retries.size() - 1).delayMillis);
            fixture.coordinator.onRetryDue(episode);
        }

        fixture.clock.nowMillis = 44_000L;
        fixture.coordinator.onProbeCompleted(
                fixture.driver.lastProbeEpisode(), PortalReadinessResult.RETRYABLE);

        assertEquals(expectedDelays.length, fixture.driver.retries.size());
    }

    @Test
    public void transportFlapsCannotRestartTheOriginalRecoveryDeadline() {
        Fixture fixture = new Fixture();
        fixture.coordinator.onTransportConnected();
        long originalEpisode = fixture.driver.lastDeadlineEpisode();
        fixture.clock.nowMillis = 44_000L;

        fixture.coordinator.onTransportConnecting();
        fixture.coordinator.onTransportDisconnected();
        fixture.coordinator.onTransportConnected();
        fixture.clock.nowMillis = 45_000L;
        fixture.coordinator.onDeadline(originalEpisode);

        assertEquals(1, fixture.driver.unavailableCount);
        assertFalse(fixture.driver.connectingVisible);
    }

    @Test
    public void connectingWithoutConnectedStillExpiresAtOneBudget() {
        Fixture fixture = new Fixture();

        fixture.coordinator.onTransportConnecting();
        long episode = fixture.driver.lastDeadlineEpisode();
        fixture.clock.nowMillis = 45_000L;
        fixture.coordinator.onDeadline(episode);

        assertEquals(1, fixture.driver.unavailableCount);
        assertEquals(0, fixture.driver.probes.size());
    }

    @Test
    public void staleFailureAfterReconnectCannotClearLoadedWorkflow() {
        Fixture fixture = loadedWorkflow();
        long staleNavigation = fixture.driver.lastNavigationIdentity;

        fixture.coordinator.onTransportDisconnected();
        fixture.coordinator.onTransportConnected();
        long reconnectEpisode = fixture.driver.lastProbeEpisode();
        fixture.coordinator.onMainFrameFailed(
                staleNavigation, PortalReadinessResult.RETRYABLE);
        fixture.coordinator.onProbeCompleted(reconnectEpisode, PortalReadinessResult.READY);

        assertEquals(1, fixture.driver.navigationCount);
        assertEquals(2, fixture.driver.portalRevealCount);
    }

    @Test
    public void staleSuccessAfterManualRetryCannotSatisfyNewNavigation() {
        Fixture fixture = new Fixture();
        fixture.coordinator.onTransportConnected();
        long firstEpisode = fixture.driver.lastProbeEpisode();
        fixture.coordinator.onProbeCompleted(firstEpisode, PortalReadinessResult.READY);
        assertEquals(1, fixture.driver.navigationCount);
        long staleNavigation = fixture.driver.lastNavigationIdentity;

        fixture.coordinator.onManualRetry();
        long retryEpisode = fixture.driver.lastProbeEpisode();
        fixture.coordinator.onMainFrameSucceeded(staleNavigation);
        fixture.coordinator.onProbeCompleted(retryEpisode, PortalReadinessResult.READY);

        assertEquals(2, fixture.driver.navigationCount);
        assertEquals(0, fixture.driver.portalRevealCount);
    }

    @Test
    public void staleFailureCannotBorrowIdentityFromNewerNavigationRequest() {
        Fixture fixture = new Fixture();
        fixture.coordinator.onTransportConnected();
        long episode = fixture.driver.lastProbeEpisode();
        fixture.coordinator.onProbeCompleted(episode, PortalReadinessResult.READY);
        long staleNavigation = fixture.driver.lastNavigationIdentity;
        long newerNavigation = fixture.coordinator.onMainFrameLoadRequested();

        fixture.coordinator.onMainFrameFailed(
                staleNavigation, PortalReadinessResult.RETRYABLE);
        fixture.coordinator.onMainFrameSucceeded(newerNavigation);

        assertEquals(1, fixture.driver.probes.size());
        assertEquals(1, fixture.driver.portalRevealCount);
    }

    @Test
    public void homeDuringReconnectNavigatesRootOnceAfterReadiness() {
        Fixture fixture = loadedWorkflow();

        fixture.coordinator.onTransportDisconnected();
        fixture.coordinator.onPortalRootRequested();
        fixture.coordinator.onTransportConnected();
        long reconnectEpisode = fixture.driver.lastProbeEpisode();
        fixture.coordinator.onProbeCompleted(reconnectEpisode, PortalReadinessResult.READY);

        assertEquals(2, fixture.driver.navigationCount);
        assertEquals(1, fixture.driver.portalRevealCount);
    }

    @Test
    public void homeDuringReconnectProbeRemainsPendingUntilReady() {
        Fixture fixture = loadedWorkflow();

        fixture.coordinator.onTransportDisconnected();
        fixture.coordinator.onTransportConnected();
        long reconnectProbe = fixture.driver.lastProbeEpisode();
        fixture.coordinator.onPortalRootRequested();
        fixture.coordinator.onProbeCompleted(reconnectProbe, PortalReadinessResult.READY);

        assertEquals(2, fixture.driver.navigationCount);
        assertEquals(1, fixture.driver.portalRevealCount);
    }

    @Test
    public void mainFrameSuccessAfterDeadlineCannotRevealPortalBeforeTimerRuns() {
        Fixture fixture = new Fixture();
        fixture.coordinator.onTransportConnected();
        long episode = fixture.driver.lastProbeEpisode();
        fixture.clock.nowMillis = 44_000L;
        fixture.coordinator.onProbeCompleted(episode, PortalReadinessResult.READY);
        fixture.clock.nowMillis = 46_000L;

        fixture.completeLastNavigationSuccessfully();

        assertEquals(1, fixture.driver.unavailableCount);
        assertEquals(0, fixture.driver.portalRevealCount);
    }

    private static Fixture loadedWorkflow() {
        Fixture fixture = new Fixture();
        fixture.coordinator.onTransportConnected();
        long episode = fixture.driver.lastProbeEpisode();
        fixture.coordinator.onProbeCompleted(episode, PortalReadinessResult.READY);
        fixture.completeLastNavigationSuccessfully();
        return fixture;
    }

    private static final class Fixture {
        final FakeClock clock = new FakeClock();
        final FakeDriver driver = new FakeDriver();
        final PortalReadinessCoordinator coordinator =
                new PortalReadinessCoordinator(clock, driver);

        Fixture() {
            coordinator.onStart();
        }

        void completeLastNavigationSuccessfully() {
            coordinator.onMainFrameSucceeded(driver.lastNavigationIdentity);
        }

        void failLastNavigation(PortalReadinessResult failure) {
            coordinator.onMainFrameFailed(driver.lastNavigationIdentity, failure);
        }
    }

    private static final class FakeClock implements PortalReadinessCoordinator.Clock {
        long nowMillis;

        @Override
        public long nowMillis() {
            return nowMillis;
        }
    }

    private static final class Scheduled {
        final long episode;
        final long delayMillis;

        Scheduled(long episode, long delayMillis) {
            this.episode = episode;
            this.delayMillis = delayMillis;
        }
    }

    private static final class FakeDriver implements PortalReadinessCoordinator.Driver {
        final List<Scheduled> probes = new ArrayList<>();
        final List<Scheduled> retries = new ArrayList<>();
        final List<Scheduled> deadlines = new ArrayList<>();
        int navigationCount;
        int portalRevealCount;
        int unavailableCount;
        int tlsUnavailableCount;
        boolean connectingVisible;
        boolean lastUnavailableWasTls;
        long lastNavigationIdentity;
        boolean probeSlotAvailable = true;
        int probeStartRequests;

        @Override
        public void showConnecting() {
            connectingVisible = true;
        }

        @Override
        public void showUnavailable(boolean tlsFailure) {
            connectingVisible = false;
            unavailableCount++;
            lastUnavailableWasTls = tlsFailure;
            if (tlsFailure) {
                tlsUnavailableCount++;
            }
        }

        @Override
        public void revealPortal() {
            connectingVisible = false;
            portalRevealCount++;
        }

        @Override
        public void navigateToPortalRoot(long navigationIdentity) {
            navigationCount++;
            lastNavigationIdentity = navigationIdentity;
        }

        @Override
        public boolean startProbe(long episode, long remainingMillis) {
            probeStartRequests++;
            if (!probeSlotAvailable) {
                return false;
            }
            probes.add(new Scheduled(episode, remainingMillis));
            return true;
        }

        @Override
        public void scheduleRetry(long episode, long delayMillis) {
            retries.add(new Scheduled(episode, delayMillis));
        }

        @Override
        public void scheduleDeadline(long episode, long delayMillis) {
            deadlines.add(new Scheduled(episode, delayMillis));
        }

        @Override
        public void cancelScheduledWork() {}

        long lastProbeEpisode() {
            return probes.get(probes.size() - 1).episode;
        }

        long lastDeadlineEpisode() {
            return deadlines.get(deadlines.size() - 1).episode;
        }
    }
}

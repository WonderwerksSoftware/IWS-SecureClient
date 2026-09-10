package com.impactwiring.iwsconnectpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class PortalDocumentCoordinatorTest {
    private static final String ROOT = "https://portal.iws.example/";

    @Test
    public void healthySameUrlReplacementSucceedsWithoutOldTerminalCallback() {
        Fixture fixture = new Fixture();
        long firstNavigation = fixture.beginInitialNavigation();
        fixture.documents.expect(firstNavigation, ROOT);
        fixture.documents.onStarted(ROOT, ROOT);

        fixture.readiness.onManualRetry();
        fixture.readiness.onProbeCompleted(
                fixture.driver.lastProbe(), PortalReadinessResult.READY);
        long replacement = fixture.driver.lastNavigation;
        fixture.documents.expect(replacement, ROOT);
        fixture.documents.onStarted(ROOT, ROOT);

        fixture.documents.onObservation(
                replacement, success(ROOT), ROOT, true);

        assertEquals(1, fixture.driver.reveals);
        assertEquals(2, fixture.driver.navigations);
    }

    @Test
    public void oldErrorAndFinishHintsCannotSatisfyOrFailReplacement() {
        Fixture fixture = new Fixture();
        long firstNavigation = fixture.beginInitialNavigation();
        fixture.documents.expect(firstNavigation, ROOT);
        fixture.documents.onStarted(ROOT, ROOT);
        fixture.readiness.onManualRetry();
        fixture.readiness.onProbeCompleted(
                fixture.driver.lastProbe(), PortalReadinessResult.READY);
        long replacement = fixture.driver.lastNavigation;
        fixture.documents.expect(replacement, ROOT);
        fixture.documents.onStarted(ROOT, ROOT);

        fixture.documents.onObservation(
                replacement, CurrentDocumentObservation.parse("\"L\""), ROOT, true);
        fixture.documents.onObservation(
                replacement, CurrentDocumentObservation.parse("\"L\""), ROOT, true);

        assertEquals(0, fixture.driver.reveals);
        assertEquals(2, fixture.driver.probes.size());

        fixture.documents.onObservation(
                replacement,
                CurrentDocumentObservation.parse(
                        "\"C|0|chrome-error%3A%2F%2Fchromewebdata%2F\""),
                ROOT,
                false);

        assertEquals(3, fixture.driver.probes.size());
        assertEquals(0, fixture.driver.reveals);
    }

    @Test
    public void loadedDocumentTlsAfterReconnectLatchesFatal() {
        Fixture fixture = new Fixture();
        long navigation = fixture.beginInitialNavigation();
        fixture.documents.expect(navigation, ROOT);
        fixture.documents.onStarted(ROOT, ROOT);
        fixture.documents.onObservation(navigation, success(ROOT), ROOT, true);
        fixture.readiness.onTransportDisconnected();
        fixture.documents.invalidatePending();
        fixture.readiness.onTransportConnected();
        fixture.readiness.onProbeCompleted(
                fixture.driver.lastProbe(), PortalReadinessResult.READY);

        fixture.documents.onTlsError(ROOT);

        assertTrue(fixture.driver.lastUnavailableTls);
        assertEquals(1, fixture.driver.tlsFailures);
    }

    private static CurrentDocumentObservation success(String url) {
        return CurrentDocumentObservation.parse(
                "\"C|200|" + url
                        .replace(":", "%3A")
                        .replace("/", "%2F") + "\"");
    }

    private static final class Fixture {
        final Driver driver = new Driver();
        final PortalReadinessCoordinator readiness =
                new PortalReadinessCoordinator(() -> 0L, driver);
        final PortalDocumentCoordinator documents =
                new PortalDocumentCoordinator(readiness);

        Fixture() {
            readiness.onStart();
        }

        long beginInitialNavigation() {
            readiness.onTransportConnected();
            readiness.onProbeCompleted(driver.lastProbe(), PortalReadinessResult.READY);
            return driver.lastNavigation;
        }
    }

    private static final class Driver implements PortalReadinessCoordinator.Driver {
        final List<Long> probes = new ArrayList<>();
        int navigations;
        int reveals;
        int tlsFailures;
        long lastNavigation;
        boolean lastUnavailableTls;

        @Override public void showConnecting() {}
        @Override public void showUnavailable(boolean tlsFailure) {
            lastUnavailableTls = tlsFailure;
            if (tlsFailure) tlsFailures++;
        }
        @Override public void revealPortal() { reveals++; }
        @Override public void navigateToPortalRoot(long navigationIdentity) {
            navigations++;
            lastNavigation = navigationIdentity;
        }
        @Override public boolean startProbe(long probeIdentity, long remainingMillis) {
            probes.add(probeIdentity);
            return true;
        }
        @Override public void scheduleRetry(long episode, long delayMillis) {}
        @Override public void scheduleDeadline(long episode, long delayMillis) {}
        @Override public void cancelScheduledWork() {}

        long lastProbe() {
            return probes.get(probes.size() - 1);
        }
    }
}

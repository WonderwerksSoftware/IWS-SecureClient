package com.impactwiring.iwsconnectpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MainFrameNavigationGuardTest {
    @Test
    public void capturedOldObservationCannotSatisfyNewerNavigation() {
        MainFrameNavigationGuard guard = started(
                1L, "https://portal.iws.example/build");
        long oldIdentity = guard.pendingIdentityForCurrentUrl(
                "https://portal.iws.example/build");
        guard.expect(2L, "https://portal.iws.example/inventory");
        guard.onStarted(
                "https://portal.iws.example/inventory",
                "https://portal.iws.example/inventory");

        assertFalse(guard.acceptsObservation(
                oldIdentity,
                "https://portal.iws.example/build",
                "epoch-old",
                "https://portal.iws.example/inventory"));
    }

    @Test
    public void invalidationRejectsDelayedDocumentObservation() {
        MainFrameNavigationGuard guard = started(
                4L, "https://portal.iws.example/build");
        long capturedIdentity = guard.pendingIdentityForCurrentUrl(
                "https://portal.iws.example/build");

        guard.invalidatePending();

        assertFalse(guard.acceptsObservation(
                capturedIdentity,
                "https://portal.iws.example/build",
                "epoch-old",
                "https://portal.iws.example/build"));
    }

    @Test
    public void startMustMatchExpectedAndCurrentUrl() {
        MainFrameNavigationGuard guard = new MainFrameNavigationGuard();
        guard.expect(7L, "https://portal.iws.example/build");

        guard.onStarted(
                "https://portal.iws.example/build",
                "https://portal.iws.example/inventory");

        assertEquals(MainFrameNavigationGuard.NONE,
                guard.pendingIdentityForCurrentUrl(
                        "https://portal.iws.example/inventory"));
    }

    @Test
    public void equivalentRootAndDefaultPortRepresentationsKeepTheirIdentity() {
        MainFrameNavigationGuard guard = new MainFrameNavigationGuard();
        guard.expect(9L, "https://PORTAL.iws.example:443");
        guard.onStarted(
                "https://portal.iws.example/",
                "https://portal.iws.example/");

        assertEquals(9L, guard.pendingIdentityForCurrentUrl(
                "https://portal.iws.example/"));
    }

    @Test
    public void sameUrlReplacementRequiresEpochDifferentFromPreNavigationDocument() {
        MainFrameNavigationGuard guard = started(
                20L, "https://portal.iws.example/");
        guard.invalidatePending();
        guard.expectReplacing(21L, "https://portal.iws.example/", "epoch-a");
        guard.onStarted(
                "https://portal.iws.example/",
                "https://portal.iws.example/");
        long replacementIdentity = guard.pendingIdentityForCurrentUrl(
                "https://portal.iws.example/");

        assertFalse(guard.acceptsObservation(
                replacementIdentity,
                "https://portal.iws.example/",
                "epoch-a",
                "https://portal.iws.example/"));
        assertTrue(guard.acceptsObservation(
                replacementIdentity,
                "https://portal.iws.example/",
                "epoch-b",
                "https://portal.iws.example/"));
    }

    @Test
    public void currentDocumentObservationMustMatchActualWebViewUrl() {
        MainFrameNavigationGuard guard = started(
                30L, "https://portal.iws.example/build");
        long identity = guard.pendingIdentityForCurrentUrl(
                "https://portal.iws.example/build");

        assertFalse(guard.acceptsObservation(
                identity,
                "https://portal.iws.example/build",
                "epoch-build",
                "https://portal.iws.example/inventory"));
    }

    @Test
    public void loadedDocumentKeepsTlsStateAfterNavigationCompletes() {
        MainFrameNavigationGuard guard = started(
                50L, "https://portal.iws.example/build");
        guard.complete(50L, "https://portal.iws.example/build", "epoch-build");

        assertTrue(guard.hasCurrentDocument("https://portal.iws.example/build"));
        assertEquals(MainFrameNavigationGuard.NONE,
                guard.pendingIdentityForCurrentUrl(
                        "https://portal.iws.example/build"));
    }

    @Test
    public void reconnectInvalidatesPendingButRetainsLoadedDocumentTlsState() {
        MainFrameNavigationGuard guard = started(
                60L, "https://portal.iws.example/build");
        guard.complete(60L, "https://portal.iws.example/build", "epoch-build");

        guard.invalidatePending();

        assertTrue(guard.hasCurrentDocument("https://portal.iws.example/build"));
    }

    @Test
    public void webOwnedSameDocumentHistoryNavigationMayKeepItsEpoch() {
        MainFrameNavigationGuard guard = started(
                70L, "https://portal.iws.example/build");
        guard.complete(
                70L, "https://portal.iws.example/build", "1000");
        guard.expect(71L, "https://portal.iws.example/build#details");
        guard.onStarted(
                "https://portal.iws.example/build#details",
                "https://portal.iws.example/build#details");

        assertTrue(guard.acceptsObservation(
                71L,
                "https://portal.iws.example/build#details",
                "1000",
                "https://portal.iws.example/build#details"));
    }

    private static MainFrameNavigationGuard started(long identity, String url) {
        MainFrameNavigationGuard guard = new MainFrameNavigationGuard();
        guard.expect(identity, url);
        guard.onStarted(url, url);
        return guard;
    }
}

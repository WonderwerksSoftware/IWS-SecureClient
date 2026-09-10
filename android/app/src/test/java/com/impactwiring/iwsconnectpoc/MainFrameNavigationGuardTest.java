package com.impactwiring.iwsconnectpoc;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class MainFrameNavigationGuardTest {
    @Test
    public void staleCallbackUrlCannotBorrowNewerNavigationIdentity() {
        MainFrameNavigationGuard guard = new MainFrameNavigationGuard();
        guard.expect(1L, "https://portal.iws.example/build");
        guard.onStarted(
                "https://portal.iws.example/build",
                "https://portal.iws.example/build");
        guard.expect(2L, "https://portal.iws.example/inventory");
        guard.onStarted(
                "https://portal.iws.example/inventory",
                "https://portal.iws.example/inventory");

        assertEquals(MainFrameNavigationGuard.NONE, guard.identityForCallback(
                "https://portal.iws.example/build",
                "https://portal.iws.example/inventory"));
        assertEquals(2L, guard.identityForCallback(
                "https://portal.iws.example/inventory",
                "https://portal.iws.example/inventory"));
    }

    @Test
    public void invalidationRejectsDelayedCallbackEvenWhenUrlStillMatches() {
        MainFrameNavigationGuard guard = new MainFrameNavigationGuard();
        guard.expect(4L, "https://portal.iws.example/build");
        guard.onStarted(
                "https://portal.iws.example/build",
                "https://portal.iws.example/build");

        guard.invalidate();

        assertEquals(MainFrameNavigationGuard.NONE, guard.identityForCallback(
                "https://portal.iws.example/build",
                "https://portal.iws.example/build"));
    }

    @Test
    public void startMustMatchExpectedAndCurrentUrl() {
        MainFrameNavigationGuard guard = new MainFrameNavigationGuard();
        guard.expect(7L, "https://portal.iws.example/build");

        guard.onStarted(
                "https://portal.iws.example/build",
                "https://portal.iws.example/inventory");

        assertEquals(MainFrameNavigationGuard.NONE, guard.identityForCallback(
                "https://portal.iws.example/build",
                "https://portal.iws.example/inventory"));
    }

    @Test
    public void equivalentRootAndDefaultPortRepresentationsKeepTheirIdentity() {
        MainFrameNavigationGuard guard = new MainFrameNavigationGuard();
        guard.expect(9L, "https://PORTAL.iws.example:443");

        guard.onStarted(
                "https://portal.iws.example/",
                "https://portal.iws.example/");

        assertEquals(9L, guard.identityForCallback(
                "https://portal.iws.example/",
                "https://portal.iws.example/"));
    }

    @Test
    public void currentDocumentIdentityCoversTlsFailureFromItsSubresources() {
        MainFrameNavigationGuard guard = new MainFrameNavigationGuard();
        guard.expect(11L, "https://portal.iws.example/build");
        guard.onStarted(
                "https://portal.iws.example/build",
                "https://portal.iws.example/build");

        assertEquals(11L, guard.identityForCurrentDocument(
                "https://portal.iws.example/build"));
        assertEquals(MainFrameNavigationGuard.NONE, guard.identityForCurrentDocument(
                "https://portal.iws.example/inventory"));
    }

    @Test
    public void sameUrlReplacementSwallowsRetiredCompletionBeforeAcceptingNewOne() {
        MainFrameNavigationGuard guard = new MainFrameNavigationGuard();
        guard.expect(20L, "https://portal.iws.example/");
        guard.onStarted(
                "https://portal.iws.example/",
                "https://portal.iws.example/");
        guard.expect(21L, "https://portal.iws.example/");
        guard.onStarted(
                "https://portal.iws.example/",
                "https://portal.iws.example/");

        assertEquals(MainFrameNavigationGuard.NONE, guard.identityForCallback(
                "https://portal.iws.example/",
                "https://portal.iws.example/"));
        assertEquals(21L, guard.identityForCallback(
                "https://portal.iws.example/",
                "https://portal.iws.example/"));
    }
}

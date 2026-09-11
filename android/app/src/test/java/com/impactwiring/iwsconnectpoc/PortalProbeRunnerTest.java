package com.impactwiring.iwsconnectpoc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public final class PortalProbeRunnerTest {
    @Test
    public void recreatedUiCannotOverlapProbeThatIgnoredItsOwnersInterrupt() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch recreatedOwnerNotified = new CountDownLatch(1);
        CountDownLatch recreatedProbeCompleted = new CountDownLatch(1);
        PortalHealthProbe firstProbe = new PortalHealthProbe(
                "https://portal.iws.example/",
                () -> {
                    firstEntered.countDown();
                    boolean released = false;
                    while (!released) {
                        try {
                            released = releaseFirst.await(1, TimeUnit.SECONDS);
                        } catch (InterruptedException ignored) {
                            // Models a platform lookup which does not stop on owner teardown.
                        }
                    }
                    return false;
                },
                url -> null);
        PortalHealthProbe secondProbe = new PortalHealthProbe(
                "https://portal.iws.example/", () -> false, url -> null);
        PortalProbeRunner firstOwner = new PortalProbeRunner(firstProbe);
        PortalProbeRunner recreatedOwner = new PortalProbeRunner(secondProbe);

        assertTrue(firstOwner.start(1L, 45_000L, (episode, result) -> {}));
        assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
        firstOwner.close();
        recreatedOwner.attach(recreatedOwnerNotified::countDown);

        boolean secondStarted = recreatedOwner.start(2L, 45_000L, (episode, result) -> {});
        releaseFirst.countDown();
        assertTrue(recreatedOwnerNotified.await(1, TimeUnit.SECONDS));
        assertTrue(recreatedOwner.start(3L, 45_000L,
                (episode, result) -> recreatedProbeCompleted.countDown()));
        assertTrue(recreatedProbeCompleted.await(1, TimeUnit.SECONDS));
        recreatedOwner.close();

        assertFalse(secondStarted);
    }
}

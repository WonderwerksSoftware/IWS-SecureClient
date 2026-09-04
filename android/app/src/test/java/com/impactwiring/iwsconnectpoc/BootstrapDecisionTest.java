package com.impactwiring.iwsconnectpoc;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public final class BootstrapDecisionTest {
    @Test public void alreadyEnrolledConnectsWithoutBootstrap() {
        assertEquals(BootstrapDecision.Action.CONNECT, BootstrapDecision.decide(true, true, false));
    }

    @Test public void missingIdentityStartsAutomaticEnrollment() {
        assertEquals(BootstrapDecision.Action.ENROLL, BootstrapDecision.decide(false, true, false));
    }

    @Test public void missingBootstrapFailsClosed() {
        assertEquals(BootstrapDecision.Action.FAIL, BootstrapDecision.decide(false, false, false));
    }

    @Test public void enrollmentInFlightWaits() {
        assertEquals(BootstrapDecision.Action.WAIT, BootstrapDecision.decide(false, true, true));
    }
}

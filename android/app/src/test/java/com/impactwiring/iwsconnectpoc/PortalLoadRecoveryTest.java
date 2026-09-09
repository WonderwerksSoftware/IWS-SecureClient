package com.impactwiring.iwsconnectpoc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PortalLoadRecoveryTest {
    @Test
    public void successfulAllowedLoadMayRevealPortal() {
        PortalLoadRecovery recovery = new PortalLoadRecovery();

        recovery.onMainFrameLoadStarted();

        assertTrue(recovery.mayRevealPortal(true));
    }

    @Test
    public void failedLoadCannotHideTheErrorPanelWhenFinished() {
        PortalLoadRecovery recovery = new PortalLoadRecovery();

        recovery.onMainFrameLoadStarted();
        recovery.onMainFrameLoadFailed();

        assertFalse(recovery.mayRevealPortal(true));
    }

    @Test
    public void newSuccessfulRetryMayRevealPortalAfterPreviousLoadFailed() {
        PortalLoadRecovery recovery = new PortalLoadRecovery();

        recovery.onMainFrameLoadStarted();
        recovery.onMainFrameLoadFailed();
        assertFalse(recovery.mayRevealPortal(true));

        recovery.onMainFrameLoadStarted();

        assertTrue(recovery.mayRevealPortal(true));
    }

    @Test
    public void completedDisallowedLoadCannotRevealPortal() {
        PortalLoadRecovery recovery = new PortalLoadRecovery();

        recovery.onMainFrameLoadStarted();

        assertFalse(recovery.mayRevealPortal(false));
    }
}

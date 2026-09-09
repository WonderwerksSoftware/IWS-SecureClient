package com.impactwiring.iwsconnectpoc;

final class PortalLoadRecovery {
    private boolean mainFrameLoadFailed;

    void onMainFrameLoadStarted() {
        mainFrameLoadFailed = false;
    }

    void onMainFrameLoadFailed() {
        mainFrameLoadFailed = true;
    }

    boolean mayRevealPortal(boolean allowedDestination) {
        return allowedDestination && !mainFrameLoadFailed;
    }
}

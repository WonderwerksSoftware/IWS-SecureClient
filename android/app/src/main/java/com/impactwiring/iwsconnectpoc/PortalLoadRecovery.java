package com.impactwiring.iwsconnectpoc;

final class PortalLoadRecovery {
    private boolean mainFrameLoadFailed;

    void onMainFrameLoadRequested() {
        mainFrameLoadFailed = false;
    }

    void onMainFrameLoadStarted() {}

    void onMainFrameLoadFailed() {
        mainFrameLoadFailed = true;
    }

    boolean mayRevealPortal(boolean allowedDestination) {
        return allowedDestination && !mainFrameLoadFailed;
    }

    boolean shouldKeepErrorPanelVisible() {
        return mainFrameLoadFailed;
    }
}

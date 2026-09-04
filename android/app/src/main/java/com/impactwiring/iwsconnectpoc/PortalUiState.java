package com.impactwiring.iwsconnectpoc;

final class PortalUiState {
    final boolean mayLoadPortal;
    final boolean showRetry;
    final String employeeMessage;
    final String employeeDetail;

    private PortalUiState(
            boolean mayLoadPortal, boolean showRetry, String employeeMessage, String employeeDetail) {
        this.mayLoadPortal = mayLoadPortal;
        this.showRetry = showRetry;
        this.employeeMessage = employeeMessage;
        this.employeeDetail = employeeDetail;
    }

    static PortalUiState from(TransportState state) {
        switch (state) {
            case CONNECTED:
                return new PortalUiState(true, false, "", "");
            case CONNECTING:
                return new PortalUiState(false, false, "Connecting to IWS…", "");
            case DISCONNECTING:
                return new PortalUiState(false, false, "Closing IWS connection…", "");
            case ERROR:
            case DISCONNECTED:
            default:
                return new PortalUiState(
                        false, true, "IWS is unavailable.", "Check your connection and try again.");
        }
    }
}

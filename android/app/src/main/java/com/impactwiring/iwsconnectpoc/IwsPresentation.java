package com.impactwiring.iwsconnectpoc;

final class IwsPresentation {
    private IwsPresentation() {}

    static String notificationText(TransportState state) {
        switch (state) {
            case CONNECTED:
                return "IWS is ready";
            case CONNECTING:
                return "IWS is opening";
            case DISCONNECTING:
                return "IWS is closing";
            case ERROR:
                return "IWS needs attention";
            case DISCONNECTED:
            default:
                return "IWS is offline";
        }
    }
}

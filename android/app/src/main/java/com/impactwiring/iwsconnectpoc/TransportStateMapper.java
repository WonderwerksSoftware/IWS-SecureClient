package com.impactwiring.iwsconnectpoc;

import io.netbird.gomobile.android.Android;

public final class TransportStateMapper {
    private TransportStateMapper() {}

    public static TransportState fromNative(long state) {
        if (state == Android.ClientStateDisconnected) {
            return TransportState.DISCONNECTED;
        }
        if (state == Android.ClientStateConnecting) {
            return TransportState.CONNECTING;
        }
        if (state == Android.ClientStateConnected) {
            return TransportState.CONNECTED;
        }
        if (state == Android.ClientStateDisconnecting) {
            return TransportState.DISCONNECTING;
        }
        return TransportState.ERROR;
    }
}

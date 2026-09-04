package com.impactwiring.iwsconnectpoc;

import static org.junit.Assert.assertEquals;

import io.netbird.gomobile.android.Android;
import org.junit.Test;

public final class TransportStateMapperTest {
    @Test
    public void mapsNativeStatesWithoutInventingConnected() {
        assertEquals(TransportState.DISCONNECTED,
                TransportStateMapper.fromNative(Android.ClientStateDisconnected));
        assertEquals(TransportState.CONNECTING,
                TransportStateMapper.fromNative(Android.ClientStateConnecting));
        assertEquals(TransportState.CONNECTED,
                TransportStateMapper.fromNative(Android.ClientStateConnected));
        assertEquals(TransportState.DISCONNECTING,
                TransportStateMapper.fromNative(Android.ClientStateDisconnecting));
        assertEquals(TransportState.ERROR,
                TransportStateMapper.fromNative(Android.ClientStateNoNetwork));
        assertEquals(TransportState.ERROR, TransportStateMapper.fromNative(Long.MAX_VALUE));
    }
}

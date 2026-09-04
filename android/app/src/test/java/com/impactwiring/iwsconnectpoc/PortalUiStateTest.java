package com.impactwiring.iwsconnectpoc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PortalUiStateTest {
    @Test
    public void portalLoadsOnlyAfterRealConnectedState() {
        assertFalse(PortalUiState.from(TransportState.DISCONNECTED).mayLoadPortal);
        assertFalse(PortalUiState.from(TransportState.CONNECTING).mayLoadPortal);
        assertFalse(PortalUiState.from(TransportState.DISCONNECTING).mayLoadPortal);
        assertFalse(PortalUiState.from(TransportState.ERROR).mayLoadPortal);
        assertTrue(PortalUiState.from(TransportState.CONNECTED).mayLoadPortal);
    }

    @Test
    public void onlyFailureStateOffersRetry() {
        assertFalse(PortalUiState.from(TransportState.CONNECTING).showRetry);
        assertFalse(PortalUiState.from(TransportState.CONNECTED).showRetry);
        assertTrue(PortalUiState.from(TransportState.ERROR).showRetry);
        assertTrue(PortalUiState.from(TransportState.DISCONNECTED).showRetry);
    }
}

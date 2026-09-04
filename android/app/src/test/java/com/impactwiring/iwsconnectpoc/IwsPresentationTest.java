package com.impactwiring.iwsconnectpoc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Locale;
import org.junit.Test;

public final class IwsPresentationTest {
    @Test
    public void notificationTextUsesOnlyEmployeeFacingIwsLanguage() {
        for (TransportState state : TransportState.values()) {
            String text = IwsPresentation.notificationText(state);
            String normalized = text.toLowerCase(Locale.ROOT);
            assertFalse(normalized.contains("netbird"));
            assertFalse(normalized.contains("vpn"));
            assertFalse(normalized.contains("transport"));
            assertTrue(text.startsWith("IWS"));
        }
    }
}

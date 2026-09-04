package com.impactwiring.iwsconnectpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PortalPolicyTest {
    @Test
    public void allowsOnlyConfiguredOriginAcrossFuturePortalPaths() {
        PortalPolicy policy = new PortalPolicy("https://portal.dev.iws.example/");

        assertTrue(policy.isAllowed("https://portal.dev.iws.example/build"));
        assertTrue(policy.isAllowed("https://portal.dev.iws.example/receive?id=4#actor"));
        assertFalse(policy.isAllowed("https://other.iws.example/build"));
        assertFalse(policy.isAllowed("http://portal.dev.iws.example/build"));
        assertFalse(policy.isAllowed("https://portal.dev.iws.example:444/build"));
        assertFalse(policy.isAllowed("javascript:alert(1)"));
    }

    @Test
    public void treatsExplicitAndDefaultPortsAsTheSameOrigin() {
        PortalPolicy policy = new PortalPolicy("https://portal.dev.iws.example:443/root/");

        assertTrue(policy.isAllowed("https://portal.dev.iws.example/inventory"));
        assertEquals("https://portal.dev.iws.example:443/root/", policy.portalRoot());
    }

    @Test
    public void allowsOnlyMatchingSecureWebSocketAsPortalResource() {
        PortalPolicy policy = new PortalPolicy("https://portal.dev.iws.example/");

        assertTrue(policy.isAllowedResource("wss://portal.dev.iws.example/socket.io/?EIO=4"));
        assertFalse(policy.isAllowed("wss://portal.dev.iws.example/socket.io/?EIO=4"));
        assertFalse(policy.isAllowedResource("ws://portal.dev.iws.example/socket.io/"));
        assertFalse(policy.isAllowedResource("wss://other.iws.example/socket.io/"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsPortalUrlWithoutHttpOrigin() {
        new PortalPolicy("file:///tmp/index.html");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsPortalUrlWithCredentials() {
        new PortalPolicy("https://employee:password@portal.dev.iws.example/");
    }
}

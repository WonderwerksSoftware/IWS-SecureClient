package com.impactwiring.iwsconnectpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.net.URI;
import java.util.List;
import org.junit.Test;

public final class RoutePolicyTest {
    private static final String EXPECTED_OVERLAY = "100.83.0.0/16";
    private static final String ALLOWED_ENDPOINT = "100.83.246.85";
    private static final int ALLOWED_PORT = 443;
    private static final String PROHIBITED =
            "100.65.0.0/16,100.70.0.0/16,100.74.0.0/16,100.96.0.0/16,"
                    + "100.98.0.0/16,100.99.0.0/16,100.111.0.0/16,"
                    + "100.116.0.0/16,100.117.0.0/16,100.127.0.0/16,"
                    + "10.0.0.0/8,172.16.0.0/12,192.168.0.0/16";

    @Test
    public void derivesExpectedOverlayAndCapturesAllIpv4ForTheAllowedApp() {
        RoutePolicy policy = policy();

        List<RoutePolicy.Route> routes = policy.routesForTun(
                "100.83.50.15/16",
                "",
                "");

        assertEquals(List.of(new RoutePolicy.Route("0.0.0.0", 0)), routes);
    }

    @Test
    public void rejectsExpectedOverlayThatOverlapsTailscale() {
        assertThrows(
                SecurityException.class,
                () -> new RoutePolicy(
                        "100.116.0.0/16", PROHIBITED, ALLOWED_ENDPOINT, ALLOWED_PORT));
    }

    @Test
    public void rejectsAssignedAddressOutsideExpectedOverlay() {
        RoutePolicy policy = policy();

        assertThrows(
                SecurityException.class,
                () -> policy.routesForTun(
                        "100.84.50.15/16", "", ""));
    }

    @Test
    public void rejectsWrongAssignedPrefix() {
        RoutePolicy policy = policy();

        assertThrows(
                SecurityException.class,
                () -> policy.routesForTun(
                        "100.83.50.15/24", "", ""));
    }

    @Test
    public void rejectsMissingAssignedAddress() {
        RoutePolicy policy = policy();

        assertThrows(
                SecurityException.class,
                () -> policy.routesForTun(null, "", ""));
        assertThrows(
                SecurityException.class,
                () -> policy.routesForTun("", "", ""));
    }

    @Test
    public void rejectsMalformedAssignedAddress() {
        RoutePolicy policy = policy();

        assertThrows(
                SecurityException.class,
                () -> policy.routesForTun("not-an-ip/16", "", ""));
    }

    @Test
    public void rejectsIpv6ForTheIpv4OnlyPoc() {
        RoutePolicy policy = policy();

        assertThrows(
                SecurityException.class,
                () -> policy.routesForTun(
                        "100.83.50.15/16", "fd00::2/64", ""));
    }

    @Test
    public void rejectsUnexpectedControlPlaneRoutes() {
        RoutePolicy policy = policy();

        assertThrows(
                SecurityException.class,
                () -> policy.routesForTun(
                        "100.83.50.15/16", "", "100.83.0.0/16"));
        assertThrows(
                SecurityException.class,
                () -> policy.routesForTun(
                        "100.83.50.15/16", "", "192.168.50.0/24"));
    }

    @Test
    public void allowsOnlyConfiguredEndpointAndPort() {
        RoutePolicy policy = policy();

        URI allowed = policy.requireAllowedEndpoint("http://100.83.246.85:443/iws-test");

        assertEquals("100.83.246.85", allowed.getHost());
        assertEquals(443, allowed.getPort());
    }

    @Test
    public void rejectsEndpointOutsideExpectedOverlay() {
        RoutePolicy policy = policy();

        assertThrows(
                SecurityException.class,
                () -> policy.requireAllowedEndpoint("http://192.168.50.1/"));
        assertThrows(
                SecurityException.class,
                () -> policy.requireAllowedEndpoint("http://100.116.25.100/"));
    }

    @Test
    public void rejectsUnconfiguredEndpointInsideExpectedOverlay() {
        RoutePolicy policy = policy();

        assertThrows(
                SecurityException.class,
                () -> policy.requireAllowedEndpoint("http://100.83.246.86:443/"));
    }

    @Test
    public void rejectsUnconfiguredPortOnAllowedEndpoint() {
        RoutePolicy policy = policy();

        assertThrows(
                SecurityException.class,
                () -> policy.requireAllowedEndpoint("http://100.83.246.85:80/"));
        assertThrows(
                SecurityException.class,
                () -> policy.requireAllowedEndpoint("http://100.83.246.85/"));
    }

    @Test
    public void rejectsHostnameToAvoidDnsAndLanDiscovery() {
        RoutePolicy policy = policy();

        assertThrows(
                SecurityException.class,
                () -> policy.requireAllowedEndpoint("https://portal.iws.internal/"));
    }

    private static RoutePolicy policy() {
        return new RoutePolicy(
                EXPECTED_OVERLAY, PROHIBITED, ALLOWED_ENDPOINT, ALLOWED_PORT);
    }
}

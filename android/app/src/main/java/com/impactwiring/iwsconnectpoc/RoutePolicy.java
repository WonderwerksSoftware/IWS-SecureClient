package com.impactwiring.iwsconnectpoc;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Fail-closed routing policy for the IPv4-only transport spike. */
public final class RoutePolicy {
    public static final class Route {
        public final String address;
        public final int prefixLength;

        public Route(String address, int prefixLength) {
            this.address = address;
            this.prefixLength = prefixLength;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Route)) {
                return false;
            }
            Route route = (Route) other;
            return prefixLength == route.prefixLength && address.equals(route.address);
        }

        @Override
        public int hashCode() {
            return Objects.hash(address, prefixLength);
        }

        @Override
        public String toString() {
            return address + "/" + prefixLength;
        }
    }

    private static final class Cidr {
        final int network;
        final int prefixLength;

        Cidr(int network, int prefixLength) {
            this.prefixLength = prefixLength;
            this.network = network & mask(prefixLength);
        }

        static Cidr parse(String text) {
            if (text == null) {
                throw new SecurityException("CIDR is required");
            }
            String[] parts = text.trim().split("/", -1);
            if (parts.length != 2) {
                throw new SecurityException("Invalid IPv4 CIDR");
            }
            int prefix;
            try {
                prefix = Integer.parseInt(parts[1]);
            } catch (NumberFormatException error) {
                throw new SecurityException("Invalid IPv4 prefix", error);
            }
            if (prefix < 0 || prefix > 32) {
                throw new SecurityException("Invalid IPv4 prefix");
            }
            return new Cidr(parseIpv4(parts[0]), prefix);
        }

        boolean contains(int address) {
            return (address & mask(prefixLength)) == network;
        }

        boolean overlaps(Cidr other) {
            int commonPrefix = Math.min(prefixLength, other.prefixLength);
            int commonMask = mask(commonPrefix);
            return (network & commonMask) == (other.network & commonMask);
        }

        Route asRoute() {
            return new Route(formatIpv4(network), prefixLength);
        }

        private static int mask(int prefixLength) {
            return prefixLength == 0 ? 0 : (int) (0xffffffffL << (32 - prefixLength));
        }
    }

    private final Cidr expectedOverlay;
    private final int allowedEndpoint;
    private final int allowedPort;

    public RoutePolicy(
            String expectedOverlayCidr,
            String prohibitedCidrsCsv,
            String allowedEndpointIpv4,
            int allowedPort) {
        if (expectedOverlayCidr == null || expectedOverlayCidr.trim().isEmpty()) {
            throw new SecurityException("Expected overlay CIDR is not configured");
        }
        expectedOverlay = Cidr.parse(expectedOverlayCidr);
        for (Cidr prohibited : parseCidrs(prohibitedCidrsCsv)) {
            if (expectedOverlay.overlaps(prohibited)) {
                throw new SecurityException("Expected overlay overlaps a prohibited network");
            }
        }
        allowedEndpoint = parseIpv4(allowedEndpointIpv4);
        if (!expectedOverlay.contains(allowedEndpoint)) {
            throw new SecurityException("Allowed endpoint is outside the expected overlay");
        }
        if (allowedPort < 1 || allowedPort > 65535) {
            throw new SecurityException("Allowed endpoint port is invalid");
        }
        this.allowedPort = allowedPort;
    }

    public List<Route> routesForTun(
            String assignedAddressV4, String assignedAddressV6, String coreRoutes) {
        if (assignedAddressV6 != null && !assignedAddressV6.trim().isEmpty()) {
            throw new SecurityException("IPv6 is disabled for this isolation POC");
        }

        Cidr assigned = Cidr.parse(assignedAddressV4);
        if (assigned.network != expectedOverlay.network
                || assigned.prefixLength != expectedOverlay.prefixLength) {
            throw new SecurityException("Assigned address does not derive the expected overlay");
        }

        if (coreRoutes != null && !coreRoutes.trim().isEmpty()) {
            throw new SecurityException("Control-plane routes are not permitted in this POC");
        }

        return Collections.singletonList(new Route("0.0.0.0", 0));
    }

    public URI requireAllowedEndpoint(String endpoint) {
        final URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException error) {
            throw new SecurityException("Invalid test endpoint", error);
        }
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
            throw new SecurityException("Test endpoint must use HTTP or HTTPS");
        }
        if (uri.getUserInfo() != null || uri.getHost() == null) {
            throw new SecurityException("Test endpoint must be a literal IPv4 address");
        }
        int address = parseIpv4(uri.getHost());
        if (address != allowedEndpoint) {
            throw new SecurityException("Test endpoint is not the configured peer");
        }
        int port = uri.getPort();
        if (port == -1) {
            port = "https".equals(uri.getScheme()) ? 443 : 80;
        }
        if (port != allowedPort) {
            throw new SecurityException("Test endpoint port is not permitted");
        }
        return uri;
    }

    private static List<Cidr> parseCidrs(String csv) {
        List<Cidr> cidrs = new ArrayList<>();
        if (csv == null || csv.trim().isEmpty()) {
            return cidrs;
        }
        for (String item : csv.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                cidrs.add(Cidr.parse(trimmed));
            }
        }
        return cidrs;
    }

    private static int parseIpv4(String text) {
        String[] octets = text.split("\\.", -1);
        if (octets.length != 4) {
            throw new SecurityException("IPv4 literal required");
        }
        int address = 0;
        for (String octet : octets) {
            if (octet.isEmpty() || (octet.length() > 1 && octet.charAt(0) == '0')) {
                throw new SecurityException("Invalid IPv4 literal");
            }
            final int value;
            try {
                value = Integer.parseInt(octet);
            } catch (NumberFormatException error) {
                throw new SecurityException("Invalid IPv4 literal", error);
            }
            if (value < 0 || value > 255) {
                throw new SecurityException("Invalid IPv4 literal");
            }
            address = (address << 8) | value;
        }
        return address;
    }

    private static String formatIpv4(int address) {
        return ((address >>> 24) & 0xff)
                + "." + ((address >>> 16) & 0xff)
                + "." + ((address >>> 8) & 0xff)
                + "." + (address & 0xff);
    }
}

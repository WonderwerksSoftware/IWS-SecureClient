package com.impactwiring.iwsconnectpoc;

import android.os.ParcelFileDescriptor;
import io.netbird.gomobile.android.TunAdapter;
import java.util.List;

/** Independently written Android VpnService adapter for the BSD gomobile API. */
final class NativeTunAdapter implements TunAdapter {
    private final IwsVpnService service;

    NativeTunAdapter(IwsVpnService service) {
        this.service = service;
    }

    @Override
    public long configureInterface(
            String addressV4,
            String addressV6,
            long mtu,
            String dns,
            String searchDomains,
            String coreRoutes) throws Exception {
        if ((dns != null && !dns.isEmpty())
                || (searchDomains != null && !searchDomains.isEmpty())) {
            throw new SecurityException("DNS is disabled for the IP-only isolation POC");
        }

        RoutePolicy policy = new RoutePolicy(
                BuildConfig.EXPECTED_OVERLAY_CIDR,
                BuildConfig.PROHIBITED_CIDRS,
                BuildConfig.ALLOWED_ENDPOINT_IPV4,
                BuildConfig.ALLOWED_ENDPOINT_PORT);
        List<RoutePolicy.Route> routes = policy.routesForTun(addressV4, addressV6, coreRoutes);
        Address assigned = Address.parse(addressV4);

        android.net.VpnService.Builder builder = service.newBuilder()
                .setSession("IWS")
                .addAllowedApplication(BuildConfig.APPLICATION_ID)
                .addAddress(assigned.address, assigned.prefixLength)
                .setMtu(Math.toIntExact(mtu))
                .setBlocking(true);
        for (RoutePolicy.Route route : routes) {
            builder.addRoute(route.address, route.prefixLength);
        }

        ParcelFileDescriptor descriptor = builder.establish();
        if (descriptor == null) {
            throw new IllegalStateException("Android did not create the IWS VPN interface");
        }
        return descriptor.detachFd();
    }

    @Override
    public boolean protectSocket(int fd) {
        return service.protect(fd);
    }

    @Override
    public void updateAddr(String ignoredAddress) {
        throw new UnsupportedOperationException("Address mutation is not permitted in this POC");
    }

    private static final class Address {
        final String address;
        final int prefixLength;

        private Address(String address, int prefixLength) {
            this.address = address;
            this.prefixLength = prefixLength;
        }

        static Address parse(String cidr) {
            int slash = cidr == null ? -1 : cidr.lastIndexOf('/');
            if (slash <= 0 || slash == cidr.length() - 1) {
                throw new SecurityException("Assigned IPv4 CIDR is invalid");
            }
            int prefix;
            try {
                prefix = Integer.parseInt(cidr.substring(slash + 1));
            } catch (NumberFormatException error) {
                throw new SecurityException("Assigned IPv4 prefix is invalid", error);
            }
            if (prefix < 0 || prefix > 32) {
                throw new SecurityException("Assigned IPv4 prefix is invalid");
            }
            return new Address(cidr.substring(0, slash), prefix);
        }
    }
}

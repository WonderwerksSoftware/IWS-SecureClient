package com.impactwiring.iwsconnectpoc;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/** Checks the app-visible VPN and resolver state without selecting or binding a network. */
final class AppVpnDnsReadiness implements PortalHealthProbe.NetworkReadiness {
    private final ConnectivityManager connectivityManager;
    private final String approvedDnsIpv4;

    AppVpnDnsReadiness(Context context, String approvedDnsIpv4) {
        connectivityManager = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.approvedDnsIpv4 = approvedDnsIpv4;
    }

    @Override
    public boolean isReady() {
        if (connectivityManager == null) {
            return false;
        }
        Network activeNetwork = connectivityManager.getActiveNetwork();
        if (activeNetwork == null) {
            return false;
        }
        NetworkCapabilities capabilities =
                connectivityManager.getNetworkCapabilities(activeNetwork);
        if (capabilities == null
                || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return false;
        }
        LinkProperties linkProperties = connectivityManager.getLinkProperties(activeNetwork);
        if (linkProperties == null) {
            return false;
        }
        List<String> dnsServers = new ArrayList<>();
        for (InetAddress address : linkProperties.getDnsServers()) {
            dnsServers.add(address.getHostAddress());
        }
        return ApprovedDnsPolicy.allServersMatch(dnsServers, approvedDnsIpv4);
    }
}

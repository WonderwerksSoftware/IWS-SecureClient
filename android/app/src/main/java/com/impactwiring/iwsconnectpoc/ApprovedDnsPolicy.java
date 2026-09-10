package com.impactwiring.iwsconnectpoc;

import java.util.List;

/** Fails closed unless the app-visible DNS set contains only the compiled resolver. */
final class ApprovedDnsPolicy {
    private ApprovedDnsPolicy() {}

    static boolean allServersMatch(List<String> dnsServers, String approvedIpv4) {
        if (approvedIpv4 == null || approvedIpv4.isEmpty() || dnsServers.isEmpty()) {
            return false;
        }
        for (String dnsServer : dnsServers) {
            if (!approvedIpv4.equals(dnsServer)) {
                return false;
            }
        }
        return true;
    }
}

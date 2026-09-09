package com.impactwiring.iwsconnectpoc;

/** Exact production resolver boundary; no search-domain expansion. */
final class PrivateDnsPolicy {
    static String resolver(String supplied, String searchDomains) {
        if (searchDomains != null && !searchDomains.isEmpty()) {
            throw new SecurityException("DNS search expansion is not permitted");
        }
        if (supplied != null && !supplied.isEmpty() && !"100.83.75.124".equals(supplied)) {
            throw new SecurityException("Unapproved DNS resolver");
        }
        return "100.83.75.124";
    }
}

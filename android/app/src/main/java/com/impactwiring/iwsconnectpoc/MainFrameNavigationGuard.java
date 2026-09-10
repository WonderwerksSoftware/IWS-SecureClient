package com.impactwiring.iwsconnectpoc;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Associates WebView main-frame callbacks with an explicitly observed live navigation. */
final class MainFrameNavigationGuard {
    static final long NONE = 0L;

    private long expectedIdentity;
    private String expectedUrl;
    private long activeIdentity;
    private String activeUrl;
    private String retiredUrl;
    private long pendingStartedIdentity;
    private String pendingStartedUrl;

    void expect(long navigationIdentity, String url) {
        retireActiveNavigation();
        expectedIdentity = navigationIdentity;
        expectedUrl = url;
        activeIdentity = NONE;
        activeUrl = null;
        pendingStartedIdentity = NONE;
        pendingStartedUrl = null;
    }

    boolean expects(String url) {
        return expectedIdentity != NONE && same(url, expectedUrl);
    }

    void onStarted(String startedUrl, String currentUrl) {
        if (expectedIdentity == NONE
                || !same(startedUrl, expectedUrl)
                || !same(startedUrl, currentUrl)) {
            return;
        }
        if (retiredUrl != null && same(startedUrl, retiredUrl)) {
            pendingStartedIdentity = expectedIdentity;
            pendingStartedUrl = startedUrl;
            return;
        }
        activeIdentity = expectedIdentity;
        activeUrl = startedUrl;
    }

    long identityForCallback(String callbackUrl, String currentUrl) {
        if (retiredUrl != null && same(callbackUrl, retiredUrl)) {
            retiredUrl = null;
            if (pendingStartedIdentity != NONE && same(pendingStartedUrl, currentUrl)) {
                activeIdentity = pendingStartedIdentity;
                activeUrl = pendingStartedUrl;
            }
            pendingStartedIdentity = NONE;
            pendingStartedUrl = null;
            return NONE;
        }
        if (activeIdentity == NONE
                || !same(callbackUrl, activeUrl)
                || !same(callbackUrl, currentUrl)) {
            return NONE;
        }
        return activeIdentity;
    }

    long identityForCurrentDocument(String currentUrl) {
        if (pendingStartedIdentity != NONE && same(currentUrl, pendingStartedUrl)) {
            return pendingStartedIdentity;
        }
        if (activeIdentity == NONE || !same(currentUrl, activeUrl)) {
            return NONE;
        }
        return activeIdentity;
    }

    void complete(long navigationIdentity) {
        if (navigationIdentity == activeIdentity || navigationIdentity == pendingStartedIdentity) {
            expectedIdentity = NONE;
            expectedUrl = null;
            activeIdentity = NONE;
            activeUrl = null;
            pendingStartedIdentity = NONE;
            pendingStartedUrl = null;
        }
    }

    void invalidate() {
        retireActiveNavigation();
        expectedIdentity = NONE;
        expectedUrl = null;
        activeIdentity = NONE;
        activeUrl = null;
        pendingStartedIdentity = NONE;
        pendingStartedUrl = null;
    }

    private void retireActiveNavigation() {
        if (activeIdentity != NONE) {
            retiredUrl = activeUrl;
        }
    }

    private static boolean same(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        if (left.equals(right)) {
            return true;
        }
        return canonical(left).equals(canonical(right));
    }

    private static String canonical(String value) {
        try {
            URI uri = new URI(value).normalize();
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return "invalid:" + value;
            }
            scheme = scheme.toLowerCase(Locale.ROOT);
            host = host.toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            if (port < 0) {
                port = "https".equals(scheme) ? 443 : "http".equals(scheme) ? 80 : -1;
            }
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
            String fragment = uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment();
            return scheme + "://" + host + ":" + port + path + query + fragment;
        } catch (URISyntaxException failure) {
            return "invalid:" + value;
        }
    }
}

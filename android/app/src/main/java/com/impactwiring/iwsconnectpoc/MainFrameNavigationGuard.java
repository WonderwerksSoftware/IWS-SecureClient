package com.impactwiring.iwsconnectpoc;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Tracks pending navigation separately from the last successfully loaded document. */
final class MainFrameNavigationGuard {
    static final long NONE = 0L;

    private long expectedIdentity;
    private String expectedUrl;
    private long startedIdentity;
    private String startedUrl;
    private String loadedDocumentUrl;

    void expect(long navigationIdentity, String url) {
        expectedIdentity = navigationIdentity;
        expectedUrl = url;
        startedIdentity = NONE;
        startedUrl = null;
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
        startedIdentity = expectedIdentity;
        this.startedUrl = startedUrl;
    }

    long pendingIdentityForCurrentUrl(String currentUrl) {
        if (startedIdentity == NONE || !same(currentUrl, startedUrl)) {
            return NONE;
        }
        return startedIdentity;
    }

    boolean acceptsObservation(
            long navigationIdentity, String observedUrl, String currentUrl) {
        return navigationIdentity != NONE
                && navigationIdentity == startedIdentity
                && same(observedUrl, startedUrl)
                && same(currentUrl, startedUrl);
    }

    void complete(long navigationIdentity, String observedUrl) {
        if (navigationIdentity != startedIdentity || !same(observedUrl, startedUrl)) {
            return;
        }
        loadedDocumentUrl = observedUrl;
        clearPending();
    }

    void fail(long navigationIdentity) {
        if (navigationIdentity == startedIdentity) {
            clearPending();
        }
    }

    void invalidatePending() {
        clearPending();
    }

    boolean hasCurrentDocument(String currentUrl) {
        return same(currentUrl, loadedDocumentUrl)
                || same(currentUrl, startedUrl)
                || same(currentUrl, expectedUrl);
    }

    private void clearPending() {
        expectedIdentity = NONE;
        expectedUrl = null;
        startedIdentity = NONE;
        startedUrl = null;
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

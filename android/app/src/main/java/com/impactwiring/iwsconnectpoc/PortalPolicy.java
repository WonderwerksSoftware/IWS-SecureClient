package com.impactwiring.iwsconnectpoc;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Restricts the WebView to the explicitly configured IWS HTTP(S) origin. */
final class PortalPolicy {
    private final String portalRoot;
    private final String scheme;
    private final String host;
    private final int port;

    PortalPolicy(String portalRoot) {
        URI parsed = parse(portalRoot);
        validatePortalRoot(parsed);
        this.portalRoot = portalRoot;
        scheme = parsed.getScheme().toLowerCase(Locale.ROOT);
        host = parsed.getHost().toLowerCase(Locale.ROOT);
        port = effectivePort(parsed);
    }

    String portalRoot() {
        return portalRoot;
    }

    boolean isAllowed(String candidate) {
        return matchesOrigin(candidate, false);
    }

    boolean isAllowedResource(String candidate) {
        return matchesOrigin(candidate, true);
    }

    private boolean matchesOrigin(String candidate, boolean allowWebSocketEquivalent) {
        try {
            URI parsed = new URI(candidate);
            String candidateScheme = parsed.getScheme() == null
                    ? ""
                    : parsed.getScheme().toLowerCase(Locale.ROOT);
            boolean schemeMatches = scheme.equals(candidateScheme)
                    || (allowWebSocketEquivalent
                        && (("https".equals(scheme) && "wss".equals(candidateScheme))
                            || ("http".equals(scheme) && "ws".equals(candidateScheme))));
            return parsed.getScheme() != null
                    && parsed.getHost() != null
                    && parsed.getRawUserInfo() == null
                    && schemeMatches
                    && host.equals(parsed.getHost().toLowerCase(Locale.ROOT))
                    && port == effectivePort(parsed);
        } catch (IllegalArgumentException | URISyntaxException ignored) {
            return false;
        }
    }

    private static URI parse(String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("IWS portal URL is malformed", error);
        }
    }

    private static void validatePortalRoot(URI parsed) {
        String scheme = parsed.getScheme();
        if (scheme == null
                || parsed.getHost() == null
                || parsed.getRawUserInfo() != null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("IWS portal URL must be an HTTP(S) origin");
        }
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        if ("https".equalsIgnoreCase(uri.getScheme())
                || "wss".equalsIgnoreCase(uri.getScheme())) {
            return 443;
        }
        if ("http".equalsIgnoreCase(uri.getScheme())
                || "ws".equalsIgnoreCase(uri.getScheme())) {
            return 80;
        }
        throw new IllegalArgumentException("Unsupported portal scheme");
    }
}

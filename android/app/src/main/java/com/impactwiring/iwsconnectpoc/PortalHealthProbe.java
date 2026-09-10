package com.impactwiring.iwsconnectpoc;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.cert.CertificateException;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;

/** Performs one ordinary app-process HTTPS readiness request using platform trust and DNS. */
final class PortalHealthProbe {
    interface NetworkReadiness {
        boolean isReady();
    }

    interface ConnectionFactory {
        HttpsURLConnection open(URL url) throws IOException;
    }

    private static final int MAX_IO_TIMEOUT_MILLIS = 10_000;

    private final URL healthUrl;
    private final NetworkReadiness networkReadiness;
    private final ConnectionFactory connectionFactory;

    PortalHealthProbe(String portalRoot, NetworkReadiness networkReadiness) {
        this(portalRoot, networkReadiness,
                url -> (HttpsURLConnection) url.openConnection());
    }

    PortalHealthProbe(
            String portalRoot,
            NetworkReadiness networkReadiness,
            ConnectionFactory connectionFactory) {
        this.healthUrl = healthUrl(portalRoot);
        this.networkReadiness = networkReadiness;
        this.connectionFactory = connectionFactory;
    }

    PortalReadinessResult execute(long remainingMillis) {
        if (!networkReadiness.isReady()) {
            return PortalReadinessResult.RETRYABLE;
        }
        HttpsURLConnection connection = null;
        try {
            connection = connectionFactory.open(healthUrl);
            int timeoutMillis = (int) Math.max(
                    1L, Math.min(remainingMillis, MAX_IO_TIMEOUT_MILLIS));
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            return classifyStatus(connection.getResponseCode());
        } catch (IOException failure) {
            return isTlsFailure(failure)
                    ? PortalReadinessResult.FATAL_TLS
                    : PortalReadinessResult.RETRYABLE;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    static PortalReadinessResult classifyStatus(int statusCode) {
        if (statusCode >= HttpURLConnection.HTTP_OK && statusCode < 300) {
            return PortalReadinessResult.READY;
        }
        if (statusCode >= 500 && statusCode < 600) {
            return PortalReadinessResult.RETRYABLE;
        }
        return PortalReadinessResult.PERMANENT;
    }

    private static boolean isTlsFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SSLException || current instanceof CertificateException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static URL healthUrl(String portalRoot) {
        try {
            URI origin = new URI(portalRoot);
            if (!"https".equalsIgnoreCase(origin.getScheme())
                    || origin.getHost() == null
                    || origin.getRawUserInfo() != null) {
                throw new IllegalArgumentException("IWS readiness requires an HTTPS portal origin");
            }
            return origin.resolve("/api/health").toURL();
        } catch (IOException | URISyntaxException failure) {
            throw new IllegalArgumentException("IWS portal URL is malformed", failure);
        }
    }
}

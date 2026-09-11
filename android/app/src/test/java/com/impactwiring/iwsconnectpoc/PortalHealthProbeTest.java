package com.impactwiring.iwsconnectpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.security.Principal;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLHandshakeException;
import org.junit.Test;

public final class PortalHealthProbeTest {
    @Test
    public void usesBoundedNonRedirectingHttpsGetWithoutReadingBody() throws Exception {
        FakeHttpsConnection connection = new FakeHttpsConnection(204, null);
        RecordingFactory factory = new RecordingFactory(connection);
        PortalHealthProbe probe = new PortalHealthProbe(
                "https://portal.iws.example:8443/workflow", () -> true, factory);

        PortalReadinessResult result = probe.execute(7_000);

        assertEquals(PortalReadinessResult.READY, result);
        assertEquals("https://portal.iws.example:8443/api/health", factory.url.toString());
        assertEquals("GET", connection.getRequestMethod());
        assertFalse(connection.getInstanceFollowRedirects());
        assertFalse(connection.getUseCaches());
        assertEquals(7_000, connection.getConnectTimeout());
        assertEquals(7_000, connection.getReadTimeout());
        assertFalse(connection.inputStreamRequested);
        assertTrue(connection.disconnected);
    }

    @Test
    public void retryableServerErrorDoesNotCountAsReady() throws Exception {
        PortalHealthProbe probe = new PortalHealthProbe(
                "https://portal.iws.example/", () -> true, new RecordingFactory(
                        new FakeHttpsConnection(503, null)));

        assertEquals(PortalReadinessResult.RETRYABLE, probe.execute(1_000));
    }

    @Test
    public void permanentClientErrorDoesNotRetry() throws Exception {
        PortalHealthProbe probe = new PortalHealthProbe(
                "https://portal.iws.example/", () -> true, new RecordingFactory(
                        new FakeHttpsConnection(404, null)));

        assertEquals(PortalReadinessResult.PERMANENT, probe.execute(1_000));
    }

    @Test
    public void tlsHandshakeFailureIsFatal() throws Exception {
        PortalHealthProbe probe = new PortalHealthProbe(
                "https://portal.iws.example/", () -> true, new RecordingFactory(
                        new FakeHttpsConnection(0, new SSLHandshakeException("untrusted"))));

        assertEquals(PortalReadinessResult.FATAL_TLS, probe.execute(1_000));
    }

    @Test
    public void wrappedCertificateFailureIsFatal() throws Exception {
        PortalHealthProbe probe = new PortalHealthProbe(
                "https://portal.iws.example/", () -> true, new RecordingFactory(
                        new FakeHttpsConnection(0, new IOException(
                                "certificate rejected", new CertificateException("expired")))));

        assertEquals(PortalReadinessResult.FATAL_TLS, probe.execute(1_000));
    }

    @Test
    public void ordinaryIoFailureIsRetryable() throws Exception {
        PortalHealthProbe probe = new PortalHealthProbe(
                "https://portal.iws.example/", () -> true, new RecordingFactory(
                        new FakeHttpsConnection(0, new IOException("network unavailable"))));

        assertEquals(PortalReadinessResult.RETRYABLE, probe.execute(1_000));
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesNonHttpsPortalOrigins() {
        new PortalHealthProbe("http://portal.iws.example/", () -> true, url -> null);
    }

    @Test
    public void vpnDnsGateFailureDoesNotBeginHostnameLookup() throws Exception {
        RecordingFactory factory = new RecordingFactory(new FakeHttpsConnection(204, null));
        PortalHealthProbe probe = new PortalHealthProbe(
                "https://portal.iws.example/", () -> false, factory);

        assertEquals(PortalReadinessResult.RETRYABLE, probe.execute(1_000));
        assertEquals(0, factory.openCount);
    }

    private static final class RecordingFactory implements PortalHealthProbe.ConnectionFactory {
        private final HttpsURLConnection connection;
        URL url;
        int openCount;

        RecordingFactory(HttpsURLConnection connection) {
            this.connection = connection;
        }

        @Override
        public HttpsURLConnection open(URL url) {
            this.url = url;
            openCount++;
            return connection;
        }
    }

    private static final class FakeHttpsConnection extends HttpsURLConnection {
        private final int responseCode;
        private final IOException failure;
        boolean inputStreamRequested;
        boolean disconnected;

        FakeHttpsConnection(int responseCode, IOException failure) throws Exception {
            super(new URL("https://portal.iws.example/api/health"));
            this.responseCode = responseCode;
            this.failure = failure;
        }

        @Override
        public int getResponseCode() throws IOException {
            if (failure != null) {
                throw failure;
            }
            return responseCode;
        }

        @Override
        public java.io.InputStream getInputStream() {
            inputStreamRequested = true;
            throw new AssertionError("health response body must not be read");
        }

        @Override
        public void disconnect() {
            disconnected = true;
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {}

        @Override
        public String getCipherSuite() {
            return "";
        }

        @Override
        public Certificate[] getLocalCertificates() {
            return null;
        }

        @Override
        public Certificate[] getServerCertificates() {
            return null;
        }

        @Override
        public Principal getPeerPrincipal() {
            return null;
        }

        @Override
        public Principal getLocalPrincipal() {
            return null;
        }
    }
}

package com.impactwiring.iwsconnectpoc;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** Parses the minimal, content-free result of the WebView document readiness query. */
final class CurrentDocumentObservation {
    static final CurrentDocumentObservation NOT_READY =
            new CurrentDocumentObservation(false, 0, "");

    final boolean complete;
    final int responseStatus;
    final String url;

    private CurrentDocumentObservation(boolean complete, int responseStatus, String url) {
        this.complete = complete;
        this.responseStatus = responseStatus;
        this.url = url;
    }

    static CurrentDocumentObservation parse(String javascriptResult) {
        if (javascriptResult == null
                || javascriptResult.length() < 2
                || javascriptResult.charAt(0) != '"'
                || javascriptResult.charAt(javascriptResult.length() - 1) != '"') {
            return NOT_READY;
        }
        String payload = javascriptResult.substring(1, javascriptResult.length() - 1);
        if ("L".equals(payload)) {
            return NOT_READY;
        }
        String[] fields = payload.split("\\|", 3);
        if (fields.length != 3 || !"C".equals(fields[0])) {
            return NOT_READY;
        }
        try {
            int responseStatus = Integer.parseInt(fields[1]);
            String url = URLDecoder.decode(
                    fields[2], StandardCharsets.UTF_8.name());
            if (url.isEmpty()) {
                return NOT_READY;
            }
            return new CurrentDocumentObservation(true, responseStatus, url);
        } catch (IllegalArgumentException | UnsupportedEncodingException failure) {
            return NOT_READY;
        }
    }
}

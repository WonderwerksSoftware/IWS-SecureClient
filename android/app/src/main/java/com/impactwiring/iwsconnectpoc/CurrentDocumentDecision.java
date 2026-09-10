package com.impactwiring.iwsconnectpoc;

/** Classifies an observed current WebView document without trusting callback ordering. */
final class CurrentDocumentDecision {
    enum Outcome {
        WAIT,
        SUCCESS,
        RETRYABLE_FAILURE,
        PERMANENT_FAILURE,
        UNSUPPORTED
    }

    private CurrentDocumentDecision() {}

    static Outcome decide(
            boolean expectedLocation,
            CurrentDocumentObservation observation) {
        if (!observation.complete) {
            return Outcome.WAIT;
        }
        if (!expectedLocation) {
            return observation.url.startsWith("chrome-error://")
                    ? Outcome.RETRYABLE_FAILURE
                    : Outcome.WAIT;
        }
        int status = observation.responseStatus;
        if ((status >= 200 && status < 300) || status == 304) {
            return Outcome.SUCCESS;
        }
        if (status >= 500 && status < 600) {
            return Outcome.RETRYABLE_FAILURE;
        }
        if (status >= 400 && status < 500) {
            return Outcome.PERMANENT_FAILURE;
        }
        if (status <= 0) {
            return Outcome.UNSUPPORTED;
        }
        return Outcome.PERMANENT_FAILURE;
    }
}

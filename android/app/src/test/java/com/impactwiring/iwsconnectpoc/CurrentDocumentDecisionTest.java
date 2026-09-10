package com.impactwiring.iwsconnectpoc;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class CurrentDocumentDecisionTest {
    @Test
    public void staleErrorHintCannotOverrideHealthyCurrentDocument() {
        CurrentDocumentObservation observation = CurrentDocumentObservation.parse(
                "\"C|200|https%3A%2F%2Fportal.iws.example%2F\"");

        assertEquals(CurrentDocumentDecision.Outcome.SUCCESS,
                CurrentDocumentDecision.decide(true, observation));
    }

    @Test
    public void cachedValidatedNavigationStatusIsUsable() {
        CurrentDocumentObservation observation = CurrentDocumentObservation.parse(
                "\"C|304|https%3A%2F%2Fportal.iws.example%2F\"");

        assertEquals(CurrentDocumentDecision.Outcome.SUCCESS,
                CurrentDocumentDecision.decide(true, observation));
    }

    @Test
    public void currentHttpStatusesRemainFailureClassified() {
        CurrentDocumentObservation unavailable = CurrentDocumentObservation.parse(
                "\"C|503|https%3A%2F%2Fportal.iws.example%2F\"");
        CurrentDocumentObservation rejected = CurrentDocumentObservation.parse(
                "\"C|404|https%3A%2F%2Fportal.iws.example%2F\"");

        assertEquals(CurrentDocumentDecision.Outcome.RETRYABLE_FAILURE,
                CurrentDocumentDecision.decide(true, unavailable));
        assertEquals(CurrentDocumentDecision.Outcome.PERMANENT_FAILURE,
                CurrentDocumentDecision.decide(true, rejected));
    }

    @Test
    public void unsupportedResponseStatusIsExplicitNotAHiddenTimeout() {
        CurrentDocumentObservation observation = CurrentDocumentObservation.parse(
                "\"C|-1|https%3A%2F%2Fportal.iws.example%2F\"");

        assertEquals(CurrentDocumentDecision.Outcome.UNSUPPORTED,
                CurrentDocumentDecision.decide(true, observation));
    }

    @Test
    public void oldCallbacksWhileReplacementLoadsDoNothing() {
        assertEquals(CurrentDocumentDecision.Outcome.WAIT,
                CurrentDocumentDecision.decide(true,
                        CurrentDocumentObservation.parse("\"L\"")));
    }

    @Test
    public void currentChromeErrorRetriesWithoutBorrowingCallbackErrorCode() {
        CurrentDocumentObservation errorDocument = CurrentDocumentObservation.parse(
                "\"C|0|chrome-error%3A%2F%2Fchromewebdata%2F\"");

        assertEquals(CurrentDocumentDecision.Outcome.RETRYABLE_FAILURE,
                CurrentDocumentDecision.decide(false, errorDocument));
    }
}

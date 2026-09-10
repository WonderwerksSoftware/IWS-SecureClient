package com.impactwiring.iwsconnectpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CurrentDocumentObservationTest {
    @Test
    public void parsesCompleteCurrentDocumentWithoutReadingItsContents() {
        CurrentDocumentObservation observation = CurrentDocumentObservation.parse(
                "\"C|204|https%3A%2F%2Fportal.iws.example%2Fbuild%3Fid%3D4\"");

        assertTrue(observation.complete);
        assertEquals(204, observation.responseStatus);
        assertEquals("https://portal.iws.example/build?id=4", observation.url);
    }

    @Test
    public void loadingDocumentIsNotComplete() {
        CurrentDocumentObservation observation = CurrentDocumentObservation.parse("\"L\"");

        assertFalse(observation.complete);
    }

    @Test
    public void keepsCurrentHttpFailureStatusForClassification() {
        CurrentDocumentObservation observation = CurrentDocumentObservation.parse(
                "\"C|503|https%3A%2F%2Fportal.iws.example%2F\"");

        assertTrue(observation.complete);
        assertEquals(503, observation.responseStatus);
    }

    @Test
    public void malformedJavascriptResultFailsClosed() {
        assertFalse(CurrentDocumentObservation.parse("null").complete);
        assertFalse(CurrentDocumentObservation.parse("\"C|200|%zz\"").complete);
    }
}

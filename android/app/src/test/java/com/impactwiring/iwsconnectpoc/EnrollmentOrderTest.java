package com.impactwiring.iwsconnectpoc;

import static org.junit.Assert.assertEquals;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class EnrollmentOrderTest {
    @Test public void initializesProtectedNativeClientBeforeStartingAuth() throws Exception {
        List<String> calls = new ArrayList<>();
        EnrollmentOrder.protectThenAuthenticate(
                () -> calls.add("native-client-protect"),
                () -> calls.add("auth"));
        assertEquals(Arrays.asList("native-client-protect", "auth"), calls);
    }

    @Test public void failedProtectionInitializationPreventsAuth() {
        List<String> calls = new ArrayList<>();
        try {
            EnrollmentOrder.protectThenAuthenticate(
                    () -> { throw new Exception("protect failed"); },
                    () -> calls.add("auth"));
        } catch (Exception expected) {
            calls.add("failed-closed");
        }
        assertEquals(Arrays.asList("failed-closed"), calls);
    }
}

package com.impactwiring.iwsconnectpoc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public final class ApprovedDnsPolicyTest {
    @Test
    public void requiresAtLeastOneDnsServer() {
        assertFalse(ApprovedDnsPolicy.allServersMatch(
                Collections.emptyList(), "100.83.75.124"));
    }

    @Test
    public void acceptsOnlyTheCompiledDnsAddress() {
        assertTrue(ApprovedDnsPolicy.allServersMatch(
                Collections.singletonList("100.83.75.124"), "100.83.75.124"));
    }

    @Test
    public void rejectsApprovedDnsAlongsideAnUnrelatedResolver() {
        assertFalse(ApprovedDnsPolicy.allServersMatch(
                Arrays.asList("100.83.75.124", "8.8.8.8"), "100.83.75.124"));
    }
}

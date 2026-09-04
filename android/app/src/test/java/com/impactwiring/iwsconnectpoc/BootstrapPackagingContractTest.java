package com.impactwiring.iwsconnectpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class BootstrapPackagingContractTest {
    @Test public void packageIdentityRemainsTheIwsClient() {
        assertEquals("com.impactwiring.iwsconnectpoc", BuildConfig.APPLICATION_ID);
        assertFalse(BuildConfig.APPLICATION_ID.toLowerCase().contains("netbird"));
    }
}

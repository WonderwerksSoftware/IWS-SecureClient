package com.impactwiring.iwsconnectpoc;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class PrivateDnsPolicyTest {
    @Test public void acceptsOnlyPinnedResolverWithoutSearchExpansion() {
        assertEquals("100.83.75.124", PrivateDnsPolicy.resolver("", ""));
        assertEquals("100.83.75.124", PrivateDnsPolicy.resolver("100.83.75.124", ""));
    }
    @Test(expected = SecurityException.class) public void rejectsOtherResolver() {
        PrivateDnsPolicy.resolver("1.1.1.1", "");
    }
    @Test(expected = SecurityException.class) public void rejectsRetiredStagingResolver() {
        PrivateDnsPolicy.resolver("100.83.246.85", "");
    }
    @Test(expected = SecurityException.class) public void rejectsSearchDomains() {
        PrivateDnsPolicy.resolver("", "example.com");
    }
    @Test(expected = SecurityException.class) public void rejectsMultipleResolvers() {
        PrivateDnsPolicy.resolver("100.83.75.124,1.1.1.1", "");
    }
    @Test(expected = SecurityException.class) public void rejectsIpv6Resolver() {
        PrivateDnsPolicy.resolver("::1", "");
    }
}

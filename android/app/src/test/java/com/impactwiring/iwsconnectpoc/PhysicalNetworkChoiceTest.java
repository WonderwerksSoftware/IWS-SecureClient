package com.impactwiring.iwsconnectpoc;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;
public class PhysicalNetworkChoiceTest {
 @Test public void vpnAndMissingDnsNeverBecomeTransportUnderlay() {
  assertNull(PhysicalNetworkChoice.choose(Arrays.asList(
   new PhysicalNetworkChoice.Candidate(1, false, true, true, true),
   new PhysicalNetworkChoice.Candidate(2, true, true, false, true)), null));
 }
 @Test public void validatedReplacementWinsOverLostInternet() {
  assertEquals(Integer.valueOf(2), PhysicalNetworkChoice.choose(Arrays.asList(
   new PhysicalNetworkChoice.Candidate(1, true, true, true, false),
   new PhysicalNetworkChoice.Candidate(2, true, true, true, true)), 1));
 }
 @Test public void stablePhysicalNetworkIsRetainedAndLossFallsBack() {
  PhysicalNetworkChoice.Candidate a = new PhysicalNetworkChoice.Candidate(1,true,true,true,true);
  PhysicalNetworkChoice.Candidate b = new PhysicalNetworkChoice.Candidate(2,true,true,true,true);
  assertEquals(Integer.valueOf(2), PhysicalNetworkChoice.choose(Arrays.asList(a,b),2));
  assertEquals(Integer.valueOf(1), PhysicalNetworkChoice.choose(Arrays.asList(a),2));
  assertNull(PhysicalNetworkChoice.choose(Arrays.asList(),1));
 }
}

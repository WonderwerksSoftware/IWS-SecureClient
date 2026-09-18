package com.impactwiring.iwsconnectpoc;
import org.junit.Test;
import static org.junit.Assert.*;
public class EngineRecoveryTest {
 @Test public void repeatedRetryWaitsForExitAndStartsExactlyOneReplacement() {
  EngineRecovery r = new EngineRecovery();
  assertTrue(r.start()); assertTrue(r.retry()); assertFalse(r.retry());
  assertFalse(r.start()); assertTrue(r.finished());
  assertTrue(r.start()); assertFalse(r.finished());
 }
 @Test public void disconnectCancelsQueuedRecovery() {
  EngineRecovery r = new EngineRecovery(); r.start(); r.retry(); r.cancel();
  assertFalse(r.start()); assertFalse(r.finished()); assertTrue(r.start());
 }
 @Test public void timedOutStopNeverStartsConcurrentEngineOrRestartsLater() {
  EngineRecovery r = new EngineRecovery(); r.start(); r.retry();
  assertTrue(r.timeout()); assertFalse(r.timeout()); assertFalse(r.start());
  assertFalse(r.finished()); assertTrue(r.start());
 }
 @Test public void inactiveRetryUsesOrdinaryStart() {
  EngineRecovery r = new EngineRecovery(); assertFalse(r.retry()); assertTrue(r.start());
 }
}

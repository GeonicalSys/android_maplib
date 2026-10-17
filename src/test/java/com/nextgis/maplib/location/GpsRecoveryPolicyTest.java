package com.nextgis.maplib.location;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.nextgis.maplib.location.GpsRecoveryPolicy.Reason.*;

public class GpsRecoveryPolicyTest {
    private GpsRecoveryPolicy.Update tick(GpsRecoveryPolicy p, long t, GpsRecoveryPolicy.Reason reason) {
        return p.update(t, true, true, false, reason);
    }
    private void fresh(GpsRecoveryPolicy p, long t) { p.update(t, true, true, true, NO_FIX); }

    @Test public void productionTwentyThreeSecondGapStaysLocalAndRecoversWithoutRetry() {
        GpsRecoveryPolicy p = new GpsRecoveryPolicy();
        fresh(p, 1_000);
        assertEquals(NO_FIX, GpsRecoveryPolicy.classify(false, false, 23_076, 95, 0, "accept"));
        assertNull(tick(p, 9_451, NO_FIX));
        GpsRecoveryPolicy.Update gap = tick(p, 24_591, NO_FIX);
        assertEquals(15_140, gap.waitingMs);
        assertFalse(gap.report); assertFalse(gap.retry);
        GpsRecoveryPolicy.Update end = p.update(25_000, true, true, true, NO_FIX);
        assertEquals("recovered", end.phase); assertEquals(gap.episodeId, end.episodeId);
        assertFalse(end.report); assertFalse(end.retryAttempted);
    }

    @Test public void longNoFixSendsOneWarningAndItsCorrelatedRecovery() {
        GpsRecoveryPolicy p = new GpsRecoveryPolicy();
        tick(p, 1_000, NO_FIX);
        GpsRecoveryPolicy.Update local = tick(p, 31_000, NO_FIX);
        assertEquals(30_000, local.thresholdMs); assertFalse(local.report);
        assertNull(tick(p, 60_999, NO_FIX));
        GpsRecoveryPolicy.Update warning = tick(p, 61_000, NO_FIX);
        assertTrue(warning.report); assertFalse(warning.retry);
        assertNull(tick(p, 500_000, NO_FIX));
        GpsRecoveryPolicy.Update end = p.update(501_000, true, true, true, NO_FIX);
        assertTrue(end.report); assertEquals(warning.episodeId, end.episodeId);
        assertEquals(500_000, end.waitingMs);
        assertNull(p.update(502_000, true, true, true, NO_FIX));
    }

    @Test public void thirdShortGapEscalatesButSubsequentEpisodesAreRateLimited() {
        GpsRecoveryPolicy p = new GpsRecoveryPolicy();
        for (int i = 0; i < 4; i++) {
            long start = 1_000 + i * 30_000;
            fresh(p, start);
            tick(p, start + 1, NO_FIX);
            GpsRecoveryPolicy.Update gap = tick(p, start + 15_001, NO_FIX);
            assertEquals(i == 2, gap.report);
            assertFalse(gap.retry);
            GpsRecoveryPolicy.Update end = p.update(start + 16_000, true, true, true, NO_FIX);
            assertEquals(i == 2, end.report);
        }
        tick(p, 800_000, NO_FIX);
        assertFalse(tick(p, 815_000, NO_FIX).report);
    }

    @Test public void classificationsDistinguishReceptionDeliveryAndProcessing() {
        assertEquals(CALLBACK_STALLED, GpsRecoveryPolicy.classify(false, false, 23_000, 95, 8, "accept"));
        assertEquals(PROVIDER_SILENT, GpsRecoveryPolicy.classify(false, false, 23_000, 20_000, 8, "accept"));
        assertEquals(STALE_FIX, GpsRecoveryPolicy.classify(false, false, 1, 20, 8, "stale"));
        assertEquals(STALE_FIX, GpsRecoveryPolicy.classify(false, false, 1, 20, 8, "duplicate"));
        assertEquals(INVALID_FIX, GpsRecoveryPolicy.classify(false, false, 1, 20, 8, "invalid"));
        assertEquals(FILTER_WAIT, GpsRecoveryPolicy.classify(true, false, 1, 20, 8, "hold:filter"));
        assertEquals(DISPLAY_PROCESSING, GpsRecoveryPolicy.classify(true, false, 1, 20, 8, "accept"));
        assertEquals(ACQUISITION, GpsRecoveryPolicy.classify(false, false, -1, -1, 0, "none"));
    }

    @Test public void recoveryKeepsTheWarningGroupEvenIfTheGapReasonChanged() {
        GpsRecoveryPolicy p = new GpsRecoveryPolicy();
        tick(p, 1_000, NO_FIX); tick(p, 61_000, NO_FIX);
        tick(p, 62_000, PROVIDER_SILENT);
        GpsRecoveryPolicy.Update end = p.update(63_000, true, true, true, NO_FIX);
        assertTrue(end.report); assertEquals(NO_FIX, end.reason);
    }

    @Test public void sustainedDeliveryStallRetriesOnceAndKeepsAttemptOnRecovery() {
        GpsRecoveryPolicy p = new GpsRecoveryPolicy();
        fresh(p, 1_000); tick(p, 2_000, CALLBACK_STALLED);
        assertTrue(tick(p, 17_000, CALLBACK_STALLED).report);
        assertNull(tick(p, 31_999, CALLBACK_STALLED));
        GpsRecoveryPolicy.Update retry = tick(p, 32_000, CALLBACK_STALLED);
        assertTrue(retry.retry); assertTrue(retry.retryAttempted); assertFalse(retry.report);
        assertNull(tick(p, 100_000, CALLBACK_STALLED));
        GpsRecoveryPolicy.Update end = p.update(101_000, true, true, true, NO_FIX);
        assertTrue(end.retryAttempted); assertTrue(end.report);
        tick(p, 102_000, CALLBACK_STALLED);
        assertFalse(tick(p, 117_000, CALLBACK_STALLED).report);
        assertNull(tick(p, 132_000, CALLBACK_STALLED));
    }

    @Test public void changeToNoFixResetsTheContinuousStallTimer() {
        GpsRecoveryPolicy p = new GpsRecoveryPolicy();
        tick(p, 1_000, CALLBACK_STALLED); tick(p, 20_000, NO_FIX);
        tick(p, 30_000, CALLBACK_STALLED);
        GpsRecoveryPolicy.Update delay = tick(p, 31_000, CALLBACK_STALLED);
        assertFalse(delay.retry);
        assertNull(tick(p, 59_999, CALLBACK_STALLED));
        assertTrue(tick(p, 60_000, CALLBACK_STALLED).retry);
    }

    @Test public void noFixFilterWaitAndProviderSilenceNeverRetry() {
        for (GpsRecoveryPolicy.Reason reason : new GpsRecoveryPolicy.Reason[]{NO_FIX, FILTER_WAIT, PROVIDER_SILENT}) {
            GpsRecoveryPolicy p = new GpsRecoveryPolicy();
            tick(p, 1_000, reason);
            assertFalse(tick(p, 31_000, reason).retry);
            assertFalse(tick(p, 61_000, reason).retry);
            assertNull(tick(p, 100_000, reason));
        }
    }

    @Test public void permissionLossAndSubscriptionStopCancelRatherThanInventRecovery() {
        GpsRecoveryPolicy p = new GpsRecoveryPolicy();
        assertNull(p.update(1_000, true, false, false, NO_FIX));
        fresh(p, 2_000); tick(p, 3_000, NO_FIX);
        assertFalse(tick(p, 18_000, NO_FIX).report);
        GpsRecoveryPolicy.Update end = p.update(19_000, false, true, true, NO_FIX);
        assertEquals("cancelled", end.phase); assertFalse(end.report);
        assertEquals(30_000, p.thresholdMs());
        tick(p, 10_000_000, NO_FIX);
        assertNull(tick(p, 10_029_999, NO_FIX));
        assertFalse(tick(p, 10_030_000, NO_FIX).report);
        assertTrue(tick(p, 10_060_000, NO_FIX).report);
        assertEquals("cancelled", p.update(10_060_001, true, false, true, NO_FIX).phase);
    }

    @Test public void historyHasFixedMemoryAndCadenceAndReturnsAnIndependentSnapshot() {
        GpsRecoveryPolicy p = new GpsRecoveryPolicy();
        for (int i = 0; i < 100; i++) {
            p.recordHistory(i * 5_000, "sample-" + i);
            p.recordHistory(i * 5_000 + 1, "too-soon");
        }
        assertEquals(24, p.history().size());
        assertEquals("sample-76", p.history().get(0));
        java.util.List<String> copy = p.history(); p.clearHistory();
        assertEquals(24, copy.size()); assertTrue(p.history().isEmpty());
        assertTrue(p.historyDue(500_000));
    }
}

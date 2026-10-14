package com.nextgis.maplib.location;

import org.junit.Test;
import static org.junit.Assert.*;

public class GpsRecoveryPolicyTest {
    @Test public void previouslyWorkingGpsHasAShorterRecoveryThreshold() {
        GpsRecoveryPolicy policy = new GpsRecoveryPolicy();
        policy.shouldReport(1_000, true, true, true);
        assertEquals(15_000, policy.thresholdMs());
        assertFalse(policy.shouldReport(2_000, true, true, false));
        assertFalse(policy.shouldReport(16_999, true, true, false));
        assertTrue(policy.shouldReport(17_000, true, true, false));
    }
    @Test public void longAcquisitionReportsOnceAndFreshFixEndsItsEpisode() {
        GpsRecoveryPolicy policy = new GpsRecoveryPolicy();
        assertFalse(policy.shouldReport(1_000, true, true, false));
        assertFalse(policy.shouldReport(30_999, true, true, false));
        assertTrue(policy.shouldReport(31_000, true, true, false));
        assertEquals(30_000, policy.waitingMs(31_000));
        assertFalse(policy.shouldReport(500_000, true, true, false));
        assertFalse(policy.shouldReport(501_000, true, true, true));
        assertEquals(0, policy.waitingMs(501_000));
    }

    @Test public void recurringGapsAreLimitedToOneReportPerTenMinutes() {
        GpsRecoveryPolicy policy = new GpsRecoveryPolicy();
        policy.shouldReport(1_000, true, true, false);
        assertTrue(policy.shouldReport(31_000, true, true, false));
        policy.shouldReport(32_000, true, true, true);
        policy.shouldReport(33_000, true, true, false);
        assertFalse(policy.shouldReport(63_000, true, true, false));
        assertTrue(policy.shouldReport(631_000, true, true, false));
    }

    @Test public void disabledProviderPermissionLossAndStoppedRequestsAreQuiet() {
        GpsRecoveryPolicy policy = new GpsRecoveryPolicy();
        assertFalse(policy.shouldReport(1_000, true, false, false));
        assertFalse(policy.shouldReport(90_000, true, false, false));
        assertFalse(policy.shouldReport(120_000, false, true, false));
        assertFalse(policy.shouldReport(121_000, true, true, false));
        assertTrue(policy.shouldReport(151_000, true, true, false));
    }
}

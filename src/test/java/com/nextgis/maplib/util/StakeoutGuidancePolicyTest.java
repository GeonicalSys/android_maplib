package com.nextgis.maplib.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class StakeoutGuidancePolicyTest {
    @Test
    public void selectsConfiguredBands() {
        StakeoutGuidancePolicy policy = policy();

        assertEquals(StakeoutGuidancePolicy.Band.SILENT, policy.evaluate(6.0, true));
        assertEquals(StakeoutGuidancePolicy.Band.FAR, policy.evaluate(5.0, true));
        assertEquals(StakeoutGuidancePolicy.Band.MEDIUM, policy.evaluate(1.0, true));
        assertEquals(StakeoutGuidancePolicy.Band.NEAR, policy.evaluate(0.5, true));
        assertEquals(StakeoutGuidancePolicy.Band.REACHED, policy.evaluate(0.1, true));
    }

    @Test
    public void receiverAccuracyPlaceholderDoesNotSilenceFineGuidance() {
        assertEquals(
                StakeoutGuidancePolicy.Band.NEAR,
                policy().evaluate(0.2, true));
    }

    @Test
    public void nonPrecisionFixNeverDrivesAudio() {
        assertEquals(
                StakeoutGuidancePolicy.Band.SILENT,
                policy().evaluate(0.05, false));
    }

    @Test
    public void hysteresisPreventsThresholdChatter() {
        StakeoutGuidancePolicy policy = policy();

        assertEquals(StakeoutGuidancePolicy.Band.NEAR, policy.evaluate(0.49, true));
        assertEquals(StakeoutGuidancePolicy.Band.NEAR, policy.evaluate(0.53, true));
        assertEquals(StakeoutGuidancePolicy.Band.MEDIUM, policy.evaluate(0.56, true));
    }

    @Test
    public void invalidDistanceIsSilent() {
        assertEquals(
                StakeoutGuidancePolicy.Band.SILENT,
                policy().evaluate(Double.NaN, true));
    }

    private static StakeoutGuidancePolicy policy() {
        return new StakeoutGuidancePolicy(5.0, 1.0, 0.5, 0.1);
    }
}

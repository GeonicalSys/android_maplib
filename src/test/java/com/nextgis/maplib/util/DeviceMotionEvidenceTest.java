package com.nextgis.maplib.util;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.nextgis.maplib.util.DeviceMotionEvidence.State.*;

public class DeviceMotionEvidenceTest {
    @Test public void requiresSustainedQuietAndExpiresWithoutSensorCallbacks() {
        DeviceMotionEvidence evidence = new DeviceMotionEvidence();
        for (int i = 0; i <= 150; i++) evidence.add(1_000_000_000L + i * 40_000_000L,
                Math.sin(i) * .02, .01, 9.81);
        assertEquals(UNKNOWN, evidence.at(2_000_000_000L));
        assertEquals(STILL, evidence.at(7_000_000_000L));
        assertEquals(UNKNOWN, evidence.at(10_000_000_000L));
    }

    @Test public void walkingReleasesQuietAndHistoricalFixUsesHistoricalEvidence() {
        DeviceMotionEvidence evidence = new DeviceMotionEvidence();
        for (int i = 0; i <= 250; i++) evidence.add(1_000_000_000L + i * 40_000_000L,
                i < 150 ? .01 : Math.sin(i * .5) * 2, 0, 9.81);
        assertEquals(STILL, evidence.at(6_000_000_000L));
        assertEquals(MOVING, evidence.at(11_000_000_000L));
        assertEquals(UNKNOWN, evidence.at(500_000_000L));
        evidence.reset();
        assertEquals(UNKNOWN, evidence.at(11_000_000_000L));
    }

    @Test public void callbackGapAndSparseSamplesCannotConfirmAStop() {
        DeviceMotionEvidence evidence = new DeviceMotionEvidence();
        for (int i = 1; i < 10; i++) evidence.add(i * 1_000_000_000L, 0, 0, 9.81);
        assertEquals(UNKNOWN, evidence.at(9_000_000_000L));
        evidence.add(20_000_000_000L, 0, 0, 9.81);
        assertEquals(UNKNOWN, evidence.at(20_000_000_000L));
    }
}

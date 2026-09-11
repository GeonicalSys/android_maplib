package com.nextgis.maplib.util;

import org.junit.Test;
import java.util.Random;
import static org.junit.Assert.*;

public class AdaptiveLocationFilterCoreTest {
    private static AdaptiveLocationFilterCore.Sample fix(double x, double y, long seconds,
                                                         double accuracy, double speed, double bearing) {
        return new AdaptiveLocationFilterCore.Sample(x, y, 1000 + seconds * 1000,
                accuracy, speed, 0.15, bearing);
    }

    @Test public void stationaryNoiseAndIsolatedFortyMeterSpikesDoNotAccumulateDistance() {
        AdaptiveLocationFilterCore filter = new AdaptiveLocationFilterCore();
        Random random = new Random(710);
        AdaptiveLocationFilterCore.Estimate previous = null;
        double travel = 0;
        for (int i = 0; i < 600; i++) {
            boolean spike = i > 20 && i % 31 == 0;
            double x = spike ? 40 : random.nextGaussian() * 1.5;
            double y = random.nextGaussian() * 1.5;
            AdaptiveLocationFilterCore.Estimate estimate = filter.onSample(fix(x, y, i, 8, 0, Double.NaN));
            if (estimate != null) {
                if (i > 20 && previous != null) travel += Math.hypot(estimate.x - previous.x, estimate.y - previous.y);
                assertTrue("stationary displacement at " + i, Math.hypot(estimate.x, estimate.y) < 6);
                previous = estimate;
            }
        }
        assertTrue("ten minutes standing: " + travel, travel < 15);
    }

    @Test public void weakStationaryReceptionWithoutSpeedRejectsIsolatedJumps() {
        AdaptiveLocationFilterCore filter = new AdaptiveLocationFilterCore();
        Random random = new Random(419);
        for (int i = 0; i < 300; i++) {
            double x = i > 20 && i % 23 == 0 ? 45 : random.nextGaussian() * 2.5;
            AdaptiveLocationFilterCore.Estimate result = filter.onSample(
                    fix(x, random.nextGaussian() * 2.5, i, 20, Double.NaN, Double.NaN));
            if (result != null && i > 15) assertTrue("weak reception drift " + i,
                    Math.hypot(result.x, result.y) < 8);
        }
    }

    @Test public void supportsWalkingAndCarsThrough160KmhWithAndWithoutReportedSpeed() {
        for (double speed : new double[]{0.5, 1.4, 8.3, 25, 44.44}) {
            for (boolean reportsSpeed : new boolean[]{true, false}) {
                AdaptiveLocationFilterCore filter = new AdaptiveLocationFilterCore();
                for (int i = 0; i < 120; i++) {
                    AdaptiveLocationFilterCore.Estimate result = filter.onSample(fix(speed * i, 0, i, 4,
                            reportsSpeed ? speed : Double.NaN, reportsSpeed ? 90 : Double.NaN));
                    assertNotNull("motion " + speed + " at " + i, result);
                    if (i > 10) assertEquals(speed * i, result.x, 2.0);
                }
            }
        }
    }

    @Test public void startsWalkingAfterStandingWithoutAReportedSpeed() {
        AdaptiveLocationFilterCore filter = new AdaptiveLocationFilterCore();
        for (int i = 0; i < 15; i++) filter.onSample(fix(0, 0, i, 5, Double.NaN, Double.NaN));
        AdaptiveLocationFilterCore.Estimate result = null;
        for (int i = 15; i < 30; i++) result = filter.onSample(fix((i - 14) * 1.2, 0, i, 5, Double.NaN, Double.NaN));
        assertNotNull(result);
        assertFalse(result.stationary);
        assertEquals(18, result.x, 2);
    }

    @Test public void retainsNinetyDegreeTurnsAtWalkingAndRoadSpeeds() {
        for (double speed : new double[]{1.4, 15}) {
            AdaptiveLocationFilterCore filter = new AdaptiveLocationFilterCore();
            for (int i = 0; i < 40; i++) {
                double x = Math.min(i, 20) * speed;
                double y = Math.max(0, i - 20) * speed;
                AdaptiveLocationFilterCore.Estimate result = filter.onSample(fix(x, y, i, 4, speed, i <= 20 ? 90 : 0));
                assertNotNull("corner speed=" + speed + " time=" + i, result);
                assertTrue("corner deviation speed=" + speed + " time=" + i + " error=" + Math.hypot(result.x - x, result.y - y), Math.hypot(result.x - x, result.y - y) < 6);
            }
        }
    }

    @Test public void isolatedOutlierDoesNotPoisonSubsequentWalking() {
        AdaptiveLocationFilterCore filter = new AdaptiveLocationFilterCore();
        for (int i = 0; i < 60; i++) {
            AdaptiveLocationFilterCore.Estimate result = filter.onSample(fix(i * 1.4, i == 30 ? 70 : 0,
                    i, 5, 1.4, 90));
            if (i == 30) assertNull(result);
            else {
                assertNotNull("recovery at " + i, result);
                assertEquals(0, result.y, 2);
            }
        }
    }

    @Test public void reacquiresAfterTunnelOnlyFromAConsistentSequence() {
        AdaptiveLocationFilterCore filter = new AdaptiveLocationFilterCore();
        for (int i = 0; i < 10; i++) filter.onSample(fix(i * 20, 0, i, 5, 20, 90));
        assertNull(filter.onSample(fix(1500, 0, 75, 5, 20, 90)));
        assertNull(filter.onSample(fix(1520, 0, 76, 5, 20, 90)));
        AdaptiveLocationFilterCore.Estimate resumed = filter.onSample(fix(1540, 0, 77, 5, 20, 90));
        assertNotNull(resumed);
        assertEquals(1540, resumed.x, 0.1);
    }

    @Test public void repeatedOrReversedMeasurementsCannotRenewState() {
        AdaptiveLocationFilterCore filter = new AdaptiveLocationFilterCore();
        filter.onSample(fix(0, 0, 10, 4, 0, 0));
        assertNull(filter.onSample(fix(10, 0, 10, 4, 0, 0)));
        assertNull(filter.onSample(fix(10, 0, 9, 4, 0, 0)));
        assertNull(filter.onSample(fix(Double.NaN, 0, 11, 4, 0, 0)));
        assertEquals(0, filter.onSample(fix(0, 0, 11, 4, 0, 0)).x, 0);
    }

    @Test public void stationaryWindowWorksWithThePrecisionToolQuarterSecondLease() {
        AdaptiveLocationFilterCore filter = new AdaptiveLocationFilterCore();
        AdaptiveLocationFilterCore.Estimate result = null;
        for (int i = 0; i < 80; i++) {
            result = filter.onSample(new AdaptiveLocationFilterCore.Sample(
                    Math.sin(i) * 0.5, Math.cos(i) * 0.5, 1000 + 250L * i, 5, 0, 0.15, Double.NaN));
        }
        assertNotNull(result);
        assertTrue(result.stationary);
        assertTrue(Math.hypot(result.x, result.y) < 1);
    }

    @Test public void retainsCurvedRoadWithoutReportedSpeedOrBearing() {
        AdaptiveLocationFilterCore filter = new AdaptiveLocationFilterCore();
        for (int i = 0; i < 60; i++) {
            double angle = i * 0.3;
            double x = 50 * Math.sin(angle), y = 50 * (1 - Math.cos(angle));
            AdaptiveLocationFilterCore.Estimate result = filter.onSample(fix(x, y, i, 4, Double.NaN, Double.NaN));
            assertNotNull("road curve time=" + i, result);
            assertTrue("road curve error=" + Math.hypot(result.x - x, result.y - y),
                    Math.hypot(result.x - x, result.y - y) < 6);
        }
    }

    @Test public void displayedCircleContainsTheOriginalMeasurementCircle() {
        AdaptiveLocationFilterCore filter = new AdaptiveLocationFilterCore();
        for (int i = 0; i < 30; i++) {
            double x = Math.sin(i) * 2;
            AdaptiveLocationFilterCore.Estimate result = filter.onSample(fix(x, 0, i, 8, 0, Double.NaN));
            assertNotNull(result);
            assertTrue(result.accuracy >= 8 + Math.abs(x - result.x) - 0.001);
        }
    }
    private static AdaptiveLocationFilterCore.Sample measured(double x, double y, int second,
            double accuracy, double speed, double speedError, double bearing, DeviceMotionEvidence.State motion) {
        return new AdaptiveLocationFilterCore.Sample(x, y, 1000L + second * 1000L,
                accuracy, speed, speedError, bearing, motion);
    }

    @Test public void twentyMinutesIndoorCorrelatedDriftDoesNotCreateATrack() {
        AdaptiveLocationFilterCore filter = new AdaptiveLocationFilterCore();
        Random random = new Random(54);
        double total = 0;
        AdaptiveLocationFilterCore.Estimate previous = null;
        for (int i = 0; i < 1200; i++) {
            // Multipath bias wanders 100 m, with falsely optimistic 20-30 m accuracy
            // and occasional, equally false speed readings. This is not independent white noise.
            double bias = i < 20 ? 0 : 90 * Math.sin((i - 20) / 120d);
            double x = bias + random.nextGaussian() * 3;
            double y = i < 20 ? 0 : 40 * Math.sin((i - 20) / 75d);
            AdaptiveLocationFilterCore.Estimate result = filter.onSample(measured(x, y, i,
                    25 + 5 * Math.sin(i / 30d), .8 + random.nextDouble() * 1.5, .15,
                    random.nextDouble() * 360, i < 4 ? DeviceMotionEvidence.State.UNKNOWN : DeviceMotionEvidence.State.STILL));
            assertNotNull(result);
            assertTrue("false departure at " + i, result.stationary);
            if (previous != null && i > 20) total += Math.hypot(result.x - previous.x, result.y - previous.y);
            assertTrue("anchor drift " + i, Math.hypot(result.x, result.y) < 10);
            assertTrue("must not hide provider error", result.accuracy >= 20);
            previous = result;
        }
        assertTrue("standing distance " + total, total < 1);
    }

    @Test public void outdoorFifteenMeterDriftAndFalseWalkingSpeedStayAtRest() {
        AdaptiveLocationFilterCore filter = new AdaptiveLocationFilterCore();
        for (int i = 0; i < 600; i++) {
            double x = i < 20 ? 0 : 15 * Math.sin((i - 20) / 25d);
            AdaptiveLocationFilterCore.Estimate result = filter.onSample(measured(x, 0, i, 5, 1.1, .2,
                    90, DeviceMotionEvidence.State.STILL));
            assertNotNull(result);
            assertTrue(result.stationary);
            assertEquals(0, result.x, .01);
        }
    }

    @Test public void poorInitialAnchorCanBeCorrectedWithoutStartingAFalseJourney() {
        AdaptiveLocationFilterCore filter = new AdaptiveLocationFilterCore();
        AdaptiveLocationFilterCore.Estimate result = null;
        long stop = 0;
        for (int i = 0; i < 60; i++) {
            double x = i < 15 ? 35 + Math.sin(i) : Math.sin(i) * .5;
            result = filter.onSample(measured(x, 0, i, i < 15 ? 30 : 5,
                    0, .2, Double.NaN, DeviceMotionEvidence.State.STILL));
            assertNotNull(result);
            if (i == 0) stop = result.stopId;
            assertTrue(result.stationary);
            assertEquals(stop, result.stopId);
        }
        assertEquals(0, result.x, 1);
    }

    @Test public void oneWrongFirstFixIsRefinedEvenIfReportedAccuracyDoesNotChange() {
        AdaptiveLocationFilterCore filter = new AdaptiveLocationFilterCore();
        AdaptiveLocationFilterCore.Estimate result = null;
        for (int i = 0; i < 20; i++) result = filter.onSample(measured(i == 0 ? 25 : Math.sin(i),
                0, i, 8, 0, .2, Double.NaN, DeviceMotionEvidence.State.STILL));
        assertNotNull(result);
        assertTrue(result.stationary);
        assertEquals(0, result.x, 1);
    }

    @Test public void noisySlowWalkingLeavesAStopWithFreshSensorEvidence() {
        for (double speed : new double[]{.5, 1.4}) {
            AdaptiveLocationFilterCore filter = new AdaptiveLocationFilterCore();
            Random random = new Random(13);
            AdaptiveLocationFilterCore.Estimate result = null;
            int releasedAt = -1;
            for (int i = 0; i < 100; i++) {
                boolean moving = i >= 20;
                double x = moving ? (i - 20) * speed : 0;
                result = filter.onSample(measured(x + random.nextGaussian() * .6,
                        random.nextGaussian() * .6, i, 6, moving ? speed : 0, .08, 90,
                        moving ? DeviceMotionEvidence.State.MOVING : DeviceMotionEvidence.State.STILL));
                if (moving && result != null && !result.stationary && releasedAt < 0) releasedAt = i;
            }
            assertTrue("slow walk departure " + speed + " at " + releasedAt, releasedAt >= 20 && releasedAt <= 30);
            assertNotNull(result);
            assertEquals(79 * speed, result.x, 2);
        }
    }

    @Test public void uniformVehicleMotionOverridesAQuietPhoneIncludingAfterAStop() {
        for (boolean speedAvailable : new boolean[]{true, false}) {
            AdaptiveLocationFilterCore filter = new AdaptiveLocationFilterCore();
            AdaptiveLocationFilterCore.Estimate result = null;
            for (int i = 0; i < 100; i++) {
                double x = i < 20 ? 0 : i < 50 ? (i - 20) * 10 : i < 70 ? 300 : 300 + (i - 70) * 10;
                double speed = i >= 20 && i < 50 || i >= 70 ? 10 : 0;
                result = filter.onSample(measured(x, 0, i, 5, speedAvailable ? speed : Double.NaN,
                        .15, 90, DeviceMotionEvidence.State.STILL));
                if (i >= 60 && i < 70) assertTrue("parked car", result != null && result.stationary);
                if (i > 78) assertTrue("moving car", result != null && !result.stationary);
            }
            assertNotNull(result);
            assertEquals(590, result.x, 2);
        }
    }

    @Test public void noisyWalkingWithPoorReceptionDoesNotRemainAtTheInitialPoint() {
        AdaptiveLocationFilterCore filter = new AdaptiveLocationFilterCore();
        Random random = new Random(141);
        AdaptiveLocationFilterCore.Estimate result = null;
        int departure = -1;
        for (int i = 0; i < 150; i++) {
            result = filter.onSample(measured(i * 1.4 + random.nextGaussian() * 3,
                    random.nextGaussian() * 3, i, 25, 1.4, .15, 90, DeviceMotionEvidence.State.MOVING));
            if (result != null && !result.stationary && departure < 0) departure = i;
        }
        assertTrue("departure " + departure, departure >= 0 && departure <= 15);
        assertNotNull(result);
        assertEquals(149 * 1.4, result.x, 10);
    }

    @Test public void poorReceptionWithoutSpeedOrSensorsStillAllowsPersistentSlowDeparture() {
        for (DeviceMotionEvidence.State motion : new DeviceMotionEvidence.State[]{
                DeviceMotionEvidence.State.UNKNOWN, DeviceMotionEvidence.State.MOVING}) {
            AdaptiveLocationFilterCore filter = new AdaptiveLocationFilterCore();
            int departure = -1;
            AdaptiveLocationFilterCore.Estimate result = null;
            for (int i = 0; i < 180; i++) {
                double x = i < 20 ? 0 : (i - 20) * .5;
                result = filter.onSample(measured(x, 0, i, 25, Double.NaN, Double.NaN, Double.NaN, motion));
                if (i >= 20 && result != null && !result.stationary && departure < 0) departure = i;
            }
            assertTrue("permanently pinned, motion=" + motion + " departure=" + departure,
                    departure >= 20 && departure <= 100);
            assertNotNull(result);
            assertEquals(79.5, result.x, 2);
        }
    }

    @Test public void sustainedVerySlowVehicleCanOverrideQuietSensorsWithoutAcceleration() {
        AdaptiveLocationFilterCore filter = new AdaptiveLocationFilterCore();
        AdaptiveLocationFilterCore.Estimate result = null;
        for (int i = 0; i < 100; i++) result = filter.onSample(measured(i * 1.2, 0, i,
                5, 1.2, .1, 90, DeviceMotionEvidence.State.STILL));
        assertNotNull(result);
        assertFalse(result.stationary);
        assertEquals(118.8, result.x, 2);
    }

}

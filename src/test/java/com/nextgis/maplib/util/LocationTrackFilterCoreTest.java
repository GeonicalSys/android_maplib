package com.nextgis.maplib.util;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LocationTrackFilterCoreTest {
    private static final float ACCURACY_M = 3f;

    @Test
    public void recordsConstantMovementThrough160Kmh() {
        double[] speedsKmh = {0d, 5d, 30d, 90d, 160d};
        for (double speedKmh : speedsKmh) {
            Harness harness = new Harness();
            List<Fix> output = new ArrayList<>();
            double speedMps = speedKmh / 3.6d;
            for (int i = 0; i < 20; i++) {
                output.addAll(harness.add(fix(
                        speedMps * 2d * i,
                        0d,
                        1_000L + 2_000L * i,
                        ACCURACY_M,
                        (float) speedMps)));
            }
            output.addAll(harness.filter.flushRemaining());

            assertEquals("speedKmh=" + speedKmh, 20, output.size());
            assertEquals("speedKmh=" + speedKmh, 0L,
                    harness.filter.getDroppedInputFixCount());
            assertEquals("speedKmh=" + speedKmh, 0L,
                    harness.filter.getChordDroppedFixCount());
        }
    }

    @Test
    public void records160KmhWhenProviderDoesNotReportSpeed() {
        Harness harness = new Harness();
        List<Fix> output = new ArrayList<>();
        double speedMps = 160d / 3.6d;
        for (int i = 0; i < 12; i++) {
            Fix fix = fix(
                    speedMps * 5d * i,
                    0d,
                    1_000L + 5_000L * i,
                    ACCURACY_M,
                    0f);
            output.addAll(harness.add(new Fix(
                    fix.x,
                    fix.y,
                    fix.timeMs,
                    fix.elapsedNanos,
                    fix.accuracy,
                    fix.speed,
                    true,
                    false)));
        }
        output.addAll(harness.filter.flushRemaining());

        assertEquals(12, output.size());
        assertEquals(0L, harness.filter.getDroppedInputFixCount());
    }

    @Test
    public void recordsRealisticAccelerationTo160Kmh() {
        Harness harness = new Harness();
        List<Fix> output = new ArrayList<>();
        double accelerationMps2 = (160d / 3.6d) / 10d;
        double position = 0d;
        double previousSpeed = 0d;
        for (int i = 0; i <= 5; i++) {
            double timeSec = i * 2d;
            double speed = accelerationMps2 * timeSec;
            if (i > 0) {
                position += (previousSpeed + speed) * 0.5d * 2d;
            }
            output.addAll(harness.add(fix(
                    position,
                    0d,
                    1_000L + 2_000L * i,
                    ACCURACY_M,
                    (float) speed)));
            previousSpeed = speed;
        }
        output.addAll(harness.filter.flushRemaining());

        assertEquals(6, output.size());
        assertEquals(0L, harness.filter.getDroppedInputFixCount());
    }

    @Test
    public void rejectsSustainedPhysicallyUnsupportedMovement() {
        Harness harness = new Harness();
        List<Fix> output = new ArrayList<>();
        double speedMps = 300d / 3.6d;
        for (int i = 0; i < 20; i++) {
            output.addAll(harness.add(fix(
                    speedMps * 2d * i,
                    0d,
                    1_000L + 2_000L * i,
                    ACCURACY_M,
                    (float) speedMps)));
        }
        output.addAll(harness.filter.flushRemaining());

        assertTrue(harness.filter.getDroppedInputFixCount() > 0L);
        assertTrue(output.size() < 5);
        assertTrue(harness.diagnostics.stream()
                .anyMatch(reason -> reason.startsWith("drop:speed_dist:")));
    }

    @Test
    public void removesIsolatedOutAndBackSpike() {
        Harness harness = new Harness();
        List<Fix> output = new ArrayList<>();
        output.addAll(harness.add(fix(0d, 0d, 1_000L, 5f, 0f)));
        output.addAll(harness.add(fix(100d, 0d, 3_000L, 5f, 0f)));
        output.addAll(harness.add(fix(0d, 0d, 5_000L, 5f, 0f)));
        output.addAll(harness.add(fix(0d, 0d, 7_000L, 5f, 0f)));
        output.addAll(harness.filter.flushRemaining());

        assertEquals(3, output.size());
        assertFalse(output.stream().anyMatch(item -> item.x == 100d));
        assertEquals(1L, harness.filter.getChordDroppedFixCount());
    }

    @Test
    public void preservesSmallLegitimateReversal() {
        Harness harness = new Harness();
        List<Fix> output = new ArrayList<>();
        output.addAll(harness.add(fix(0d, 0d, 1_000L, 5f, 5f)));
        output.addAll(harness.add(fix(20d, 0d, 3_000L, 5f, 5f)));
        output.addAll(harness.add(fix(0d, 0d, 5_000L, 5f, 5f)));
        output.addAll(harness.add(fix(-10d, 0d, 7_000L, 5f, 5f)));
        output.addAll(harness.filter.flushRemaining());

        assertEquals(4, output.size());
        assertTrue(output.stream().anyMatch(item -> item.x == 20d));
        assertEquals(0L, harness.filter.getChordDroppedFixCount());
    }

    @Test
    public void preservesEveryFixAcrossFortyFiveSecondSamplingGaps() {
        Harness harness = new Harness();
        List<Fix> output = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            output.addAll(harness.add(fix(
                    450d * i,
                    0d,
                    1_000L + 45_000L * i,
                    ACCURACY_M,
                    10f)));
        }
        output.addAll(harness.filter.flushRemaining());

        assertEquals(5, output.size());
        assertEquals(4L, harness.filter.getGapSegmentCount());
        assertEquals(0L, harness.filter.getDroppedInputFixCount());
    }

    @Test
    public void longGapStillValidatesImpossibleDistanceBeforeStartingSegment() {
        Harness harness = new Harness();
        List<Fix> output = new ArrayList<>();
        output.addAll(harness.add(fix(0d, 0d, 1_000L, ACCURACY_M, 0f)));
        output.addAll(harness.add(fix(5_000d, 0d, 46_000L, ACCURACY_M, 10f)));
        output.addAll(harness.filter.flushRemaining());

        assertEquals(1, output.size());
        assertEquals(1L, harness.filter.getDroppedInputFixCount());
        assertEquals(0L, harness.filter.getGapSegmentCount());
        assertTrue(harness.diagnostics.stream()
                .anyMatch(reason -> reason.startsWith("drop:speed_dist:")));
    }

    @Test
    public void oneRejectedJumpDoesNotPoisonFollowingNormalFixes() {
        Harness harness = new Harness();
        List<Fix> output = new ArrayList<>();
        output.addAll(harness.add(fix(0d, 0d, 1_000L, ACCURACY_M, 0f)));
        output.addAll(harness.add(fix(1_000d, 0d, 3_000L, ACCURACY_M, 0f)));
        output.addAll(harness.add(fix(2d, 0d, 5_000L, ACCURACY_M, 1f)));
        output.addAll(harness.add(fix(4d, 0d, 7_000L, ACCURACY_M, 1f)));
        output.addAll(harness.filter.flushRemaining());

        assertEquals(3, output.size());
        assertFalse(output.stream().anyMatch(item -> item.x == 1_000d));
        assertEquals(1L, harness.filter.getDroppedInputFixCount());
    }

    @Test
    public void rejectsBadAccuracyStaleFutureAndInvalidPosition() {
        Harness harness = new Harness();
        harness.add(fix(0d, 0d, 1_000L, 100f, 0f));

        Fix stale = fix(0d, 0d, 2_000L, ACCURACY_M, 0f);
        harness.clockNowNanos = 20_000_000_000L;
        harness.filter.onSample(stale);

        Fix future = fix(0d, 0d, 30_000L, ACCURACY_M, 0f);
        harness.clockNowNanos = 20_000_000_000L;
        harness.filter.onSample(future);

        Fix invalid = fix(Double.NaN, 0d, 21_000L, ACCURACY_M, 0f);
        harness.clockNowNanos = invalid.elapsedNanos;
        harness.filter.onSample(invalid);

        assertEquals(4L, harness.filter.getDroppedInputFixCount());
        assertTrue(harness.diagnostics.stream()
                .anyMatch(reason -> reason.startsWith("drop:accuracy:")));
        assertTrue(harness.diagnostics.stream()
                .anyMatch(reason -> reason.startsWith("drop:age:")));
        assertTrue(harness.diagnostics.contains("drop:position"));
    }

    @Test
    public void rejectsCorruptReportedSpeed() {
        Harness harness = new Harness();
        harness.add(fix(0d, 0d, 1_000L, ACCURACY_M, 101f));

        assertEquals(1L, harness.filter.getDroppedInputFixCount());
        assertTrue(harness.diagnostics.stream()
                .anyMatch(reason -> reason.startsWith("drop:absurd_speed:")));
    }

    @Test
    public void rejectsImplausibleReportedAcceleration() {
        Harness harness = new Harness();
        harness.add(fix(0d, 0d, 1_000L, ACCURACY_M, 0f));
        harness.add(fix(10d, 0d, 3_000L, ACCURACY_M, 50f));

        assertEquals(1L, harness.filter.getDroppedInputFixCount());
        assertTrue(harness.diagnostics.stream()
                .anyMatch(reason -> reason.startsWith("drop:accel:")));
    }

    @Test
    public void providerHandoverDoesNotCreateFalseAccelerationDrop() {
        Harness harness = new Harness();
        List<Fix> output = new ArrayList<>();
        output.addAll(harness.add(new Fix(
                0d, 0d, 1_000L, 1_000_000_000L, ACCURACY_M, 0f,
                true, true, "network")));
        output.addAll(harness.add(new Fix(
                88d, 0d, 3_000L, 3_000_000_000L, ACCURACY_M, 44f,
                true, true, "gps")));
        output.addAll(harness.filter.flushRemaining());

        assertEquals(2, output.size());
        assertEquals(0L, harness.filter.getDroppedInputFixCount());
    }

    private static Fix fix(
            double x,
            double y,
            long timeMs,
            float accuracy,
            float speed) {
        return new Fix(x, y, timeMs, timeMs * 1_000_000L, accuracy, speed, true, true);
    }

    private static final class Harness {
        long clockNowNanos;
        long clockNowMillis;
        final ArrayList<String> diagnostics = new ArrayList<>();
        final LocationTrackFilterCore<Fix> filter = new LocationTrackFilterCore<>(
                new FixOps(),
                new LocationTrackFilterCore.Clock() {
                    @Override
                    public long elapsedRealtimeNanos() {
                        return clockNowNanos;
                    }

                    @Override
                    public long elapsedRealtimeMillis() {
                        return clockNowMillis;
                    }
                },
                diagnostics::add,
                LocationTrackFilter.DEFAULT_MAX_ACCURACY_M,
                LocationTrackFilter.DEFAULT_MAX_FIX_AGE_MS,
                LocationTrackFilter.DEFAULT_MIN_DT_MS,
                LocationTrackFilter.DEFAULT_MAX_DT_MS,
                LocationTrackFilter.DEFAULT_MAX_SPEED_MPS,
                LocationTrackFilter.DEFAULT_MAX_ACCEL_MPS2,
                LocationTrackFilter.DEFAULT_ACCURACY_MARGIN_K,
                LocationTrackFilter.DEFAULT_ABSURD_SPEED_MPS);

        List<Fix> add(Fix fix) {
            clockNowNanos = fix.elapsedNanos;
            clockNowMillis = fix.timeMs;
            return filter.onSample(fix);
        }
    }

    private static final class Fix {
        final double x;
        final double y;
        final long timeMs;
        final long elapsedNanos;
        final float accuracy;
        final float speed;
        final boolean hasAccuracy;
        final boolean hasSpeed;
        final String provider;

        Fix(
                double x,
                double y,
                long timeMs,
                long elapsedNanos,
                float accuracy,
                float speed,
                boolean hasAccuracy,
                boolean hasSpeed) {
            this(
                    x, y, timeMs, elapsedNanos, accuracy, speed,
                    hasAccuracy, hasSpeed, "gps");
        }

        Fix(
                double x,
                double y,
                long timeMs,
                long elapsedNanos,
                float accuracy,
                float speed,
                boolean hasAccuracy,
                boolean hasSpeed,
                String provider) {
            this.x = x;
            this.y = y;
            this.timeMs = timeMs;
            this.elapsedNanos = elapsedNanos;
            this.accuracy = accuracy;
            this.speed = speed;
            this.hasAccuracy = hasAccuracy;
            this.hasSpeed = hasSpeed;
            this.provider = provider;
        }
    }

    private static final class FixOps
            implements LocationTrackFilterCore.SampleOps<Fix> {
        @Override
        public Fix copy(Fix sample) {
            return new Fix(
                    sample.x,
                    sample.y,
                    sample.timeMs,
                    sample.elapsedNanos,
                    sample.accuracy,
                    sample.speed,
                    sample.hasAccuracy,
                    sample.hasSpeed,
                    sample.provider);
        }

        @Override
        public boolean hasValidPosition(Fix sample) {
            return Double.isFinite(sample.x) && Double.isFinite(sample.y);
        }

        @Override
        public boolean isMock(Fix sample) {
            return false;
        }

        @Override
        public boolean hasAccuracy(Fix sample) {
            return sample.hasAccuracy;
        }

        @Override
        public float getAccuracy(Fix sample) {
            return sample.accuracy;
        }

        @Override
        public boolean hasSpeed(Fix sample) {
            return sample.hasSpeed;
        }

        @Override
        public float getSpeed(Fix sample) {
            return sample.speed;
        }

        @Override
        public String getProvider(Fix sample) {
            return sample.provider;
        }

        @Override
        public long getElapsedRealtimeNanos(Fix sample) {
            return sample.elapsedNanos;
        }

        @Override
        public long getWallTimeMillis(Fix sample) {
            return sample.timeMs;
        }

        @Override
        public float distance(Fix from, Fix to) {
            return (float) Math.hypot(to.x - from.x, to.y - from.y);
        }
    }
}

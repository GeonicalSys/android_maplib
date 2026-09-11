package com.nextgis.maplib.util;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class LocationRecordingSamplerCoreTest {
    private static final class Point {
        final double x, y;
        final long time, stop;
        Point(double x, double y, long time, long stop) { this.x = x; this.y = y; this.time = time; this.stop = stop; }
    }
    private static final LocationRecordingSamplerCore.Ops<Point> OPS = new LocationRecordingSamplerCore.Ops<Point>() {
        public Point copy(Point p) { return new Point(p.x, p.y, p.time, p.stop); }
        public long timeMs(Point p) { return p.time; }
        public long stopId(Point p) { return p.stop; }
        public double distance(Point a, Point b) { return Math.hypot(a.x - b.x, a.y - b.y); }
        public double bearing(Point a, Point b) { return Math.toDegrees(Math.atan2(b.x - a.x, b.y - a.y)); }
    };

    @Test public void improvingStopReplacesOneVertexInsteadOfDrawingSpuriousDistance() {
        AdaptiveLocationFilterCore filter = new AdaptiveLocationFilterCore();
        LocationRecordingSamplerCore<Point> sampler = new LocationRecordingSamplerCore<>(OPS, 5000, 5);
        List<Point> stored = new ArrayList<>();
        int corrected = 0;
        for (int i = 0; i < 600; i++) {
            AdaptiveLocationFilterCore.Estimate estimate = filter.onSample(new AdaptiveLocationFilterCore.Sample(
                    i < 20 ? 35 : Math.sin(i) * .5, 0, 1000 + i * 1000L,
                    i < 20 ? 30 : 5, 0, .2, Double.NaN, DeviceMotionEvidence.State.STILL));
            assertNotNull(estimate);
            stored.addAll(sampler.onLocation(new Point(estimate.x, estimate.y, 1000 + i * 1000L, estimate.stopId)));
            Point correction = sampler.takeStationaryCorrection();
            if (correction != null) {
                Point original = stored.get(stored.size() - 1);
                stored.set(stored.size() - 1, new Point(correction.x, correction.y, original.time, original.stop));
                corrected++;
            }
        }
        stored.addAll(sampler.flush());
        assertEquals(1, stored.size());
        assertEquals(1, corrected);
        assertEquals(0, stored.get(0).x, 1);
        assertEquals(1000, stored.get(0).time);
    }

    @Test public void movingAfterARefinedStopRecordsTheRealRouteAndPreservesACorner() {
        LocationRecordingSamplerCore<Point> sampler = new LocationRecordingSamplerCore<>(OPS, 5000, 5);
        List<Point> stored = new ArrayList<>(sampler.onLocation(new Point(20, 0, 1000, 1)));
        assertTrue(sampler.onLocation(new Point(0, 0, 10_000, 1)).isEmpty());
        stored.set(0, sampler.takeStationaryCorrection());
        for (int i = 1; i <= 30; i++) {
            double x = Math.min(15, i), y = Math.max(0, i - 15);
            stored.addAll(sampler.onLocation(new Point(x, y, 10_000 + i * 1000L, 0)));
            assertNull(sampler.takeStationaryCorrection());
        }
        stored.addAll(sampler.flush());
        double length = 0;
        boolean corner = false;
        for (int i = 1; i < stored.size(); i++) {
            length += OPS.distance(stored.get(i - 1), stored.get(i));
            if (stored.get(i).x == 15 && stored.get(i).y == 0) corner = true;
        }
        assertTrue(corner);
        assertEquals(30, length, .01);
    }

    @Test public void freshSessionCannotCorrectThePreviousSessionsVertex() {
        LocationRecordingSamplerCore<Point> sampler = new LocationRecordingSamplerCore<>(OPS, 5000, 5);
        sampler.onLocation(new Point(0, 0, 1000, 1));
        sampler.onLocation(new Point(5, 0, 9000, 1));
        sampler.reset();
        assertNull(sampler.takeStationaryCorrection());
        List<Point> fresh = sampler.onLocation(new Point(8, 0, 10_000, 1));
        assertEquals(1, fresh.size());
        assertNull(sampler.takeStationaryCorrection());
    }

    @Test public void lateFixCannotUndoTheLatestStationaryCorrection() {
        LocationRecordingSamplerCore<Point> sampler = new LocationRecordingSamplerCore<>(OPS, 5000, 5);
        sampler.onLocation(new Point(20, 0, 1000, 1));
        sampler.onLocation(new Point(0, 0, 10_000, 1));
        assertEquals(0, sampler.takeStationaryCorrection().x, .01);
        assertTrue(sampler.onLocation(new Point(20, 0, 9000, 1)).isEmpty());
        assertNull(sampler.takeStationaryCorrection());
        assertTrue(sampler.flush().isEmpty());
    }
}

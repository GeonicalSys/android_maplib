package com.nextgis.maplib.util;

import java.util.ArrayList;
import java.util.List;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.nextgis.maplib.util.DeviceMotionEvidence.State.*;

/** Runs motion estimation and per-recorder persistence together, in anonymous local metres. */
public class LocationDepartureTest {
    private static final class Point {
        final double x, y, anchorX, anchorY;
        final long time, stop, departure;
        Point(double x, double y, double anchorX, double anchorY, long time, long stop, long departure) {
            this.x = x; this.y = y; this.anchorX = anchorX; this.anchorY = anchorY;
            this.time = time; this.stop = stop; this.departure = departure;
        }
    }
    private static final LocationRecordingSamplerCore.Ops<Point> OPS = new LocationRecordingSamplerCore.Ops<Point>() {
        public Point copy(Point p) { return p; } // Immutable test observations.
        public long timeMs(Point p) { return p.time; }
        public long stopId(Point p) { return p.stop; }
        public long departureSinceMs(Point p) { return p.departure; }
        public Point anchor(Point p) { return new Point(p.anchorX, p.anchorY, p.anchorX, p.anchorY, p.time, p.stop, p.departure); }
        public Point moving(Point p) { return new Point(p.x, p.y, p.x, p.y, p.time, 0, p.departure); }
        public double distance(Point a, Point b) { return Math.hypot(a.x - b.x, a.y - b.y); }
        public double bearing(Point a, Point b) { return Math.toDegrees(Math.atan2(b.x - a.x, b.y - a.y)); }
    };
    private static final class Recording {
        final AdaptiveLocationFilterCore filter = new AdaptiveLocationFilterCore();
        final LocationRecordingSamplerCore<Point> sampler = new LocationRecordingSamplerCore<>(OPS, 5000, 5);
        final List<Point> stored = new ArrayList<>();
        Point accept(int second, double x, double y, double accuracy, double speed, double error,
                     double bearing, DeviceMotionEvidence.State motion) {
            long time = 1000 + second * 1000L;
            AdaptiveLocationFilterCore.Estimate e = filter.onSample(new AdaptiveLocationFilterCore.Sample(
                    x, y, time, accuracy, speed, error, bearing, motion));
            assertNotNull("measurement at " + second, e);
            Point p = new Point(e.candidateX, e.candidateY, e.x, e.y, time, e.stopId, e.departureSinceMs);
            persist(p);
            return p;
        }
        void persist(Point p) {
            stored.addAll(sampler.onLocation(p));
            Point corrected = sampler.takeStationaryCorrection();
            if (corrected != null) {
                Point old = stored.get(stored.size() - 1);
                stored.set(stored.size() - 1, new Point(corrected.x, corrected.y,
                        corrected.x, corrected.y, old.time, corrected.stop, 0));
            }
        }
    }

    @Test public void pickingUpPhoneAndCoherentExcursionsInsideTwoAccuracyRadiiDoNotDraw() {
        Recording r = new Recording();
        for (int i = 0; i < 900; i++) {
            // Repeated, directionally convincing 18 m excursions with false walking speed.
            double phase = i < 20 ? 0 : (i - 20) % 60;
            double x = phase < 30 ? phase * .6 : (60 - phase) * .6;
            Point p = r.accept(i, x, 0, 10, i < 20 ? 0 : .6, .08,
                    phase < 30 ? 90 : 270, i < 20 ? STILL : MOVING);
            assertTrue("handheld drift released at " + i, p.stop != 0);
            assertEquals(1, r.stored.size());
        }
        assertTrue(r.sampler.flush().isEmpty());
    }

    @Test public void fieldTrackSymptomStaysOneStopEvenWhenTheSensorReportsHandling() {
        // Reconstructed symptom from the already-filtered exported GPX, NOT a raw GNSS replay.
        // Absolute coordinates and user identifiers are deliberately absent.
        double[][] knots = {{0,0,0,8.5},{316,6.99,-.66,8.5},{329,10.2,-4.48,7},
                {332,8.3,-5.72,7.5},{344,7.4,-3.41,6.5},{431,5.9,.78,7},
                {438,7.7,1.86,7},{444,11.64,4.98,9},{445,11.92,6.16,9.5},
                {540,30.39,4.55,24},{541,25.58,4.48,24},{551,37.41,1.63,34},{552,32.99,3.35,35}};
        Recording r = new Recording();
        int index = 0;
        for (int second = 0; second <= 552; second++) {
            while (index + 2 < knots.length && second > knots[index + 1][0]) index++;
            double[] a = knots[index], b = knots[index + 1];
            double t = (second - a[0]) / (b[0] - a[0]);
            r.accept(second, a[1] + t * (b[1] - a[1]), a[2] + t * (b[2] - a[2]),
                    a[3] + t * (b[3] - a[3]), .9, .4, 90, second < 300 ? STILL : MOVING);
        }
        r.stored.addAll(r.sampler.flush());
        assertEquals("stationary symptom must not become 70 m of travel", 1, r.stored.size());
    }

    @Test public void confirmedDepartureBackfillsItsStartAndEarlyCornerWithOriginalTimes() {
        Recording r = new Recording();
        for (int i = 0; i < 85; i++) {
            double travel = Math.max(0, i - 20), x = Math.min(8, travel), y = Math.max(0, travel - 8);
            Point p = r.accept(i, x, y, 10, travel == 0 ? 0 : 1, .1, travel <= 8 ? 90 : 0,
                    travel == 0 ? STILL : MOVING);
            if (Math.hypot(x, y) <= 20) {
                assertTrue(p.stop != 0);
                assertEquals("nothing drawn before confirmation", 1, r.stored.size());
            }
        }
        r.stored.addAll(r.sampler.flush());
        assertTrue(r.stored.stream().anyMatch(p -> p.x == 8 && p.y == 0 && p.time < 35_000));
        assertTrue(r.stored.stream().anyMatch(p -> p.x > 0 && p.x < 8 && p.time < 30_000));
        double path = 0;
        for (int i = 1; i < r.stored.size(); i++) {
            assertTrue(r.stored.get(i).time > r.stored.get(i - 1).time);
            path += OPS.distance(r.stored.get(i - 1), r.stored.get(i));
        }
        assertEquals(64, path, 2);
    }

    @Test public void worsenedAccuracyAndAnIsolatedFarFixCannotConfirmDeparture() {
        Recording r = new Recording();
        for (int i = 0; i < 100; i++) {
            double x = i < 20 ? 0 : Math.min(50, i - 20);
            if (i == 50) x = 110;
            r.accept(i, x, 0, i < 20 ? 10 : 30, 1, 5, 90, MOVING);
        }
        assertEquals(1, r.stored.size());
    }

    @Test public void stoppingOrLosingGpsDiscardsUnconfirmedBufferAndLateFixesCannotReviveIt() {
        Recording r = new Recording();
        for (int i = 0; i < 35; i++) r.accept(i, Math.max(0, i - 20), 0, 10, .7, .1, 90, MOVING);
        assertEquals(1, r.stored.size());
        assertTrue(r.sampler.flush().isEmpty());
        assertTrue(r.sampler.onLocation(new Point(40, 0, 40, 0, 20_000, 0, 1000)).isEmpty());
        r.sampler.reset();
        List<Point> next = r.sampler.onLocation(new Point(100, 0, 100, 0, 100_000, 0, 1000));
        assertEquals(1, next.size());
        assertEquals(100, next.get(0).x, 0);
        assertTrue(r.sampler.flush().isEmpty());
    }

    @Test public void recorderStartingDuringTentativeMotionDoesNotImportEarlierObservations() {
        Recording first = new Recording();
        Recording second = new Recording();
        for (int i = 0; i < 70; i++) {
            Point p = first.accept(i, Math.max(0, i - 20), 0, 10, 1, .1, 90, MOVING);
            if (i >= 30) second.persist(p);
        }
        second.stored.addAll(second.sampler.flush());
        assertTrue(second.stored.size() > 3);
        for (Point p : second.stored) assertTrue(p.time >= 31_000);
    }

    @Test public void recordedA54MultipathWithFalseSixMetrePerSecondSpeedStaysOneStop() throws Exception {
        for (DeviceMotionEvidence.State state : new DeviceMotionEvidence.State[]{MOVING, UNKNOWN, STILL}) {
            Recording r = new Recording();
            long lastTime = 0;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    getClass().getResourceAsStream("/location/a54-stationary-handheld.csv"), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("#")) continue;
                    String[] fields = line.split(",");
                    double[] v = new double[fields.length];
                    for (int i = 0; i < v.length; i++) v[i] = Double.parseDouble(fields[i]);
                    lastTime = (long) v[0];
                    AdaptiveLocationFilterCore.Estimate e = r.filter.onSample(new AdaptiveLocationFilterCore.Sample(
                            v[1], v[2], lastTime, v[3], v[4], v[5], v[6], state));
                    assertNotNull(e);
                    assertTrue("false departure at " + lastTime + " state=" + state, e.stationary);
                    r.persist(new Point(e.candidateX, e.candidateY, e.x, e.y, lastTime, e.stopId, e.departureSinceMs));
                }
            }
            assertEquals(1, r.stored.size());
            assertTrue(r.sampler.flush().isEmpty());
            // The stronger gate must also release the same state when genuine walking
            // follows the captured multipath, rather than permanently pinning its anchor.
            Point anchor = r.stored.get(0);
            AdaptiveLocationFilterCore.Estimate e = null;
            for (int i = 1; i <= 100; i++) e = r.filter.onSample(new AdaptiveLocationFilterCore.Sample(
                    anchor.x + i * 1.4, anchor.y, lastTime + i * 1000L,
                    8, 1.4, .1, 90, MOVING));
            assertNotNull(e);
            assertFalse(e.stationary);
            assertEquals(anchor.x + 140, e.x, 2);
        }
    }
}

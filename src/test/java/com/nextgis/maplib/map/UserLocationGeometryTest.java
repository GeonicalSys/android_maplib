package com.nextgis.maplib.map;

import org.junit.Test;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.Point;
import org.maplibre.geojson.Polygon;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UserLocationGeometryTest {
    private static final Point CENTER = Point.fromLngLat(37.62, 55.75);
    private static final double METERS = 50;

    @Test
    public void accuracyRingIsClosedAndHasExpectedSteps() {
        List<Point> ring = UserLocationGeometry.accuracyRing(CENTER, METERS);
        assertEquals(UserLocationGeometry.CIRCLE_STEPS + 1, ring.size());
        assertEquals(ring.get(0).longitude(), ring.get(ring.size() - 1).longitude(), 1e-12);
        assertEquals(ring.get(0).latitude(), ring.get(ring.size() - 1).latitude(), 1e-12);
    }

    @Test
    public void coneRadiusIsFixedAndIndependentOfAccuracy() {
        assertEquals(UserLocationGeometry.CONE_METERS, UserLocationGeometry.coneRadiusMeters(0.1f), 0f);
        assertEquals(UserLocationGeometry.CONE_METERS, UserLocationGeometry.coneRadiusMeters(0f), 0f);
        assertEquals(UserLocationGeometry.CONE_METERS, UserLocationGeometry.coneRadiusMeters(50f), 0f);
        assertEquals(UserLocationGeometry.CONE_METERS, UserLocationGeometry.coneRadiusMeters(400f), 0f);
        assertEquals(UserLocationGeometry.CONE_METERS, UserLocationGeometry.coneRadiusMeters(Float.NaN), 0f);
        assertEquals(8f, UserLocationGeometry.CONE_METERS, 0f);
    }

    @Test
    public void headingSectorStartsAndEndsAtTheFix() {
        List<Point> sector = UserLocationGeometry.headingSector(CENTER, METERS, 0, 30);
        assertEquals(CENTER.longitude(), sector.get(0).longitude(), 1e-12);
        assertEquals(CENTER.latitude(), sector.get(0).latitude(), 1e-12);
        assertEquals(CENTER.longitude(), sector.get(sector.size() - 1).longitude(), 1e-12);
        assertEquals(CENTER.latitude(), sector.get(sector.size() - 1).latitude(), 1e-12);
        assertEquals(UserLocationGeometry.SECTOR_ARC_STEPS + 3, sector.size());
    }

    @Test
    public void northHeadingPlacesTheArcMidpointNorthOfTheFix() {
        List<Point> sector = UserLocationGeometry.headingSector(CENTER, METERS, 0, 20);
        Point mid = sector.get(1 + UserLocationGeometry.SECTOR_ARC_STEPS / 2);
        assertTrue(mid.latitude() > CENTER.latitude());
        assertEquals(CENTER.longitude(), mid.longitude(), 1e-4);
    }

    @Test
    public void headingWrapsAroundZero() {
        List<Point> sector = UserLocationGeometry.headingSector(CENTER, METERS, 0, 20);
        Point firstArc = sector.get(1);
        Point lastArc = sector.get(sector.size() - 2);
        assertTrue(firstArc.longitude() < CENTER.longitude());
        assertTrue(lastArc.longitude() > CENTER.longitude());
        assertTrue(firstArc.latitude() > CENTER.latitude());
        assertTrue(lastArc.latitude() > CENTER.latitude());
    }

    @Test
    public void headingNear360StillWrapsTheArcAcrossNorth() {
        List<Point> sector = UserLocationGeometry.headingSector(CENTER, METERS, 350, 20);
        Point firstArc = sector.get(1);
        Point lastArc = sector.get(sector.size() - 2);
        assertTrue(firstArc.longitude() < CENTER.longitude());
        assertTrue(lastArc.longitude() > CENTER.longitude());
    }

    @Test
    public void zeroOrInvalidHalfAngleOmitsTheSector() {
        assertTrue(UserLocationGeometry.headingSector(CENTER, METERS, 90, 0).isEmpty());
        assertTrue(UserLocationGeometry.headingSector(CENTER, METERS, 90, -1).isEmpty());
        assertTrue(UserLocationGeometry.headingSector(CENTER, METERS, Double.NaN, 20).isEmpty());
        assertTrue(UserLocationGeometry.headingSector(CENTER, METERS, 90, Double.NaN).isEmpty());
        assertFalse(UserLocationGeometry.hasHeadingSector(90f, 0f));
        assertFalse(UserLocationGeometry.hasHeadingSector(Float.NaN, 20f));
    }

    @Test
    public void hugeHalfAngleIsClampedToASemicircle() {
        List<Point> huge = UserLocationGeometry.headingSector(CENTER, METERS, 0, 400);
        List<Point> half = UserLocationGeometry.headingSector(CENTER, METERS, 0, 90);
        assertEquals(half.size(), huge.size());
        Point hugeMid = huge.get(1 + UserLocationGeometry.SECTOR_ARC_STEPS / 2);
        Point halfMid = half.get(1 + UserLocationGeometry.SECTOR_ARC_STEPS / 2);
        assertEquals(halfMid.longitude(), hugeMid.longitude(), 1e-9);
        assertEquals(halfMid.latitude(), hugeMid.latitude(), 1e-9);
        assertEquals(90f, UserLocationGeometry.clampHalfAngleDegrees(400f), 0f);
        assertEquals(5f, UserLocationGeometry.clampHalfAngleDegrees(1f), 0f);
    }

    @Test
    public void overlayFeaturesTagPolygonRolesAndKeepThePuck() {
        List<Feature> features = UserLocationGeometry.overlayFeatures(
                CENTER, true, 45f, 12f, 0f, 25f);
        assertEquals(3, features.size());
        assertEquals(UserLocationGeometry.ROLE_ACCURACY, features.get(0).getStringProperty(
                UserLocationGeometry.ROLE_PROPERTY));
        assertEquals(UserLocationGeometry.ROLE_HEADING, features.get(1).getStringProperty(
                UserLocationGeometry.ROLE_PROPERTY));
        assertTrue(features.get(0).geometry() instanceof Polygon);
        assertTrue(features.get(1).geometry() instanceof Polygon);
        assertEquals("stand", features.get(2).getStringProperty("type"));
        assertEquals(0, features.get(2).getNumberProperty("bearing").floatValue(), 0f);
    }

    @Test
    public void overlayOmitsTheConeWhenHeadingIsMissing() {
        List<Feature> features = UserLocationGeometry.overlayFeatures(
                CENTER, false, 80f, 40f, Float.NaN, Float.NaN);
        assertEquals(2, features.size());
        assertEquals(UserLocationGeometry.ROLE_ACCURACY, features.get(0).getStringProperty(
                UserLocationGeometry.ROLE_PROPERTY));
        assertEquals("go", features.get(1).getStringProperty("type"));
        assertEquals(80f, features.get(1).getNumberProperty("bearing").floatValue(), 0f);
        assertTrue(features.get(1).getBooleanProperty(UserLocationGeometry.HAS_DIRECTION_PROPERTY));
    }

    @Test
    public void compassOverridesTheMovementCourseAndRotatesTheStandingMarker() {
        for (boolean standing : new boolean[]{true, false}) {
            List<Feature> features = UserLocationGeometry.overlayFeatures(
                    CENTER, standing, 140f, 12f, 350f, 25f, 36f);
            Feature marker = features.get(features.size() - 1);
            assertTrue(marker.getBooleanProperty(UserLocationGeometry.HAS_DIRECTION_PROPERTY));
            assertEquals(350f, marker.getNumberProperty("bearing").floatValue(), 0f);
            Polygon cone = (Polygon) features.get(1).geometry();
            Point midpoint = cone.coordinates().get(0).get(1 + UserLocationGeometry.SECTOR_ARC_STEPS / 2);
            assertTrue(midpoint.longitude() < CENTER.longitude());
            assertTrue(midpoint.latitude() > CENTER.latitude());
        }
    }

    @Test
    public void noCompassAndNoMovementShowsOnlyTheCircleAndPlainDot() {
        List<Feature> features = UserLocationGeometry.overlayFeatures(
                CENTER, true, 140f, 12f, Float.NaN, Float.NaN);
        assertEquals(2, features.size());
        Feature marker = features.get(1);
        assertFalse(marker.getBooleanProperty(UserLocationGeometry.HAS_DIRECTION_PROPERTY));
        assertEquals(0f, marker.getNumberProperty("bearing").floatValue(), 0f);
    }

    @Test
    public void invalidCompassFallsBackToNorthwardMovementWithoutInventingACone() {
        List<Feature> features = UserLocationGeometry.overlayFeatures(
                CENTER, false, 0f, 12f, 80f, Float.NaN);
        assertEquals(2, features.size());
        assertTrue(features.get(1).getBooleanProperty(UserLocationGeometry.HAS_DIRECTION_PROPERTY));
        assertEquals(0f, features.get(1).getNumberProperty("bearing").floatValue(), 0f);
    }

    @Test
    public void invalidMovementBearingsCannotProduceAnOrbitalPointer() {
        for (float bearing : new float[]{Float.NaN, Float.POSITIVE_INFINITY, -1f, 360f}) {
            List<Feature> features = UserLocationGeometry.overlayFeatures(
                    CENTER, false, bearing, 0f, Float.NaN, Float.NaN);
            assertEquals(1, features.size());
            assertFalse(features.get(0).getBooleanProperty(UserLocationGeometry.HAS_DIRECTION_PROPERTY));
            assertEquals(0f, features.get(0).getNumberProperty("bearing").floatValue(), 0f);
        }
        assertEquals(350f, UserLocationGeometry.markerBearing(true, 0f, -10f, 20f), 0f);
    }

    @Test
    public void compassSectorKeepsItsScreenLengthWhileGpsAccuracyRemainsMetric() {
        for (double metersPerPixel : new double[]{0.05, 1.2, 12d}) {
            float radius = UserLocationGeometry.coneRadiusForScale(metersPerPixel);
            assertEquals(56d, radius / metersPerPixel, 1e-5);
            List<Feature> features = UserLocationGeometry.overlayFeatures(
                    CENTER, true, 0f, 12f, 0f, 30f, radius);
            Polygon cone = (Polygon) features.get(1).geometry();
            Point midpoint = cone.coordinates().get(0).get(1 + UserLocationGeometry.SECTOR_ARC_STEPS / 2);
            double meters = Math.toRadians(midpoint.latitude() - CENTER.latitude())
                    * UserLocationGeometry.EARTH_RADIUS_METERS;
            assertEquals(radius, meters, 0.001);
            Polygon circle = (Polygon) features.get(0).geometry();
            double gpsMeters = Math.toRadians(circle.coordinates().get(0).get(0).latitude() - CENTER.latitude())
                    * UserLocationGeometry.EARTH_RADIUS_METERS;
            assertEquals(12d, gpsMeters, 0.001);
        }
        assertEquals(8f, UserLocationGeometry.coneRadiusForScale(Double.NaN), 0f);
        assertEquals(8f, UserLocationGeometry.coneRadiusForScale(0d), 0f);
    }

    @Test
    public void ringWindowKeepsUnderlyingFeaturesClearWhilePreservingOuterAccuracyAndHeading() {
        for (double metersPerPixel : new double[]{0.05, 1.2, 12d}) {
            float accuracyMeters = (float) (100d * metersPerPixel);
            List<Feature> features = UserLocationGeometry.overlayFeaturesForScale(
                    CENTER, true, 140f, accuracyMeters, 0f, 30f, metersPerPixel);
            Polygon accuracy = (Polygon) features.get(0).geometry();
            assertEquals(2, accuracy.coordinates().size());
            assertEquals(accuracyMeters, northMeters(accuracy.coordinates().get(0).get(0)), 0.001);
            List<Point> hole = accuracy.coordinates().get(1);
            assertEquals(UserLocationGeometry.CIRCLE_STEPS + 1, hole.size());
            assertEquals(10.5, northMeters(hole.get(0)) / metersPerPixel, 0.001);
            assertTrue("The hole winds opposite to the outer accuracy ring",
                    hole.get(1).longitude() < CENTER.longitude());
            Polygon heading = (Polygon) features.get(1).geometry();
            List<Point> sector = heading.coordinates().get(0);
            assertEquals(2 * (UserLocationGeometry.SECTOR_ARC_STEPS + 1) + 1, sector.size());
            assertEquals(56, northMeters(sector.get(UserLocationGeometry.SECTOR_ARC_STEPS / 2))
                    / metersPerPixel, 0.001);
            assertEquals(13, northMeters(sector.get(UserLocationGeometry.SECTOR_ARC_STEPS + 1
                    + UserLocationGeometry.SECTOR_ARC_STEPS / 2)) / metersPerPixel, 0.001);
            assertEquals(sector.get(0), sector.get(sector.size() - 1));
            Feature marker = features.get(2);
            assertTrue(marker.getBooleanProperty(UserLocationGeometry.HAS_DIRECTION_PROPERTY));
            assertEquals(0, marker.getNumberProperty("bearing").floatValue(), 0f);
        }
    }

    @Test
    public void anAccuracyCircleCoveredByTheWindowDoesNotProduceAnInvalidHole() {
        List<Feature> features = UserLocationGeometry.overlayFeaturesForScale(
                CENTER, false, 80f, 5f, Float.NaN, Float.NaN, 1d);
        assertEquals(1, features.size());
        assertEquals(80f, features.get(0).getNumberProperty("bearing").floatValue(), 0f);
        assertTrue(features.get(0).getBooleanProperty(UserLocationGeometry.HAS_DIRECTION_PROPERTY));
    }

    private static double northMeters(Point point) {
        return Math.toRadians(point.latitude() - CENTER.latitude()) * UserLocationGeometry.EARTH_RADIUS_METERS;
    }
}

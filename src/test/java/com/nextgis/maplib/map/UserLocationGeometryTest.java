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
    }
}

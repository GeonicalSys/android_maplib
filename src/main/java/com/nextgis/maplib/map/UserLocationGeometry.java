package com.nextgis.maplib.map;

import org.maplibre.geojson.Feature;
import org.maplibre.geojson.Point;
import org.maplibre.geojson.Polygon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Geodesic accuracy circle and heading sector for the MapLibre user-location overlay.
 * Angles are clockwise from true north, in degrees.
 */
public final class UserLocationGeometry {
    public static final String ROLE_PROPERTY = "role";
    public static final String ROLE_ACCURACY = "accuracy";
    public static final String ROLE_HEADING = "heading";

    public static final double EARTH_RADIUS_METERS = 6371008.8;
    public static final int CIRCLE_STEPS = 64;
    public static final int SECTOR_ARC_STEPS = 32;
    public static final float MIN_CONE_METERS = 32f;
    public static final float MAX_CONE_METERS = 80f;
    public static final float MIN_HALF_ANGLE_DEGREES = 8f;
    public static final float MAX_HALF_ANGLE_DEGREES = 90f;

    private UserLocationGeometry() { }

    public static float coneRadiusMeters(float accuracyMeters) {
        float accuracy = Float.isFinite(accuracyMeters) ? Math.max(accuracyMeters, 0f) : 0f;
        return Math.min(MAX_CONE_METERS, Math.max(MIN_CONE_METERS, accuracy));
    }

    public static float clampHalfAngleDegrees(float halfAngleDegrees) {
        return Math.min(MAX_HALF_ANGLE_DEGREES, Math.max(MIN_HALF_ANGLE_DEGREES, halfAngleDegrees));
    }

    public static boolean hasHeadingSector(float headingTrueDegrees, float headingHalfAngleDegrees) {
        return Float.isFinite(headingTrueDegrees)
                && Float.isFinite(headingHalfAngleDegrees)
                && headingHalfAngleDegrees > 0f;
    }

    public static List<Feature> overlayFeatures(
            Point point,
            boolean isStanding,
            float bearing,
            float accuracyMeters,
            float headingTrueDegrees,
            float headingHalfAngleDegrees) {
        List<Feature> features = new ArrayList<>();
        if (Float.isFinite(accuracyMeters) && accuracyMeters > 0f) {
            features.add(polygon(accuracyRing(point, accuracyMeters), ROLE_ACCURACY));
        }
        if (hasHeadingSector(headingTrueDegrees, headingHalfAngleDegrees)) {
            List<Point> sector = headingSector(
                    point,
                    coneRadiusMeters(accuracyMeters),
                    headingTrueDegrees,
                    clampHalfAngleDegrees(headingHalfAngleDegrees));
            if (sector.size() >= 4) {
                features.add(polygon(sector, ROLE_HEADING));
            }
        }
        Feature marker = Feature.fromGeometry(point);
        marker.addStringProperty("type", isStanding ? "stand" : "go");
        marker.addNumberProperty("bearing", isStanding ? 0f : bearing);
        features.add(marker);
        return features;
    }

    public static List<Point> accuracyRing(Point center, double meters) {
        List<Point> ring = new ArrayList<>(CIRCLE_STEPS + 1);
        double radius = Math.min(meters / EARTH_RADIUS_METERS, Math.PI / 2);
        for (int i = 0; i <= CIRCLE_STEPS; i++) {
            double angle = 2 * Math.PI * i / CIRCLE_STEPS;
            ring.add(destination(center, radius, angle));
        }
        ring.set(CIRCLE_STEPS, ring.get(0));
        return ring;
    }

    public static List<Point> headingSector(
            Point center,
            double meters,
            double headingTrueDegrees,
            double halfAngleDegrees) {
        if (!(halfAngleDegrees > 0) || !Double.isFinite(headingTrueDegrees)
                || !Double.isFinite(halfAngleDegrees) || !Double.isFinite(meters) || meters <= 0) {
            return Collections.emptyList();
        }
        double half = Math.min(MAX_HALF_ANGLE_DEGREES, Math.max(MIN_HALF_ANGLE_DEGREES, halfAngleDegrees));
        double radius = Math.min(meters / EARTH_RADIUS_METERS, Math.PI / 2);
        List<Point> ring = new ArrayList<>(SECTOR_ARC_STEPS + 3);
        ring.add(center);
        for (int i = 0; i <= SECTOR_ARC_STEPS; i++) {
            double azimuth = headingTrueDegrees - half
                    + (2 * half) * i / (double) SECTOR_ARC_STEPS;
            ring.add(destination(center, radius, Math.toRadians(azimuth)));
        }
        ring.add(center);
        return ring;
    }

    static Point destination(Point origin, double angularDistanceRadians, double bearingRadians) {
        double lat = Math.toRadians(origin.latitude());
        double phi = Math.asin(Math.sin(lat) * Math.cos(angularDistanceRadians)
                + Math.cos(lat) * Math.sin(angularDistanceRadians) * Math.cos(bearingRadians));
        double delta = Math.atan2(
                Math.sin(bearingRadians) * Math.sin(angularDistanceRadians) * Math.cos(lat),
                Math.cos(angularDistanceRadians) - Math.sin(lat) * Math.sin(phi));
        return Point.fromLngLat(origin.longitude() + Math.toDegrees(delta), Math.toDegrees(phi));
    }

    private static Feature polygon(List<Point> ring, String role) {
        Feature feature = Feature.fromGeometry(Polygon.fromLngLats(Collections.singletonList(ring)));
        feature.addStringProperty(ROLE_PROPERTY, role);
        return feature;
    }
}

/*
 * Project:  NextGIS Mobile
 * Purpose:  Mobile GIS for Android.
 * Copyright (c) 2026 GeonicalSystem
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.nextgis.maplib.util;

import com.nextgis.maplib.datasource.GeoGeometry;
import com.nextgis.maplib.datasource.GeoPoint;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.operation.distance.IndexedFacetDistance;

import static com.nextgis.maplib.util.GeoConstants.CRS_WEB_MERCATOR;
import static com.nextgis.maplib.util.GeoConstants.CRS_WGS84;
import static com.nextgis.maplib.util.GeoConstants.GTLineString;
import static com.nextgis.maplib.util.GeoConstants.GTMultiLineString;
import static com.nextgis.maplib.util.GeoConstants.GTMultiPoint;
import static com.nextgis.maplib.util.GeoConstants.GTMultiPolygon;
import static com.nextgis.maplib.util.GeoConstants.GTPoint;
import static com.nextgis.maplib.util.GeoConstants.GTPolygon;

/**
 * Immutable, indexed stakeout target. Point targets use the point itself; line targets use the
 * nearest point on a segment; polygon targets use the nearest point on any boundary, including
 * interior rings. Distances and bearings are returned in WGS84 without mutating the source
 * geometry.
 */
public final class StakeoutGeometryTarget {
    private static final double WGS84_A = 6378137.0;
    private static final double WGS84_F = 1.0 / 298.257223563;
    private static final double WGS84_B = (1.0 - WGS84_F) * WGS84_A;

    private final GeometryFactory geometryFactory;
    private final IndexedFacetDistance indexedDistance;

    public StakeoutGeometryTarget(GeoGeometry source) {
        if (source == null) {
            throw new IllegalArgumentException("Stakeout geometry is missing");
        }
        if (!isSupportedType(source.getType())) {
            throw new IllegalArgumentException("Stakeout geometry type is not supported");
        }
        if (source.getCRS() != CRS_WEB_MERCATOR && source.getCRS() != CRS_WGS84) {
            throw new IllegalArgumentException("Stakeout geometry CRS is not supported");
        }

        GeoGeometry projected = source.copy();
        // Several legacy collection copy constructors preserve coordinate values but not the
        // collection-level CRS marker. Restore it on our private copy before projecting.
        projected.setCRS(source.getCRS());
        if (projected.getCRS() == CRS_WGS84 && !projected.project(CRS_WEB_MERCATOR)) {
            throw new IllegalArgumentException("Stakeout geometry cannot be projected");
        }

        try {
            Geometry target = new WKTReader().read(projected.toWKT(true));
            if (target.isEmpty()) {
                throw new IllegalArgumentException("Stakeout geometry is empty");
            }
            geometryFactory = target.getFactory();
            indexedDistance = new IndexedFacetDistance(target);
        } catch (ParseException | RuntimeException exception) {
            throw new IllegalArgumentException("Stakeout geometry cannot be indexed", exception);
        }
    }

    public Result calculate(double longitude, double latitude) {
        if (!Double.isFinite(longitude) || !Double.isFinite(latitude)
                || longitude < -180.0 || longitude > 180.0
                || latitude < -90.0 || latitude > 90.0) {
            throw new IllegalArgumentException("Stakeout location is invalid");
        }

        GeoPoint currentMercator = new GeoPoint(longitude, latitude);
        currentMercator.setCRS(CRS_WGS84);
        if (!currentMercator.project(CRS_WEB_MERCATOR)) {
            throw new IllegalArgumentException("Stakeout location cannot be projected");
        }

        Geometry current = geometryFactory.createPoint(
                new Coordinate(currentMercator.getX(), currentMercator.getY()));
        Coordinate[] nearest = indexedDistance.nearestPoints(current);
        if (nearest == null || nearest.length < 2 || nearest[0] == null) {
            throw new IllegalStateException("Nearest stakeout point is unavailable");
        }

        GeoPoint nearestWgs84 = new GeoPoint(nearest[0].x, nearest[0].y);
        nearestWgs84.setCRS(CRS_WEB_MERCATOR);
        if (!nearestWgs84.project(CRS_WGS84)) {
            throw new IllegalStateException("Nearest stakeout point cannot be projected");
        }

        GeodesicResult geodesic = inverseWgs84(
                latitude, longitude, nearestWgs84.getY(), nearestWgs84.getX());
        return new Result(
                geodesic.distanceMeters,
                geodesic.initialBearingDegrees,
                nearestWgs84.getX(),
                nearestWgs84.getY());
    }

    public static boolean isSupportedType(int geometryType) {
        return geometryType == GTPoint
                || geometryType == GTMultiPoint
                || geometryType == GTLineString
                || geometryType == GTMultiLineString
                || geometryType == GTPolygon
                || geometryType == GTMultiPolygon;
    }

    private static GeodesicResult inverseWgs84(
            double latitude1, double longitude1, double latitude2, double longitude2) {
        if (latitude1 == latitude2 && longitude1 == longitude2) {
            return new GeodesicResult(0.0, 0.0);
        }

        double phi1 = Math.toRadians(latitude1);
        double phi2 = Math.toRadians(latitude2);
        double reduced1 = Math.atan((1.0 - WGS84_F) * Math.tan(phi1));
        double reduced2 = Math.atan((1.0 - WGS84_F) * Math.tan(phi2));
        double sinReduced1 = Math.sin(reduced1);
        double cosReduced1 = Math.cos(reduced1);
        double sinReduced2 = Math.sin(reduced2);
        double cosReduced2 = Math.cos(reduced2);
        double longitudeDifference = Math.toRadians(longitude2 - longitude1);
        double lambda = longitudeDifference;

        double sinSigma = 0.0;
        double cosSigma = 0.0;
        double sigma = 0.0;
        double sinAlpha = 0.0;
        double cosSqAlpha = 0.0;
        double cos2SigmaM = 0.0;
        boolean converged = false;
        for (int iteration = 0; iteration < 100; iteration++) {
            double sinLambda = Math.sin(lambda);
            double cosLambda = Math.cos(lambda);
            double x = cosReduced2 * sinLambda;
            double y = cosReduced1 * sinReduced2
                    - sinReduced1 * cosReduced2 * cosLambda;
            sinSigma = Math.sqrt(x * x + y * y);
            if (sinSigma == 0.0) {
                return new GeodesicResult(0.0, 0.0);
            }
            cosSigma = sinReduced1 * sinReduced2
                    + cosReduced1 * cosReduced2 * cosLambda;
            sigma = Math.atan2(sinSigma, cosSigma);
            sinAlpha = cosReduced1 * cosReduced2 * sinLambda / sinSigma;
            cosSqAlpha = 1.0 - sinAlpha * sinAlpha;
            cos2SigmaM = cosSqAlpha == 0.0
                    ? 0.0
                    : cosSigma - 2.0 * sinReduced1 * sinReduced2 / cosSqAlpha;
            double c = WGS84_F / 16.0 * cosSqAlpha
                    * (4.0 + WGS84_F * (4.0 - 3.0 * cosSqAlpha));
            double previous = lambda;
            lambda = longitudeDifference + (1.0 - c) * WGS84_F * sinAlpha
                    * (sigma + c * sinSigma
                    * (cos2SigmaM + c * cosSigma
                    * (-1.0 + 2.0 * cos2SigmaM * cos2SigmaM)));
            if (Math.abs(lambda - previous) <= 1e-12) {
                converged = true;
                break;
            }
        }

        if (!converged) {
            return inverseSphere(latitude1, longitude1, latitude2, longitude2);
        }

        double uSq = cosSqAlpha
                * (WGS84_A * WGS84_A - WGS84_B * WGS84_B)
                / (WGS84_B * WGS84_B);
        double coefficientA = 1.0 + uSq / 16384.0
                * (4096.0 + uSq * (-768.0 + uSq * (320.0 - 175.0 * uSq)));
        double coefficientB = uSq / 1024.0
                * (256.0 + uSq * (-128.0 + uSq * (74.0 - 47.0 * uSq)));
        double deltaSigma = coefficientB * sinSigma
                * (cos2SigmaM + coefficientB / 4.0
                * (cosSigma * (-1.0 + 2.0 * cos2SigmaM * cos2SigmaM)
                - coefficientB / 6.0 * cos2SigmaM
                * (-3.0 + 4.0 * sinSigma * sinSigma)
                * (-3.0 + 4.0 * cos2SigmaM * cos2SigmaM)));
        double distance = WGS84_B * coefficientA * (sigma - deltaSigma);
        double initialBearing = Math.toDegrees(Math.atan2(
                cosReduced2 * Math.sin(lambda),
                cosReduced1 * sinReduced2
                        - sinReduced1 * cosReduced2 * Math.cos(lambda)));
        return new GeodesicResult(distance, normalizeBearing(initialBearing));
    }

    private static GeodesicResult inverseSphere(
            double latitude1, double longitude1, double latitude2, double longitude2) {
        double phi1 = Math.toRadians(latitude1);
        double phi2 = Math.toRadians(latitude2);
        double deltaPhi = phi2 - phi1;
        double deltaLambda = Math.toRadians(longitude2 - longitude1);
        double haversine = Math.sin(deltaPhi / 2.0) * Math.sin(deltaPhi / 2.0)
                + Math.cos(phi1) * Math.cos(phi2)
                * Math.sin(deltaLambda / 2.0) * Math.sin(deltaLambda / 2.0);
        double distance = 6371008.8 * 2.0
                * Math.atan2(Math.sqrt(haversine), Math.sqrt(1.0 - haversine));
        double bearing = Math.toDegrees(Math.atan2(
                Math.sin(deltaLambda) * Math.cos(phi2),
                Math.cos(phi1) * Math.sin(phi2)
                        - Math.sin(phi1) * Math.cos(phi2) * Math.cos(deltaLambda)));
        return new GeodesicResult(distance, normalizeBearing(bearing));
    }

    private static double normalizeBearing(double bearing) {
        double normalized = bearing % 360.0;
        return normalized < 0.0 ? normalized + 360.0 : normalized;
    }

    private static final class GeodesicResult {
        private final double distanceMeters;
        private final double initialBearingDegrees;

        private GeodesicResult(double distanceMeters, double initialBearingDegrees) {
            this.distanceMeters = distanceMeters;
            this.initialBearingDegrees = initialBearingDegrees;
        }
    }

    public static final class Result {
        private final double distanceMeters;
        private final double bearingDegrees;
        private final double nearestLongitude;
        private final double nearestLatitude;

        private Result(
                double distanceMeters,
                double bearingDegrees,
                double nearestLongitude,
                double nearestLatitude) {
            this.distanceMeters = distanceMeters;
            this.bearingDegrees = bearingDegrees;
            this.nearestLongitude = nearestLongitude;
            this.nearestLatitude = nearestLatitude;
        }

        public double getDistanceMeters() {
            return distanceMeters;
        }

        public double getBearingDegrees() {
            return bearingDegrees;
        }

        public double getNearestLongitude() {
            return nearestLongitude;
        }

        public double getNearestLatitude() {
            return nearestLatitude;
        }
    }
}

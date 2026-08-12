/*
 * Project:  NextGIS Mobile
 * Purpose:  Geometry helpers for photo overlay and similar features.
 */

package com.nextgis.maplib.util;

import com.nextgis.maplib.datasource.GeoGeometry;
import com.nextgis.maplib.datasource.GeoLineString;
import com.nextgis.maplib.datasource.GeoLinearRing;
import com.nextgis.maplib.datasource.GeoMultiLineString;
import com.nextgis.maplib.datasource.GeoMultiPoint;
import com.nextgis.maplib.datasource.GeoMultiPolygon;
import com.nextgis.maplib.datasource.GeoPoint;
import com.nextgis.maplib.datasource.GeoPolygon;
import com.nextgis.maplib.map.MPLFeaturesUtils;

import java.util.List;

public final class GeoGeometryUtil {

    private GeoGeometryUtil() {
    }

    /**
     * @return {longitude, latitude} in WGS84, or null if geometry is empty/unsupported
     */
    public static double[] getWgs84RepresentativePoint(GeoGeometry geometry) {
        if (geometry == null) {
            return null;
        }

        switch (geometry.getType()) {
            case GeoConstants.GTPoint:
                return pointToWgs84((GeoPoint) geometry);
            case GeoConstants.GTMultiPoint:
                return averagePointsWgs84(collectMultiPointVertices((GeoMultiPoint) geometry));
            case GeoConstants.GTLineString:
                return averagePointsWgs84(collectLineVertices((GeoLineString) geometry));
            case GeoConstants.GTMultiLineString:
                return averagePointsWgs84(collectMultiLineVertices((GeoMultiLineString) geometry));
            case GeoConstants.GTPolygon:
                return averagePointsWgs84(collectRingVertices(((GeoPolygon) geometry).getOuterRing()));
            case GeoConstants.GTMultiPolygon:
                return averagePointsWgs84(collectMultiPolygonVertices((GeoMultiPolygon) geometry));
            default:
                return null;
        }
    }

    private static double[] pointToWgs84(GeoPoint point) {
        if (point == null) {
            return null;
        }
        if (point.getCRS() == GeoConstants.CRS_WGS84) {
            return new double[]{point.getX(), point.getY()};
        }
        return MPLFeaturesUtils.convert3857To4326(point.getX(), point.getY());
    }

    private static double[][] collectMultiPointVertices(GeoMultiPoint multiPoint) {
        int count = multiPoint.size();
        double[][] vertices = new double[count][];
        for (int i = 0; i < count; i++) {
            vertices[i] = pointToWgs84((GeoPoint) multiPoint.get(i));
        }
        return vertices;
    }

    private static double[][] collectLineVertices(GeoLineString line) {
        List<GeoPoint> points = line.getPoints();
        double[][] vertices = new double[points.size()][];
        for (int i = 0; i < points.size(); i++) {
            vertices[i] = pointToWgs84(points.get(i));
        }
        return vertices;
    }

    private static double[][] collectMultiLineVertices(GeoMultiLineString multiLine) {
        int total = 0;
        for (int i = 0; i < multiLine.size(); i++) {
            total += ((GeoLineString) multiLine.get(i)).getPointCount();
        }
        double[][] vertices = new double[total][];
        int index = 0;
        for (int i = 0; i < multiLine.size(); i++) {
            List<GeoPoint> points = ((GeoLineString) multiLine.get(i)).getPoints();
            for (GeoPoint point : points) {
                vertices[index++] = pointToWgs84(point);
            }
        }
        return vertices;
    }

    private static double[][] collectRingVertices(GeoLinearRing ring) {
        List<GeoPoint> points = ring.getPoints();
        double[][] vertices = new double[points.size()][];
        for (int i = 0; i < points.size(); i++) {
            vertices[i] = pointToWgs84(points.get(i));
        }
        return vertices;
    }

    private static double[][] collectMultiPolygonVertices(GeoMultiPolygon multiPolygon) {
        int total = 0;
        for (int i = 0; i < multiPolygon.size(); i++) {
            total += ((GeoPolygon) multiPolygon.get(i)).getOuterRing().getPoints().size();
        }
        double[][] vertices = new double[total][];
        int index = 0;
        for (int i = 0; i < multiPolygon.size(); i++) {
            GeoLinearRing ring = ((GeoPolygon) multiPolygon.get(i)).getOuterRing();
            for (GeoPoint point : ring.getPoints()) {
                vertices[index++] = pointToWgs84(point);
            }
        }
        return vertices;
    }

    private static double[] averagePointsWgs84(double[][] vertices) {
        if (vertices == null || vertices.length == 0) {
            return null;
        }
        double sumLon = 0;
        double sumLat = 0;
        int count = 0;
        for (double[] vertex : vertices) {
            if (vertex == null) {
                continue;
            }
            sumLon += vertex[0];
            sumLat += vertex[1];
            count++;
        }
        if (count == 0) {
            return null;
        }
        return new double[]{sumLon / count, sumLat / count};
    }
}

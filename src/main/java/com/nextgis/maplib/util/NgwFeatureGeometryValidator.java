/*
 * Project: NextGIS Mobile
 * Purpose: Bounded-cost topology validation for geometries received from NGW.
 */

package com.nextgis.maplib.util;

import com.nextgis.maplib.datasource.GeoGeometry;
import com.nextgis.maplib.datasource.GeoLinearRing;
import com.nextgis.maplib.datasource.GeoMultiPolygon;
import com.nextgis.maplib.datasource.GeoPoint;
import com.nextgis.maplib.datasource.GeoPolygon;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.valid.IsValidOp;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates server polygon topology without the legacy quadratic segment-pair scan.
 *
 * <p>A {@link GeoMultiPolygon} is intentionally checked polygon-by-polygon to retain the old
 * import semantics: the legacy collection validator checked every member but did not reject two
 * otherwise valid members merely because they overlap. Other geometry types keep their existing
 * validator.</p>
 */
public final class NgwFeatureGeometryValidator {
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    private NgwFeatureGeometryValidator() {
    }

    public static boolean isValid(GeoGeometry geometry) {
        if (geometry == null) {
            return false;
        }
        try {
            if (geometry instanceof GeoPolygon) {
                return isPolygonValid((GeoPolygon) geometry);
            }
            if (geometry instanceof GeoMultiPolygon) {
                GeoMultiPolygon multiPolygon = (GeoMultiPolygon) geometry;
                for (int i = 0; i < multiPolygon.size(); i++) {
                    if (!isPolygonValid(multiPolygon.get(i))) {
                        return false;
                    }
                }
                return multiPolygon.size() > 0;
            }
            return geometry.isValid();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean isPolygonValid(GeoPolygon polygon) {
        if (polygon == null) {
            return false;
        }
        LinearRing shell = toJtsRing(polygon.getOuterRing());
        LinearRing[] holes = new LinearRing[polygon.getInnerRingCount()];
        for (int i = 0; i < polygon.getInnerRingCount(); i++) {
            holes[i] = toJtsRing(polygon.getInnerRing(i));
        }
        Polygon jtsPolygon = GEOMETRY_FACTORY.createPolygon(shell, holes);
        return new IsValidOp(jtsPolygon).isValid();
    }

    private static LinearRing toJtsRing(GeoLinearRing ring) {
        if (ring == null || ring.getPoints() == null) {
            throw new IllegalArgumentException("ring is missing");
        }
        List<Coordinate> coordinates = new ArrayList<>(ring.getPoints().size() + 1);
        for (GeoPoint point : ring.getPoints()) {
            if (point == null || !isFinite(point.getX()) || !isFinite(point.getY())) {
                throw new IllegalArgumentException("ring contains invalid coordinate");
            }
            Coordinate coordinate = new Coordinate(point.getX(), point.getY());
            if (coordinates.isEmpty()
                    || !coordinates.get(coordinates.size() - 1).equals2D(coordinate)) {
                coordinates.add(coordinate);
            }
        }
        if (coordinates.size() < 3) {
            throw new IllegalArgumentException("ring has fewer than three positions");
        }
        if (!coordinates.get(0).equals2D(coordinates.get(coordinates.size() - 1))) {
            coordinates.add(new Coordinate(coordinates.get(0)));
        }
        if (coordinates.size() < 4) {
            throw new IllegalArgumentException("closed ring has fewer than four positions");
        }
        return GEOMETRY_FACTORY.createLinearRing(coordinates.toArray(new Coordinate[0]));
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}

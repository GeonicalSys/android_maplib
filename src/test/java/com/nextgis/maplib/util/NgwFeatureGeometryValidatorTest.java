package com.nextgis.maplib.util;

import com.nextgis.maplib.datasource.GeoMultiPolygon;
import com.nextgis.maplib.datasource.GeoPoint;
import com.nextgis.maplib.datasource.GeoPolygon;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NgwFeatureGeometryValidatorTest {
    private static final int CRS = GeoConstants.CRS_WEB_MERCATOR;

    @Test(timeout = 10_000L)
    public void validatesLargeServerPolygonWithoutQuadraticLegacyScan() {
        GeoPolygon polygon = new GeoPolygon() {
            @Override
            public boolean isValid() {
                throw new AssertionError("legacy quadratic validator must not be called");
            }
        };
        polygon.setCRS(CRS);

        int vertexCount = 60_000;
        double radius = 10_000.0;
        for (int i = 0; i < vertexCount; i++) {
            double angle = (Math.PI * 2.0 * i) / vertexCount;
            addPoint(polygon, Math.cos(angle) * radius, Math.sin(angle) * radius);
        }
        addPoint(polygon, radius, 0.0);

        assertTrue(NgwFeatureGeometryValidator.isValid(polygon));
    }

    @Test
    public void rejectsSelfIntersectingServerPolygon() {
        GeoPolygon polygon = new GeoPolygon();
        polygon.setCRS(CRS);
        addPoint(polygon, 0, 0);
        addPoint(polygon, 10, 10);
        addPoint(polygon, 0, 10);
        addPoint(polygon, 10, 0);
        addPoint(polygon, 0, 0);

        assertFalse(NgwFeatureGeometryValidator.isValid(polygon));
    }

    @Test
    public void keepsLegacyMultiPolygonMemberSemantics() {
        GeoMultiPolygon multiPolygon = new GeoMultiPolygon();
        multiPolygon.setCRS(CRS);
        multiPolygon.add(square(0, 0, 10, 10));
        multiPolygon.add(square(5, 0, 15, 10));

        assertTrue(NgwFeatureGeometryValidator.isValid(multiPolygon));
    }

    private static GeoPolygon square(double minX, double minY, double maxX, double maxY) {
        GeoPolygon polygon = new GeoPolygon();
        polygon.setCRS(CRS);
        addPoint(polygon, minX, minY);
        addPoint(polygon, maxX, minY);
        addPoint(polygon, maxX, maxY);
        addPoint(polygon, minX, maxY);
        addPoint(polygon, minX, minY);
        return polygon;
    }

    private static void addPoint(GeoPolygon polygon, double x, double y) {
        GeoPoint point = new GeoPoint(x, y);
        point.setCRS(CRS);
        polygon.add(point);
    }
}

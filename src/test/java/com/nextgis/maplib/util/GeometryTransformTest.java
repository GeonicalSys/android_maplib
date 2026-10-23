package com.nextgis.maplib.util;

import com.nextgis.maplib.datasource.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.nextgis.maplib.util.GeoConstants.*;

public class GeometryTransformTest {
    @Test public void translatesEveryGeometryWithoutChangingItsSourceOrCrs() {
        String[] examples = {"POINT (1 2)", "MULTIPOINT ((1 2), (3 4))",
                "LINESTRING (1 2, 3 4)", "MULTILINESTRING ((1 2, 3 4), (5 6, 7 8))",
                "POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0), (2 2, 8 2, 8 8, 2 8, 2 2))",
                "MULTIPOLYGON (((0 0, 10 0, 10 10, 0 10, 0 0), (2 2, 8 2, 8 8, 2 8, 2 2)),"
                        + " ((20 20, 30 20, 30 30, 20 30, 20 20)))"};
        for (String wkt : examples) {
            GeoGeometry source = GeoGeometryFactory.fromWKT(wkt, CRS_WEB_MERCATOR);
            String before = source.toWKT(true);
            GeoGeometry moved = GeometryTransform.apply(source, 100, -50, 0, 0, 0);
            assertEquals(source.getType(), moved.getType());
            assertEquals(CRS_WEB_MERCATOR, moved.getCRS());
            assertEquals(source.getEnvelope().getMinX() + 100, moved.getEnvelope().getMinX(), 1e-8);
            assertEquals(source.getEnvelope().getMaxY() - 50, moved.getEnvelope().getMaxY(), 1e-8);
            assertEquals(before, source.toWKT(true));
            GeoGeometry restored = GeometryTransform.apply(moved, -100, 50, 0, 0, 0);
            assertEquals(before, restored.toWKT(true));
        }
    }

    @Test public void rotatesAllPolygonPartsAndHolesWithoutScalingOrOpeningRings() {
        GeoMultiPolygon source = (GeoMultiPolygon) GeoGeometryFactory.fromWKT(
                "MULTIPOLYGON (((0 0, 10 0, 10 10, 0 10, 0 0), (2 2, 8 2, 8 8, 2 8, 2 2)),"
                + " ((20 20, 30 20, 30 30, 20 30, 20 20)))", CRS_WEB_MERCATOR);
        GeoMultiPolygon rotated = (GeoMultiPolygon) GeometryTransform.apply(source, 0, 0, Math.PI/2, 15, 15);
        assertEquals(2, rotated.size()); assertEquals(1, rotated.get(0).getInnerRingCount());
        GeoPoint first = rotated.get(0).getOuterRing().getPoint(0);
        assertEquals(30, first.getX(), 1e-8); assertEquals(0, first.getY(), 1e-8);
        for (int i=0; i<rotated.size(); i++) {
            GeoPolygon polygon = rotated.get(i);
            assertTrue(polygon.getOuterRing().isClosed());
            assertEquals(10, polygon.getOuterRing().getPoint(0).distance(polygon.getOuterRing().getPoint(1)), 1e-8);
            for (GeoLinearRing hole : polygon.getInnerRings()) assertTrue(hole.isClosed());
        }
        assertEquals(0, source.get(0).getOuterRing().getPoint(0).getX(), 0);
    }
}

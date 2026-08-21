package com.nextgis.maplib.datasource;

import com.nextgis.maplib.util.GeoConstants;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GeoMultiPolygonWktTest {
    private static final int CRS = GeoConstants.CRS_WEB_MERCATOR;

    @Test
    public void parsesSinglePolygonWithoutHole() {
        GeoMultiPolygon multiPolygon = parse(
                "MULTIPOLYGON (((0 0, 10 0, 10 10, 0 10, 0 0)))");

        assertEquals(1, multiPolygon.size());
        assertEquals(0, multiPolygon.get(0).getInnerRingCount());
        assertEquals(5, multiPolygon.get(0).getOuterRing().getPointCount());
    }

    @Test
    public void parsesSinglePolygonWithHole() {
        GeoMultiPolygon multiPolygon = parse(
                "MULTIPOLYGON (((0 0, 10 0, 10 10, 0 10, 0 0), "
                        + "(2 2, 8 2, 8 8, 2 8, 2 2)))");

        assertEquals(1, multiPolygon.size());
        assertEquals(1, multiPolygon.get(0).getInnerRingCount());
        assertEquals(5, multiPolygon.get(0).getInnerRing(0).getPointCount());
    }

    @Test
    public void preservesAllPolygonMembersAndHoles() {
        String wkt = "MULTIPOLYGON (((0 0, 10 0, 10 10, 0 10, 0 0), "
                + "(2 2, 8 2, 8 8, 2 8, 2 2)), "
                + "((20 20, 30 20, 30 30, 20 30, 20 20)))";

        GeoMultiPolygon multiPolygon = parse(wkt);

        assertEquals(2, multiPolygon.size());
        assertEquals(1, multiPolygon.get(0).getInnerRingCount());
        assertEquals(0, multiPolygon.get(1).getInnerRingCount());

        GeoMultiPolygon restored = parse(multiPolygon.toWKT(true));
        assertEquals(2, restored.size());
        assertEquals(1, restored.get(0).getInnerRingCount());
        assertEquals(0, restored.get(1).getInnerRingCount());
        assertEquals(multiPolygon.toWKT(true), restored.toWKT(true));
    }

    private static GeoMultiPolygon parse(String wkt) {
        GeoGeometry geometry = GeoGeometryFactory.fromWKT(wkt, CRS);
        assertTrue(geometry instanceof GeoMultiPolygon);
        return (GeoMultiPolygon) geometry;
    }
}

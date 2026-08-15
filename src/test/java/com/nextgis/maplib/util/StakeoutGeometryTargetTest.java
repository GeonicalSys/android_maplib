package com.nextgis.maplib.util;

import com.nextgis.maplib.datasource.GeoGeometry;
import com.nextgis.maplib.datasource.GeoGeometryFactory;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class StakeoutGeometryTargetTest {
    private static final int WGS84 = GeoConstants.CRS_WGS84;

    @Test
    public void calculatesDistanceAndBearingToPoint() {
        StakeoutGeometryTarget target = target("POINT (37.6208 55.7539)");

        StakeoutGeometryTarget.Result result = target.calculate(37.6208, 55.7530);

        assertEquals(100.2, result.getDistanceMeters(), 0.5);
        assertEquals(0.0, result.getBearingDegrees(), 0.2);
    }

    @Test
    public void usesNearestInteriorPointOnLine() {
        StakeoutGeometryTarget target = target(
                "LINESTRING (37.6200 55.7539, 37.6220 55.7539)");

        StakeoutGeometryTarget.Result result = target.calculate(37.6210, 55.7530);

        assertEquals(100.2, result.getDistanceMeters(), 0.5);
        assertEquals(37.6210, result.getNearestLongitude(), 0.00001);
        assertEquals(55.7539, result.getNearestLatitude(), 0.00001);
    }

    @Test
    public void polygonInsideUsesBoundaryInsteadOfZeroDistance() {
        StakeoutGeometryTarget target = target(
                "POLYGON ((37.6200 55.7530, 37.6220 55.7530, "
                        + "37.6220 55.7550, 37.6200 55.7550, 37.6200 55.7530))");

        StakeoutGeometryTarget.Result result = target.calculate(37.6210, 55.7540);

        assertTrue(result.getDistanceMeters() > 60.0);
        assertTrue(result.getDistanceMeters() < 115.0);
    }

    @Test
    public void polygonHoleIsAStakeoutBoundary() {
        StakeoutGeometryTarget target = target(
                "POLYGON ((37.6190 55.7520, 37.6230 55.7520, "
                        + "37.6230 55.7560, 37.6190 55.7560, 37.6190 55.7520), "
                        + "(37.6205 55.7535, 37.6215 55.7535, 37.6215 55.7545, "
                        + "37.6205 55.7545, 37.6205 55.7535))");

        StakeoutGeometryTarget.Result result = target.calculate(37.6210, 55.7540);

        assertTrue(result.getDistanceMeters() > 25.0);
        assertTrue(result.getDistanceMeters() < 35.0);
    }

    @Test
    public void supportsMultiGeometryAndDoesNotMutateSource() {
        GeoGeometry source = GeoGeometryFactory.fromWKT(
                "MULTILINESTRING ((37.6200 55.7530, 37.6200 55.7540), "
                        + "(37.6220 55.7530, 37.6220 55.7540))",
                WGS84);
        String before = source.toWKT(true);

        StakeoutGeometryTarget.Result result =
                new StakeoutGeometryTarget(source).calculate(37.6201, 55.7535);

        assertTrue(result.getDistanceMeters() < 7.0);
        assertEquals(WGS84, source.getCRS());
        assertEquals(before, source.toWKT(true));
    }

    @Test
    public void rejectsMixedGeometryCollection() {
        GeoGeometry source = GeoGeometryFactory.fromWKT(
                "GEOMETRYCOLLECTION (POINT (37.62 55.75), "
                        + "LINESTRING (37.62 55.75, 37.63 55.76))",
                WGS84);

        assertThrows(IllegalArgumentException.class,
                () -> new StakeoutGeometryTarget(source));
    }

    private static StakeoutGeometryTarget target(String wkt) {
        return new StakeoutGeometryTarget(GeoGeometryFactory.fromWKT(wkt, WGS84));
    }
}

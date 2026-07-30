package com.nextgis.maplib.util;

import com.nextgis.maplib.datasource.GeoMultiPolygon;
import com.nextgis.maplib.datasource.GeoLinearRing;
import com.nextgis.maplib.datasource.GeoPoint;
import com.nextgis.maplib.datasource.GeoPolygon;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class MultiPolygonGeometryRepairTest {
    private static final int CRS = GeoConstants.CRS_WEB_MERCATOR;

    @Test
    public void optsInOnlyMultiPolygonLayers() {
        assertTrue(MultiPolygonGeometryRepair.supportsLayerType(
                GeoConstants.GTMultiPolygon));
        assertFalse(MultiPolygonGeometryRepair.supportsLayerType(
                GeoConstants.GTPolygon));
        assertFalse(MultiPolygonGeometryRepair.supportsLayerType(
                GeoConstants.GTLineString));
        assertFalse(MultiPolygonGeometryRepair.supportsLayerType(
                GeoConstants.GTMultiLineString));
    }

    @Test
    public void repairsBowTieIntoTwoValidPolygonParts() {
        GeoMultiPolygon input = multiPolygon(polygon(new double[][]{
                {0, 0},
                {10, 10},
                {0, 10},
                {10, 0},
                {0, 0}
        }));

        MultiPolygonGeometryRepair.Result result =
                MultiPolygonGeometryRepair.repairIfNeeded(input);

        assertEquals(MultiPolygonGeometryRepair.Status.REPAIRED, result.getStatus());
        assertEquals(2, result.getPolygonCount());
        assertTrue(result.getGeometry().isValid());
        assertEquals(CRS, result.getGeometry().getCRS());
        for (int i = 0; i < result.getGeometry().size(); i++) {
            assertEquals(CRS, result.getGeometry().get(i).getCRS());
        }
    }

    @Test
    public void repairsMaplibreBowTieWhenCollectionCrsWasUnset() {
        GeoMultiPolygon input = new GeoMultiPolygon();
        input.add(polygon(new double[][]{
                {0, 0},
                {10, 10},
                {0, 10},
                {10, 0},
                {0, 0}
        }));

        MultiPolygonGeometryRepair.Result result =
                MultiPolygonGeometryRepair.repairIfNeeded(input);

        assertEquals(MultiPolygonGeometryRepair.Status.REPAIRED, result.getStatus());
        assertEquals(2, result.getPolygonCount());
        assertEquals(CRS, result.getGeometry().getCRS());
        assertTrue(result.getGeometry().isValid());
    }

    @Test
    public void keepsAlreadyValidMultiPolygonUnchanged() {
        GeoMultiPolygon input = multiPolygon(
                square(0, 0, 10, 10),
                square(20, 20, 30, 30));

        MultiPolygonGeometryRepair.Result result =
                MultiPolygonGeometryRepair.repairIfNeeded(input);

        assertEquals(MultiPolygonGeometryRepair.Status.UNCHANGED, result.getStatus());
        assertSame(input, result.getGeometry());
        assertEquals(2, result.getPolygonCount());
    }

    @Test
    public void unionsOverlappingPartsIntoValidResult() {
        GeoMultiPolygon input = multiPolygon(
                square(0, 0, 10, 10),
                square(5, 0, 15, 10));

        MultiPolygonGeometryRepair.Result result =
                MultiPolygonGeometryRepair.repairIfNeeded(input);

        assertEquals(MultiPolygonGeometryRepair.Status.REPAIRED, result.getStatus());
        assertEquals(1, result.getPolygonCount());
        assertTrue(result.getGeometry().isValid());
    }

    @Test
    public void preservesValidHoleWhenRepairingOverlappingParts() {
        GeoPolygon polygon = square(0, 0, 10, 10);
        polygon.addInnerRing(ring(new double[][]{
                {2, 2},
                {4, 2},
                {4, 4},
                {2, 4},
                {2, 2}
        }));

        MultiPolygonGeometryRepair.Result result =
                MultiPolygonGeometryRepair.repairIfNeeded(
                        multiPolygon(polygon, square(8, 0, 15, 10)));

        assertEquals(MultiPolygonGeometryRepair.Status.REPAIRED, result.getStatus());
        assertTrue(result.getGeometry().isValid());
        int holeCount = 0;
        for (int i = 0; i < result.getGeometry().size(); i++) {
            holeCount += result.getGeometry().get(i).getInnerRingCount();
        }
        assertEquals(1, holeCount);
    }

    @Test
    public void failsWithoutInventingAreaForTooShortRing() {
        GeoMultiPolygon input = multiPolygon(polygon(new double[][]{
                {0, 0},
                {1, 1}
        }));

        MultiPolygonGeometryRepair.Result result =
                MultiPolygonGeometryRepair.repairIfNeeded(input);

        assertEquals(MultiPolygonGeometryRepair.Status.FAILED, result.getStatus());
        assertSame(input, result.getGeometry());
        assertTrue(result.getDiagnostic().contains("fewer than three"));
    }

    private static GeoPolygon square(double minX, double minY, double maxX, double maxY) {
        return polygon(new double[][]{
                {minX, minY},
                {maxX, minY},
                {maxX, maxY},
                {minX, maxY},
                {minX, minY}
        });
    }

    private static GeoPolygon polygon(double[][] coordinates) {
        GeoPolygon polygon = new GeoPolygon();
        polygon.setCRS(CRS);
        for (GeoPoint point : points(coordinates)) {
            polygon.add(point);
        }
        return polygon;
    }

    private static GeoLinearRing ring(double[][] coordinates) {
        GeoLinearRing ring = new GeoLinearRing();
        ring.setCRS(CRS);
        for (GeoPoint point : points(coordinates)) {
            ring.add(point);
        }
        return ring;
    }

    private static GeoPoint[] points(double[][] coordinates) {
        GeoPoint[] points = new GeoPoint[coordinates.length];
        int index = 0;
        for (double[] coordinate : coordinates) {
            GeoPoint point = new GeoPoint(coordinate[0], coordinate[1]);
            point.setCRS(CRS);
            points[index++] = point;
        }
        return points;
    }

    private static GeoMultiPolygon multiPolygon(GeoPolygon... polygons) {
        GeoMultiPolygon multiPolygon = new GeoMultiPolygon();
        multiPolygon.setCRS(CRS);
        for (GeoPolygon polygon : polygons) {
            multiPolygon.add(polygon);
        }
        return multiPolygon;
    }
}

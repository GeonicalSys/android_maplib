package com.nextgis.maplib.map;

import com.nextgis.maplib.datasource.*;
import com.nextgis.maplib.util.GeoConstants;
import org.junit.Test;
import org.maplibre.geojson.FeatureCollection;
import static org.junit.Assert.*;

public class WalkPreviewGeometryTest {
    @Test public void storedMetreCoordinatesAreProjectedToVisibleDegreesAfterCopying() throws Exception {
        GeoGeometry geometry = GeoGeometryFactory.fromWKT(
                "LINESTRING (1113194.907932736 6446275.841017161, 1113214.907932736 6446295.841017161)",
                GeoConstants.CRS_WEB_MERCATOR);
        String before = geometry.toWKT(true);
        org.maplibre.geojson.LineString line = (org.maplibre.geojson.LineString)
                WalkPreviewGeometry.build(geometry).features().get(0).geometry();
        assertEquals(10, line.coordinates().get(0).longitude(), .000001);
        assertEquals(50, line.coordinates().get(0).latitude(), .000001);
        assertTrue(line.coordinates().get(1).longitude() > 10);
        assertTrue(line.coordinates().get(1).latitude() > 50);
        assertEquals(before, geometry.toWKT(true));
        assertEquals(GeoConstants.CRS_WEB_MERCATOR, geometry.getCRS());
    }
    @Test public void oneNodeAndTwoNodePolygonDraftsAreVisibleWithoutClosingTheirGeometry() throws Exception {
        GeoPolygon polygon = new GeoPolygon();
        polygon.setCRS(GeoConstants.CRS_WGS84);
        polygon.getOuterRing().add(new GeoPoint(1, 2));
        assertEquals("Point", WalkPreviewGeometry.build(polygon).features().get(0).geometry().type());
        polygon.getOuterRing().add(new GeoPoint(2, 2));
        assertEquals("LineString", WalkPreviewGeometry.build(polygon).features().get(0).geometry().type());
        polygon.getOuterRing().add(new GeoPoint(2, 3));
        String before = polygon.toWKT(true);
        FeatureCollection preview = WalkPreviewGeometry.build(polygon);
        assertEquals("Polygon", preview.features().get(0).geometry().type());
        assertEquals(before, polygon.toWKT(true));
        assertEquals(3, polygon.getOuterRing().getPointCount());
    }

    @Test public void everyMemberAndHoleSurvivesThePreview() throws Exception {
        GeoGeometry geometry = GeoGeometryFactory.fromWKT(
                "MULTIPOLYGON (((0 0, 10 0, 10 10, 0 0), (1 1, 2 1, 2 2, 1 1)),"
                        + " ((20 0, 30 0, 30 10, 20 0)))", GeoConstants.CRS_WGS84);
        FeatureCollection preview = WalkPreviewGeometry.build(geometry);
        assertEquals(2, preview.features().size());
        org.maplibre.geojson.Polygon first = (org.maplibre.geojson.Polygon) preview.features().get(0).geometry();
        assertEquals(2, first.coordinates().size());
        assertTrue(WalkPreviewGeometry.build(null).features().isEmpty());
    }
}

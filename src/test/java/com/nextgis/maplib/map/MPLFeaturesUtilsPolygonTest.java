package com.nextgis.maplib.map;

import com.google.gson.JsonObject;
import com.nextgis.maplib.datasource.GeoLinearRing;
import com.nextgis.maplib.datasource.GeoGeometry;
import com.nextgis.maplib.datasource.GeoGeometryFactory;
import com.nextgis.maplib.datasource.GeoPoint;
import com.nextgis.maplib.datasource.GeoPolygon;
import com.nextgis.maplib.util.GeoConstants;

import org.junit.Test;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.Geometry;
import org.maplibre.geojson.MultiPolygon;
import org.maplibre.geojson.Point;
import org.maplibre.geojson.Polygon;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MPLFeaturesUtilsPolygonTest {
    @Test
    public void converterClosesOpenPolygonRingForMapLibre() {
        GeoPolygon polygon = polygon(false);

        Feature feature = MPLFeaturesUtils.getFeatureFromNGFeature(polygon);
        List<Point> ring = ((Polygon) feature.geometry()).coordinates().get(0);

        assertEquals(4, ring.size());
        assertEquals(ring.get(0), ring.get(ring.size() - 1));
    }

    @Test
    public void converterDoesNotDuplicateExistingClosingPoint() {
        GeoPolygon polygon = polygon(true);

        Feature feature = MPLFeaturesUtils.getFeatureFromNGFeature(polygon);
        List<Point> ring = ((Polygon) feature.geometry()).coordinates().get(0);

        assertEquals(4, ring.size());
        assertEquals(ring.get(0), ring.get(ring.size() - 1));
    }

    @Test
    public void converterPreservesSingleNodeDraftRing() {
        GeoLinearRing sourceRing = new GeoLinearRing();
        sourceRing.setCRS(GeoConstants.CRS_WEB_MERCATOR);
        sourceRing.add(point(5, 7));
        GeoPolygon source = new GeoPolygon();
        source.setCRS(GeoConstants.CRS_WEB_MERCATOR);
        source.setOuterRing(sourceRing);

        Feature feature = MPLFeaturesUtils.getFeatureFromNGFeature(source);
        List<Point> ring = ((Polygon) feature.geometry()).coordinates().get(0);

        assertEquals(1, ring.size());
    }

    @Test
    public void singleNodePolygonDraftSurvivesWktRoundTripWithoutExtraClosure() {
        GeoLinearRing sourceRing = new GeoLinearRing();
        sourceRing.setCRS(GeoConstants.CRS_WEB_MERCATOR);
        sourceRing.add(point(5, 7));
        GeoPolygon source = new GeoPolygon();
        source.setCRS(GeoConstants.CRS_WEB_MERCATOR);
        source.setOuterRing(sourceRing);

        GeoGeometry restored = GeoGeometryFactory.fromWKT(
                source.toWKT(true), GeoConstants.CRS_WEB_MERCATOR);

        assertTrue(restored instanceof GeoPolygon);
        GeoPolygon restoredPolygon = (GeoPolygon) restored;
        GeoLinearRing restoredRing = restoredPolygon.getOuterRing();
        assertEquals(2, restoredRing.getPointCount());
        assertEquals(restoredRing.getPoint(0), restoredRing.getPoint(1));
        assertEquals(0, restoredPolygon.getInnerRingCount());

        Feature feature = MPLFeaturesUtils.getFeatureFromNGFeature(restored);
        List<Point> mapLibreRing = ((Polygon) feature.geometry()).coordinates().get(0);
        assertEquals(2, mapLibreRing.size());
        assertEquals(mapLibreRing.get(0), mapLibreRing.get(1));
    }

    @Test
    public void polygonDraftWktRoundTripDoesNotDuplicateOuterRingAsHole() {
        GeoPolygon source = polygon(true);

        GeoGeometry restored = GeoGeometryFactory.fromWKT(
                source.toWKT(true), GeoConstants.CRS_WEB_MERCATOR);

        assertTrue(restored instanceof GeoPolygon);
        GeoPolygon restoredPolygon = (GeoPolygon) restored;
        assertEquals(4, restoredPolygon.getOuterRing().getPointCount());
        assertEquals(0, restoredPolygon.getInnerRingCount());
        assertEquals(source.toWKT(true), restoredPolygon.toWKT(true));
    }

    @Test
    public void polygonDraftWktRoundTripPreservesRealHoleOnce() {
        GeoPolygon source = polygon(true);
        GeoLinearRing hole = new GeoLinearRing();
        hole.setCRS(GeoConstants.CRS_WEB_MERCATOR);
        GeoPoint first = point(2, 2);
        hole.add(first);
        hole.add(point(3, 2));
        hole.add(point(2, 3));
        hole.add((GeoPoint) first.copy());
        source.addInnerRing(hole);

        GeoGeometry restored = GeoGeometryFactory.fromWKT(
                source.toWKT(true), GeoConstants.CRS_WEB_MERCATOR);

        assertTrue(restored instanceof GeoPolygon);
        GeoPolygon restoredPolygon = (GeoPolygon) restored;
        assertEquals(1, restoredPolygon.getInnerRingCount());
        assertEquals(4, restoredPolygon.getInnerRing(0).getPointCount());
        assertEquals(source.toWKT(true), restoredPolygon.toWKT(true));
    }

    @Test
    public void labelAnchorStaysInsideConcavePolygon() {
        Polygon polygon = Polygon.fromLngLats(Collections.singletonList(ring(
                0, 0, 6, 0, 6, 1, 1, 1, 1, 6, 0, 6, 0, 0)));
        JsonObject properties = new JsonObject();
        properties.addProperty("signature", "concave");

        Feature label = labelFor(polygon, properties);
        Point anchor = (Point) label.geometry();

        assertTrue((anchor.longitude() > 0 && anchor.longitude() < 1
                && anchor.latitude() > 0 && anchor.latitude() < 6)
                || (anchor.longitude() > 0 && anchor.longitude() < 6
                && anchor.latitude() > 0 && anchor.latitude() < 1));
        assertEquals("concave", label.properties().get("signature").getAsString());
    }

    @Test
    public void labelAnchorAvoidsPolygonHole() {
        Polygon polygon = Polygon.fromLngLats(Arrays.asList(
                ring(0, 0, 10, 0, 10, 10, 0, 10, 0, 0),
                ring(3, 3, 3, 7, 7, 7, 7, 3, 3, 3)));

        Point anchor = (Point) labelFor(polygon, new JsonObject()).geometry();

        assertTrue(anchor.longitude() > 0 && anchor.longitude() < 10);
        assertTrue(anchor.latitude() > 0 && anchor.latitude() < 10);
        assertTrue(anchor.longitude() <= 3 || anchor.longitude() >= 7
                || anchor.latitude() <= 3 || anchor.latitude() >= 7);
    }

    @Test
    public void labelAnchorUsesOnePartOfMultiPolygon() {
        MultiPolygon multiPolygon = MultiPolygon.fromLngLats(Arrays.asList(
                Collections.singletonList(ring(0, 0, 2, 0, 2, 2, 0, 2, 0, 0)),
                Collections.singletonList(ring(10, 10, 14, 10, 14, 14, 10, 14, 10, 10))));

        Point anchor = (Point) labelFor(multiPolygon, new JsonObject()).geometry();

        assertTrue(anchor.longitude() > 10 && anchor.longitude() < 14);
        assertTrue(anchor.latitude() > 10 && anchor.latitude() < 14);
    }

    @Test
    public void degeneratePolygonHasNoLabelAnchor() {
        Polygon polygon = Polygon.fromLngLats(Collections.singletonList(ring(
                0, 0, 1, 1, 2, 2, 0, 0)));

        assertTrue(MPLFeaturesUtils.convertToPointFeatures(
                Collections.singletonList(Feature.fromGeometry(polygon))).isEmpty());
    }

    private static Feature labelFor(Geometry geometry, JsonObject properties) {
        List<Feature> labels = MPLFeaturesUtils.convertToPointFeatures(
                Collections.singletonList(Feature.fromGeometry(geometry, properties)));
        assertEquals(1, labels.size());
        return labels.get(0);
    }

    private static List<Point> ring(double... positions) {
        List<Point> ring = new java.util.ArrayList<>();
        for (int i = 0; i < positions.length; i += 2) {
            ring.add(Point.fromLngLat(positions[i], positions[i + 1]));
        }
        return ring;
    }

    private static GeoPolygon polygon(boolean close) {
        GeoPoint first = point(0, 0);
        GeoLinearRing ring = new GeoLinearRing();
        ring.setCRS(GeoConstants.CRS_WEB_MERCATOR);
        ring.add(first);
        ring.add(point(10, 0));
        ring.add(point(0, 10));
        if (close) {
            ring.add((GeoPoint) first.copy());
        }
        GeoPolygon polygon = new GeoPolygon();
        polygon.setCRS(GeoConstants.CRS_WEB_MERCATOR);
        polygon.setOuterRing(ring);
        return polygon;
    }

    private static GeoPoint point(double x, double y) {
        GeoPoint point = new GeoPoint(x, y);
        point.setCRS(GeoConstants.CRS_WEB_MERCATOR);
        return point;
    }
}

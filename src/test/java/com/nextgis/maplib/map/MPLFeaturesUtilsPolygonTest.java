package com.nextgis.maplib.map;

import com.nextgis.maplib.datasource.GeoLinearRing;
import com.nextgis.maplib.datasource.GeoGeometry;
import com.nextgis.maplib.datasource.GeoGeometryFactory;
import com.nextgis.maplib.datasource.GeoPoint;
import com.nextgis.maplib.datasource.GeoPolygon;
import com.nextgis.maplib.util.GeoConstants;

import org.junit.Test;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.Point;
import org.maplibre.geojson.Polygon;

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
        GeoLinearRing restoredRing = ((GeoPolygon) restored).getOuterRing();
        assertEquals(2, restoredRing.getPointCount());
        assertEquals(restoredRing.getPoint(0), restoredRing.getPoint(1));

        Feature feature = MPLFeaturesUtils.getFeatureFromNGFeature(restored);
        List<Point> mapLibreRing = ((Polygon) feature.geometry()).coordinates().get(0);
        assertEquals(2, mapLibreRing.size());
        assertEquals(mapLibreRing.get(0), mapLibreRing.get(1));
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

package com.nextgis.maplib.map;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.nextgis.maplib.datasource.Feature;
import com.nextgis.maplib.datasource.GeoEnvelope;
import com.nextgis.maplib.datasource.GeoGeometry;
import com.nextgis.maplib.datasource.GeoPoint;
import com.nextgis.maplib.datasource.GeoPolygon;
import com.nextgis.maplib.display.SimpleMarkerStyle;

import org.junit.Test;

public class LocalVectorTileProviderTest {

    @Test
    public void clipFeatureToEnvelope_limitsLargePolygonToTileBuffer() {
        Feature feature = new Feature();
        feature.setId(7);
        feature.setGeometry(square(-100, -100, 100, 100));
        GeoEnvelope tileBuffer = new GeoEnvelope(0, 10, 0, 10);

        Feature clipped = LocalVectorTileProvider.clipFeatureToEnvelope(feature, tileBuffer);

        assertNotNull(clipped);
        GeoGeometry clippedGeometry = clipped.getGeometry();
        assertNotNull(clippedGeometry);
        assertTrue(tileBuffer.contains(clippedGeometry.getEnvelope()));
        assertTrue(feature.getGeometry().getEnvelope().width() > tileBuffer.width());
    }

    @Test
    public void clipFeatureToEnvelope_keepsOnlyPointsInsideTileBuffer() {
        GeoEnvelope tileBuffer = new GeoEnvelope(0, 10, 0, 10);
        Feature inside = pointFeature(7, 4, 6);
        Feature outside = pointFeature(8, 14, 6);

        Feature clippedInside =
                LocalVectorTileProvider.clipFeatureToEnvelope(inside, tileBuffer);

        assertNotNull(clippedInside);
        assertTrue(clippedInside.getGeometry() instanceof GeoPoint);
        assertNull(LocalVectorTileProvider.clipFeatureToEnvelope(outside, tileBuffer));
    }

    @Test
    public void simplePointSupport_requiresReadOnlyCircleWithoutIconOrRules() {
        assertTrue(LocalVectorTileProvider.isSupportedSimplePointStyle(
                SimpleMarkerStyle.MarkerStyleCircle, null, false, false, false));
        assertTrue(!LocalVectorTileProvider.isSupportedSimplePointStyle(
                SimpleMarkerStyle.MarkerStyleCircle, null, false, true, false));
        assertTrue(!LocalVectorTileProvider.isSupportedSimplePointStyle(
                SimpleMarkerStyle.MarkerStyleCircle, null, false, false, true));
        assertTrue(!LocalVectorTileProvider.isSupportedSimplePointStyle(
                SimpleMarkerStyle.MarkerStyleDiamond, null, false, false, false));
        assertTrue(!LocalVectorTileProvider.isSupportedSimplePointStyle(
                SimpleMarkerStyle.MarkerStyleCircle, "custom", false, false, false));
        assertTrue(!LocalVectorTileProvider.isSupportedSimplePointStyle(
                SimpleMarkerStyle.MarkerStyleCircle, null, true, false, false));
    }

    private static Feature pointFeature(long id, double x, double y) {
        Feature feature = new Feature();
        feature.setId(id);
        feature.setGeometry(new GeoPoint(x, y));
        return feature;
    }

    private static GeoPolygon square(double minX, double minY, double maxX, double maxY) {
        GeoPolygon polygon = new GeoPolygon();
        polygon.add(new GeoPoint(minX, minY));
        polygon.add(new GeoPoint(maxX, minY));
        polygon.add(new GeoPoint(maxX, maxY));
        polygon.add(new GeoPoint(minX, maxY));
        polygon.add(new GeoPoint(minX, minY));
        return polygon;
    }
}

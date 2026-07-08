package com.nextgis.maplib.map;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.nextgis.maplib.datasource.Feature;
import com.nextgis.maplib.datasource.GeoEnvelope;
import com.nextgis.maplib.datasource.GeoGeometry;
import com.nextgis.maplib.datasource.GeoPoint;
import com.nextgis.maplib.datasource.GeoPolygon;

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

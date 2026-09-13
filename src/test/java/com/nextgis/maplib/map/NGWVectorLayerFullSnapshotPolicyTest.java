package com.nextgis.maplib.map;

import com.nextgis.maplib.datasource.Feature;
import com.nextgis.maplib.datasource.GeoPoint;
import com.nextgis.maplib.datasource.GeoPolygon;
import com.nextgis.maplib.util.GeoConstants;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NGWVectorLayerFullSnapshotPolicyTest {
    private static final int CRS = GeoConstants.CRS_WEB_MERCATOR;

    @Test
    public void invalidGeometryIsSkippedButRemoteIdIsPreserved() {
        Feature feature = new Feature();
        feature.setId(703);
        feature.setGeometry(selfIntersectingPolygon());
        Set<Long> remoteIds = new HashSet<>();

        assertFalse(NGWVectorLayer.trackFullSnapshotFeatureAndCheckGeometry(
                feature, remoteIds));
        assertTrue(remoteIds.contains(703L));
    }

    @Test
    public void missingGeometryIsSkippedButRemoteIdIsPreserved() {
        Feature feature = new Feature();
        feature.setId(400);
        Set<Long> remoteIds = new HashSet<>();

        assertFalse(NGWVectorLayer.trackFullSnapshotFeatureAndCheckGeometry(
                feature, remoteIds));
        assertTrue(remoteIds.contains(400L));
    }

    @Test
    public void validGeometryIsAppliedAndRemoteIdIsPreserved() {
        Feature feature = new Feature();
        feature.setId(1322);
        feature.setGeometry(square());
        Set<Long> remoteIds = new HashSet<>();

        assertTrue(NGWVectorLayer.trackFullSnapshotFeatureAndCheckGeometry(
                feature, remoteIds));
        assertTrue(remoteIds.contains(1322L));
    }

    private static GeoPolygon selfIntersectingPolygon() {
        GeoPolygon polygon = new GeoPolygon();
        polygon.setCRS(CRS);
        addPoint(polygon, 0, 0);
        addPoint(polygon, 10, 10);
        addPoint(polygon, 0, 10);
        addPoint(polygon, 10, 0);
        addPoint(polygon, 0, 0);
        return polygon;
    }

    private static GeoPolygon square() {
        GeoPolygon polygon = new GeoPolygon();
        polygon.setCRS(CRS);
        addPoint(polygon, 0, 0);
        addPoint(polygon, 10, 0);
        addPoint(polygon, 10, 10);
        addPoint(polygon, 0, 10);
        addPoint(polygon, 0, 0);
        return polygon;
    }

    private static void addPoint(GeoPolygon polygon, double x, double y) {
        GeoPoint point = new GeoPoint(x, y);
        point.setCRS(CRS);
        polygon.add(point);
    }
}

package com.nextgis.maplib.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.nextgis.maplib.datasource.GeoLineString;
import com.nextgis.maplib.datasource.GeoPoint;
import com.nextgis.maplib.datasource.GeoPolygon;
import com.nextgis.maplib.map.MPLFeaturesUtils;

import org.junit.Test;

public class GeoGeometryUtilTest {

    @Test
    public void nullGeometry_returnsNull() {
        assertNull(GeoGeometryUtil.getWgs84RepresentativePoint(null));
    }

    @Test
    public void point_inWebMercator_convertsToWgs84() {
        double[] mercator = MPLFeaturesUtils.convert4326To3857(37.6, 55.75);
        GeoPoint point = new GeoPoint(mercator[0], mercator[1]);
        point.setCRS(GeoConstants.CRS_WEB_MERCATOR);

        double[] wgs = GeoGeometryUtil.getWgs84RepresentativePoint(point);

        assertNotNull(wgs);
        assertEquals(37.6, wgs[0], 0.01);
        assertEquals(55.75, wgs[1], 0.01);
    }

    @Test
    public void line_usesVertexAverage() {
        double[] a = MPLFeaturesUtils.convert4326To3857(37.0, 55.0);
        double[] b = MPLFeaturesUtils.convert4326To3857(38.0, 56.0);
        GeoLineString line = new GeoLineString();
        GeoPoint p1 = new GeoPoint(a[0], a[1]);
        p1.setCRS(GeoConstants.CRS_WEB_MERCATOR);
        GeoPoint p2 = new GeoPoint(b[0], b[1]);
        p2.setCRS(GeoConstants.CRS_WEB_MERCATOR);
        line.add(p1);
        line.add(p2);

        double[] wgs = GeoGeometryUtil.getWgs84RepresentativePoint(line);

        assertNotNull(wgs);
        assertEquals(37.5, wgs[0], 0.01);
        assertEquals(55.5, wgs[1], 0.01);
    }

    @Test
    public void polygon_usesOuterRingAverage() {
        GeoPolygon polygon = new GeoPolygon();
        double[][] corners = {
                MPLFeaturesUtils.convert4326To3857(37.0, 55.0),
                MPLFeaturesUtils.convert4326To3857(38.0, 55.0),
                MPLFeaturesUtils.convert4326To3857(38.0, 56.0),
                MPLFeaturesUtils.convert4326To3857(37.0, 56.0),
        };
        for (double[] corner : corners) {
            GeoPoint point = new GeoPoint(corner[0], corner[1]);
            point.setCRS(GeoConstants.CRS_WEB_MERCATOR);
            polygon.getOuterRing().add(point);
        }

        double[] wgs = GeoGeometryUtil.getWgs84RepresentativePoint(polygon);

        assertNotNull(wgs);
        assertEquals(37.5, wgs[0], 0.01);
        assertEquals(55.5, wgs[1], 0.01);
    }
}

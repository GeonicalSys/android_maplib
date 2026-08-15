package com.nextgis.maplib.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GeoJSONUtilCrsTest
{
    @Test
    public void acceptsCommonWgs84Names() throws Exception
    {
        assertTrue(GeoJSONUtil.checkCRSSupportAndWGS("EPSG:4326", null));
        assertTrue(GeoJSONUtil.checkCRSSupportAndWGS("urn:ogc:def:crs:EPSG::4326", null));
        assertTrue(GeoJSONUtil.checkCRSSupportAndWGS("urn:ogc:def:crs:EPSG:6.6:4326", null));
        assertTrue(GeoJSONUtil.checkCRSSupportAndWGS(
                "http://www.opengis.net/def/crs/EPSG/0/4326", null));
        assertTrue(GeoJSONUtil.checkCRSSupportAndWGS(
                " urn:ogc:def:crs:ogc:1.3:crs84 ", null));
    }

    @Test
    public void acceptsCommonWebMercatorNames() throws Exception
    {
        assertFalse(GeoJSONUtil.checkCRSSupportAndWGS("EPSG:3857", null));
        assertFalse(GeoJSONUtil.checkCRSSupportAndWGS("urn:ogc:def:crs:EPSG::3857", null));
        assertFalse(GeoJSONUtil.checkCRSSupportAndWGS(
                "http://www.opengis.net/def/crs/EPSG/0/3857", null));
    }

}

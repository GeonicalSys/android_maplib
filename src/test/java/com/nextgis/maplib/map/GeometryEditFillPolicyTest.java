package com.nextgis.maplib.map;

import com.nextgis.maplib.util.GeoConstants;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GeometryEditFillPolicyTest {
    @Test
    public void showsFillOnlyForPolygonTypes() {
        assertTrue(GeometryEditFillPolicy.shouldShow(GeoConstants.GTPolygon));
        assertTrue(GeometryEditFillPolicy.shouldShow(GeoConstants.GTMultiPolygon));

        assertFalse(GeometryEditFillPolicy.shouldShow(GeoConstants.GTLineString));
        assertFalse(GeometryEditFillPolicy.shouldShow(GeoConstants.GTMultiLineString));
        assertFalse(GeometryEditFillPolicy.shouldShow(GeoConstants.GTPoint));
        assertFalse(GeometryEditFillPolicy.shouldShow(GeoConstants.GTMultiPoint));
    }
}

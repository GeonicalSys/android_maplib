package com.nextgis.maplib.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UnderlayRasterZoomPolicyTest {
    @Test
    public void enabledUnderlaysStartAtSeven() {
        assertEquals(7, UnderlayRasterZoomPolicy.sourceMinZoom());
        assertEquals(7f, UnderlayRasterZoomPolicy.rasterMinZoom(), 0);
    }

    @Test
    public void boundsUnderzoomComposition() {
        assertTrue(UnderlayRasterZoomPolicy.canComposeUnderzoom(7, 11));
        assertFalse(UnderlayRasterZoomPolicy.canComposeUnderzoom(7, 12));
        assertFalse(UnderlayRasterZoomPolicy.canComposeUnderzoom(7, 7));
    }

    @Test
    public void convertsXyzRowsToMbtilesTms() {
        assertEquals(3L, UnderlayRasterZoomPolicy.databaseRow(3, 4, false));
        assertEquals(4L, UnderlayRasterZoomPolicy.databaseRow(3, 4, true));
    }
}

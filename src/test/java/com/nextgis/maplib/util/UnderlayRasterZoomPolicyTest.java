package com.nextgis.maplib.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class UnderlayRasterZoomPolicyTest {
    @Test
    public void pyramidIgnoresPaddedVisibility() {
        assertEquals(16, UnderlayRasterZoomPolicy.pyramidZoom(16, 18));
        assertEquals(7, UnderlayRasterZoomPolicy.pyramidZoom(7, 5));
        assertEquals(18, UnderlayRasterZoomPolicy.pyramidZoom(-1, 18));
    }

    @Test
    public void holesStayInsidePyramid() {
        assertTrue(UnderlayRasterZoomPolicy.shouldFillHole(15, 7, 16));
        assertTrue(UnderlayRasterZoomPolicy.shouldFillHole(16, 7, 16));
        assertTrue(UnderlayRasterZoomPolicy.shouldFillHole(7, 7, 16));
        assertFalse(UnderlayRasterZoomPolicy.shouldFillHole(17, 7, 16));
        assertFalse(UnderlayRasterZoomPolicy.shouldFillHole(6, 7, 16));
    }

    @Test
    public void lastLevelBindsTileSetToPyramidOnly() {
        assertEquals(Integer.valueOf(7), UnderlayRasterZoomPolicy.tileSetMinZoom(true, 7));
        assertEquals(Integer.valueOf(16), UnderlayRasterZoomPolicy.tileSetMaxZoom(true, 16));
        assertNull(UnderlayRasterZoomPolicy.tileSetMinZoom(false, 7));
        assertNull(UnderlayRasterZoomPolicy.tileSetMaxZoom(false, 16));
    }

    @Test
    public void lastLevelRaisesRasterLayerMaxAndDropsPadding() {
        assertEquals(7f, UnderlayRasterZoomPolicy.rasterLayerMinZoom(true, 7, 5f), 0);
        assertEquals(
                (float) GeoConstants.DEFAULT_MAX_ZOOM,
                UnderlayRasterZoomPolicy.rasterLayerMaxZoom(true, 16, 18f),
                0);
        assertEquals(5f, UnderlayRasterZoomPolicy.rasterLayerMinZoom(false, 7, 5f), 0);
        assertEquals(19f, UnderlayRasterZoomPolicy.rasterLayerMaxZoom(false, 16, 18f), 0);
    }
}

package com.nextgis.maplib.datasource.ngw;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CollectorProjectCompositionSyncTest {
    @Test
    public void acceptsOnlyStyleClassesAlreadySupportedByConnection() {
        assertTrue(CollectorProjectCompositionSync.isSupportedRasterStyle(
                "qgis_vector_style"));
        assertTrue(CollectorProjectCompositionSync.isSupportedRasterStyle(
                "qgis_raster_style"));

        assertFalse(CollectorProjectCompositionSync.isSupportedRasterStyle(
                "mapserver_style"));
        assertFalse(CollectorProjectCompositionSync.isSupportedRasterStyle(
                "sld_style"));
        assertFalse(CollectorProjectCompositionSync.isSupportedRasterStyle(
                "vector_layer"));
    }
}

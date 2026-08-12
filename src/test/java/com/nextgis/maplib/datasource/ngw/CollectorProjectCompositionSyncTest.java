package com.nextgis.maplib.datasource.ngw;

import static org.junit.Assert.assertEquals;
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

    @Test
    public void addsOnlyWhenNoPhysicalIdentityExists() {
        assertEquals(
                CollectorProjectCompositionSync.LocalIdentityAction.ADD,
                CollectorProjectCompositionSync.decideLocalIdentityAction(
                        0, 0, false, false));
    }

    @Test
    public void repairsOnlyUnownedSinglePhysicalMatch() {
        assertEquals(
                CollectorProjectCompositionSync.LocalIdentityAction.REPAIR_ORIGIN,
                CollectorProjectCompositionSync.decideLocalIdentityAction(
                        0, 1, true, true));
        assertEquals(
                CollectorProjectCompositionSync.LocalIdentityAction.BLOCK,
                CollectorProjectCompositionSync.decideLocalIdentityAction(
                        0, 1, true, false));
    }

    @Test
    public void blocksAmbiguousOrWrongKindIdentity() {
        assertEquals(
                CollectorProjectCompositionSync.LocalIdentityAction.BLOCK,
                CollectorProjectCompositionSync.decideLocalIdentityAction(
                        1, 2, true, false));
        assertEquals(
                CollectorProjectCompositionSync.LocalIdentityAction.BLOCK,
                CollectorProjectCompositionSync.decideLocalIdentityAction(
                        2, 2, true, false));
        assertEquals(
                CollectorProjectCompositionSync.LocalIdentityAction.BLOCK,
                CollectorProjectCompositionSync.decideLocalIdentityAction(
                        1, 1, false, false));
    }

    @Test
    public void usesExactlyOneManagedPhysicalMatch() {
        assertEquals(
                CollectorProjectCompositionSync.LocalIdentityAction.USE_MANAGED,
                CollectorProjectCompositionSync.decideLocalIdentityAction(
                        1, 1, true, false));
    }
}

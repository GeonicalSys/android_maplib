package com.nextgis.maplib.map;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LayerVisibilityReloadPolicyTest {
    @Test
    public void reloadsWhenPreparedCacheIsStaleButLiveStyleIsMissing() {
        assertTrue(LayerVisibilityReloadPolicy.shouldReload(
                true,
                false,
                false,
                true));
    }

    @Test
    public void reloadsWhenSourceOrStyleLayerIsMissing() {
        assertTrue(LayerVisibilityReloadPolicy.shouldReload(
                true,
                false,
                true,
                true));
        assertTrue(LayerVisibilityReloadPolicy.shouldReload(
                true,
                true,
                false,
                true));
    }

    @Test
    public void doesNotReloadHiddenOrCompleteLayer() {
        assertFalse(LayerVisibilityReloadPolicy.shouldReload(
                false,
                false,
                false,
                true));
        assertFalse(LayerVisibilityReloadPolicy.shouldReload(
                true,
                true,
                true,
                true));
    }
}

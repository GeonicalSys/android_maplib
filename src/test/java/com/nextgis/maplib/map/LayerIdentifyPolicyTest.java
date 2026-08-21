package com.nextgis.maplib.map;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LayerIdentifyPolicyTest {
    @Test
    public void visibleClassicLayerIsIncluded() {
        assertTrue(LayerIdentifyPolicy.shouldInclude(true, false));
    }

    @Test
    public void hiddenClassicLayerIsExcluded() {
        assertFalse(LayerIdentifyPolicy.shouldInclude(false, false));
    }

    @Test
    public void hiddenLocalVectorTileLayerIsIncluded() {
        assertTrue(LayerIdentifyPolicy.shouldInclude(false, true));
    }
}

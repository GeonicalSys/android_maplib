package com.nextgis.maplib.map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NGWVectorLayerPolicyTest {
    @Test
    public void managed404IsNotConvertedToLocalLayer() {
        LayerOriginMetadata origin =
                LayerOriginMetadata.collectorLayer("project-uid", 0, 0L);

        assertFalse(NGWVectorLayer.shouldConvertMissingNgwLayerToLocal(origin));
    }

    @Test
    public void nonManaged404KeepsLegacyConversionPolicy() {
        assertTrue(NGWVectorLayer.shouldConvertMissingNgwLayerToLocal(null));
        assertTrue(NGWVectorLayer.shouldConvertMissingNgwLayerToLocal(
                LayerOriginMetadata.manualNgwLayer(0L)));
    }
}

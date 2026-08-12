package com.nextgis.maplib.map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LayerEditingPolicyTest {

    @Test
    public void collectorManaged_usesCollectorPolicyInsteadOfGenericEditableFlag() {
        assertTrue(LayerEditingPolicy.isEditingAllowed(
                false, true, true, true));
    }

    @Test
    public void collectorManaged_requiresCollectorEditableAndOutboundSync() {
        assertFalse(LayerEditingPolicy.isEditingAllowed(
                true, false, true, true));
        assertFalse(LayerEditingPolicy.isEditingAllowed(
                true, true, true, false));
    }

    @Test
    public void ordinaryLayer_keepsGenericEditableGate() {
        assertFalse(LayerEditingPolicy.isEditingAllowed(
                false, true, false, true));
        assertTrue(LayerEditingPolicy.isEditingAllowed(
                true, true, false, true));
    }
}

package com.nextgis.maplib.map;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CollectorLayerOrderPolicyTest {

    @Test
    public void initialCollectorLayer_isInsertedBelowTracks() {
        assertEquals(0, CollectorLayerOrderPolicy.keepBelowTracks(1, 0));
    }

    @Test
    public void collectorOrderBelowTracks_isPreserved() {
        assertEquals(2, CollectorLayerOrderPolicy.keepBelowTracks(2, 4));
    }

    @Test
    public void groupWithoutTracks_keepsComputedIndex() {
        assertEquals(3, CollectorLayerOrderPolicy.keepBelowTracks(3, -1));
    }
}

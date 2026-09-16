package com.nextgis.maplib.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NgwSyncNoneReloadDecisionTest {

    @Test
    public void unknownServerCount_keepsLocal() {
        assertEquals(NgwSyncNoneReloadDecision.Action.KEEP,
                NgwSyncNoneReloadDecision.decide(12, NgwFeatureCountParser.UNKNOWN));
        assertEquals(NgwSyncNoneReloadDecision.Action.KEEP,
                NgwSyncNoneReloadDecision.decide(12, NgwFeatureCountParser.NEED_FALLBACK));
    }

    @Test
    public void serverZero_keepsLocalEvenIfLocalHasRows() {
        assertEquals(NgwSyncNoneReloadDecision.Action.KEEP,
                NgwSyncNoneReloadDecision.decide(12, 0));
        assertEquals(NgwSyncNoneReloadDecision.Action.KEEP,
                NgwSyncNoneReloadDecision.decide(0, 0));
    }

    @Test
    public void equalPositiveCounts_keep() {
        assertEquals(NgwSyncNoneReloadDecision.Action.KEEP,
                NgwSyncNoneReloadDecision.decide(7, 7));
    }

    @Test
    public void mismatchWithPositiveServer_reloads() {
        assertEquals(NgwSyncNoneReloadDecision.Action.RELOAD,
                NgwSyncNoneReloadDecision.decide(3, 7));
        assertEquals(NgwSyncNoneReloadDecision.Action.RELOAD,
                NgwSyncNoneReloadDecision.decide(7, 3));
        assertEquals(NgwSyncNoneReloadDecision.Action.RELOAD,
                NgwSyncNoneReloadDecision.decide(0, 5));
    }

    @Test
    public void missingLocalTable_reloadsWhenServerHasFeatures() {
        assertEquals(NgwSyncNoneReloadDecision.Action.RELOAD,
                NgwSyncNoneReloadDecision.decide(-1, 5));
    }
}

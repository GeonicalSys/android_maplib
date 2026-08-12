package com.nextgis.maplib.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.HashMap;

public class NgwPullDecisionTest {

    @Test
    public void nullResult_isFailure() {
        assertEquals(NgwPullDecision.Action.ABORT_FAILED, NgwPullDecision.decide(null));
    }

    @Test
    public void code404_aborts() {
        ExistFeatureResult r = new ExistFeatureResult(null, false, 404);
        assertEquals(NgwPullDecision.Action.ABORT_404, NgwPullDecision.decide(r));
    }

    @Test
    public void ioFailure_nullObjectFalseResult_isFailureNotEmpty() {
        // getFeatures() returns (null, false, 0) on IOException/NGException/OOM. This must abort,
        // not be mistaken for a legitimately empty pull.
        ExistFeatureResult r = new ExistFeatureResult(null, false, 0);
        assertEquals(NgwPullDecision.Action.ABORT_FAILED, NgwPullDecision.decide(r));
    }

    @Test
    public void emptySuccess_nullObjectTrueResult_isEmptyOk() {
        ExistFeatureResult r = new ExistFeatureResult(null, true, 200);
        assertEquals(NgwPullDecision.Action.EMPTY_OK, NgwPullDecision.decide(r));
    }

    @Test
    public void payloadSuccess_proceeds() {
        HashMap<Integer, Object> payload = new HashMap<>();
        ExistFeatureResult r = new ExistFeatureResult(payload, true, 200);
        assertEquals(NgwPullDecision.Action.PROCEED, NgwPullDecision.decide(r));
    }
}

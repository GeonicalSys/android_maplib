/*
 * Project:  NextGIS Mobile
 * Purpose:  Pure decision for NGW pull (getFeatures) results, extracted for unit testing.
 */

package com.nextgis.maplib.util;

/**
 * Decides how {@code NGWVectorLayer.getChangesFromServer} should react to a {@code getFeatures()}
 * result, kept as a pure function so the success / empty / failure distinction can be unit tested
 * (the real method is Android/network coupled).
 *
 * <p>Critical contract: a failed pull (I/O / parse / OOM / connect) must NOT be treated as success.
 * Otherwise the layer would push local changes and advance its tracked timestamp as if the pull
 * succeeded, permanently skipping server deltas.
 */
public final class NgwPullDecision {

    public enum Action {
        /** Server returned 404 — resource gone; stop and clear layer sync. */
        ABORT_404,
        /** Pull failed (result == null, or success flag false) — stop, keep local changes, retry later. */
        ABORT_FAILED,
        /** Pull succeeded but there are no objects — nothing to apply, treat as success. */
        EMPTY_OK,
        /** Pull succeeded with a payload — proceed to apply changes. */
        PROCEED
    }

    private NgwPullDecision() {
    }

    public static Action decide(ExistFeatureResult result) {
        if (result == null) {
            return Action.ABORT_FAILED;
        }
        if (result.code == 404) {
            return Action.ABORT_404;
        }
        if (!result.result) {
            return Action.ABORT_FAILED;
        }
        if (result.object == null) {
            return Action.EMPTY_OK;
        }
        return Action.PROCEED;
    }
}

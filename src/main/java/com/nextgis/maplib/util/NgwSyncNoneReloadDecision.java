/*
 * Project:  NextGIS Mobile
 * Purpose:  Pure SYNC_NONE local-vs-server count reload policy for unit testing.
 */

package com.nextgis.maplib.util;

/**
 * Decides whether a {@code SYNC_NONE} NGW vector layer should replace its local table
 * with a full server snapshot after comparing feature counts.
 *
 * <p>Server count {@code <= 0} keeps local data: {@code 0} is the product rule (do not
 * wipe when the server looks empty), and negative values mean the count could not be
 * obtained. A positive server count that differs from the local SQLite row count
 * (including a missing local table, reported as {@code -1}) triggers a reload.
 */
public final class NgwSyncNoneReloadDecision {

    public enum Action {
        KEEP,
        RELOAD
    }

    private NgwSyncNoneReloadDecision() {
    }

    public static Action decide(int localCount, int serverCount) {
        if (serverCount <= 0) {
            return Action.KEEP;
        }
        if (localCount != serverCount) {
            return Action.RELOAD;
        }
        return Action.KEEP;
    }
}

package com.nextgis.maplib.util;

/** One active refresh and at most one follow-up, irrespective of event frequency. */
public final class CoalescingRefresh {
    private boolean running;
    private boolean pending;

    public synchronized boolean request() {
        if (running) {
            pending = true;
            return false;
        }
        running = true;
        return true;
    }

    /** Releases the active slot; the caller must request again if true is returned. */
    public synchronized boolean complete() {
        boolean again = pending;
        running = false;
        pending = false;
        return again;
    }
}

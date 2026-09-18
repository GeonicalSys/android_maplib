package com.nextgis.maplib.util;

import com.hypertrack.hyperlog.HyperLog;
import java.util.UUID;

/** Bounded, payload-free stage diagnostics for a single layer snapshot attempt. */
public final class NgwSyncTrace {
    private final String identity;
    private final long started = System.nanoTime();
    private long lastProgress;
    private String stage = "start";

    public NgwSyncTrace(long remoteId) {
        identity = "NGW snapshot attempt=" + UUID.randomUUID() + " remoteId=" + remoteId;
    }

    public void stage(String next) {
        stage = next;
        lastProgress = System.nanoTime();
        log("begin");
    }

    public void progress(long completed, String unit) {
        long now = System.nanoTime();
        if (now - lastProgress >= 10_000_000_000L) {
            lastProgress = now;
            log("completed=" + completed + " unit=" + unit);
        }
    }

    public void finish(boolean committed) {
        log("finished committed=" + committed);
    }

    private void log(String detail) {
        HyperLog.i(Constants.TAG, identity + " stage=" + stage
                + " elapsedMs=" + (System.nanoTime() - started) / 1_000_000L + " " + detail);
    }
}

package com.nextgis.maplib.location;

/** Monotonic, bounded recovery diagnostics; expected permission/provider-off states are quiet. */
public final class GpsRecoveryPolicy {
    public static final long SLOW_RECOVERY_MS = 30_000L;
    public static final long REACQUISITION_MS = 15_000L;
    public static final long REPORT_INTERVAL_MS = 600_000L;
    private long waitingSince = -1L;
    private long lastReportAt = -1L;
    private boolean reported;
    private boolean hadFreshFix;

    public boolean shouldReport(long now, boolean requested, boolean allowed, boolean fresh) {
        if (fresh) hadFreshFix = true;
        if (!requested || !allowed || fresh) {
            waitingSince = -1L;
            reported = false;
            return false;
        }
        if (waitingSince < 0L) waitingSince = now;
        if (reported || now - waitingSince < thresholdMs()
                || (lastReportAt >= 0L && now - lastReportAt < REPORT_INTERVAL_MS)) return false;
        reported = true;
        lastReportAt = now;
        return true;
    }

    public long waitingMs(long now) { return waitingSince < 0L ? 0L : Math.max(0L, now - waitingSince); }
    public long thresholdMs() { return hadFreshFix ? REACQUISITION_MS : SLOW_RECOVERY_MS; }
}

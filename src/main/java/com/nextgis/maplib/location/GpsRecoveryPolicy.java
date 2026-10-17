package com.nextgis.maplib.location;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Monotonic episode diagnostics. A missing satellite solution is not a stalled callback channel. */
public final class GpsRecoveryPolicy {
    public static final long SLOW_RECOVERY_MS = 30_000L;
    public static final long REACQUISITION_MS = 15_000L;
    public static final long LONG_GAP_MS = 60_000L;
    public static final long REPORT_INTERVAL_MS = 600_000L;
    public static final long RETRY_DELAY_MS = 30_000L;
    public static final long STATUS_FRESH_MS = 5_000L;
    public static final long HISTORY_INTERVAL_MS = 5_000L;
    public static final int HISTORY_LIMIT = 24;

    public enum Reason {
        NO_FIX, CALLBACK_STALLED, PROVIDER_SILENT, STALE_FIX, INVALID_FIX, FILTER_WAIT,
        DISPLAY_PROCESSING, ACQUISITION;
        public String wireName() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    public static Reason classify(boolean rawFresh, boolean displayFresh, long callbackAgeMs,
            long statusAgeMs, int satellitesUsed, String decision) {
        if (rawFresh && !displayFresh)
            return decision != null && decision.startsWith("hold:") ? Reason.FILTER_WAIT : Reason.DISPLAY_PROCESSING;
        if (callbackAgeMs >= 0 && callbackAgeMs <= 8_000L) {
            if ("invalid".equals(decision)) return Reason.INVALID_FIX;
            if ("stale".equals(decision) || "duplicate".equals(decision)) return Reason.STALE_FIX;
        }
        if (statusAgeMs >= 0 && statusAgeMs <= STATUS_FRESH_MS) {
            if (satellitesUsed == 0) return Reason.NO_FIX;
            if (callbackAgeMs < 0 || callbackAgeMs >= REACQUISITION_MS) return Reason.CALLBACK_STALLED;
        }
        if (callbackAgeMs >= REACQUISITION_MS
                && (statusAgeMs < 0 || statusAgeMs >= REACQUISITION_MS)) return Reason.PROVIDER_SILENT;
        return Reason.ACQUISITION;
    }

    public static final class Update {
        public final String phase, episodeId;
        public final Reason reason;
        public final long waitingMs, thresholdMs;
        public final int recurringGaps;
        public final boolean report, retry, retryAttempted;
        private Update(String phase, String id, Reason reason, long waiting, long threshold,
                int recurring, boolean report, boolean retry, boolean retryAttempted) {
            this.phase = phase; episodeId = id; this.reason = reason; waitingMs = waiting;
            thresholdMs = threshold; recurringGaps = recurring; this.report = report; this.retry = retry;
            this.retryAttempted = retryAttempted;
        }
    }

    private long waitingSince = -1L, lastReportAt = -1L, lastRetryAt = -1L, reasonSince = -1L;
    private boolean delayed, reported, retried, hadFreshFix;
    private String episodeId;
    private Reason reason, reportedReason;
    private final ArrayDeque<Long> slowGaps = new ArrayDeque<>();
    private final ArrayDeque<String> history = new ArrayDeque<>();
    private long lastHistoryAt = -1L;

    public Update update(long now, boolean requested, boolean allowed, boolean fresh, Reason currentReason) {
        while (!slowGaps.isEmpty() && now - slowGaps.peekFirst() >= REPORT_INTERVAL_MS) slowGaps.removeFirst();
        if (!requested || !allowed || fresh) {
            Update end = waitingSince >= 0 && delayed
                    ? result(requested && allowed && fresh ? "recovered" : "cancelled", now, reported, false) : null;
            waitingSince = reasonSince = -1L;
            delayed = reported = retried = false;
            reason = null;
            reportedReason = null;
            hadFreshFix = requested && allowed && fresh;
            return end;
        }
        if (waitingSince < 0L) {
            waitingSince = now;
            episodeId = UUID.randomUUID().toString();
        }
        if (reason != currentReason) { reason = currentReason; reasonSince = now; }
        boolean firstDelay = !delayed && now - waitingSince >= thresholdMs();
        if (firstDelay) { delayed = true; slowGaps.addLast(now); }
        boolean actionable = reason == Reason.CALLBACK_STALLED || reason == Reason.DISPLAY_PROCESSING
                || reason == Reason.STALE_FIX || reason == Reason.INVALID_FIX;
        boolean send = delayed && !reported && (actionable || now - waitingSince >= LONG_GAP_MS || slowGaps.size() >= 3)
                && (lastReportAt < 0 || now - lastReportAt >= REPORT_INTERVAL_MS);
        if (send) { reported = true; reportedReason = reason; lastReportAt = now; }
        boolean retry = reason == Reason.CALLBACK_STALLED && now - reasonSince >= RETRY_DELAY_MS && !retried
                && (lastRetryAt < 0 || now - lastRetryAt >= REPORT_INTERVAL_MS);
        if (retry) { retried = true; lastRetryAt = now; }
        return firstDelay || send || retry
                ? result(retry ? "retry" : "delayed", now, send, retry) : null;
    }

    private Update result(String phase, long now, boolean send, boolean retry) {
        Reason groupedReason = ("recovered".equals(phase) || "cancelled".equals(phase)) && reportedReason != null
                ? reportedReason : reason;
        return new Update(phase, episodeId, groupedReason, Math.max(0L, now - waitingSince),
                thresholdMs(), slowGaps.size(), send, retry, retried);
    }

    public long thresholdMs() { return hadFreshFix ? REACQUISITION_MS : SLOW_RECOVERY_MS; }
    public boolean historyDue(long now) { return lastHistoryAt < 0 || now - lastHistoryAt >= HISTORY_INTERVAL_MS; }
    public void recordHistory(long now, String sample) {
        if (!historyDue(now)) return;
        lastHistoryAt = now;
        if (history.size() == HISTORY_LIMIT) history.removeFirst();
        history.addLast(sample);
    }
    public List<String> history() { return new ArrayList<>(history); }
    public void clearHistory() { history.clear(); lastHistoryAt = -1L; }
}

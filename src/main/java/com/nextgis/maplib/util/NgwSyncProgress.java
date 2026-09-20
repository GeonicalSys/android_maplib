package com.nextgis.maplib.util;

import android.content.Context;
import android.content.Intent;

/**
 * Session-scoped sync progress for the drawer ring and optional notifications.
 * Layer weights are known before work; intra-layer fractions use real push/download/apply
 * counts and never invent remaining bytes.
 */
public final class NgwSyncProgress {
    public static final String SYNC_PROGRESS = "com.nextgis.maplib.sync_progress";
    public static final String EXTRA_DONE = "done";
    public static final String EXTRA_TOTAL = "total";
    public static final String EXTRA_DETERMINATE = "determinate";
    public static final String EXTRA_ACTIVE = "active";

    public static final int PULL_UNITS = 10;
    public static final int MAX_PUSH_UNITS = 30;
    public static final int SCALE = 1000;
    public static final double FINISH_RESERVE = 0.05;

    static final long MIN_PUBLISH_INTERVAL_NS = 250_000_000L;
    static final int MIN_PUBLISH_PERMILLE = 10;

    public interface Clock {
        long nanoTime();
    }

    public interface Listener {
        void onProgress(Snapshot snapshot);
    }

    public static final class Snapshot {
        public final boolean active;
        public final boolean determinate;
        public final int done;
        public final int total;

        Snapshot(boolean active, boolean determinate, int done, int total) {
            this.active = active;
            this.determinate = determinate;
            this.done = done;
            this.total = total;
        }
    }

    private static final Object LOCK = new Object();
    private static final Clock SYSTEM_CLOCK = new Clock() {
        @Override
        public long nanoTime() {
            return System.nanoTime();
        }
    };

    private static Clock clock = SYSTEM_CLOCK;
    private static Listener listener;
    private static Context appContext;

    private static boolean active;
    private static boolean finished;
    private static int accountCount;
    private static int accountsStarted;
    private static int accountsFinished;
    private static int discoveredUnits;
    private static int upcomingCount;
    private static int completedUnits;
    private static boolean currentOpen;
    private static int currentWeight;
    private static int currentPushUnits;
    private static int pushSent;
    private static int pushTotal;
    private static double tusFraction;
    private static boolean downloadComplete;
    private static long downloadReceived;
    private static long downloadTotal;
    private static int applyProcessed;
    private static int applyTotal;
    private static double displayed;
    private static long lastPublishNanos;
    private static int lastPublishedDone = -1;
    private static boolean lastPublishedDeterminate;
    private static boolean lastPublishedActive;
    private static Snapshot lastSnapshot = new Snapshot(false, false, 0, SCALE);

    private NgwSyncProgress() { }

    public static int layerWeight(int pendingChanges) {
        int push = Math.min(Math.max(pendingChanges, 0), MAX_PUSH_UNITS);
        return PULL_UNITS + push;
    }

    public static void beginSession(Context context, int accountCount) {
        synchronized (LOCK) {
            resetLocked();
            active = true;
            finished = false;
            NgwSyncProgress.accountCount = Math.max(1, accountCount);
            if (context != null) {
                appContext = context.getApplicationContext();
            }
            displayed = 0d;
            publishLocked(true);
        }
    }

    /**
     * Starts a one-account session when periodic/adapter sync is not already inside a
     * multi-account manual session.
     *
     * @return {@code true} when this call created the session and must finish it
     */
    public static boolean ensureSession(Context context, int accountCount) {
        synchronized (LOCK) {
            if (active && !finished) {
                if (context != null && appContext == null) {
                    appContext = context.getApplicationContext();
                }
                return false;
            }
        }
        beginSession(context, accountCount);
        return true;
    }

    /**
     * Marks the current account as registered. Call after {@link #addUpcomingLayer} for every
     * leaf of this account so remaining-account estimates do not drop the current work.
     */
    public static void beginAccount() {
        synchronized (LOCK) {
            if (!active || finished) {
                return;
            }
            accountsStarted++;
            publishLocked(true);
        }
    }

    public static void addUpcomingLayer(int pendingChanges) {
        synchronized (LOCK) {
            if (!active || finished) {
                return;
            }
            discoveredUnits += layerWeight(pendingChanges);
            upcomingCount++;
            publishLocked(false);
        }
    }

    public static void beginLayer(int pendingChanges) {
        beginLayerInternal(pendingChanges, true);
    }

    public static void resumeLayer(int pendingChanges) {
        beginLayerInternal(pendingChanges, false);
    }

    public static void reportPush(int sent, int total) {
        synchronized (LOCK) {
            if (!currentOpen) {
                return;
            }
            pushTotal = Math.max(0, total);
            pushSent = clampInt(sent, 0, pushTotal > 0 ? pushTotal : sent);
            tusFraction = 0d;
            publishLocked(false);
        }
    }

    public static void reportTus(long offset, long size) {
        synchronized (LOCK) {
            if (!currentOpen) {
                return;
            }
            if (size > 0L) {
                tusFraction = clamp01((double) offset / (double) size);
            } else {
                tusFraction = 0d;
            }
            publishLocked(false);
        }
    }

    public static void reportDownload(long received, long contentLength) {
        synchronized (LOCK) {
            if (!currentOpen) {
                return;
            }
            downloadReceived = Math.max(0L, received);
            downloadTotal = contentLength;
            publishLocked(false);
        }
    }

    public static void reportDownloadComplete() {
        synchronized (LOCK) {
            if (!currentOpen) {
                return;
            }
            downloadComplete = true;
            publishLocked(false);
        }
    }

    public static void reportApply(int processed, int total) {
        synchronized (LOCK) {
            if (!currentOpen) {
                return;
            }
            applyTotal = Math.max(0, total);
            applyProcessed = clampInt(processed, 0, applyTotal > 0 ? applyTotal : processed);
            publishLocked(false);
        }
    }

    public static void deferCurrentLayer() {
        synchronized (LOCK) {
            if (!currentOpen) {
                return;
            }
            closeCurrentLocked();
            publishLocked(true);
        }
    }

    public static void completeLayer() {
        synchronized (LOCK) {
            if (!currentOpen) {
                return;
            }
            completedUnits += currentWeight;
            closeCurrentLocked();
            publishLocked(true);
        }
    }

    public static void finishAccount() {
        synchronized (LOCK) {
            if (!active) {
                return;
            }
            if (currentOpen) {
                if (!NgwSyncIo.isCancellationRequested()) {
                    completedUnits += currentWeight;
                }
                closeCurrentLocked();
            }
            accountsFinished++;
            publishLocked(true);
        }
    }

    public static void finishSession() {
        synchronized (LOCK) {
            if (!active) {
                return;
            }
            finishSessionLocked();
        }
    }

    public static void cancel() {
        synchronized (LOCK) {
            active = false;
            finished = false;
            closeCurrentLocked();
            publishLocked(true);
            resetAfterInactiveLocked();
        }
    }

    public static Snapshot snapshot() {
        synchronized (LOCK) {
            return lastSnapshot;
        }
    }

    static void setClockForTest(Clock testClock) {
        synchronized (LOCK) {
            clock = testClock == null ? SYSTEM_CLOCK : testClock;
        }
    }

    static void setListenerForTest(Listener testListener) {
        synchronized (LOCK) {
            listener = testListener;
        }
    }

    static void resetForTest() {
        synchronized (LOCK) {
            resetLocked();
            clock = SYSTEM_CLOCK;
            listener = null;
            appContext = null;
        }
    }

    private static void beginLayerInternal(int pendingChanges, boolean addIfMissing) {
        synchronized (LOCK) {
            if (!active || finished) {
                return;
            }
            if (currentOpen) {
                completedUnits += currentWeight;
                closeCurrentLocked();
            }
            currentWeight = layerWeight(pendingChanges);
            currentPushUnits = Math.min(Math.max(pendingChanges, 0), MAX_PUSH_UNITS);
            if (upcomingCount > 0) {
                upcomingCount--;
            } else if (addIfMissing) {
                discoveredUnits += currentWeight;
            }
            currentOpen = true;
            pushSent = 0;
            pushTotal = currentPushUnits > 0 ? Math.max(pendingChanges, 0) : 0;
            tusFraction = 0d;
            downloadComplete = false;
            downloadReceived = 0L;
            downloadTotal = -1L;
            applyProcessed = 0;
            applyTotal = 0;
            publishLocked(true);
        }
    }

    private static void finishSessionLocked() {
        finished = true;
        active = false;
        if (currentOpen) {
            completedUnits += currentWeight;
            closeCurrentLocked();
        }
        displayed = 1d;
        publishLocked(true);
        resetAfterInactiveLocked();
    }

    private static void closeCurrentLocked() {
        currentOpen = false;
        currentWeight = 0;
        currentPushUnits = 0;
        pushSent = 0;
        pushTotal = 0;
        tusFraction = 0d;
        downloadComplete = false;
        downloadReceived = 0L;
        downloadTotal = -1L;
        applyProcessed = 0;
        applyTotal = 0;
    }

    private static void resetLocked() {
        active = false;
        finished = false;
        accountCount = 1;
        accountsStarted = 0;
        accountsFinished = 0;
        discoveredUnits = 0;
        upcomingCount = 0;
        completedUnits = 0;
        displayed = 0d;
        lastPublishNanos = 0L;
        lastPublishedDone = -1;
        lastPublishedDeterminate = false;
        lastPublishedActive = false;
        lastSnapshot = new Snapshot(false, false, 0, SCALE);
        closeCurrentLocked();
    }

    private static void resetAfterInactiveLocked() {
        accountCount = 1;
        accountsStarted = 0;
        accountsFinished = 0;
        discoveredUnits = 0;
        upcomingCount = 0;
        completedUnits = 0;
        closeCurrentLocked();
    }

    private static double currentFractionLocked() {
        if (!currentOpen || currentWeight <= 0) {
            return 0d;
        }
        double pushShare = (double) currentPushUnits / (double) currentWeight;
        double pullShare = 1d - pushShare;
        double pushDone = 1d;
        if (currentPushUnits > 0 && pushTotal > 0) {
            pushDone = clamp01(((double) pushSent + tusFraction) / (double) pushTotal);
        } else if (currentPushUnits > 0) {
            pushDone = 0d;
        }
        double pullDone = pullFractionLocked();
        return clamp01(pushShare * pushDone + pullShare * pullDone);
    }

    private static double pullFractionLocked() {
        double downloadPart = 0d;
        if (downloadComplete) {
            downloadPart = 1d;
        } else if (downloadTotal > 0L) {
            downloadPart = clamp01((double) downloadReceived / (double) downloadTotal);
        }
        double applyPart = 0d;
        if (applyTotal > 0) {
            applyPart = clamp01((double) applyProcessed / (double) applyTotal);
        }
        if (applyTotal > 0 && (downloadComplete || downloadTotal > 0L)) {
            return clamp01(0.5d * downloadPart + 0.5d * applyPart);
        }
        if (applyTotal > 0) {
            return applyPart;
        }
        if (downloadComplete || downloadTotal > 0L) {
            return 0.5d * downloadPart;
        }
        return 0d;
    }

    private static Snapshot computeLocked() {
        if (!active && !finished) {
            return new Snapshot(false, false, 0, SCALE);
        }
        double remainingAccounts = Math.max(0, accountCount - accountsStarted);
        double average = accountsStarted > 0
                ? Math.max((double) discoveredUnits / (double) accountsStarted, PULL_UNITS)
                : PULL_UNITS;
        double total = discoveredUnits + remainingAccounts * average;
        if (total < 1d) {
            total = 1d;
        }
        double rawDone = completedUnits + currentWeight * currentFractionLocked();
        double raw = clamp01(rawDone / total);
        double capped = finished ? 1d : raw * (1d - FINISH_RESERVE);
        if (active || finished) {
            displayed = Math.max(displayed, capped);
        }
        if (finished) {
            displayed = 1d;
        }
        boolean determinate = discoveredUnits > 0 || accountsStarted > 0;
        int done = (int) Math.round(displayed * SCALE);
        if (done > SCALE) {
            done = SCALE;
        }
        return new Snapshot(active || finished, determinate, done, SCALE);
    }

    private static void publishLocked(boolean force) {
        Snapshot snapshot = computeLocked();
        if (finished) {
            snapshot = new Snapshot(false, true, SCALE, SCALE);
        } else if (!active) {
            snapshot = new Snapshot(false, snapshot.determinate, snapshot.done, SCALE);
        }
        long now = clock.nanoTime();
        if (!force) {
            if (lastPublishNanos > 0L && now - lastPublishNanos < MIN_PUBLISH_INTERVAL_NS) {
                lastSnapshot = new Snapshot(
                        snapshot.active, snapshot.determinate, snapshot.done, snapshot.total);
                return;
            }
            boolean samePhase = snapshot.determinate == lastPublishedDeterminate
                    && snapshot.active == lastPublishedActive;
            if (samePhase && lastPublishedDone >= 0
                    && Math.abs(snapshot.done - lastPublishedDone) < MIN_PUBLISH_PERMILLE) {
                lastSnapshot = snapshot;
                return;
            }
        }
        lastPublishNanos = now;
        lastPublishedDone = snapshot.done;
        lastPublishedDeterminate = snapshot.determinate;
        lastPublishedActive = snapshot.active;
        lastSnapshot = snapshot;
        Listener currentListener = listener;
        Context context = appContext;
        if (currentListener != null) {
            currentListener.onProgress(snapshot);
        }
        if (context != null) {
            Intent intent = new Intent(SYNC_PROGRESS);
            intent.putExtra(EXTRA_DONE, snapshot.done);
            intent.putExtra(EXTRA_TOTAL, snapshot.total);
            intent.putExtra(EXTRA_DETERMINATE, snapshot.determinate);
            intent.putExtra(EXTRA_ACTIVE, snapshot.active);
            intent.setPackage(context.getPackageName());
            context.sendBroadcast(intent);
        }
    }

    private static int clampInt(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static double clamp01(double value) {
        if (value < 0d) {
            return 0d;
        }
        if (value > 1d) {
            return 1d;
        }
        return value;
    }
}

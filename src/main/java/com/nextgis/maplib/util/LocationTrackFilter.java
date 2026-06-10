/*
 * Project:  NextGIS Mobile / Geonical fork
 * Purpose:  Reject GPS spikes for track recording and walk-by-geometry using
 *           accuracy/age/dt gates, implied speed + acceleration limits, and a
 *           three-point chord check on a short buffer.
 */

package com.nextgis.maplib.util;

import android.location.Location;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import com.hypertrack.hyperlog.HyperLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stateful filter for sequences of {@link Location} fixes (track points, walk vertices).
 * Call {@link #onLocation(Location)} for each fix; insert returned locations in order.
 * Call {@link #reset()} when starting a new session and {@link #flushRemaining()} when
 * stopping so buffered points are not lost.
 */
public final class LocationTrackFilter {

    /** Horizontal accuracy above this (meters) is rejected (68% radius semantics on Android). */
    public static final float DEFAULT_MAX_ACCURACY_M = 50f;
    /** Reject fixes older than this compared to {@link SystemClock#elapsedRealtimeNanos()}. */
    public static final long DEFAULT_MAX_FIX_AGE_MS = 8_000L;
    /** Ignore callbacks closer than this (duplicate / jitter), in milliseconds of monotonic time. */
    public static final long DEFAULT_MIN_DT_MS = 250L;
    /** Larger gap: reset internal state and accept the new fix as a new segment. */
    public static final long DEFAULT_MAX_DT_MS = 30_000L;
    /** Pedestrian-oriented cap on implied speed (m/s), ~25 km/h. */
    public static final float DEFAULT_MAX_SPEED_MPS = 7f;
    /** Cap on implied acceleration (m/s²) when speeds are available. */
    public static final float DEFAULT_MAX_ACCEL_MPS2 = 6f;
    /** Extra distance margin: k * (accuracy_prev + accuracy_curr) added to v_max * dt. */
    public static final float DEFAULT_ACCURACY_MARGIN_K = 2.5f;
    /** Reject reported {@link Location#getSpeed()} above this (m/s). */
    public static final float DEFAULT_ABSURD_SPEED_MPS = 100f;

    private final float mMaxAccuracyM;
    private final long mMaxFixAgeMs;
    private final long mMinDtMs;
    private final long mMaxDtMs;
    private final float mMaxSpeedMps;
    private final float mMaxAccelMps2;
    private final float mAccuracyMarginK;

    /** Monotonic time of last fix that passed gates and entered the chord buffer. */
    private long mLastAcceptedElapsedNanos;
    /** When {@link Location#getElapsedRealtimeNanos()} is 0, use wall clock for dt gates. */
    private long mLastAcceptedClockRealtimeMs;
    private Location mLastEmitted;

    private final ArrayList<Location> mBuffer = new ArrayList<>();

    public LocationTrackFilter() {
        this(DEFAULT_MAX_ACCURACY_M, DEFAULT_MAX_FIX_AGE_MS, DEFAULT_MIN_DT_MS, DEFAULT_MAX_DT_MS,
                DEFAULT_MAX_SPEED_MPS, DEFAULT_MAX_ACCEL_MPS2, DEFAULT_ACCURACY_MARGIN_K);
    }

    public LocationTrackFilter(
            float maxAccuracyM,
            long maxFixAgeMs,
            long minDtMs,
            long maxDtMs,
            float maxSpeedMps,
            float maxAccelMps2,
            float accuracyMarginK) {
        mMaxAccuracyM = maxAccuracyM;
        mMaxFixAgeMs = maxFixAgeMs;
        mMinDtMs = minDtMs;
        mMaxDtMs = maxDtMs;
        mMaxSpeedMps = maxSpeedMps;
        mMaxAccelMps2 = maxAccelMps2;
        mAccuracyMarginK = accuracyMarginK;
    }

    public void reset() {
        mLastAcceptedElapsedNanos = 0L;
        mLastAcceptedClockRealtimeMs = 0L;
        mLastEmitted = null;
        mBuffer.clear();
    }

    /**
     * Remaining buffered points that were waiting for a triple; emit them in order.
     */
    public List<Location> flushRemaining() {
        if (mBuffer.isEmpty()) {
            return Collections.emptyList();
        }
        ArrayList<Location> out = new ArrayList<>(mBuffer.size());
        for (Location loc : mBuffer) {
            out.add(new Location(loc));
        }
        mBuffer.clear();
        if (!out.isEmpty()) {
            Location last = out.get(out.size() - 1);
            mLastEmitted = new Location(last);
            long en = getElapsedRealtimeNanos(last);
            if (en > 0L) {
                mLastAcceptedElapsedNanos = en;
            }
            mLastAcceptedClockRealtimeMs = SystemClock.elapsedRealtime();
        }
        return out;
    }

    /**
     * Process one raw fix. Returns zero or more locations to persist (already vetted).
     */
    public List<Location> onLocation(Location raw) {
        if (raw == null) {
            return Collections.emptyList();
        }

        String drop = basicGateReason(raw);
        if (drop != null) {
            debugDrop(drop);
            return Collections.emptyList();
        }

        long elapsedNanos = getElapsedRealtimeNanos(raw);
        long nowNanos = SystemClock.elapsedRealtimeNanos();

        if (!passesMaxFixAgeNanos(elapsedNanos, nowNanos, mMaxFixAgeMs)) {
            long ageMs = elapsedNanos > 0L ? (nowNanos - elapsedNanos) / 1_000_000L : -1L;
            debugDrop("drop:age:" + ageMs);
            return Collections.emptyList();
        }

        long dtMs = -1L;
        if (elapsedNanos > 0L && mLastAcceptedElapsedNanos > 0L) {
            dtMs = (elapsedNanos - mLastAcceptedElapsedNanos) / 1_000_000L;
        } else if (mLastAcceptedClockRealtimeMs > 0L) {
            dtMs = SystemClock.elapsedRealtime() - mLastAcceptedClockRealtimeMs;
        }
        if (dtMs >= 0L) {
            if (dtMs < mMinDtMs) {
                debugDrop("drop:dt_small:" + dtMs);
                return Collections.emptyList();
            }
            if (dtMs > mMaxDtMs) {
                resetKeepingNothing();
            }
        }

        Location ref = referenceForMotion();
        if (ref != null) {
            String motionDrop = motionGateReason(ref, raw);
            if (motionDrop != null) {
                debugDrop(motionDrop);
                return Collections.emptyList();
            }
        }

        if (elapsedNanos > 0L) {
            mLastAcceptedElapsedNanos = elapsedNanos;
        }
        mLastAcceptedClockRealtimeMs = SystemClock.elapsedRealtime();

        return appendAndDrainChord(new Location(raw));
    }

    private void resetKeepingNothing() {
        mBuffer.clear();
        mLastEmitted = null;
        mLastAcceptedElapsedNanos = 0L;
        mLastAcceptedClockRealtimeMs = 0L;
    }

    private Location referenceForMotion() {
        if (mLastEmitted != null) {
            return mLastEmitted;
        }
        if (!mBuffer.isEmpty()) {
            return mBuffer.get(mBuffer.size() - 1);
        }
        return null;
    }

    private List<Location> appendAndDrainChord(Location c) {
        mBuffer.add(c);
        ArrayList<Location> emitted = new ArrayList<>();

        while (mBuffer.size() >= 3) {
            Location a = mBuffer.get(0);
            Location b = mBuffer.get(1);
            Location c2 = mBuffer.get(2);
            if (chordRejectsMiddle(a, b, c2)) {
                mBuffer.remove(1);
                int guard = 0;
                while (mBuffer.size() >= 3 && chordRejectsMiddle(
                        mBuffer.get(0), mBuffer.get(1), mBuffer.get(2)) && guard < 32) {
                    mBuffer.remove(1);
                    guard++;
                }
            } else {
                Location head = mBuffer.remove(0);
                emitted.add(head);
                mLastEmitted = new Location(head);
            }
        }

        return emitted.isEmpty() ? Collections.emptyList() : emitted;
    }

    private static boolean chordRejectsMiddle(Location a, Location b, Location c) {
        float ab = a.distanceTo(b);
        float bc = b.distanceTo(c);
        float ac = a.distanceTo(c);
        return ab > ac || bc > ac;
    }

    /**
     * Same checks as the first stage of {@link #onLocation(Location)} (mock, accuracy, absurd speed)
     * plus fix age vs {@link #DEFAULT_MAX_FIX_AGE_MS}. For walk closing snap when {@code min_dt} skipped a fix.
     */
    public static boolean passesBasicIntegrity(Location loc) {
        if (loc == null) {
            return false;
        }
        if (basicIntegrityReason(loc, DEFAULT_MAX_ACCURACY_M) != null) {
            return false;
        }
        long elapsedNanos = getElapsedRealtimeNanos(loc);
        if (elapsedNanos <= 0L) {
            return true;
        }
        return passesMaxFixAgeNanos(elapsedNanos, SystemClock.elapsedRealtimeNanos(), DEFAULT_MAX_FIX_AGE_MS);
    }

    private static boolean passesMaxFixAgeNanos(long elapsedNanos, long nowNanos, long maxFixAgeMs) {
        if (elapsedNanos <= 0L) {
            return true;
        }
        long ageMs = (nowNanos - elapsedNanos) / 1_000_000L;
        return ageMs <= maxFixAgeMs;
    }

    private String basicGateReason(Location loc) {
        return basicIntegrityReason(loc, mMaxAccuracyM);
    }

    /**
     * @return null if OK, else same reason tokens as {@link #debugDrop(String)}.
     */
    private static String basicIntegrityReason(Location loc, float maxAccuracyM) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                if (loc.isMock()) {
                    return "drop:mock";
                }
            } catch (Exception ignored) {
                // ignore
            }
        }
        if (!loc.hasAccuracy() || loc.getAccuracy() <= 0f) {
            return "drop:no_accuracy";
        }
        if (loc.getAccuracy() > maxAccuracyM) {
            return "drop:accuracy:" + loc.getAccuracy();
        }
        if (loc.hasSpeed() && Math.abs(loc.getSpeed()) > DEFAULT_ABSURD_SPEED_MPS) {
            return "drop:absurd_speed:" + loc.getSpeed();
        }
        return null;
    }

    private String motionGateReason(Location prev, Location curr) {
        double dtSec = dtSeconds(prev, curr);
        if (dtSec <= 0d) {
            return "drop:dt_nonpositive";
        }
        float dist = prev.distanceTo(curr);
        double maxDist = mMaxSpeedMps * dtSec
                + mAccuracyMarginK * (prev.getAccuracy() + curr.getAccuracy());
        if (dist > maxDist) {
            return "drop:speed_dist:" + dist + "/" + (float) maxDist;
        }

        if (prev.hasSpeed() && curr.hasSpeed()) {
            float vPrev = Math.abs(prev.getSpeed());
            float vCurr = Math.abs(curr.getSpeed());
            float accel = Math.abs(vCurr - vPrev) / (float) dtSec;
            if (accel > mMaxAccelMps2) {
                return "drop:accel:" + accel;
            }
        }
        return null;
    }

    private static double dtSeconds(Location a, Location b) {
        long na = getElapsedRealtimeNanos(a);
        long nb = getElapsedRealtimeNanos(b);
        if (na > 0L && nb > 0L) {
            return (nb - na) / 1_000_000_000d;
        }
        return (b.getTime() - a.getTime()) / 1000d;
    }

    private static long getElapsedRealtimeNanos(Location loc) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            return loc.getElapsedRealtimeNanos();
        }
        return 0L;
    }

    private void debugDrop(String reason) {
        HyperLog.d(Constants.TAG, "LocationTrackFilter: " + reason);
        if (Constants.DEBUG_MODE) {
            Log.d(Constants.TAG, "LocationTrackFilter: " + reason);
        }
    }
}

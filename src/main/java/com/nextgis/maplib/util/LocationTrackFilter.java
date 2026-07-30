/*
 * Project:  NextGIS Mobile / Geonical fork
 * Purpose:  Reject GPS garbage while retaining real track and walk movement up to 160 km/h.
 */

package com.nextgis.maplib.util;

import android.location.Location;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import com.hypertrack.hyperlog.HyperLog;

import java.util.List;

/**
 * Android adapter for the shared track/walk sequence filter.
 *
 * <p>Call {@link #onLocation(Location)} for every provider callback and immediately persist all
 * returned locations. Call {@link #flushRemaining()} before stopping. The filter deliberately
 * delays at most two valid fixes so an isolated spatial spike can be removed.</p>
 */
public final class LocationTrackFilter {
    /** Horizontal accuracy above this (meters) is rejected. */
    public static final float DEFAULT_MAX_ACCURACY_M = 50f;
    /** Reject delayed fixes older than this. */
    public static final long DEFAULT_MAX_FIX_AGE_MS = 8_000L;
    /** Ignore duplicate/jitter callbacks closer than this. */
    public static final long DEFAULT_MIN_DT_MS = 250L;
    /** Start a fresh validation segment after this gap, preserving the previous buffer. */
    public static final long DEFAULT_MAX_DT_MS = 30_000L;
    /**
     * Supported ground speed with headroom over the product requirement of 160 km/h
     * (44.44 m/s). 55 m/s is approximately 198 km/h.
     */
    public static final float DEFAULT_MAX_SPEED_MPS = 55f;
    /** Reject only physically implausible reported speed changes (about 2 g). */
    public static final float DEFAULT_MAX_ACCEL_MPS2 = 20f;
    /** Extra distance margin: k * (accuracy_previous + accuracy_current). */
    public static final float DEFAULT_ACCURACY_MARGIN_K = 2.5f;
    /** Reported Android speed above this is treated as corrupt (360 km/h). */
    public static final float DEFAULT_ABSURD_SPEED_MPS = 100f;

    private final LocationTrackFilterCore<Location> mCore;
    private String mDiagnosticProvider;

    public LocationTrackFilter() {
        this(
                DEFAULT_MAX_ACCURACY_M,
                DEFAULT_MAX_FIX_AGE_MS,
                DEFAULT_MIN_DT_MS,
                DEFAULT_MAX_DT_MS,
                DEFAULT_MAX_SPEED_MPS,
                DEFAULT_MAX_ACCEL_MPS2,
                DEFAULT_ACCURACY_MARGIN_K);
    }

    public LocationTrackFilter(
            float maxAccuracyM,
            long maxFixAgeMs,
            long minDtMs,
            long maxDtMs,
            float maxSpeedMps,
            float maxAccelMps2,
            float accuracyMarginK) {
        mCore = new LocationTrackFilterCore<>(
                new AndroidLocationOps(),
                new AndroidClock(),
                this::debugDiagnostic,
                maxAccuracyM,
                maxFixAgeMs,
                minDtMs,
                maxDtMs,
                maxSpeedMps,
                maxAccelMps2,
                accuracyMarginK,
                DEFAULT_ABSURD_SPEED_MPS);
    }

    public void reset() {
        mCore.reset();
    }

    public List<Location> flushRemaining() {
        return mCore.flushRemaining();
    }

    public List<Location> onLocation(Location raw) {
        mDiagnosticProvider = raw == null ? "null" : raw.getProvider();
        try {
            return mCore.onSample(raw);
        } finally {
            mDiagnosticProvider = null;
        }
    }

    public long getInputFixCount() {
        return mCore.getInputFixCount();
    }

    public long getPassedInputFixCount() {
        return mCore.getPassedInputFixCount();
    }

    public long getDroppedInputFixCount() {
        return mCore.getDroppedInputFixCount();
    }

    public long getChordDroppedFixCount() {
        return mCore.getChordDroppedFixCount();
    }

    public long getGapSegmentCount() {
        return mCore.getGapSegmentCount();
    }

    public int getBufferedFixCount() {
        return mCore.getBufferedFixCount();
    }

    /**
     * First-stage integrity check used by the final closing snap in both recording services.
     */
    public static boolean passesBasicIntegrity(Location location) {
        if (location == null || !hasValidPosition(location)) {
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (location.isMock()) {
                    return false;
                }
            } else if (location.isFromMockProvider()) {
                return false;
            }
        } catch (RuntimeException ignored) {
            // Treat an unavailable platform mock flag as unknown, not as corrupt data.
        }
        if (!location.hasAccuracy()
                || location.getAccuracy() <= 0f
                || location.getAccuracy() > DEFAULT_MAX_ACCURACY_M) {
            return false;
        }
        if (location.hasSpeed()
                && Math.abs(location.getSpeed()) > DEFAULT_ABSURD_SPEED_MPS) {
            return false;
        }
        return LocationTrackFilterCore.passesFixAgeNanos(
                location.getElapsedRealtimeNanos(),
                SystemClock.elapsedRealtimeNanos(),
                DEFAULT_MAX_FIX_AGE_MS);
    }

    private void debugDiagnostic(String reason) {
        String message = "LocationTrackFilter: " + reason
                + " provider=" + (mDiagnosticProvider == null ? "unknown" : mDiagnosticProvider);
        HyperLog.d(Constants.TAG, message);
        if (Constants.DEBUG_MODE) {
            Log.d(Constants.TAG, message);
        }
    }

    private static boolean hasValidPosition(Location location) {
        double latitude = location.getLatitude();
        double longitude = location.getLongitude();
        return Double.isFinite(latitude)
                && Double.isFinite(longitude)
                && latitude >= -90d
                && latitude <= 90d
                && longitude >= -180d
                && longitude <= 180d;
    }

    private static final class AndroidClock implements LocationTrackFilterCore.Clock {
        @Override
        public long elapsedRealtimeNanos() {
            return SystemClock.elapsedRealtimeNanos();
        }

        @Override
        public long elapsedRealtimeMillis() {
            return SystemClock.elapsedRealtime();
        }
    }

    private static final class AndroidLocationOps
            implements LocationTrackFilterCore.SampleOps<Location> {
        @Override
        public Location copy(Location sample) {
            return new Location(sample);
        }

        @Override
        public boolean hasValidPosition(Location sample) {
            return LocationTrackFilter.hasValidPosition(sample);
        }

        @Override
        public boolean isMock(Location sample) {
            try {
                return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        ? sample.isMock()
                        : sample.isFromMockProvider();
            } catch (RuntimeException ignored) {
                return false;
            }
        }

        @Override
        public boolean hasAccuracy(Location sample) {
            return sample.hasAccuracy();
        }

        @Override
        public float getAccuracy(Location sample) {
            return sample.getAccuracy();
        }

        @Override
        public boolean hasSpeed(Location sample) {
            return sample.hasSpeed();
        }

        @Override
        public float getSpeed(Location sample) {
            return sample.getSpeed();
        }

        @Override
        public String getProvider(Location sample) {
            return sample.getProvider();
        }

        @Override
        public long getElapsedRealtimeNanos(Location sample) {
            return sample.getElapsedRealtimeNanos();
        }

        @Override
        public long getWallTimeMillis(Location sample) {
            return sample.getTime();
        }

        @Override
        public float distance(Location from, Location to) {
            return from.distanceTo(to);
        }
    }
}

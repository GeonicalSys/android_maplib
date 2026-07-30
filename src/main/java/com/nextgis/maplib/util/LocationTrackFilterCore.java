/*
 * Project:  NextGIS Mobile / Geonical fork
 * Purpose:  Android-independent sequence filter used by LocationTrackFilter.
 */

package com.nextgis.maplib.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Stateful, platform-independent location sequence filter.
 *
 * <p>The two-point buffer delays emission until a third fix can disprove an isolated spatial
 * spike. A long sampling gap drains that buffer before starting a new validation segment, so
 * user-selected intervals above the segment threshold never erase valid fixes.</p>
 */
final class LocationTrackFilterCore<T> {
    interface SampleOps<T> {
        T copy(T sample);

        boolean hasValidPosition(T sample);

        boolean isMock(T sample);

        boolean hasAccuracy(T sample);

        float getAccuracy(T sample);

        boolean hasSpeed(T sample);

        float getSpeed(T sample);

        String getProvider(T sample);

        long getElapsedRealtimeNanos(T sample);

        long getWallTimeMillis(T sample);

        float distance(T from, T to);
    }

    interface Clock {
        long elapsedRealtimeNanos();

        long elapsedRealtimeMillis();
    }

    interface DiagnosticListener {
        void onDiagnostic(String reason);
    }

    static final long MAX_FUTURE_FIX_SKEW_MS = 1_000L;

    private final SampleOps<T> mOps;
    private final Clock mClock;
    private final DiagnosticListener mDiagnosticListener;
    private final float mMaxAccuracyM;
    private final long mMaxFixAgeMs;
    private final long mMinDtMs;
    private final long mMaxDtMs;
    private final float mMaxSpeedMps;
    private final float mMaxAccelMps2;
    private final float mAccuracyMarginK;
    private final float mAbsurdSpeedMps;

    private long mLastAcceptedElapsedNanos;
    private long mLastAcceptedClockRealtimeMs;
    private T mLastEmitted;
    private final ArrayList<T> mBuffer = new ArrayList<>();

    private long mInputFixCount;
    private long mPassedInputFixCount;
    private long mDroppedInputFixCount;
    private long mChordDroppedFixCount;
    private long mGapSegmentCount;

    LocationTrackFilterCore(
            SampleOps<T> ops,
            Clock clock,
            DiagnosticListener diagnosticListener,
            float maxAccuracyM,
            long maxFixAgeMs,
            long minDtMs,
            long maxDtMs,
            float maxSpeedMps,
            float maxAccelMps2,
            float accuracyMarginK,
            float absurdSpeedMps) {
        mOps = ops;
        mClock = clock;
        mDiagnosticListener = diagnosticListener;
        mMaxAccuracyM = maxAccuracyM;
        mMaxFixAgeMs = maxFixAgeMs;
        mMinDtMs = minDtMs;
        mMaxDtMs = maxDtMs;
        mMaxSpeedMps = maxSpeedMps;
        mMaxAccelMps2 = maxAccelMps2;
        mAccuracyMarginK = accuracyMarginK;
        mAbsurdSpeedMps = absurdSpeedMps;
    }

    void reset() {
        resetSequenceState();
        mInputFixCount = 0L;
        mPassedInputFixCount = 0L;
        mDroppedInputFixCount = 0L;
        mChordDroppedFixCount = 0L;
        mGapSegmentCount = 0L;
    }

    List<T> flushRemaining() {
        return drainBuffer();
    }

    List<T> onSample(T raw) {
        mInputFixCount++;
        if (raw == null) {
            return dropInput("drop:null");
        }

        String integrityDrop = basicIntegrityReason(raw);
        if (integrityDrop != null) {
            return dropInput(integrityDrop);
        }

        long elapsedNanos = mOps.getElapsedRealtimeNanos(raw);
        long nowNanos = mClock.elapsedRealtimeNanos();
        if (!passesFixAgeNanos(elapsedNanos, nowNanos, mMaxFixAgeMs)) {
            long ageMs = elapsedNanos > 0L
                    ? (nowNanos - elapsedNanos) / 1_000_000L
                    : -1L;
            return dropInput("drop:age:" + ageMs);
        }

        ArrayList<T> emitted = new ArrayList<>();
        long dtMs = dtFromLastAcceptedMillis(elapsedNanos);
        boolean motionValidatedForGap = false;
        if (dtMs >= 0L) {
            if (dtMs < mMinDtMs) {
                return dropInput("drop:dt_small:" + dtMs);
            }
            if (dtMs > mMaxDtMs) {
                T gapReference = referenceForMotion();
                if (gapReference != null) {
                    String motionDrop = motionGateReason(gapReference, raw);
                    if (motionDrop != null) {
                        return dropInput(motionDrop);
                    }
                    motionValidatedForGap = true;
                }
                emitted.addAll(drainBuffer());
                resetSequenceState();
                mGapSegmentCount++;
                diagnostic("segment:gap:" + dtMs);
            }
        }

        T reference = referenceForMotion();
        if (!motionValidatedForGap && reference != null) {
            String motionDrop = motionGateReason(reference, raw);
            if (motionDrop != null) {
                mDroppedInputFixCount++;
                diagnostic(motionDrop);
                return emitted;
            }
        }

        if (elapsedNanos > 0L) {
            mLastAcceptedElapsedNanos = elapsedNanos;
        }
        mLastAcceptedClockRealtimeMs = mClock.elapsedRealtimeMillis();
        mPassedInputFixCount++;

        emitted.addAll(appendAndDrainChord(mOps.copy(raw)));
        return emitted.isEmpty() ? Collections.emptyList() : emitted;
    }

    long getInputFixCount() {
        return mInputFixCount;
    }

    long getPassedInputFixCount() {
        return mPassedInputFixCount;
    }

    long getDroppedInputFixCount() {
        return mDroppedInputFixCount;
    }

    long getChordDroppedFixCount() {
        return mChordDroppedFixCount;
    }

    long getGapSegmentCount() {
        return mGapSegmentCount;
    }

    int getBufferedFixCount() {
        return mBuffer.size();
    }

    static boolean passesFixAgeNanos(long elapsedNanos, long nowNanos, long maxFixAgeMs) {
        if (elapsedNanos <= 0L) {
            return true;
        }
        long ageMs = (nowNanos - elapsedNanos) / 1_000_000L;
        return ageMs >= -MAX_FUTURE_FIX_SKEW_MS && ageMs <= maxFixAgeMs;
    }

    private long dtFromLastAcceptedMillis(long elapsedNanos) {
        if (elapsedNanos > 0L && mLastAcceptedElapsedNanos > 0L) {
            return (elapsedNanos - mLastAcceptedElapsedNanos) / 1_000_000L;
        }
        if (mLastAcceptedClockRealtimeMs > 0L) {
            return mClock.elapsedRealtimeMillis() - mLastAcceptedClockRealtimeMs;
        }
        return -1L;
    }

    private void resetSequenceState() {
        mBuffer.clear();
        mLastEmitted = null;
        mLastAcceptedElapsedNanos = 0L;
        mLastAcceptedClockRealtimeMs = 0L;
    }

    /**
     * Validate motion against the most recent accepted sample, including samples still waiting in
     * the chord buffer. Comparing against an older emitted point unnecessarily amplifies distance
     * after turns and was one cause of high-speed rejection cascades.
     */
    private T referenceForMotion() {
        if (!mBuffer.isEmpty()) {
            return mBuffer.get(mBuffer.size() - 1);
        }
        return mLastEmitted;
    }

    private List<T> appendAndDrainChord(T sample) {
        mBuffer.add(sample);
        ArrayList<T> emitted = new ArrayList<>();

        while (mBuffer.size() >= 3) {
            T a = mBuffer.get(0);
            T b = mBuffer.get(1);
            T c = mBuffer.get(2);
            if (chordRejectsMiddle(a, b, c)) {
                mBuffer.remove(1);
                mChordDroppedFixCount++;
                diagnostic("drop:chord");
            } else {
                T head = mBuffer.remove(0);
                emitted.add(mOps.copy(head));
                mLastEmitted = mOps.copy(head);
            }
        }

        return emitted.isEmpty() ? Collections.emptyList() : emitted;
    }

    /**
     * Reject only a material out-and-back detour beyond the combined uncertainty of all three
     * fixes. This still removes a classic isolated spike, while preserving ordinary corners and
     * small legitimate reversals that the old {@code ab > ac || bc > ac} rule removed.
     */
    private boolean chordRejectsMiddle(T a, T b, T c) {
        float ab = mOps.distance(a, b);
        float bc = mOps.distance(b, c);
        float ac = mOps.distance(a, c);
        if (!isFiniteNonNegative(ab) || !isFiniteNonNegative(bc) || !isFiniteNonNegative(ac)) {
            return true;
        }

        double uncertainty = Math.max(
                15d,
                mAccuracyMarginK * (
                        mOps.getAccuracy(a) + mOps.getAccuracy(b) + mOps.getAccuracy(c)));
        double detour = ab + bc - ac;
        boolean returnsCloser = ab > ac + uncertainty || bc > ac + uncertainty;
        return returnsCloser && detour > 2d * uncertainty;
    }

    private String basicIntegrityReason(T sample) {
        if (!mOps.hasValidPosition(sample)) {
            return "drop:position";
        }
        if (mOps.isMock(sample)) {
            return "drop:mock";
        }
        if (!mOps.hasAccuracy(sample) || mOps.getAccuracy(sample) <= 0f) {
            return "drop:no_accuracy";
        }
        if (mOps.getAccuracy(sample) > mMaxAccuracyM) {
            return "drop:accuracy:" + mOps.getAccuracy(sample);
        }
        if (mOps.hasSpeed(sample) && Math.abs(mOps.getSpeed(sample)) > mAbsurdSpeedMps) {
            return "drop:absurd_speed:" + mOps.getSpeed(sample);
        }
        return null;
    }

    private String motionGateReason(T previous, T current) {
        double dtSec = dtSeconds(previous, current);
        if (dtSec <= 0d || !Double.isFinite(dtSec)) {
            return "drop:dt_nonpositive";
        }

        float distance = mOps.distance(previous, current);
        if (!isFiniteNonNegative(distance)) {
            return "drop:distance";
        }
        double maxDistance = mMaxSpeedMps * dtSec
                + mAccuracyMarginK
                * (mOps.getAccuracy(previous) + mOps.getAccuracy(current));
        if (distance > maxDistance) {
            return "drop:speed_dist:" + distance + "/" + (float) maxDistance;
        }

        if (mMaxAccelMps2 > 0f
                && Objects.equals(mOps.getProvider(previous), mOps.getProvider(current))
                && mOps.hasSpeed(previous)
                && mOps.hasSpeed(current)) {
            float previousSpeed = Math.abs(mOps.getSpeed(previous));
            float currentSpeed = Math.abs(mOps.getSpeed(current));
            float acceleration = Math.abs(currentSpeed - previousSpeed) / (float) dtSec;
            if (acceleration > mMaxAccelMps2) {
                return "drop:accel:" + acceleration;
            }
        }
        return null;
    }

    private double dtSeconds(T a, T b) {
        long firstNanos = mOps.getElapsedRealtimeNanos(a);
        long secondNanos = mOps.getElapsedRealtimeNanos(b);
        if (firstNanos > 0L && secondNanos > 0L) {
            return (secondNanos - firstNanos) / 1_000_000_000d;
        }
        return (mOps.getWallTimeMillis(b) - mOps.getWallTimeMillis(a)) / 1000d;
    }

    private List<T> drainBuffer() {
        if (mBuffer.isEmpty()) {
            return Collections.emptyList();
        }
        ArrayList<T> output = new ArrayList<>(mBuffer.size());
        for (T sample : mBuffer) {
            output.add(mOps.copy(sample));
        }
        T last = mBuffer.get(mBuffer.size() - 1);
        mLastEmitted = mOps.copy(last);
        mBuffer.clear();
        return output;
    }

    private List<T> dropInput(String reason) {
        mDroppedInputFixCount++;
        diagnostic(reason);
        return Collections.emptyList();
    }

    private void diagnostic(String reason) {
        if (mDiagnosticListener != null) {
            mDiagnosticListener.onDiagnostic(reason);
        }
    }

    private static boolean isFiniteNonNegative(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value) && value >= 0f;
    }
}

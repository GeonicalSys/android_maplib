package com.nextgis.maplib.util;

import java.util.ArrayDeque;

/** Short-lived accelerometer evidence, not a position or a pedestrian/vehicle classifier. */
public final class DeviceMotionEvidence {
    public enum State { UNKNOWN, STILL, MOVING }
    private static final class Observation {
        final long at;
        final State state;
        Observation(long at, State state) { this.at = at; this.state = state; }
    }
    private final ArrayDeque<Observation> history = new ArrayDeque<>();
    private long started, last, quietSince;
    private int count;
    private double sx, sy, sz, squared;

    public void reset() {
        history.clear();
        started = last = quietSince = 0;
        clearWindow();
    }

    public void add(long atNanos, double x, double y, double z) {
        if (atNanos <= last || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return;
        if (last != 0 && atNanos - last > 2_000_000_000L) reset();
        if (started == 0) started = atNanos;
        last = atNanos;
        count++;
        sx += x; sy += y; sz += z;
        squared += x * x + y * y + z * z;
        if (atNanos - started < 1_000_000_000L) return;
        // Remove the mean gravity vector; rotation and walking both add motion evidence.
        double variance = Math.max(0, squared / count - (sx * sx + sy * sy + sz * sz) / (count * (double) count));
        State state = State.UNKNOWN;
        if (count >= 10 && variance < 0.14 * 0.14) {
            if (quietSince == 0) quietSince = started;
            if (atNanos - quietSince >= 3_000_000_000L) state = State.STILL;
        } else {
            quietSince = 0;
            if (count >= 10 && variance > 0.3 * 0.3) state = State.MOVING;
        }
        history.addLast(new Observation(atNanos, state));
        while (history.size() > 120) history.removeFirst();
        started = atNanos;
        clearWindow();
    }

    /** Look up evidence at measurement time, including ordered GNSS batches. Never use future data. */
    public State at(long atNanos) {
        for (java.util.Iterator<Observation> it = history.descendingIterator(); it.hasNext();) {
            Observation observation = it.next();
            if (observation.at <= atNanos) return atNanos - observation.at <= 2_000_000_000L
                    ? observation.state : State.UNKNOWN;
        }
        return State.UNKNOWN;
    }

    private void clearWindow() { count = 0; sx = sy = sz = squared = 0; }
}

package com.nextgis.maplib.util;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;

/** Geometry decimation with one revisable vertex per confirmed stop. */
final class LocationRecordingSamplerCore<T> {
    interface Ops<T> {
        T copy(T point);
        long timeMs(T point);
        long stopId(T point);
        default long departureSinceMs(T point) { return 0; }
        default T anchor(T point) { return copy(point); }
        default T moving(T point) { return copy(point); }
        double distance(T a, T b);
        double bearing(T a, T b);
    }
    private final Ops<T> ops;
    private final long minTimeMs;
    private final float minDistance;
    private T saved, pending, correction, corner;
    private final ArrayDeque<T> tentative = new ArrayDeque<>();
    private long lastInputMs, savedAtMs;

    LocationRecordingSamplerCore(Ops<T> ops, long minTimeMs, float minDistance) {
        this.ops = ops;
        this.minTimeMs = Math.max(0, minTimeMs);
        this.minDistance = Math.max(0, minDistance);
    }

    void reset() {
        saved = pending = correction = corner = null;
        lastInputMs = savedAtMs = 0;
        tentative.clear();
    }

    List<T> onLocation(T location) {
        List<T> result = new ArrayList<>();
        correction = null;
        long now = ops.timeMs(location);
        if (now <= lastInputMs) return result;
        lastInputMs = now;
        if (ops.stopId(location) != 0) {
            // Keep actual, validated observations while exposing only the stop anchor.
            // Each recorder owns this bounded buffer; starting another session cannot
            // import positions from before that session's subscription cutoff.
            if (saved == null || ops.stopId(saved) != ops.stopId(location)) tentative.clear();
            tentative.addLast(ops.copy(location));
            while (tentative.size() > 512 || now - ops.timeMs(tentative.peekFirst()) > 120_000)
                tentative.removeFirst();
            sample(ops.anchor(location), result);
        } else {
            long departure = ops.departureSinceMs(location);
            for (T point : tentative) {
                if (departure > 0 && ops.timeMs(point) >= departure && ops.timeMs(point) > savedAtMs)
                    sample(ops.moving(point), result);
            }
            tentative.clear();
            sample(location, result);
        }
        return result;
    }

    private void sample(T location, List<T> result) {
        if (saved == null) { save(location, result); return; }
        long stop = ops.stopId(location);
        if (stop != 0 && stop == ops.stopId(saved)) {
            // Anchor refinement corrects this stop's vertex. It is not travelled distance.
            if (ops.distance(saved, location) >= .5) correction = ops.copy(location);
            saved = ops.copy(location);
            pending = corner = null;
            return;
        }
        // At walking speed the outgoing leg often needs several one-second fixes to
        // reach the 2 m corner threshold. Do not overwrite the bend during those fixes.
        if (corner == null && pending != null && ops.distance(saved, pending) >= 2
                && ops.distance(pending, location) >= .5
                && Math.abs(Math.IEEEremainder(ops.bearing(pending, location)
                - ops.bearing(saved, pending), 360)) >= 25) corner = ops.copy(pending);
        if (corner != null && ops.distance(corner, location) >= 2) {
            if (RecordingSamplingPolicy.isCorner(ops.distance(saved, corner),
                    ops.distance(corner, location), ops.bearing(saved, corner), ops.bearing(corner, location)))
                save(corner, result);
            corner = null;
        }
        if (pending != null && RecordingSamplingPolicy.isCorner(ops.distance(saved, pending),
                ops.distance(pending, location), ops.bearing(saved, pending), ops.bearing(pending, location))) {
            save(pending, result);
        }
        if (stop != 0) {
            if (ops.distance(saved, location) >= .5) save(location, result);
            else {
                correction = ops.copy(location);
                saved = ops.copy(location);
                pending = corner = null;
            }
        } else if (RecordingSamplingPolicy.save(ops.timeMs(location) - savedAtMs,
                ops.distance(saved, location), minTimeMs, minDistance)) save(location, result);
        else pending = ops.copy(location);
    }

    T takeStationaryCorrection() {
        T result = correction;
        correction = null;
        return result == null ? null : ops.copy(result);
    }

    List<T> flush() {
        List<T> result = new ArrayList<>();
        tentative.clear(); // Finishing or losing GPS never confirms a possible departure.
        if (pending != null && ops.distance(saved, pending) >= .5) save(pending, result);
        pending = null;
        corner = null;
        return result;
    }

    private void save(T point, List<T> result) {
        saved = ops.copy(point);
        savedAtMs = ops.timeMs(point);
        pending = corner = null;
        result.add(ops.copy(saved));
    }
}

package com.nextgis.maplib.util;

import java.util.ArrayList;
import java.util.List;

/** Geometry decimation with one revisable vertex per confirmed stop. */
final class LocationRecordingSamplerCore<T> {
    interface Ops<T> {
        T copy(T point);
        long timeMs(T point);
        long stopId(T point);
        double distance(T a, T b);
        double bearing(T a, T b);
    }
    private final Ops<T> ops;
    private final long minTimeMs;
    private final float minDistance;
    private T saved, pending, correction;

    LocationRecordingSamplerCore(Ops<T> ops, long minTimeMs, float minDistance) {
        this.ops = ops;
        this.minTimeMs = Math.max(0, minTimeMs);
        this.minDistance = Math.max(0, minDistance);
    }

    void reset() { saved = pending = correction = null; }

    List<T> onLocation(T location) {
        List<T> result = new ArrayList<>();
        correction = null;
        if (saved == null) { save(location, result); return result; }
        if (ops.timeMs(location) <= ops.timeMs(saved)
                || pending != null && ops.timeMs(location) <= ops.timeMs(pending)) return result;
        long stop = ops.stopId(location);
        if (stop != 0 && stop == ops.stopId(saved)) {
            // Anchor refinement corrects this stop's vertex. It is not travelled distance.
            if (ops.distance(saved, location) >= .5) correction = ops.copy(location);
            saved = ops.copy(location);
            pending = null;
            return result;
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
                pending = null;
            }
        } else if (RecordingSamplingPolicy.save(ops.timeMs(location) - ops.timeMs(saved),
                ops.distance(saved, location), minTimeMs, minDistance)) save(location, result);
        else pending = ops.copy(location);
        return result;
    }

    T takeStationaryCorrection() {
        T result = correction;
        correction = null;
        return result == null ? null : ops.copy(result);
    }

    List<T> flush() {
        List<T> result = new ArrayList<>();
        if (pending != null && ops.distance(saved, pending) >= .5) save(pending, result);
        pending = null;
        return result;
    }

    private void save(T point, List<T> result) {
        saved = ops.copy(point);
        pending = null;
        result.add(ops.copy(saved));
    }
}

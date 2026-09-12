package com.nextgis.maplib.util;

import android.location.Location;
import java.util.List;

/** Per-session geometry sampling. Call takeStationaryCorrection after onLocation. */
public final class LocationRecordingSampler {
    private final LocationRecordingSamplerCore<Location> core;

    public LocationRecordingSampler(long minTimeMs, float minDistance) {
        core = new LocationRecordingSamplerCore<>(new LocationRecordingSamplerCore.Ops<Location>() {
            public Location copy(Location point) { return new Location(point); }
            public long timeMs(Location point) { return point.getElapsedRealtimeNanos() / 1_000_000; }
            public long stopId(Location point) {
                return point.getExtras() == null ? 0 : point.getExtras().getLong(LocationMotionFilter.STOP_ID, 0);
            }
            public long departureSinceMs(Location point) {
                return point.getExtras() == null ? 0
                        : point.getExtras().getLong(LocationMotionFilter.DEPARTURE_SINCE, 0);
            }
            public Location anchor(Location point) { return LocationMotionFilter.anchor(point); }
            public Location moving(Location point) {
                Location copy = new Location(point);
                android.os.Bundle extras = copy.getExtras() == null ? new android.os.Bundle()
                        : new android.os.Bundle(copy.getExtras());
                extras.putLong(LocationMotionFilter.STOP_ID, 0);
                copy.setExtras(extras);
                return copy;
            }
            public double distance(Location a, Location b) { return a.distanceTo(b); }
            public double bearing(Location a, Location b) { return a.bearingTo(b); }
        }, minTimeMs, minDistance);
    }

    public void reset() { core.reset(); }
    public List<Location> onLocation(Location location) { return core.onLocation(location); }
    /** Replace only this sampler's last persisted vertex, keeping its arrival timestamp. */
    public Location takeStationaryCorrection() { return core.takeStationaryCorrection(); }
    public List<Location> flush() { return core.flush(); }
}

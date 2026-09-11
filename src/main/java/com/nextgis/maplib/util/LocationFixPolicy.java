package com.nextgis.maplib.util;

/** Rules shared by display, recording health and tests. Times are since boot, never wall time. */
public final class LocationFixPolicy {
    public static final long FRESHNESS_MS = 8_000L;
    public static final long FUTURE_TOLERANCE_MS = 1_000L;

    private LocationFixPolicy() { }

    public static boolean isFresh(long fixNanos, long nowNanos) {
        if (fixNanos <= 0 || nowNanos <= 0) return false;
        long age = (nowNanos - fixNanos) / 1_000_000L;
        return age >= -FUTURE_TOLERANCE_MS && age <= FRESHNESS_MS;
    }

    public static boolean validPosition(double latitude, double longitude, float accuracy) {
        return Double.isFinite(latitude) && Math.abs(latitude) <= 90
                && Double.isFinite(longitude) && Math.abs(longitude) <= 180
                && Float.isFinite(accuracy) && accuracy >= 0;
    }

    /** A precise but old fix must never mask a current approximate one. */
    public static boolean preferGps(long gpsNanos, float gpsAccuracy,
                                    long networkNanos, float networkAccuracy, long nowNanos) {
        if (!isFresh(gpsNanos, nowNanos)) return false;
        if (!isFresh(networkNanos, nowNanos)) return true;
        // Allow a materially better and newer network estimate when GNSS reception is poor.
        return !(networkNanos - gpsNanos > 2_000_000_000L
                || networkNanos > gpsNanos && networkAccuracy * 2 < gpsAccuracy);
    }
}

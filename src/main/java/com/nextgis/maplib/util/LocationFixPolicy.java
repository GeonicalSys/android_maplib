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

    /**
     * Fresh GPS (chip or mock, any reported accuracy) always wins. Network is only
     * a fallback when GPS is missing or older than {@link #FRESHNESS_MS}.
     */
    public static boolean preferGps(long gpsNanos, float gpsAccuracy,
                                    long networkNanos, float networkAccuracy, long nowNanos) {
        return isFresh(gpsNanos, nowNanos);
    }
}

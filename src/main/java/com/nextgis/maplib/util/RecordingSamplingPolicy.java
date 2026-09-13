package com.nextgis.maplib.util;

/** Sampling is separate from acquisition: retain corners even with sparse straight-line settings. */
public final class RecordingSamplingPolicy {
    private RecordingSamplingPolicy() { }

    public static boolean isCorner(double beforeMeters, double afterMeters,
                                   double incomingBearing, double outgoingBearing) {
        double turn = Math.abs(Math.IEEEremainder(outgoingBearing - incomingBearing, 360));
        return beforeMeters >= 2 && afterMeters >= 2 && turn >= 25;
    }

    public static boolean save(long elapsedMs, double distance, long minTimeMs, double minDistance) {
        return elapsedMs > 0 && distance >= 0.5
                && elapsedMs >= minTimeMs && distance >= minDistance;
    }
}

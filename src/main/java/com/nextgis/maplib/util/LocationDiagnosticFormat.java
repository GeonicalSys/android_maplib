package com.nextgis.maplib.util;

/** Pure diagnostic line format for verbose GNSS HyperLog. */
public final class LocationDiagnosticFormat {
    private LocationDiagnosticFormat() { }

    public static String incoming(
            String provider,
            float accuracy,
            long ageMs,
            boolean mock,
            boolean nmea,
            int sats,
            double latitude,
            double longitude,
            String action) {
        return "GNSS in provider=" + provider
                + " acc=" + accuracy
                + " ageMs=" + ageMs
                + " mock=" + mock
                + " nmea=" + nmea
                + " sats=" + sats
                + " lat=" + latitude
                + " lon=" + longitude
                + " action=" + action;
    }

    public static String unavailable(
            String reason,
            long gpsAgeMs,
            boolean gpsEnabled,
            long networkAgeMs,
            boolean external) {
        return "GNSS publish unavailable reason=" + reason
                + " gpsAgeMs=" + gpsAgeMs
                + " gpsEnabled=" + gpsEnabled
                + " netAgeMs=" + networkAgeMs
                + " external=" + external;
    }

    public static String stakeoutWaiting(boolean waiting, long ageMs, String provider, float accuracy) {
        return "Stakeout waitingForFix=" + waiting
                + " ageMs=" + ageMs
                + " provider=" + provider
                + " acc=" + accuracy;
    }
}

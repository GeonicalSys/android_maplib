package com.nextgis.maplib.util;

/**
 * External GNSS published as Android mock: keep the receiver stream, not the
 * GPS Connector accuracy placeholder, phone chip or network mix.
 */
public final class ExternalGnssFixPolicy {
    public static final String EXTRA_HDOP = "hdop";
    public static final String EXTRA_VDOP = "vdop";
    public static final String EXTRA_DIFF_STATUS = "diffStatus";
    public static final String EXTRA_NATIVE_NMEA = "nativeNmea";
    public static final String EXTRA_NMEA_QUALITY = "nmeaQuality";
    public static final String EXTRA_AGE_OF_DIFF = "ageOfDiff";
    public static final long MOCK_MAX_MIN_TIME_MS = 2_000L;
    public static final float MOCK_MAX_MIN_DISTANCE_M = 1f;

    private ExternalGnssFixPolicy() { }

    public static boolean hasReceiverExtras(boolean hasHdop, boolean hasDiffStatus) {
        return hasHdop || hasDiffStatus;
    }

    /** Mock GPS Connector extras or Lisa-owned NMEA both skip the phone chip smoother. */
    public static boolean isReceiverStream(boolean mock, boolean nativeNmea) {
        return mock || nativeNmea;
    }

    /** GPS Connector republishes the same point with default 47 m and no NMEA extras. */
    public static boolean dropPlaceholderMock(boolean mock, boolean receiverExtras,
            boolean freshReceiverMock) {
        return mock && !receiverExtras && freshReceiverMock;
    }

    public static boolean dropChipWhileMock(boolean mock, boolean freshMock) {
        return !mock && freshMock;
    }

    public static long sampleMinTimeMs(boolean mock, long settingMs) {
        return sampleMinTimeMs(mock, false, settingMs);
    }

    public static long sampleMinTimeMs(boolean mock, boolean nativeNmea, long settingMs) {
        long setting = Math.max(0L, settingMs);
        return isReceiverStream(mock, nativeNmea) ? Math.min(setting, MOCK_MAX_MIN_TIME_MS) : setting;
    }

    public static float sampleMinDistanceM(boolean mock, float settingM) {
        return sampleMinDistanceM(mock, false, settingM);
    }

    public static float sampleMinDistanceM(boolean mock, boolean nativeNmea, float settingM) {
        float setting = settingM < 0f ? 0f : settingM;
        return isReceiverStream(mock, nativeNmea) ? Math.min(setting, MOCK_MAX_MIN_DISTANCE_M) : setting;
    }
}

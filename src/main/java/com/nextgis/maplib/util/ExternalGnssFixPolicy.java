package com.nextgis.maplib.util;

/**
 * External GNSS published as Android mock: keep the receiver stream, not the
 * GPS Connector accuracy placeholder, phone chip or network mix.
 */
public final class ExternalGnssFixPolicy {
    public static final String EXTRA_HDOP = "hdop";
    public static final String EXTRA_DIFF_STATUS = "diffStatus";
    public static final long MOCK_MAX_MIN_TIME_MS = 2_000L;
    public static final float MOCK_MAX_MIN_DISTANCE_M = 1f;

    private ExternalGnssFixPolicy() { }

    public static boolean hasReceiverExtras(boolean hasHdop, boolean hasDiffStatus) {
        return hasHdop || hasDiffStatus;
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
        long setting = Math.max(0L, settingMs);
        return mock ? Math.min(setting, MOCK_MAX_MIN_TIME_MS) : setting;
    }

    public static float sampleMinDistanceM(boolean mock, float settingM) {
        float setting = settingM < 0f ? 0f : settingM;
        return mock ? Math.min(setting, MOCK_MAX_MIN_DISTANCE_M) : setting;
    }
}

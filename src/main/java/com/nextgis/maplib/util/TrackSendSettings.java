package com.nextgis.maplib.util;

import android.content.SharedPreferences;

/** Upload intent is independent of whether the device has been registered on the server. */
public final class TrackSendSettings {
    public static final String DEFAULT_MIGRATED = "track_send_default_enabled_v1";
    private TrackSendSettings() {}

    public static boolean isEnabled(SharedPreferences preferences) {
        return preferences.getBoolean(SettingsConstants.KEY_PREF_TRACK_SEND, true);
    }

    /** Legacy registration checks persisted false themselves; enable once, then respect opt-out. */
    public static void migrateDefault(SharedPreferences preferences) {
        if (!preferences.getBoolean(DEFAULT_MIGRATED, false)) {
            preferences.edit().putBoolean(SettingsConstants.KEY_PREF_TRACK_SEND, true)
                    .putBoolean(DEFAULT_MIGRATED, true).commit();
        }
    }
}

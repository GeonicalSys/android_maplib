package com.nextgis.maplib.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

/** Display options applied lazily by the local raster tile server. */
public final class UnderlayDisplaySettings {
    public final boolean whiteAsTransparent;
    public final boolean lastLevelOverzoom;

    public UnderlayDisplaySettings(boolean whiteAsTransparent, boolean lastLevelOverzoom) {
        this.whiteAsTransparent = whiteAsTransparent;
        this.lastLevelOverzoom = lastLevelOverzoom;
    }

    public static UnderlayDisplaySettings from(Context context) {
        if (context == null) {
            return defaults();
        }
        return from(PreferenceManager.getDefaultSharedPreferences(context));
    }

    public static UnderlayDisplaySettings from(SharedPreferences preferences) {
        if (preferences == null) {
            return defaults();
        }
        return new UnderlayDisplaySettings(
                preferences.getBoolean(
                        SettingsConstants.KEY_PREF_WHITE_AS_TRANSPARENT,
                        SettingsConstants.DEFAULT_WHITE_AS_TRANSPARENT),
                preferences.getBoolean(
                        SettingsConstants.KEY_PREF_UNDERLAY_LAST_LEVEL_OVERZOOM,
                        SettingsConstants.DEFAULT_UNDERLAY_LAST_LEVEL_OVERZOOM));
    }

    private static UnderlayDisplaySettings defaults() {
        return new UnderlayDisplaySettings(
                SettingsConstants.DEFAULT_WHITE_AS_TRANSPARENT,
                SettingsConstants.DEFAULT_UNDERLAY_LAST_LEVEL_OVERZOOM);
    }

    public String fingerprint() {
        return (whiteAsTransparent ? "w" : "-") + (lastLevelOverzoom ? "l" : "-");
    }
}

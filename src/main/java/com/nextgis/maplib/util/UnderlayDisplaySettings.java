package com.nextgis.maplib.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

/** Map-settings pair for local raster underlay display. Defaults are on. */
public final class UnderlayDisplaySettings {
    public final boolean whiteAsTransparent;
    public final boolean lastLevelOverzoom;

    public UnderlayDisplaySettings(boolean whiteAsTransparent, boolean lastLevelOverzoom) {
        this.whiteAsTransparent = whiteAsTransparent;
        this.lastLevelOverzoom = lastLevelOverzoom;
    }

    public static UnderlayDisplaySettings from(Context context) {
        if (context == null) {
            return new UnderlayDisplaySettings(
                    SettingsConstants.DEFAULT_WHITE_AS_TRANSPARENT,
                    SettingsConstants.DEFAULT_UNDERLAY_LAST_LEVEL_OVERZOOM);
        }
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        return from(preferences);
    }

    public static UnderlayDisplaySettings from(SharedPreferences preferences) {
        if (preferences == null) {
            return new UnderlayDisplaySettings(
                    SettingsConstants.DEFAULT_WHITE_AS_TRANSPARENT,
                    SettingsConstants.DEFAULT_UNDERLAY_LAST_LEVEL_OVERZOOM);
        }
        return new UnderlayDisplaySettings(
                preferences.getBoolean(
                        SettingsConstants.KEY_PREF_WHITE_AS_TRANSPARENT,
                        SettingsConstants.DEFAULT_WHITE_AS_TRANSPARENT),
                preferences.getBoolean(
                        SettingsConstants.KEY_PREF_UNDERLAY_LAST_LEVEL_OVERZOOM,
                        SettingsConstants.DEFAULT_UNDERLAY_LAST_LEVEL_OVERZOOM));
    }

    public boolean needsSidecar() {
        return whiteAsTransparent || lastLevelOverzoom;
    }

    public String fingerprint() {
        return (whiteAsTransparent ? "w" : "-") + (lastLevelOverzoom ? "l" : "-");
    }

    public static boolean lastLevelFromFingerprint(String fingerprint) {
        return fingerprint != null && fingerprint.length() >= 2 && fingerprint.charAt(1) == 'l';
    }
}

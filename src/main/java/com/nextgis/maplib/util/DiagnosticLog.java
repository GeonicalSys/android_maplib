package com.nextgis.maplib.util;

import android.content.SharedPreferences;

import com.hypertrack.hyperlog.HyperLog;

/** Opt-in GNSS diagnostic dump gated by {@link SettingsConstants#KEY_PREF_VERBOSE_LOG}. */
public final class DiagnosticLog {
    private static volatile SharedPreferences prefs;

    private DiagnosticLog() { }

    public static void attach(SharedPreferences preferences) {
        prefs = preferences;
    }

    public static boolean isVerbose() {
        SharedPreferences local = prefs;
        return local != null && local.getBoolean(SettingsConstants.KEY_PREF_VERBOSE_LOG, false);
    }

    public static void v(String message) {
        if (isVerbose() && message != null) {
            HyperLog.v(Constants.TAG, message);
        }
    }
}

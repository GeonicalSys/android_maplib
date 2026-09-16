package com.nextgis.maplib.gnss;

import android.content.SharedPreferences;

import com.nextgis.maplib.util.SettingsConstants;

/** Keys and values for system vs native external GNSS. */
public final class GnssInputPrefs {
    public static final String VALUE_SYSTEM = "system";
    public static final String VALUE_EXTERNAL = "external";
    public static final String TRANSPORT_BLUETOOTH_CLASSIC = "bluetooth_classic";
    public static final String TRANSPORT_BLUETOOTH_LE = "bluetooth_le";
    public static final String TRANSPORT_USB = "usb";
    public static final String TRANSPORT_TCP = "tcp";

    private GnssInputPrefs() { }

    public static boolean isExternal(SharedPreferences prefs) {
        return prefs != null && VALUE_EXTERNAL.equals(
                prefs.getString(SettingsConstants.KEY_PREF_GNSS_INPUT, VALUE_SYSTEM));
    }

    public static String transport(SharedPreferences prefs) {
        if (prefs == null) {
            return TRANSPORT_BLUETOOTH_CLASSIC;
        }
        String value = prefs.getString(SettingsConstants.KEY_PREF_GNSS_TRANSPORT,
                TRANSPORT_BLUETOOTH_CLASSIC);
        return value == null ? TRANSPORT_BLUETOOTH_CLASSIC : value;
    }

    public static String deviceId(SharedPreferences prefs) {
        return prefs == null ? "" : prefs.getString(SettingsConstants.KEY_PREF_GNSS_DEVICE_ID, "");
    }

    public static String deviceName(SharedPreferences prefs) {
        return prefs == null ? "" : prefs.getString(SettingsConstants.KEY_PREF_GNSS_DEVICE_NAME, "");
    }

    public static String tcpHost(SharedPreferences prefs) {
        return prefs == null ? "" : prefs.getString(SettingsConstants.KEY_PREF_GNSS_TCP_HOST, "");
    }

    public static int tcpPort(SharedPreferences prefs) {
        if (prefs == null) {
            return 9001;
        }
        try {
            return Integer.parseInt(prefs.getString(SettingsConstants.KEY_PREF_GNSS_TCP_PORT, "9001"));
        } catch (NumberFormatException exception) {
            return 9001;
        }
    }
}

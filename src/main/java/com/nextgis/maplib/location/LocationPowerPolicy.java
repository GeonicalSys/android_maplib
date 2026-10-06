package com.nextgis.maplib.location;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.preference.PreferenceManager;

import com.nextgis.maplib.gnss.GnssInputPrefs;

/** Battery Saver's location policy is independent of the app's Doze exemption. */
public final class LocationPowerPolicy {
    private LocationPowerPolicy() { }

    public static boolean shouldWarn(Context context) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return power != null && restrictsScreenOffGps(Build.VERSION.SDK_INT,
                power.isPowerSaveMode(), locationMode(power),
                GnssInputPrefs.isExternal(PreferenceManager.getDefaultSharedPreferences(context)));
    }

    static boolean restrictsScreenOffGps(int api, boolean powerSave, int mode, boolean external) {
        if (external || !powerSave) return false;
        // Android 8 cannot expose the location policy: warn about a possible interruption.
        if (api < 28) return true;
        return mode == PowerManager.LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF
                || mode == PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF
                || mode == PowerManager.LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF;
        // FOREGROUND_ONLY still permits our location foreground service.
    }

    private static int locationMode(PowerManager power) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? power.getLocationPowerSaveMode() : -1;
    }

    public static String diagnostics(Context context) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (power == null) return "powerState=unavailable";
        return "powerSave=" + power.isPowerSaveMode()
                + " locationPowerSaveMode=" + locationMode(power)
                + " batteryExempt=" + power.isIgnoringBatteryOptimizations(context.getPackageName())
                + " deviceIdle=" + power.isDeviceIdleMode();
    }
}

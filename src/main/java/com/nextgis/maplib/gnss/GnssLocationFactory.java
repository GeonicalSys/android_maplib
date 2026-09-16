package com.nextgis.maplib.gnss;

import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;

import com.nextgis.maplib.util.ExternalGnssFixPolicy;

/** Builds an Android Location from a native NMEA snapshot without Mock Location. */
public final class GnssLocationFactory {
    private GnssLocationFactory() { }

    public static Location toLocation(GnssFix fix, long elapsedRealtimeNanos, long wallTimeMs) {
        if (fix == null || !fix.hasFix()) {
            return null;
        }
        float accuracy = fix.horizontalAccuracyM();
        if (!GnssFix.isFinite(accuracy) || accuracy <= 0f) {
            accuracy = 50f;
        }
        Location location = new Location(LocationManager.GPS_PROVIDER);
        location.setLatitude(fix.latitude);
        location.setLongitude(fix.longitude);
        location.setAltitude(fix.altitude);
        location.setAccuracy(accuracy);
        location.setElapsedRealtimeNanos(elapsedRealtimeNanos);
        location.setTime(wallTimeMs);
        if (GnssFix.isFinite(fix.speedMps) && fix.speedMps >= 0f) {
            location.setSpeed(fix.speedMps);
        }
        if (GnssFix.isFinite(fix.bearing)) {
            location.setBearing(fix.bearing);
        }
        Bundle extras = new Bundle();
        extras.putBoolean(ExternalGnssFixPolicy.EXTRA_NATIVE_NMEA, true);
        extras.putInt(ExternalGnssFixPolicy.EXTRA_NMEA_QUALITY, fix.quality);
        extras.putString(ExternalGnssFixPolicy.EXTRA_DIFF_STATUS, fix.qualityLabel());
        extras.putInt("satellites", fix.satellites);
        if (GnssFix.isFinite(fix.hdop)) {
            extras.putFloat(ExternalGnssFixPolicy.EXTRA_HDOP, fix.hdop);
        }
        if (GnssFix.isFinite(fix.vdop)) {
            extras.putFloat(ExternalGnssFixPolicy.EXTRA_VDOP, fix.vdop);
        }
        if (GnssFix.isFinite(fix.ageOfDiff)) {
            extras.putFloat(ExternalGnssFixPolicy.EXTRA_AGE_OF_DIFF, fix.ageOfDiff);
        }
        location.setExtras(extras);
        return location;
    }
}

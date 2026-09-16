package com.nextgis.maplib.gnss;

/**
 * Talker-agnostic GNSS snapshot assembled from NMEA GGA/GST/GSA/RMC.
 * Coordinates are WGS84 decimal degrees. Missing optional fields are NaN.
 */
public final class GnssFix {
    public boolean hasPosition;
    public double latitude;
    public double longitude;
    public double altitude;
    public int quality;
    public int satellites;
    public float hdop = Float.NaN;
    public float vdop = Float.NaN;
    public float pdop = Float.NaN;
    public float ageOfDiff = Float.NaN;
    public float stdLat = Float.NaN;
    public float stdLon = Float.NaN;
    public float stdAlt = Float.NaN;
    public float speedMps = Float.NaN;
    public float bearing = Float.NaN;

    public GnssFix copy() {
        GnssFix copy = new GnssFix();
        copy.hasPosition = hasPosition;
        copy.latitude = latitude;
        copy.longitude = longitude;
        copy.altitude = altitude;
        copy.quality = quality;
        copy.satellites = satellites;
        copy.hdop = hdop;
        copy.vdop = vdop;
        copy.pdop = pdop;
        copy.ageOfDiff = ageOfDiff;
        copy.stdLat = stdLat;
        copy.stdLon = stdLon;
        copy.stdAlt = stdAlt;
        copy.speedMps = speedMps;
        copy.bearing = bearing;
        return copy;
    }

    public boolean hasFix() {
        return hasPosition && quality > 0;
    }

    public String qualityLabel() {
        switch (quality) {
            case 4:
                return "RTK FIX";
            case 5:
                return "RTK FLOAT";
            case 2:
                return "DGPS";
            case 3:
                return "PPS";
            case 1:
                return "Autonomous";
            case 6:
                return "Estimated";
            case 7:
                return "Manual";
            case 8:
                return "Simulation";
            default:
                return "No fix";
        }
    }

    public float horizontalAccuracyM() {
        if (isFinite(stdLat) && isFinite(stdLon)) {
            return (float) Math.hypot(stdLat, stdLon);
        }
        if (isFinite(hdop) && hdop > 0f) {
            return hdop * 4.7f;
        }
        return Float.NaN;
    }

    public static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}

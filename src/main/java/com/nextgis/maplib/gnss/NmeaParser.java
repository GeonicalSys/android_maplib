package com.nextgis.maplib.gnss;

/**
 * Talker-agnostic NMEA parser ($GP/$GN/$GL/$GA/$GB/$GQ) plus ComNav/NovAtel
 * {@code #BESTPOSA}. Validates checksum when present. GGA supplies position
 * and quality; GST/GSA/RMC enrich the same epoch.
 */
public final class NmeaParser {
    private final GnssFix fix = new GnssFix();
    private boolean updated;

    public void reset() {
        GnssFix empty = new GnssFix();
        copyInto(empty, fix);
        updated = false;
    }

    /** @return true when the assembled fix changed and currently has a usable position. */
    public boolean accept(String sentence) {
        updated = false;
        if (sentence == null) {
            return false;
        }
        String line = sentence.trim();
        if (line.startsWith("#")) {
            parseBestPosA(line);
            return updated && fix.hasFix();
        }
        if (!line.startsWith("$") || line.length() < 6) {
            return false;
        }
        if (!checksumOk(line)) {
            return false;
        }
        int star = line.indexOf('*');
        String body = star >= 0 ? line.substring(1, star) : line.substring(1);
        String[] fields = body.split(",", -1);
        if (fields.length == 0 || fields[0].length() < 3) {
            return false;
        }
        String type = fields[0].substring(fields[0].length() - 3).toUpperCase();
        switch (type) {
            case "GGA":
                parseGga(fields);
                break;
            case "GST":
                parseGst(fields);
                break;
            case "GSA":
                parseGsa(fields);
                break;
            case "RMC":
                parseRmc(fields);
                break;
            default:
                return false;
        }
        return updated && fix.hasFix();
    }

    private void parseBestPosA(String line) {
        if (line.length() < 10) {
            return;
        }
        if (!line.regionMatches(true, 1, "BESTPOSA", 0, 8)) {
            return;
        }
        if (!checksumOk(line)) {
            return;
        }
        int star = line.indexOf('*');
        String payload = star >= 0 ? line.substring(0, star) : line;
        int semi = payload.indexOf(';');
        if (semi < 0 || semi + 1 >= payload.length()) {
            return;
        }
        String[] fields = payload.substring(semi + 1).split(",", -1);
        if (fields.length < 14) {
            return;
        }
        String solStatus = fields[0].trim();
        String posType = fields[1].trim();
        if (!"SOL_COMPUTED".equalsIgnoreCase(solStatus)) {
            fix.hasPosition = false;
            fix.quality = 0;
            updated = true;
            return;
        }
        double lat = parseDouble(fields[2], Double.NaN);
        double lon = parseDouble(fields[3], Double.NaN);
        int quality = bestPosQuality(posType);
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || quality <= 0) {
            fix.hasPosition = false;
            fix.quality = quality;
            updated = true;
            return;
        }
        fix.hasPosition = true;
        fix.latitude = lat;
        fix.longitude = lon;
        fix.quality = quality;
        fix.altitude = parseDouble(fields[4], fix.altitude);
        if (fields.length > 8) {
            fix.stdLat = parseFloat(fields[7]);
            fix.stdLon = parseFloat(fields[8]);
        }
        if (fields.length > 11) {
            fix.ageOfDiff = parseFloat(fields[11]);
        }
        if (fields.length > 13) {
            fix.satellites = parseInt(fields[13], 0);
        }
        updated = true;
    }

    static int bestPosQuality(String posType) {
        if (posType == null) {
            return 0;
        }
        String type = posType.trim().toUpperCase();
        if (type.contains("NARROW_INT") || type.equals("L1_INT") || type.equals("WIDE_INT")
                || type.contains("RTKFIXED") || type.equals("RTK_DIRECT_INS")) {
            return 4;
        }
        if (type.contains("FLOAT") || type.equals("FLOATCONV")) {
            return 5;
        }
        if (type.equals("PSRDIFF") || type.equals("WAAS") || type.equals("CDGPS")
                || type.startsWith("OMNISTAR") || type.equals("INS_PSRDIFF")) {
            return 2;
        }
        if (type.equals("SINGLE") || type.equals("INS_PSRSP") || type.equals("PROPAGATED")
                || type.equals("DOPPLER_VELOCITY")) {
            return 1;
        }
        return 0;
    }

    /** NovAtel/ComNav BESTPOS {@code pos type} integer (OEM6). */
    static int bestPosQualityFromCode(int posType) {
        switch (posType) {
            case 48:
            case 49:
            case 50:
            case 51:
            case 56:
                return 4;
            case 4:
            case 32:
            case 33:
            case 34:
            case 55:
                return 5;
            case 17:
            case 18:
            case 20:
            case 52:
            case 54:
                return 2;
            case 1:
            case 2:
            case 8:
            case 16:
            case 19:
            case 53:
                return 1;
            default:
                return 0;
        }
    }

    public GnssFix snapshot() {
        return fix.copy();
    }

    static boolean checksumOk(String line) {
        int star = line.lastIndexOf('*');
        if (star < 1 || star + 3 > line.length()) {
            return true;
        }
        int got;
        try {
            got = Integer.parseInt(line.substring(star + 1).trim(), 16);
        } catch (NumberFormatException exception) {
            return false;
        }
        int xor = 0;
        for (int i = 1; i < star; i++) {
            xor ^= line.charAt(i);
        }
        return (xor & 0xFF) == got;
    }

    private void parseGga(String[] f) {
        if (f.length < 10) {
            return;
        }
        double lat = nmeaToDegrees(f[2], f[3]);
        double lon = nmeaToDegrees(f[4], f[5]);
        int quality = parseInt(f[6], 0);
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || quality <= 0) {
            fix.hasPosition = false;
            fix.quality = quality;
            updated = true;
            return;
        }
        fix.hasPosition = true;
        fix.latitude = lat;
        fix.longitude = lon;
        fix.quality = quality;
        fix.satellites = parseInt(f[7], 0);
        fix.hdop = parseFloat(f[8]);
        fix.altitude = parseDouble(f[9], fix.altitude);
        if (f.length > 13) {
            fix.ageOfDiff = parseFloat(f[13]);
        }
        updated = true;
    }

    private void parseGst(String[] f) {
        if (f.length < 9) {
            return;
        }
        fix.stdLat = parseFloat(f[6]);
        fix.stdLon = parseFloat(f[7]);
        fix.stdAlt = parseFloat(f[8]);
        updated = fix.hasFix();
    }

    private void parseGsa(String[] f) {
        if (f.length < 18) {
            return;
        }
        fix.pdop = parseFloat(f[15]);
        float hdop = parseFloat(f[16]);
        if (GnssFix.isFinite(hdop)) {
            fix.hdop = hdop;
        }
        fix.vdop = parseFloat(f[17]);
        updated = fix.hasFix();
    }

    private void parseRmc(String[] f) {
        if (f.length < 9) {
            return;
        }
        if (!"A".equalsIgnoreCase(f[2])) {
            return;
        }
        double lat = nmeaToDegrees(f[3], f[4]);
        double lon = nmeaToDegrees(f[5], f[6]);
        if (Double.isFinite(lat) && Double.isFinite(lon)) {
            if (!fix.hasPosition) {
                fix.hasPosition = true;
                fix.latitude = lat;
                fix.longitude = lon;
                if (fix.quality <= 0) {
                    fix.quality = 1;
                }
            }
        }
        float knots = parseFloat(f[7]);
        if (GnssFix.isFinite(knots)) {
            fix.speedMps = knots * 0.514444f;
        }
        fix.bearing = parseFloat(f[8]);
        updated = fix.hasFix();
    }

    static double nmeaToDegrees(String nmea, String hemi) {
        if (nmea == null || nmea.isEmpty()) {
            return Double.NaN;
        }
        int dot = nmea.indexOf('.');
        int split = (dot >= 0 ? dot : nmea.length()) - 2;
        if (split < 1) {
            return Double.NaN;
        }
        try {
            double degrees = Double.parseDouble(nmea.substring(0, split));
            double minutes = Double.parseDouble(nmea.substring(split));
            double value = degrees + minutes / 60d;
            if ("S".equalsIgnoreCase(hemi) || "W".equalsIgnoreCase(hemi)) {
                value = -value;
            }
            return value;
        } catch (NumberFormatException exception) {
            return Double.NaN;
        }
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static float parseFloat(String value) {
        if (value == null || value.isEmpty()) {
            return Float.NaN;
        }
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException exception) {
            return Float.NaN;
        }
    }

    private static double parseDouble(String value, double fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static void copyInto(GnssFix from, GnssFix to) {
        to.hasPosition = from.hasPosition;
        to.latitude = from.latitude;
        to.longitude = from.longitude;
        to.altitude = from.altitude;
        to.quality = from.quality;
        to.satellites = from.satellites;
        to.hdop = from.hdop;
        to.vdop = from.vdop;
        to.pdop = from.pdop;
        to.ageOfDiff = from.ageOfDiff;
        to.stdLat = from.stdLat;
        to.stdLon = from.stdLon;
        to.stdAlt = from.stdAlt;
        to.speedMps = from.speedMps;
        to.bearing = from.bearing;
    }
}

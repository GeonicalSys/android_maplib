package com.nextgis.maplib.util;

/** Pure coordinate and image-format helpers used while packing legacy tile directories. */
public final class LegacyTileMbtilesMath {
    private LegacyTileMbtilesMath() {
    }

    public static int toTmsRow(int zoom, int sourceY, int sourceTmsType) {
        if (zoom < 0 || zoom > GeoConstants.DEFAULT_MAX_ZOOM) {
            throw new IllegalArgumentException("Zoom is out of range");
        }
        int dimension = 1 << zoom;
        if (sourceY < 0 || sourceY >= dimension) {
            throw new IllegalArgumentException("Tile row is out of range");
        }
        if (sourceTmsType == GeoConstants.TMSTYPE_NORMAL) {
            return sourceY;
        }
        if (sourceTmsType == GeoConstants.TMSTYPE_OSM) {
            return dimension - sourceY - 1;
        }
        throw new IllegalArgumentException("Unsupported tile scheme");
    }

    public static double longitudeFromBoundary(double column, int dimension) {
        return column * 360.0 / dimension - 180.0;
    }

    public static double latitudeFromTmsBoundary(double row, int dimension) {
        double mercator = Math.PI * (2.0 * row / dimension - 1.0);
        return Math.toDegrees(Math.atan(Math.sinh(mercator)));
    }

    public static String detectRasterFormat(byte[] data) {
        if (data == null) {
            return null;
        }
        if (data.length >= 8
                && (data[0] & 0xff) == 0x89 && data[1] == 0x50
                && data[2] == 0x4e && data[3] == 0x47) {
            return "png";
        }
        if (data.length >= 3 && (data[0] & 0xff) == 0xff && (data[1] & 0xff) == 0xd8) {
            return "jpg";
        }
        if (data.length >= 12 && data[0] == 'R' && data[1] == 'I'
                && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return "webp";
        }
        return null;
    }
}

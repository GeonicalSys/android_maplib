package com.nextgis.maplib.util;

/** TMS tile ranges from WGS84 bounds. y=0 is south, matching MBTiles. */
public final class MbTilesTileGrid {
    public static final int MAX_HOLES_PER_ZOOM = 20_000;

    public final int minColumn;
    public final int maxColumn;
    public final int minRow;
    public final int maxRow;

    public MbTilesTileGrid(int minColumn, int maxColumn, int minRow, int maxRow) {
        this.minColumn = minColumn;
        this.maxColumn = maxColumn;
        this.minRow = minRow;
        this.maxRow = maxRow;
    }

    public int tileCount() {
        if (maxColumn < minColumn || maxRow < minRow) {
            return 0;
        }
        return (maxColumn - minColumn + 1) * (maxRow - minRow + 1);
    }

    public static int columnFromLongitude(double lon, int zoom) {
        int dimension = 1 << zoom;
        double column = (lon + 180.0) / 360.0 * dimension;
        int value = (int) Math.floor(column);
        if (value < 0) {
            return 0;
        }
        if (value >= dimension) {
            return dimension - 1;
        }
        return value;
    }

    public static int tmsRowFromLatitude(double lat, int zoom) {
        int dimension = 1 << zoom;
        double clamped = Math.max(-85.05112878, Math.min(85.05112878, lat));
        double latRad = Math.toRadians(clamped);
        double y = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI)
                / 2.0 * dimension;
        int xyzRow = (int) Math.floor(y);
        if (xyzRow < 0) {
            xyzRow = 0;
        }
        if (xyzRow >= dimension) {
            xyzRow = dimension - 1;
        }
        return dimension - 1 - xyzRow;
    }

    public static MbTilesTileGrid fromWgs84(
            double west, double south, double east, double north, int zoom) {
        int minColumn = columnFromLongitude(Math.min(west, east), zoom);
        int maxColumn = columnFromLongitude(Math.max(west, east), zoom);
        int minRow = tmsRowFromLatitude(Math.min(south, north), zoom);
        int maxRow = tmsRowFromLatitude(Math.max(south, north), zoom);
        if (minRow > maxRow) {
            int swap = minRow;
            minRow = maxRow;
            maxRow = swap;
        }
        return new MbTilesTileGrid(minColumn, maxColumn, minRow, maxRow);
    }
}

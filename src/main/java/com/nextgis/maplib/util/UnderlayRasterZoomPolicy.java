package com.nextgis.maplib.util;

/** Pure zoom and row rules used by the on-demand raster tile server. */
public final class UnderlayRasterZoomPolicy {
    public static final int MAX_UNDERZOOM_LEVELS = 4;

    private UnderlayRasterZoomPolicy() {
    }

    public static int sourceMinZoom() {
        return (int) GeoConstants.UNDERLAY_MIN_ZOOM;
    }

    public static float rasterMinZoom() {
        return GeoConstants.UNDERLAY_MIN_ZOOM;
    }

    public static boolean canComposeUnderzoom(int requestedZoom, int tileMinZoom) {
        return requestedZoom >= 0
                && tileMinZoom > requestedZoom
                && tileMinZoom - requestedZoom <= MAX_UNDERZOOM_LEVELS;
    }

    public static long databaseRow(int zoom, int xyzRow, boolean xyzScheme) {
        if (xyzScheme) {
            return xyzRow;
        }
        return (1L << zoom) - 1L - xyzRow;
    }
}

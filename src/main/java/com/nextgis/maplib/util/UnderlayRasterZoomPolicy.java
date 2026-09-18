package com.nextgis.maplib.util;

/**
 * MapLibre zoom for local MBTiles underlays. Pyramid levels come from file
 * metadata, never from padded {@code min_level}/{@code max_level}.
 */
public final class UnderlayRasterZoomPolicy {
    private UnderlayRasterZoomPolicy() {
    }

    /** Prefer metadata/MAX(tiles); ignore visibility padding. */
    public static int pyramidZoom(int metadataZoom, int paddedVisibilityZoom) {
        if (metadataZoom >= 0) {
            return metadataZoom;
        }
        return paddedVisibilityZoom;
    }

    public static boolean shouldFillHole(int zoom, int tileMin, int tileMax) {
        return tileMin >= 0 && tileMax >= tileMin && zoom >= tileMin && zoom <= tileMax;
    }

    public static Integer tileSetMinZoom(boolean lastLevelOverzoom, int tileMin) {
        if (lastLevelOverzoom && tileMin >= 0) {
            return tileMin;
        }
        return null;
    }

    public static Integer tileSetMaxZoom(boolean lastLevelOverzoom, int tileMax) {
        if (lastLevelOverzoom && tileMax >= 0) {
            return tileMax;
        }
        return null;
    }

    public static Float rasterLayerMinZoom(
            boolean lastLevelOverzoom, int tileMin, float layerMin) {
        if (lastLevelOverzoom && tileMin >= 0) {
            return (float) tileMin;
        }
        if (layerMin == -1f) {
            return null;
        }
        return layerMin;
    }

    public static Float rasterLayerMaxZoom(
            boolean lastLevelOverzoom, int tileMax, float layerMax) {
        if (lastLevelOverzoom && tileMax >= 0) {
            return (float) GeoConstants.DEFAULT_MAX_ZOOM;
        }
        if (layerMax == -1f) {
            return null;
        }
        return layerMax + 1f;
    }
}

/*
 * Project: NextGIS Mobile
 * Purpose: Local vector tile render-mode routing for NGW-backed layers.
 */

package com.nextgis.maplib.map;

import com.nextgis.maplib.util.Constants;

/**
 * Foundation for the future local-vector-tiles renderer.
 *
 * This class is intentionally small while the actual tile provider is not implemented yet. Keeping
 * the render-mode checks centralized prevents the temporary classic fallback from being mistaken for
 * unused code and gives the MVT/PMTiles implementation a single switch point later.
 */
public final class LocalVectorTileRenderMode {
    private LocalVectorTileRenderMode() {
    }

    public static boolean isRequested(VectorLayer layer) {
        if (!(layer instanceof NGWVectorLayer)) {
            return false;
        }
        LayerOriginMetadata origin = ((NGWVectorLayer) layer).getLayerOriginMetadata();
        return origin != null
                && LayerOriginMetadata.RENDER_MODE_LOCAL_VECTOR_TILES.equals(origin.getRenderMode());
    }

    public static boolean isEnabled() {
        return Constants.LOCAL_VECTOR_TILES_ENABLED;
    }

    public static boolean isProviderAvailable() {
        return true;
    }

    public static boolean shouldUseLocalVectorTiles(VectorLayer layer) {
        return isRequested(layer) && isEnabled() && isProviderAvailable();
    }

    public static boolean shouldFallbackToClassic(VectorLayer layer) {
        return isRequested(layer) && !shouldUseLocalVectorTiles(layer);
    }
}

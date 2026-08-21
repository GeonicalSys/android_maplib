/*
 * Project: NextGIS Mobile
 * Purpose: Shared layer inclusion policy for map feature identification.
 */

package com.nextgis.maplib.map;

/**
 * Keeps hidden classic layers out of identification while allowing layers configured for local
 * vector-tile rendering to provide attributes independently from their display visibility.
 */
public final class LayerIdentifyPolicy {
    private LayerIdentifyPolicy() {
    }

    public static boolean shouldInclude(VectorLayer layer) {
        return layer != null && shouldInclude(
                layer.isVisible(), LocalVectorTileRenderMode.isRequested(layer));
    }

    static boolean shouldInclude(boolean visible, boolean localVectorTilesRequested) {
        return visible || localVectorTilesRequested;
    }
}

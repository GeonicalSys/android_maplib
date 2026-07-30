/*
 * Project: NextGIS Mobile
 * Purpose: Decide when enabling a vector layer requires rebuilding live MapLibre state.
 */

package com.nextgis.maplib.map;

final class LayerVisibilityReloadPolicy {
    private LayerVisibilityReloadPolicy() {
    }

    static boolean shouldReload(
            boolean visible,
            boolean sourcePresent,
            boolean renderLayerPresent,
            boolean preparedEntryPresent) {
        return visible
                && (!sourcePresent || !renderLayerPresent || !preparedEntryPresent);
    }
}

/*
 * Project: NextGIS Mobile
 * Purpose: Shared layer inclusion policy for map feature identification.
 */

package com.nextgis.maplib.map;

/**
 * Identifies visible layers independently from obsolete rendering metadata.
 */
public final class LayerIdentifyPolicy {
    private LayerIdentifyPolicy() {
    }

    public static boolean shouldInclude(VectorLayer layer) {
        return layer != null && layer.isVisible();
    }
}

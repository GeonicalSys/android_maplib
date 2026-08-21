/*
 * Project: NextGIS Mobile
 * Purpose: Keep the shared MapLibre edit fill exclusive to polygon geometry.
 */

package com.nextgis.maplib.map;

import com.nextgis.maplib.util.GeoConstants;

final class GeometryEditFillPolicy {
    private GeometryEditFillPolicy() {
    }

    static boolean shouldShow(int geometryType) {
        return geometryType == GeoConstants.GTPolygon
                || geometryType == GeoConstants.GTMultiPolygon;
    }
}

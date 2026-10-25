package com.nextgis.maplib.map;

import com.nextgis.maplib.util.GeoConstants;

/** Default display role of the named standard field line layer; not a hit-test filter. */
final class LayerDisplayPriority {
    private LayerDisplayPriority() { }

    static boolean isFieldLines(int geometryType, String name) {
        if (geometryType != GeoConstants.GTLineString && geometryType != GeoConstants.GTMultiLineString) return false;
        if (name == null) return false;
        String value = name.trim();
        return "Полевые линейные".equalsIgnoreCase(value) || "field_lines".equalsIgnoreCase(value);
    }
}

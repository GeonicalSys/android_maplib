/*
 * Shared text label size helpers for Simple*Style types.
 */
package com.nextgis.maplib.display;

import java.util.ArrayList;
import java.util.Arrays;

public final class TextStyleUtil {

    public static final ArrayList<Float> LABEL_TEXT_SIZES = new ArrayList<>(
            Arrays.asList(1f, 3f, 6f, 10f, 12f));

    private TextStyleUtil() {
    }

    public static float getTextSize(Style style) {
        if (style instanceof SimpleMarkerStyle) {
            return ((SimpleMarkerStyle) style).getTextSize();
        }
        if (style instanceof SimpleLineStyle) {
            return ((SimpleLineStyle) style).getTextSize();
        }
        if (style instanceof SimplePolygonStyle) {
            Float size = ((SimplePolygonStyle) style).getTextSize();
            return size != null ? size : 12f;
        }
        return LABEL_TEXT_SIZES.get(0);
    }

    public static void setTextSize(Style style, float size) {
        if (style instanceof SimpleMarkerStyle) {
            ((SimpleMarkerStyle) style).setTextSize(size);
        } else if (style instanceof SimpleLineStyle) {
            ((SimpleLineStyle) style).setTextSize(size);
        } else if (style instanceof SimplePolygonStyle) {
            ((SimplePolygonStyle) style).setTextSize(size);
        }
    }

    public static int getTextColor(Style style) {
        if (style instanceof SimpleMarkerStyle) {
            return ((SimpleMarkerStyle) style).getTextColor();
        }
        if (style instanceof SimpleLineStyle) {
            return ((SimpleLineStyle) style).getTextColor();
        }
        if (style instanceof SimplePolygonStyle) {
            return ((SimplePolygonStyle) style).getTextColor();
        }
        return android.graphics.Color.BLACK;
    }

    public static void setTextColor(Style style, int color) {
        if (style instanceof SimpleMarkerStyle) {
            ((SimpleMarkerStyle) style).setTextColor(color);
        } else if (style instanceof SimpleLineStyle) {
            ((SimpleLineStyle) style).setTextColor(color);
        } else if (style instanceof SimplePolygonStyle) {
            ((SimplePolygonStyle) style).setTextColor(color);
        }
    }

    public static int indexOfTextSize(float size) {
        int index = LABEL_TEXT_SIZES.indexOf(size);
        if (index >= 0) {
            return index;
        }
        float closest = LABEL_TEXT_SIZES.get(0);
        int closestIndex = 0;
        for (int i = 1; i < LABEL_TEXT_SIZES.size(); i++) {
            float candidate = LABEL_TEXT_SIZES.get(i);
            if (Math.abs(candidate - size) < Math.abs(closest - size)) {
                closest = candidate;
                closestIndex = i;
            }
        }
        return closestIndex;
    }
}

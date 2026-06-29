/*
 * Maps NG Style fields to MapLibre paint/layout values (opacity, line cap/join).
 */
package com.nextgis.maplib.display;

import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.Property;

import static com.nextgis.maplib.display.SimpleLineStyle.LineStyleDash;
import static com.nextgis.maplib.display.SimpleLineStyle.LineStyleEdgingDash;
import static com.nextgis.maplib.display.SimpleLineStyle.LineStyleEdgingSolid;

public final class MplStyleMapper {

    public static final String PROP_FILL_OPACITY = "opacity";
    public static final String PROP_STROKE_OPACITY = "strokeopacity";

    public static final int LINE_CAP_ROUND = 0;
    public static final int LINE_CAP_BUTT = 1;
    public static final int LINE_CAP_SQUARE = 2;

    public static final int LINE_JOIN_ROUND = 0;
    public static final int LINE_JOIN_MITER = 1;
    public static final int LINE_JOIN_BEVEL = 2;

    public static final int DASH_PRESET_SHORT = 0;
    public static final int DASH_PRESET_LONG = 1;
    public static final int DASH_PRESET_DOT_DASH = 2;
    public static final int DASH_PRESET_DOTS = 3;
    public static final int DASH_PRESET_COUNT = 4;

    public static final float DEFAULT_LINE_MITER_LIMIT = 2f;

    public static final String PROP_TEXT_OPACITY = "textopacity";

    public static final float DEFAULT_CIRCLE_BLUR = 0f;
    public static final float DEFAULT_LINE_BLUR = 0f;

    /** UI presets 0 (off), 1, 2, 3 — stored as-is in style JSON. */
    public static final int BLUR_PRESET_OFF = 0;
    public static final int BLUR_PRESET_MAX = 3;

    public static float blurPresetValue(int presetIndex) {
        return Math.max(0f, Math.min(BLUR_PRESET_MAX, presetIndex));
    }

    public static int blurPresetIndex(float storedValue) {
        return Math.max(0, Math.min(BLUR_PRESET_MAX, Math.round(storedValue)));
    }

    /**
     * MapLibre line-blur on thin strokes needs a higher multiplier than circle-blur
     * for a comparable visible glow.
     */
    public static float lineBlurPaintValue(float storedBlur) {
        if (storedBlur <= 0f) {
            return 0f;
        }
        return storedBlur * 4f;
    }

    private MplStyleMapper() {
    }

    public static float alphaToOpacity(int alpha) {
        return Math.max(0f, Math.min(1f, alpha / 255f));
    }

    public static float polygonFillOpacity(Style style) {
        if (style instanceof SimplePolygonStyle && !((SimplePolygonStyle) style).isFill()) {
            return 0f;
        }
        return alphaToOpacity(style.getAlpha());
    }

    /**
     * Data-driven opacity with layer default. Do not wrap {@code get} in {@code toNumber} inside
     * {@code coalesce}: missing properties become 0 and hide geometry.
     */
    public static Expression fillOpacityExpression(String propName, float defaultOpacity) {
        return Expression.coalesce(
                Expression.get(propName),
                Expression.literal(defaultOpacity));
    }

    public static Expression opacityWithLayerMultiplier(Expression opacityExpr, float layerOpacityFactor) {
        if (layerOpacityFactor >= 0.999f) {
            return opacityExpr;
        }
        return Expression.product(opacityExpr, Expression.literal(layerOpacityFactor));
    }

    public static Expression fillOpacityExpression(
            String propName, float defaultOpacity, float layerOpacityFactor) {
        return opacityWithLayerMultiplier(
                fillOpacityExpression(propName, defaultOpacity), layerOpacityFactor);
    }

    public static Expression textOpacityExpression(float defaultOpacity, float layerOpacityFactor) {
        return opacityWithLayerMultiplier(
                Expression.literal(defaultOpacity), layerOpacityFactor);
    }

    public static Expression textOpacityExpression(
            String propName, float defaultOpacity, float layerOpacityFactor) {
        return opacityWithLayerMultiplier(
                fillOpacityExpression(propName, defaultOpacity), layerOpacityFactor);
    }

    public static float lineMiterLimit(int lineJoin, float miterLimit) {
        if (lineJoin != LINE_JOIN_MITER) {
            return DEFAULT_LINE_MITER_LIMIT;
        }
        return miterLimit > 0f ? miterLimit : DEFAULT_LINE_MITER_LIMIT;
    }

    public static String lineCapValue(int cap) {
        switch (cap) {
            case LINE_CAP_BUTT:
                return Property.LINE_CAP_BUTT;
            case LINE_CAP_SQUARE:
                return Property.LINE_CAP_SQUARE;
            case LINE_CAP_ROUND:
            default:
                return Property.LINE_CAP_ROUND;
        }
    }

    public static String lineJoinValue(int join) {
        switch (join) {
            case LINE_JOIN_MITER:
                return Property.LINE_JOIN_MITER;
            case LINE_JOIN_BEVEL:
                return Property.LINE_JOIN_BEVEL;
            case LINE_JOIN_ROUND:
            default:
                return Property.LINE_JOIN_ROUND;
        }
    }

    public static Float[] dashArray(int preset) {
        switch (preset) {
            case DASH_PRESET_LONG:
                return new Float[]{8f, 4f};
            case DASH_PRESET_DOT_DASH:
                return new Float[]{4f, 2f, 1f, 2f};
            case DASH_PRESET_DOTS:
                return new Float[]{1f, 3f};
            case DASH_PRESET_SHORT:
            default:
                return new Float[]{2f, 2f};
        }
    }

    public static boolean isLineDashType(int type) {
        return type == LineStyleDash || type == LineStyleEdgingDash;
    }

    public static boolean isLineOutlineType(int type) {
        return type == LineStyleEdgingSolid || type == LineStyleEdgingDash;
    }

    public static Expression solidMainLineFilter(Expression lineTypeEffective) {
        return Expression.all(
                Expression.neq(lineTypeEffective, Expression.literal((double) LineStyleDash)),
                Expression.neq(lineTypeEffective, Expression.literal((double) LineStyleEdgingDash)));
    }

    public static Expression outlineLineOpacity(
            Expression lineTypeEffective, Expression outlineOpacity) {
        return Expression.switchCase(
                Expression.any(
                        Expression.eq(lineTypeEffective, Expression.literal((double) LineStyleEdgingSolid)),
                        Expression.eq(lineTypeEffective, Expression.literal((double) LineStyleEdgingDash))),
                outlineOpacity,
                Expression.literal(0.0));
    }

    public static Expression dashPresetFilter(Expression lineTypeEffective, int preset, int defaultPreset) {
        return Expression.all(
                Expression.any(
                        Expression.eq(lineTypeEffective, Expression.literal((double) LineStyleDash)),
                        Expression.eq(lineTypeEffective, Expression.literal((double) LineStyleEdgingDash))),
                Expression.eq(
                        Expression.toNumber(Expression.coalesce(
                                Expression.get("dashpreset"),
                                Expression.literal((double) defaultPreset))),
                        Expression.literal((double) preset)));
    }

    /**
     * Same zoom curve as label text-size scaling in {@code MPLFeaturesUtils.buildTextSizeExpression}.
     */
    public static Expression zoomScaleExpression(Expression baseSize, boolean scaleWithZoom) {
        if (!scaleWithZoom) {
            return baseSize;
        }
        return Expression.interpolate(
                Expression.linear(),
                Expression.zoom(),
                Expression.stop(6, Expression.product(baseSize, Expression.literal(0.35))),
                Expression.stop(10, Expression.product(baseSize, Expression.literal(0.65))),
                Expression.stop(14, baseSize),
                Expression.stop(18, Expression.product(baseSize, Expression.literal(1.5))),
                Expression.stop(22, Expression.product(baseSize, Expression.literal(2.2))));
    }
}

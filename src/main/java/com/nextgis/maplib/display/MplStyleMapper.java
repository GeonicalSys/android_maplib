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

    /**
     * Text opacity with optional per-feature label zoom range.
     * <p>
     * MapLibre requires {@code ["zoom"]} only as the input of a top-level {@code step}/
     * {@code interpolate}. Nesting zoom under {@code case} is rejected, so visibility is
     * encoded as {@code step(zoom, ...)} with data-only outputs per integer level.
     */
    public static Expression textOpacityWithLabelZoomExpression(
            String opacityProp,
            String minZoomProp,
            String maxZoomProp,
            float defaultOpacity,
            float layerOpacityFactor) {
        Expression baseOpacity = opacityWithLayerMultiplier(
                fillOpacityExpression(opacityProp, defaultOpacity), layerOpacityFactor);
        Expression minZoom = Expression.toNumber(Expression.coalesce(
                Expression.get(minZoomProp),
                Expression.literal(-1.0)));
        Expression maxZoom = Expression.toNumber(Expression.coalesce(
                Expression.get(maxZoomProp),
                Expression.literal(-1.0)));

        Expression.Stop[] stops = new Expression.Stop[24];
        for (int z = 1; z <= 24; z++) {
            stops[z - 1] = Expression.stop((double) z, opacityAtZoomLevel(z, minZoom, maxZoom, baseOpacity));
        }
        return Expression.step(
                Expression.zoom(),
                opacityAtZoomLevel(0, minZoom, maxZoom, baseOpacity),
                stops);
    }

    /** Data-only gate for a fixed integer zoom (no {@code ["zoom"]} operator). */
    private static Expression opacityAtZoomLevel(
            int zoomLevel,
            Expression minZoom,
            Expression maxZoom,
            Expression baseOpacity) {
        Expression belowMin = Expression.all(
                Expression.gte(minZoom, Expression.literal(0.0)),
                Expression.lt(Expression.literal((double) zoomLevel), minZoom));
        Expression aboveMax = Expression.all(
                Expression.gte(maxZoom, Expression.literal(0.0)),
                Expression.gt(Expression.literal((double) zoomLevel), maxZoom));
        return Expression.switchCase(
                Expression.any(belowMin, aboveMax),
                Expression.literal(0.0),
                baseOpacity);
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

    public static Float[] dashArray(String customDashArray, int fallbackPreset) {
        Float[] parsed = parseFloatList(customDashArray, 2, 16);
        return parsed != null ? parsed : dashArray(fallbackPreset);
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
     * <p>
     * When the scale flag is data-driven, {@code ["zoom"]} must stay the input of a top-level
     * {@code interpolate}; put feature {@code case} only in stop outputs. Nesting
     * {@code interpolate(zoom)} under {@code case} is rejected by the MapLibre style spec.
     */
    public static Expression zoomScaleExpression(Expression baseSize, boolean scaleWithZoom) {
        return zoomScaleExpression(baseSize, scaleWithZoom, null);
    }

    public static Expression zoomScaleExpression(
            Expression baseSize,
            boolean scaleWithZoom,
            String customStops) {
        if (!scaleWithZoom) {
            return baseSize;
        }
        ZoomStop[] stops = parseZoomStops(customStops);
        if (stops != null) {
            Expression.Stop[] expressionStops = new Expression.Stop[stops.length];
            int out = 0;
            for (ZoomStop stop : stops) {
                expressionStops[out++] = Expression.stop(
                        stop.zoom,
                        Expression.product(baseSize, Expression.literal(stop.multiplier)));
            }
            return Expression.interpolate(Expression.linear(), Expression.zoom(), expressionStops);
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

    public static Expression zoomScaleExpression(
            Expression baseSize,
            Expression scaleWithZoom,
            boolean defaultScaleWithZoom,
            String customStops) {
        if (scaleWithZoom == null) {
            return zoomScaleExpression(baseSize, defaultScaleWithZoom, customStops);
        }
        Expression effectiveScaleWithZoom = Expression.toBool(
                Expression.coalesce(
                        scaleWithZoom,
                        Expression.literal(defaultScaleWithZoom)));
        ZoomStop[] stops = parseZoomStops(customStops);
        if (stops != null) {
            Expression.Stop[] expressionStops = new Expression.Stop[stops.length];
            int out = 0;
            for (ZoomStop stop : stops) {
                expressionStops[out++] = Expression.stop(
                        stop.zoom,
                        zoomScaledStopValue(baseSize, effectiveScaleWithZoom, stop.multiplier));
            }
            return Expression.interpolate(Expression.linear(), Expression.zoom(), expressionStops);
        }
        return Expression.interpolate(
                Expression.linear(),
                Expression.zoom(),
                Expression.stop(6, zoomScaledStopValue(baseSize, effectiveScaleWithZoom, 0.35)),
                Expression.stop(10, zoomScaledStopValue(baseSize, effectiveScaleWithZoom, 0.65)),
                Expression.stop(14, baseSize),
                Expression.stop(18, zoomScaledStopValue(baseSize, effectiveScaleWithZoom, 1.5)),
                Expression.stop(22, zoomScaledStopValue(baseSize, effectiveScaleWithZoom, 2.2)));
    }

    private static Expression zoomScaledStopValue(
            Expression baseSize,
            Expression scaleWithZoom,
            double multiplier) {
        if (Math.abs(multiplier - 1.0) < 0.000001) {
            return baseSize;
        }
        return Expression.switchCase(
                scaleWithZoom,
                Expression.product(baseSize, Expression.literal(multiplier)),
                baseSize);
    }

    private static Float[] parseFloatList(String spec, int minCount, int maxCount) {
        if (spec == null || spec.trim().isEmpty()) {
            return null;
        }
        String[] parts = spec.split("[,;\\s]+");
        if (parts.length < minCount || parts.length > maxCount) {
            return null;
        }
        Float[] values = new Float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                float value = Float.parseFloat(parts[i].trim());
                if (value <= 0f) {
                    return null;
                }
                values[i] = value;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return values;
    }

    private static ZoomStop[] parseZoomStops(String spec) {
        if (spec == null || spec.trim().isEmpty()) {
            return null;
        }
        String[] pairs = spec.split("[,;]+");
        if (pairs.length < 2 || pairs.length > 12) {
            return null;
        }
        ZoomStop[] stops = new ZoomStop[pairs.length];
        float prevZoom = -Float.MAX_VALUE;
        for (int i = 0; i < pairs.length; i++) {
            String[] parts = pairs[i].trim().split("[:=]");
            if (parts.length != 2) {
                return null;
            }
            try {
                float zoom = Float.parseFloat(parts[0].trim());
                float multiplier = Float.parseFloat(parts[1].trim());
                if (zoom <= prevZoom || multiplier <= 0f) {
                    return null;
                }
                stops[i] = new ZoomStop(zoom, multiplier);
                prevZoom = zoom;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return stops;
    }

    private static final class ZoomStop {
        final float zoom;
        final float multiplier;

        ZoomStop(float zoom, float multiplier) {
            this.zoom = zoom;
            this.multiplier = multiplier;
        }
    }
}

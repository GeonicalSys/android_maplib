package com.nextgis.maplib.map.mpl;

import static com.nextgis.maplib.map.MPLFeaturesUtils.dash_namepart;
import static com.nextgis.maplib.map.MPLFeaturesUtils.getColorName;
import static com.nextgis.maplib.map.MPLFeaturesUtils.getMPLThinkness;
import static com.nextgis.maplib.map.MPLFeaturesUtils.layer_namepart;
import static com.nextgis.maplib.map.MPLFeaturesUtils.outline_namepart;

import com.nextgis.maplib.display.MplStyleMapper;
import com.nextgis.maplib.display.FieldStyleRule;
import com.nextgis.maplib.display.RuleFeatureRenderer;
import com.nextgis.maplib.display.SimpleLineStyle;
import com.nextgis.maplib.display.Style;
import com.nextgis.maplib.map.MplFeatureStyleProps;
import com.nextgis.maplib.map.RuleGeometryLayouts;
import com.nextgis.maplib.map.VectorLayer;

import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.PropertyFactory;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Builds MapLibre line / multiline layers (main, outline, optional dash sublayers). */
public final class LineLayerFactory {
    public static final String DASH_KEY = "line_dash_key";
    public static String dashKey(SimpleLineStyle line) {
        return Arrays.toString(MplStyleMapper.dashArray(line.getDashArray(), line.getDashPreset()))
                + "|" + RuleGeometryLayouts.key(line);
    }

    private LineLayerFactory() {
    }

    public static void build(MplLayerBuildContext ctx, MplLayerBuildResult result) {
        MplLayerStyleVars vars = ctx.vars;

        String lineMainId = ctx.namePrefix + layer_namepart + ctx.layerId;
        String lineOutlineId = ctx.namePrefix + layer_namepart + ctx.layerId + outline_namepart;
        String legacyDashId = ctx.namePrefix + layer_namepart + ctx.layerId + dash_namepart;
        boolean useDashSublayers = ctx.ruleStyling && ctx.layersHashMapLineDash != null;
        Layer legacyDash = ctx.mapStyle.getLayer(legacyDashId);
        if (legacyDash != null) {
            ctx.mapStyle.removeLayer(legacyDash);
        }
        removeDashSublayers(ctx, legacyDashId);

        // Reuse existing layers, but drop any wrong-kind layer left from a previous geometry/style
        // so the (LineLayer) casts below cannot throw ClassCastException (mirror PointLayerFactory).
        Layer newLayer = ctx.mapStyle.getLayer(lineMainId);
        if (newLayer != null && !(newLayer instanceof LineLayer)) {
            ctx.mapStyle.removeLayer(newLayer);
            newLayer = null;
        }
        Layer newLayer2 = ctx.mapStyle.getLayer(lineOutlineId);
        if (newLayer2 != null && !(newLayer2 instanceof LineLayer)) {
            ctx.mapStyle.removeLayer(newLayer2);
            newLayer2 = null;
        }
        if (newLayer == null) {
            newLayer = new LineLayer(lineMainId, ctx.layerPath);
        }
        if (newLayer2 == null) {
            newLayer2 = new LineLayer(lineOutlineId, ctx.layerPath);
        }

        Expression lineTypeEffective = Expression.toNumber(Expression.coalesce(
                Expression.get(MplFeatureStyleProps.FILL_TYPE),
                Expression.literal((double) vars.type)));
        Expression scaleSizeWithZoom = ctx.ruleStyling
                ? Expression.get(MplFeatureStyleProps.SCALE_SIZE_WITH_ZOOM)
                : null;
        boolean defaultScaleSizeWithZoom = vars.scaleSizeWithZoom;

        Expression lineWidthBase = Expression.coalesce(
                Expression.get(MplFeatureStyleProps.THICKNESS),
                Expression.literal((double) getMPLThinkness(vars.thickness)));
        Expression lineWidthInner = ctx.scaleSize(
                lineWidthBase,
                scaleSizeWithZoom,
                defaultScaleSizeWithZoom,
                vars.sizeZoomScaleStops);
        Expression lineOutlineMultiplier = Expression.coalesce(
                Expression.get(MplFeatureStyleProps.LINE_OUTLINE_MULTIPLIER),
                Expression.literal((double) vars.lineOutlineMultiplier));
        Expression lineWidthOutline = ctx.scaleSize(
                Expression.product(lineWidthBase, lineOutlineMultiplier),
                scaleSizeWithZoom,
                defaultScaleSizeWithZoom,
                vars.sizeZoomScaleStops);

        Expression lineOpacityInner = MplStyleMapper.fillOpacityExpression(
                MplFeatureStyleProps.OPACITY, vars.fillOpacity, ctx.layerOpacityFactor);
        Expression lineOpacityOutline = MplStyleMapper.fillOpacityExpression(
                MplFeatureStyleProps.STROKE_OPACITY, vars.strokeOpacity, ctx.layerOpacityFactor);
        float lineMiterLimitValue = MplStyleMapper.lineMiterLimit(vars.lineJoin, vars.lineMiterLimit);
        String lineCapValue = MplStyleMapper.lineCapValue(vars.lineCap);
        String lineJoinValue = MplStyleMapper.lineJoinValue(vars.lineJoin);
        float lineMiterLimit = lineMiterLimitValue;
        Expression lineColorExpr = Expression.coalesce(
                Expression.get(MplFeatureStyleProps.COLOR_FILL),
                Expression.literal(getColorName(vars.fillColor)));

        Expression lineBlur = Expression.coalesce(
                Expression.get(MplFeatureStyleProps.LINE_BLUR),
                Expression.literal((double) MplStyleMapper.lineBlurPaintValue(vars.lineBlur)));
        Expression lineOffset = Expression.coalesce(
                Expression.get(MplFeatureStyleProps.LINE_OFFSET),
                Expression.literal((double) vars.lineOffset));
        Expression lineGapWidth = Expression.coalesce(
                Expression.get(MplFeatureStyleProps.LINE_GAP_WIDTH),
                Expression.literal((double) vars.lineGapWidth));
        Expression lineSortKey = Expression.toNumber(Expression.get(MplFeatureStyleProps.ORDER));

        if (useDashSublayers) {
            ((LineLayer) newLayer).setFilter(MplStyleMapper.solidMainLineFilter(lineTypeEffective));
            RuleFeatureRenderer renderer = (RuleFeatureRenderer) ((VectorLayer) ctx.iLayer).getRenderer();
            Map<String, SimpleLineStyle> strokes = new LinkedHashMap<>();
            Style fallback = renderer.getStyle();
            FieldStyleRule rules = renderer.getStyleRule() instanceof FieldStyleRule ? (FieldStyleRule) renderer.getStyleRule() : null;
            Style other = rules == null ? fallback : rules.resolveOtherStyle(fallback);
            if (other instanceof SimpleLineStyle && MplStyleMapper.isLineDashType(((SimpleLineStyle) other).getType()))
                strokes.put(dashKey((SimpleLineStyle) other), (SimpleLineStyle) other);
            if (rules != null) for (String category : rules.getStyleRules().keySet()) {
                Style effective = rules.resolveEffectiveStyle(category, fallback);
                if (effective instanceof SimpleLineStyle && MplStyleMapper.isLineDashType(((SimpleLineStyle) effective).getType()))
                    strokes.put(dashKey((SimpleLineStyle) effective), (SimpleLineStyle) effective);
            }
            int index = 0;
            for (Map.Entry<String, SimpleLineStyle> stroke : strokes.entrySet()) {
                String dashLayerId = legacyDashId + "_" + (++index);
                Layer existingDash = ctx.mapStyle.getLayer(dashLayerId);
                if (existingDash != null && !(existingDash instanceof LineLayer)) {
                    ctx.mapStyle.removeLayer(existingDash);
                    existingDash = null;
                }
                LineLayer dashLayer = (LineLayer) existingDash;
                if (dashLayer == null) {
                    dashLayer = new LineLayer(dashLayerId, ctx.layerPath);
                }
                dashLayer.setFilter(Expression.eq(Expression.get(DASH_KEY), Expression.literal(stroke.getKey())));
                dashLayer.setProperties(
                        PropertyFactory.lineColor(lineColorExpr),
                        PropertyFactory.lineWidth(lineWidthInner),
                        PropertyFactory.lineOpacity(lineOpacityInner),
                        PropertyFactory.lineCap(MplStyleMapper.lineCapValue(stroke.getValue().getLineCap())),
                        PropertyFactory.lineJoin(MplStyleMapper.lineJoinValue(stroke.getValue().getLineJoin())),
                        PropertyFactory.lineMiterLimit(MplStyleMapper.lineMiterLimit(stroke.getValue().getLineJoin(), stroke.getValue().getLineMiterLimit())),
                        PropertyFactory.lineBlur(lineBlur),
                        PropertyFactory.lineOffset(lineOffset),
                        PropertyFactory.lineGapWidth(lineGapWidth),
                        PropertyFactory.lineSortKey(lineSortKey),
                        PropertyFactory.lineDasharray(
                                MplStyleMapper.dashArray(stroke.getValue().getDashArray(), stroke.getValue().getDashPreset())));
                result.dashLayers.add(dashLayer);
            }
        }

        LineLayer mainLine = (LineLayer) newLayer;
        Expression defaultDashArray = Expression.literal(
                MplStyleMapper.dashArray(vars.dashArray, vars.dashPreset));
        if (!useDashSublayers) {
            mainLine.setFilter(Expression.all());
        }
        if (!useDashSublayers && MplStyleMapper.isLineDashType(vars.type)) {
            mainLine.setProperties(
                    PropertyFactory.lineColor(lineColorExpr),
                    PropertyFactory.lineWidth(lineWidthInner),
                    PropertyFactory.lineOpacity(lineOpacityInner),
                    PropertyFactory.lineCap(lineCapValue),
                    PropertyFactory.lineJoin(lineJoinValue),
                    PropertyFactory.lineMiterLimit(lineMiterLimit),
                    PropertyFactory.lineBlur(lineBlur),
                    PropertyFactory.lineOffset(lineOffset),
                    PropertyFactory.lineGapWidth(lineGapWidth),
                    PropertyFactory.lineSortKey(lineSortKey),
                    PropertyFactory.lineDasharray(defaultDashArray));
        } else {
            // A nullable value clears prior dashes. A null Expression would crash in toArray().
            mainLine.setProperties(
                    PropertyFactory.lineColor(lineColorExpr),
                    PropertyFactory.lineWidth(lineWidthInner),
                    PropertyFactory.lineOpacity(lineOpacityInner),
                    PropertyFactory.lineCap(lineCapValue),
                    PropertyFactory.lineJoin(lineJoinValue),
                    PropertyFactory.lineMiterLimit(lineMiterLimit),
                    PropertyFactory.lineBlur(lineBlur),
                    PropertyFactory.lineOffset(lineOffset),
                    PropertyFactory.lineGapWidth(lineGapWidth),
                    PropertyFactory.lineDasharray((Float[]) null),
                    PropertyFactory.lineSortKey(lineSortKey));
        }

        ((LineLayer) newLayer2).setFilter(Expression.all());
        newLayer2.setProperties(
                PropertyFactory.lineColor(Expression.coalesce(
                        Expression.get(MplFeatureStyleProps.COLOR_STROKE),
                        Expression.literal(getColorName(vars.outlineColor)))),
                PropertyFactory.lineWidth(lineWidthOutline),
                PropertyFactory.lineOffset(lineOffset),
                PropertyFactory.lineSortKey(lineSortKey),
                PropertyFactory.lineCap(lineCapValue),
                PropertyFactory.lineJoin(lineJoinValue),
                PropertyFactory.lineMiterLimit(lineMiterLimit),
                PropertyFactory.lineOpacity(
                        useDashSublayers
                                ? MplStyleMapper.outlineLineOpacity(lineTypeEffective, lineOpacityOutline)
                                : (MplStyleMapper.isLineOutlineType(vars.type)
                                        ? lineOpacityOutline
                                        : Expression.literal(0.0f))));

        result.mainLayer = newLayer;
        result.outlineLayer = newLayer2;
    }

    private static void removeDashSublayers(MplLayerBuildContext ctx, String legacyDashId) {
        if (ctx.layersHashMapLineDash != null) {
            ctx.layersHashMapLineDash.remove(ctx.layerId);
        }
        for (Layer layer : ctx.mapStyle.getLayers())
            if (layer.getId().startsWith(legacyDashId + "_")) ctx.mapStyle.removeLayer(layer);
    }
}

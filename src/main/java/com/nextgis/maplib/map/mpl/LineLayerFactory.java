package com.nextgis.maplib.map.mpl;

import static com.nextgis.maplib.map.MPLFeaturesUtils.dash_namepart;
import static com.nextgis.maplib.map.MPLFeaturesUtils.getColorName;
import static com.nextgis.maplib.map.MPLFeaturesUtils.getMPLThinkness;
import static com.nextgis.maplib.map.MPLFeaturesUtils.layer_namepart;
import static com.nextgis.maplib.map.MPLFeaturesUtils.outline_namepart;

import com.nextgis.maplib.display.MplStyleMapper;
import com.nextgis.maplib.map.MplFeatureStyleProps;

import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.PropertyFactory;

/** Builds MapLibre line / multiline layers (main, outline, optional dash sublayers). */
public final class LineLayerFactory {

    private LineLayerFactory() {
    }

    public static void build(MplLayerBuildContext ctx, MplLayerBuildResult result) {
        MplLayerStyleVars vars = ctx.vars;

        String lineMainId = ctx.namePrefix + layer_namepart + ctx.layerId;
        String lineOutlineId = ctx.namePrefix + layer_namepart + ctx.layerId + outline_namepart;
        String legacyDashId = ctx.namePrefix + layer_namepart + ctx.layerId + dash_namepart;
        Layer legacyDash = ctx.mapStyle.getLayer(legacyDashId);
        if (legacyDash != null) {
            ctx.mapStyle.removeLayer(legacyDash);
        }

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

        Expression lineWidthInner = MplStyleMapper.zoomScaleExpression(
                Expression.coalesce(
                        Expression.get(MplFeatureStyleProps.THICKNESS),
                        Expression.literal((double) getMPLThinkness(vars.thickness))),
                vars.scaleSizeWithZoom);
        Expression lineWidthOutline = Expression.product(
                lineWidthInner,
                Expression.literal(3.0));

        Expression lineOpacityInner = MplStyleMapper.fillOpacityExpression(
                MplFeatureStyleProps.OPACITY, vars.fillOpacity, ctx.layerOpacityFactor);
        Expression lineOpacityOutline = MplStyleMapper.fillOpacityExpression(
                MplFeatureStyleProps.STROKE_OPACITY, vars.strokeOpacity, ctx.layerOpacityFactor);
        String lineCapValue = MplStyleMapper.lineCapValue(vars.lineCap);
        String lineJoinValue = MplStyleMapper.lineJoinValue(vars.lineJoin);
        float lineMiterLimitValue = MplStyleMapper.lineMiterLimit(vars.lineJoin, vars.lineMiterLimit);
        Expression lineColorExpr = Expression.coalesce(
                Expression.get(MplFeatureStyleProps.COLOR_FILL),
                Expression.literal(getColorName(vars.fillColor)));

        float lineBlur = MplStyleMapper.lineBlurPaintValue(vars.lineBlur);

        if (ctx.layersHashMapLineDash != null) {
            ((LineLayer) newLayer).setFilter(MplStyleMapper.solidMainLineFilter(lineTypeEffective));

            for (int preset = 0; preset < MplStyleMapper.DASH_PRESET_COUNT; preset++) {
                String dashLayerId = legacyDashId + "_" + preset;
                Layer existingDash = ctx.mapStyle.getLayer(dashLayerId);
                if (existingDash != null && !(existingDash instanceof LineLayer)) {
                    ctx.mapStyle.removeLayer(existingDash);
                    existingDash = null;
                }
                LineLayer dashLayer = (LineLayer) existingDash;
                if (dashLayer == null) {
                    dashLayer = new LineLayer(dashLayerId, ctx.layerPath);
                }
                dashLayer.setFilter(MplStyleMapper.dashPresetFilter(
                        lineTypeEffective, preset, vars.dashPreset));
                dashLayer.setProperties(
                        PropertyFactory.lineColor(lineColorExpr),
                        PropertyFactory.lineWidth(lineWidthInner),
                        PropertyFactory.lineOpacity(lineOpacityInner),
                        PropertyFactory.lineCap(lineCapValue),
                        PropertyFactory.lineJoin(lineJoinValue),
                        PropertyFactory.lineMiterLimit(lineMiterLimitValue),
                        PropertyFactory.lineBlur(lineBlur),
                        PropertyFactory.lineDasharray(MplStyleMapper.dashArray(preset)));
                result.dashLayers.add(dashLayer);
            }
        }

        LineLayer mainLine = (LineLayer) newLayer;
        if (ctx.layersHashMapLineDash == null && MplStyleMapper.isLineDashType(vars.type)) {
            mainLine.setProperties(
                    PropertyFactory.lineColor(lineColorExpr),
                    PropertyFactory.lineWidth(lineWidthInner),
                    PropertyFactory.lineOpacity(lineOpacityInner),
                    PropertyFactory.lineCap(lineCapValue),
                    PropertyFactory.lineJoin(lineJoinValue),
                    PropertyFactory.lineMiterLimit(lineMiterLimitValue),
                    PropertyFactory.lineBlur(lineBlur),
                    PropertyFactory.lineDasharray(MplStyleMapper.dashArray(vars.dashPreset)));
        } else {
            // Do not pass null dasharray — MapLibre NPEs in Expression.toArray() on setProperties.
            mainLine.setProperties(
                    PropertyFactory.lineColor(lineColorExpr),
                    PropertyFactory.lineWidth(lineWidthInner),
                    PropertyFactory.lineOpacity(lineOpacityInner),
                    PropertyFactory.lineCap(lineCapValue),
                    PropertyFactory.lineJoin(lineJoinValue),
                    PropertyFactory.lineMiterLimit(lineMiterLimitValue),
                    PropertyFactory.lineBlur(lineBlur));
        }

        newLayer2.setProperties(
                PropertyFactory.lineColor(Expression.coalesce(
                        Expression.get(MplFeatureStyleProps.TEXT_COLOR),
                        Expression.literal(getColorName(vars.outlineColor)))),
                PropertyFactory.lineWidth(lineWidthOutline),
                PropertyFactory.lineCap(lineCapValue),
                PropertyFactory.lineJoin(lineJoinValue),
                PropertyFactory.lineMiterLimit(lineMiterLimitValue),
                PropertyFactory.lineOpacity(
                        ctx.layersHashMapLineDash != null
                                ? MplStyleMapper.outlineLineOpacity(lineTypeEffective, lineOpacityOutline)
                                : (MplStyleMapper.isLineOutlineType(vars.type)
                                        ? lineOpacityOutline
                                        : Expression.literal(0.0f))));

        result.mainLayer = newLayer;
        result.outlineLayer = newLayer2;
    }
}

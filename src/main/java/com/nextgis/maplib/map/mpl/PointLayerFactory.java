package com.nextgis.maplib.map.mpl;

import static com.nextgis.maplib.map.MPLFeaturesUtils.getColorName;
import static com.nextgis.maplib.map.MPLFeaturesUtils.getMPLThinkness;
import static com.nextgis.maplib.map.MPLFeaturesUtils.layer_namepart;
import static com.nextgis.maplib.map.MPLFeaturesUtils.outline_namepart;

import com.nextgis.maplib.display.MarkerIconRegistry;
import com.nextgis.maplib.display.MplStyleMapper;
import com.nextgis.maplib.map.MplFeatureStyleProps;

import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.layers.SymbolLayer;

/** Builds MapLibre point / multipoint layers (circle or symbol markers). */
public final class PointLayerFactory {

    private PointLayerFactory() {
    }

    public static void build(MplLayerBuildContext ctx, MplLayerBuildResult result) {
        MplLayerStyleVars vars = ctx.vars;
        boolean usePointSymbols = MarkerIconRegistry.useSymbolLayerForMarkerType(vars.type, ctx.ruleStyling);
        if (usePointSymbols) {
            MarkerIconRegistry.ensureRegistered(ctx.mapStyle);
        }

        String pointMainId = ctx.namePrefix + layer_namepart + ctx.layerId;
        String pointStrokeId = pointMainId + outline_namepart;
        Layer existingLayer = null;
        Layer existingStrokeLayer = ctx.mapStyle.getLayer(pointStrokeId);
        if (ctx.changeLayer || ctx.mapStyle.getLayer(pointMainId) != null) {
            existingLayer = ctx.mapStyle.getLayer(pointMainId);
        }
        if (existingLayer != null) {
            boolean wrongKind = usePointSymbols
                    ? !(existingLayer instanceof SymbolLayer)
                    : !(existingLayer instanceof CircleLayer);
            if (wrongKind) {
                ctx.mapStyle.removeLayer(existingLayer);
                ctx.layersHashMap.remove(ctx.layerId);
                existingLayer = null;
            }
        }
        if (!usePointSymbols && existingStrokeLayer != null) {
            ctx.mapStyle.removeLayer(existingStrokeLayer);
            ctx.layersHashMap2.remove(ctx.layerId);
            existingStrokeLayer = null;
        } else if (usePointSymbols && existingStrokeLayer != null
                && !(existingStrokeLayer instanceof SymbolLayer)) {
            ctx.mapStyle.removeLayer(existingStrokeLayer);
            ctx.layersHashMap2.remove(ctx.layerId);
            existingStrokeLayer = null;
        }

        float defaultRadius = getMPLThinkness(vars.markerRadius);
        Expression fillOpacityExpr = MplStyleMapper.fillOpacityExpression(
                MplFeatureStyleProps.OPACITY, vars.fillOpacity, ctx.layerOpacityFactor);
        Expression strokeOpacityExpr = MplStyleMapper.fillOpacityExpression(
                MplFeatureStyleProps.STROKE_OPACITY, vars.strokeOpacity, ctx.layerOpacityFactor);
        Expression fillIconSizeBase = MarkerIconRegistry.fillIconSizeExpression(defaultRadius);
        Expression fillIconSize = MplStyleMapper.zoomScaleExpression(fillIconSizeBase, vars.scaleSizeWithZoom);
        Expression strokePx = Expression.coalesce(
                Expression.get(MplFeatureStyleProps.THICKNESS),
                Expression.literal((double) getMPLThinkness(vars.thickness)));
        Expression strokeIconSize = MplStyleMapper.zoomScaleExpression(
                MarkerIconRegistry.strokeIconSizeExpression(fillIconSizeBase, strokePx),
                vars.scaleSizeWithZoom);
        Expression iconImage = MarkerIconRegistry.iconImageExpression(vars.type);

        Layer newLayer;
        if (existingLayer != null) {
            newLayer = existingLayer;
        } else if (usePointSymbols) {
            newLayer = new SymbolLayer(pointMainId, ctx.layerPath);
        } else {
            newLayer = new CircleLayer(pointMainId, ctx.layerPath);
        }

        Layer newLayer2 = null;
        if (usePointSymbols) {
            ((SymbolLayer) newLayer).setProperties(
                    PropertyFactory.iconImage(iconImage),
                    PropertyFactory.iconSize(fillIconSize),
                    PropertyFactory.iconColor(Expression.coalesce(
                            Expression.get(MplFeatureStyleProps.COLOR_FILL),
                            Expression.literal(getColorName(vars.fillColor)))),
                    PropertyFactory.iconOpacity(fillOpacityExpr),
                    PropertyFactory.iconAllowOverlap(true));

            if (existingStrokeLayer != null) {
                newLayer2 = existingStrokeLayer;
            } else {
                newLayer2 = new SymbolLayer(pointStrokeId, ctx.layerPath);
            }
            ((SymbolLayer) newLayer2).setProperties(
                    PropertyFactory.iconImage(iconImage),
                    PropertyFactory.iconSize(strokeIconSize),
                    PropertyFactory.iconColor(Expression.coalesce(
                            Expression.get(MplFeatureStyleProps.COLOR_STROKE),
                            Expression.literal(getColorName(vars.outlineColor)))),
                    PropertyFactory.iconOpacity(strokeOpacityExpr),
                    PropertyFactory.iconAllowOverlap(true));
        } else {
            Expression circleRadius = MplStyleMapper.zoomScaleExpression(
                    Expression.coalesce(
                            Expression.get(MplFeatureStyleProps.SIZE),
                            Expression.literal(defaultRadius)),
                    vars.scaleSizeWithZoom);
            newLayer.setProperties(
                    PropertyFactory.circleRadius(circleRadius),
                    PropertyFactory.circleColor(Expression.coalesce(
                            Expression.get(MplFeatureStyleProps.COLOR_FILL),
                            Expression.literal(getColorName(vars.fillColor)))),
                    PropertyFactory.circleStrokeColor(Expression.coalesce(
                            Expression.get(MplFeatureStyleProps.COLOR_STROKE),
                            Expression.literal(getColorName(vars.outlineColor)))),
                    PropertyFactory.circleStrokeWidth(strokePx),
                    PropertyFactory.circleOpacity(fillOpacityExpr),
                    PropertyFactory.circleStrokeOpacity(strokeOpacityExpr),
                    PropertyFactory.circleBlur(vars.circleBlur));
        }

        result.mainLayer = newLayer;
        result.outlineLayer = newLayer2;
    }
}

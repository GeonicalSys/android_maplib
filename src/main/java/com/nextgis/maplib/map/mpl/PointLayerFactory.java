package com.nextgis.maplib.map.mpl;

import static com.nextgis.maplib.map.MPLFeaturesUtils.getColorName;
import static com.nextgis.maplib.map.MPLFeaturesUtils.getMPLThinkness;
import static com.nextgis.maplib.map.MPLFeaturesUtils.layer_namepart;
import static com.nextgis.maplib.map.MPLFeaturesUtils.outline_namepart;

import com.nextgis.maplib.api.IRenderer;
import com.nextgis.maplib.display.FieldStyleRule;
import com.nextgis.maplib.display.MarkerIconRegistry;
import com.nextgis.maplib.display.MplStyleMapper;
import com.nextgis.maplib.display.RuleFeatureRenderer;
import com.nextgis.maplib.display.SimpleMarkerStyle;
import com.nextgis.maplib.map.MplFeatureStyleProps;
import com.nextgis.maplib.map.VectorLayer;

import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.layers.SymbolLayer;

import java.util.Map;

/** Builds MapLibre point / multipoint layers (circle or symbol markers). */
public final class PointLayerFactory {

    public static final String MARKER_ICON_LAYER_SUFFIX = "_icon";

    private PointLayerFactory() {
    }

    public static void build(MplLayerBuildContext ctx, MplLayerBuildResult result) {
        MplLayerStyleVars vars = ctx.vars;
        boolean usePointSymbols = MarkerIconRegistry.useSymbolLayerForMarkerType(
                vars.type,
                false,
                null) || ruleRequiresSymbolMarkerBackgrounds(ctx);
        boolean hasDefaultCustomIcon =
                vars.markerIconImage != null && !vars.markerIconImage.trim().isEmpty();
        boolean hasRuleCustomIcon = ruleHasCustomIcons(ctx);
        boolean useIconOverlay = hasDefaultCustomIcon || hasRuleCustomIcon;
        if (usePointSymbols || useIconOverlay) {
            MarkerIconRegistry.ensureRegistered(ctx.mapStyle, ctx.assetManager);
        }

        String pointMainId = ctx.namePrefix + layer_namepart + ctx.layerId;
        String pointStrokeId = pointMainId + outline_namepart;
        String pointIconId = pointMainId + MARKER_ICON_LAYER_SUFFIX;
        Layer existingLayer = null;
        Layer existingStrokeLayer = ctx.mapStyle.getLayer(pointStrokeId);
        Layer existingIconLayer = ctx.mapStyle.getLayer(pointIconId);
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
        if (!useIconOverlay && existingIconLayer != null) {
            ctx.mapStyle.removeLayer(existingIconLayer);
            existingIconLayer = null;
        } else if (useIconOverlay && existingIconLayer != null
                && !(existingIconLayer instanceof SymbolLayer)) {
            ctx.mapStyle.removeLayer(existingIconLayer);
            existingIconLayer = null;
        }

        float defaultRadius = getMPLThinkness(vars.markerRadius);
        Expression fillOpacityExpr = MplStyleMapper.fillOpacityExpression(
                MplFeatureStyleProps.OPACITY, vars.fillOpacity, ctx.layerOpacityFactor);
        Expression strokeOpacityExpr = MplStyleMapper.fillOpacityExpression(
                MplFeatureStyleProps.STROKE_OPACITY, vars.strokeOpacity, ctx.layerOpacityFactor);
        Expression iconOverlayOpacityExpr = MplStyleMapper.opacityWithLayerMultiplier(
                Expression.literal(1.0),
                ctx.layerOpacityFactor);
        Expression markerRadiusPx = Expression.coalesce(
                Expression.get(MplFeatureStyleProps.SIZE),
                Expression.literal((double) defaultRadius));
        Expression fillIconSizeBase = MarkerIconRegistry.fillIconSizeExpression(defaultRadius);
        Expression scaleSizeWithZoom = ctx.ruleStyling
                ? Expression.get(MplFeatureStyleProps.SCALE_SIZE_WITH_ZOOM)
                : null;
        boolean defaultScaleSizeWithZoom = vars.scaleSizeWithZoom;
        Expression fillIconSize = MplStyleMapper.zoomScaleExpression(
                fillIconSizeBase,
                scaleSizeWithZoom,
                defaultScaleSizeWithZoom,
                vars.sizeZoomScaleStops);
        float defaultIconRadius = vars.markerIconSize > 0f
                ? getMPLThinkness(vars.markerIconSize)
                : 0f;
        Expression iconRadiusPx = Expression.coalesce(
                Expression.get(MplFeatureStyleProps.ICON_SIZE),
                Expression.literal((double) defaultIconRadius));
        Expression effectiveIconRadiusPx = Expression.switchCase(
                Expression.gt(iconRadiusPx, Expression.literal(0.0)),
                iconRadiusPx,
                markerRadiusPx);
        Expression iconOverlaySize = MplStyleMapper.zoomScaleExpression(
                MarkerIconRegistry.iconSizeForRadiusExpression(effectiveIconRadiusPx),
                scaleSizeWithZoom,
                defaultScaleSizeWithZoom,
                vars.sizeZoomScaleStops);
        Expression strokePx = Expression.coalesce(
                Expression.get(MplFeatureStyleProps.THICKNESS),
                Expression.literal((double) getMPLThinkness(vars.thickness)));
        Expression strokeIconSize = MplStyleMapper.zoomScaleExpression(
                MarkerIconRegistry.strokeIconSizeExpression(fillIconSizeBase, strokePx),
                scaleSizeWithZoom,
                defaultScaleSizeWithZoom,
                vars.sizeZoomScaleStops);
        Expression generatedIconImage = MarkerIconRegistry.generatedIconImageExpression(vars.type);
        Expression customIconImage = MarkerIconRegistry.customIconImageExpression(vars.markerIconImage);
        Expression iconAnchor = Expression.coalesce(
                Expression.get(MplFeatureStyleProps.ICON_ANCHOR),
                Expression.literal(MarkerIconRegistry.iconAnchorValue(vars.markerIconAnchor)));
        Expression iconAllowOverlap = Expression.coalesce(
                Expression.get(MplFeatureStyleProps.ICON_ALLOW_OVERLAP),
                Expression.literal(vars.markerIconAllowOverlap));
        Expression iconIgnorePlacement = Expression.coalesce(
                Expression.get(MplFeatureStyleProps.ICON_IGNORE_PLACEMENT),
                Expression.literal(vars.markerIconIgnorePlacement));

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
                    PropertyFactory.iconImage(generatedIconImage),
                    PropertyFactory.iconSize(fillIconSize),
                    PropertyFactory.iconColor(Expression.coalesce(
                            Expression.get(MplFeatureStyleProps.COLOR_FILL),
                            Expression.literal(getColorName(vars.fillColor)))),
                    PropertyFactory.iconOpacity(fillOpacityExpr),
                    PropertyFactory.iconRotate(Expression.coalesce(
                            Expression.get(MplFeatureStyleProps.ICON_ROTATE),
                            Expression.literal((double) vars.markerIconRotate))),
                    PropertyFactory.iconOffset(Expression.coalesce(
                            Expression.get(MplFeatureStyleProps.ICON_OFFSET),
                            Expression.literal(new Float[]{
                                    vars.markerIconOffsetX,
                                    vars.markerIconOffsetY}))),
                    PropertyFactory.iconAnchor(iconAnchor),
                    PropertyFactory.iconAllowOverlap(iconAllowOverlap),
                    PropertyFactory.iconIgnorePlacement(iconIgnorePlacement),
                    PropertyFactory.symbolSortKey(Expression.toNumber(
                            Expression.get(MplFeatureStyleProps.ORDER))));

            if (existingStrokeLayer != null) {
                newLayer2 = existingStrokeLayer;
            } else {
                newLayer2 = new SymbolLayer(pointStrokeId, ctx.layerPath);
            }
            ((SymbolLayer) newLayer2).setFilter(Expression.all());
            ((SymbolLayer) newLayer2).setProperties(
                    PropertyFactory.iconImage(generatedIconImage),
                    PropertyFactory.iconSize(strokeIconSize),
                    PropertyFactory.iconColor(Expression.coalesce(
                            Expression.get(MplFeatureStyleProps.COLOR_STROKE),
                            Expression.literal(getColorName(vars.outlineColor)))),
                    PropertyFactory.iconOpacity(strokeOpacityExpr),
                    PropertyFactory.iconRotate(Expression.coalesce(
                            Expression.get(MplFeatureStyleProps.ICON_ROTATE),
                            Expression.literal((double) vars.markerIconRotate))),
                    PropertyFactory.iconOffset(Expression.coalesce(
                            Expression.get(MplFeatureStyleProps.ICON_OFFSET),
                            Expression.literal(new Float[]{
                                    vars.markerIconOffsetX,
                                    vars.markerIconOffsetY}))),
                    PropertyFactory.iconAnchor(iconAnchor),
                    PropertyFactory.iconAllowOverlap(iconAllowOverlap),
                    PropertyFactory.iconIgnorePlacement(iconIgnorePlacement),
                    PropertyFactory.symbolSortKey(Expression.toNumber(
                            Expression.get(MplFeatureStyleProps.ORDER))));
        } else {
            Expression circleBlur = Expression.coalesce(
                    Expression.get(MplFeatureStyleProps.CIRCLE_BLUR),
                    Expression.literal((double) vars.circleBlur));
            Expression circleRadius = MplStyleMapper.zoomScaleExpression(
                    Expression.coalesce(
                            Expression.get(MplFeatureStyleProps.SIZE),
                            Expression.literal(defaultRadius)),
                    scaleSizeWithZoom,
                    defaultScaleSizeWithZoom,
                    vars.sizeZoomScaleStops);
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
                    PropertyFactory.circleBlur(circleBlur),
                    PropertyFactory.circleSortKey(Expression.toNumber(
                            Expression.get(MplFeatureStyleProps.ORDER))));
        }

        SymbolLayer newIconLayer = null;
        if (useIconOverlay) {
            if (existingIconLayer != null) {
                newIconLayer = (SymbolLayer) existingIconLayer;
            } else {
                newIconLayer = new SymbolLayer(pointIconId, ctx.layerPath);
            }
            if (hasDefaultCustomIcon && !ctx.ruleStyling) {
                newIconLayer.setFilter(Expression.all());
            } else if (hasDefaultCustomIcon) {
                newIconLayer.setFilter(Expression.any(
                        Expression.not(Expression.has(MplFeatureStyleProps.ICON_IMAGE)),
                        Expression.neq(
                                Expression.get(MplFeatureStyleProps.ICON_IMAGE),
                                Expression.literal(""))));
            } else {
                newIconLayer.setFilter(Expression.all(
                        Expression.has(MplFeatureStyleProps.ICON_IMAGE),
                        Expression.neq(
                                Expression.get(MplFeatureStyleProps.ICON_IMAGE),
                                Expression.literal(""))));
            }
            newIconLayer.setProperties(
                    PropertyFactory.iconImage(customIconImage),
                    PropertyFactory.iconSize(iconOverlaySize),
                    PropertyFactory.iconOpacity(iconOverlayOpacityExpr),
                    PropertyFactory.iconRotate(Expression.coalesce(
                            Expression.get(MplFeatureStyleProps.ICON_ROTATE),
                            Expression.literal((double) vars.markerIconRotate))),
                    PropertyFactory.iconOffset(Expression.coalesce(
                            Expression.get(MplFeatureStyleProps.ICON_OFFSET),
                            Expression.literal(new Float[]{
                                    vars.markerIconOffsetX,
                                    vars.markerIconOffsetY}))),
                    PropertyFactory.iconAnchor(iconAnchor),
                    PropertyFactory.iconAllowOverlap(Expression.literal(true)),
                    PropertyFactory.iconIgnorePlacement(Expression.literal(true)),
                    PropertyFactory.symbolSortKey(Expression.toNumber(
                            Expression.get(MplFeatureStyleProps.ORDER))));
        }

        result.mainLayer = newLayer;
        result.outlineLayer = newLayer2;
        result.markerIconLayer = newIconLayer;
    }

    private static boolean ruleRequiresSymbolMarkerBackgrounds(MplLayerBuildContext ctx) {
        if (!ctx.ruleStyling || !(ctx.iLayer instanceof VectorLayer)) {
            return false;
        }
        IRenderer renderer = ((VectorLayer) ctx.iLayer).getRenderer();
        if (!(renderer instanceof RuleFeatureRenderer)) {
            return false;
        }

        RuleFeatureRenderer ruleRenderer = (RuleFeatureRenderer) renderer;
        if (markerStyleRequiresSymbolBackground(ruleRenderer.getStyle())) {
            return true;
        }
        if (ruleRenderer.getStyleRule() instanceof FieldStyleRule) {
            FieldStyleRule rule = (FieldStyleRule) ruleRenderer.getStyleRule();
            for (Map.Entry<String, com.nextgis.maplib.display.Style> entry
                    : rule.getStyleRules().entrySet()) {
                if (markerStyleRequiresSymbolBackground(entry.getValue())) {
                    return true;
                }
            }
            return markerStyleRequiresSymbolBackground(rule.getOtherStyle());
        }
        return false;
    }

    private static boolean ruleHasCustomIcons(MplLayerBuildContext ctx) {
        if (!ctx.ruleStyling || !(ctx.iLayer instanceof VectorLayer)) {
            return false;
        }
        IRenderer renderer = ((VectorLayer) ctx.iLayer).getRenderer();
        if (!(renderer instanceof RuleFeatureRenderer)) {
            return false;
        }

        RuleFeatureRenderer ruleRenderer = (RuleFeatureRenderer) renderer;
        if (markerStyleHasCustomIcon(ruleRenderer.getStyle())) {
            return true;
        }
        if (ruleRenderer.getStyleRule() instanceof FieldStyleRule) {
            FieldStyleRule rule = (FieldStyleRule) ruleRenderer.getStyleRule();
            for (Map.Entry<String, com.nextgis.maplib.display.Style> entry
                    : rule.getStyleRules().entrySet()) {
                if (markerStyleHasCustomIcon(entry.getValue())) {
                    return true;
                }
            }
            return markerStyleHasCustomIcon(rule.getOtherStyle());
        }
        return false;
    }

    private static boolean markerStyleRequiresSymbolBackground(com.nextgis.maplib.display.Style style) {
        if (!(style instanceof SimpleMarkerStyle)) {
            return false;
        }
        SimpleMarkerStyle markerStyle = (SimpleMarkerStyle) style;
        return MarkerIconRegistry.useSymbolLayerForMarkerType(
                markerStyle.getType(),
                false,
                null);
    }

    private static boolean markerStyleHasCustomIcon(com.nextgis.maplib.display.Style style) {
        if (!(style instanceof SimpleMarkerStyle)) {
            return false;
        }
        String iconImage = ((SimpleMarkerStyle) style).getIconImage();
        return iconImage != null && !iconImage.trim().isEmpty();
    }
}

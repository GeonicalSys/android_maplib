package com.nextgis.maplib.map.mpl;

import static com.nextgis.maplib.map.MPLFeaturesUtils.getColorName;
import static com.nextgis.maplib.map.MPLFeaturesUtils.getMPLThinkness;
import static com.nextgis.maplib.map.MPLFeaturesUtils.layer_namepart;
import static com.nextgis.maplib.map.MPLFeaturesUtils.outline_namepart;
import static com.nextgis.maplib.map.MPLFeaturesUtils.pattern_namepart;

import com.nextgis.maplib.display.MplStyleMapper;
import com.nextgis.maplib.display.PolygonPatternRegistry;
import com.nextgis.maplib.map.MplFeatureStyleProps;

import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.FillLayer;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.PropertyFactory;

/** Builds MapLibre polygon layers (fill, optional pattern fill, outline). */
public final class PolygonLayerFactory {

    private PolygonLayerFactory() {
    }

    public static void build(MplLayerBuildContext ctx, MplLayerBuildResult result) {
        MplLayerStyleVars vars = ctx.vars;

        String polyMainId = ctx.namePrefix + layer_namepart + ctx.layerId;
        String polyOutlineId = ctx.namePrefix + layer_namepart + ctx.layerId + outline_namepart;
        // Reuse existing layers, but drop any wrong-kind layer left from a previous geometry/style
        // so the casts below cannot throw ClassCastException (mirror PointLayerFactory).
        Layer newLayer = ctx.mapStyle.getLayer(polyMainId);
        if (newLayer != null && !(newLayer instanceof FillLayer)) {
            ctx.mapStyle.removeLayer(newLayer);
            newLayer = null;
        }
        Layer newLayer2 = ctx.mapStyle.getLayer(polyOutlineId);
        if (newLayer2 != null && !(newLayer2 instanceof LineLayer)) {
            ctx.mapStyle.removeLayer(newLayer2);
            newLayer2 = null;
        }
        if (newLayer == null) {
            newLayer = new FillLayer(polyMainId, ctx.layerPath);
        }
        if (newLayer2 == null) {
            newLayer2 = new LineLayer(polyOutlineId, ctx.layerPath);
        }

        PolygonPatternRegistry.ensureRegistered(ctx.mapStyle);

        FillLayer fillLayer = (FillLayer) newLayer;
        // Legacy exports may use colorfill on polygons; NGW rule styles use fillcolor.
        Expression fillColorExpr = Expression.coalesce(
                Expression.get(MplFeatureStyleProps.COLOR_FILL_RULE),
                Expression.get(MplFeatureStyleProps.COLOR_FILL),
                Expression.literal(getColorName(vars.fillColor)));
        Expression fillOpacityExpr = MplStyleMapper.fillOpacityExpression(
                MplFeatureStyleProps.OPACITY, vars.fillOpacity, ctx.layerOpacityFactor);
        Expression fillSortKeyExpr = Expression.toNumber(Expression.get(MplFeatureStyleProps.ORDER));
        Expression fillTranslate = Expression.coalesce(
                Expression.get(MplFeatureStyleProps.FILL_TRANSLATE),
                Expression.literal(new Float[]{vars.fillTranslateX, vars.fillTranslateY}));

        String polyPatternId = polyMainId + pattern_namepart;
        FillLayer patternFillLayer = null;
        boolean hasCustomPatternImage =
                vars.fillPatternImage != null && !vars.fillPatternImage.trim().isEmpty();
        int effectiveDefaultPattern = hasCustomPatternImage
                ? PolygonPatternRegistry.FILL_PATTERN_HATCH
                : vars.fillPattern;
        if (ctx.ruleStyling) {
            fillLayer.setFilter(clearFilter());
            fillLayer.setProperties(
                    PropertyFactory.fillColor(fillColorExpr),
                    PropertyFactory.fillOpacity(fillOpacityExpr),
                    PropertyFactory.fillAntialias(true),
                    PropertyFactory.fillSortKey(fillSortKeyExpr),
                    PropertyFactory.fillTranslate(fillTranslate));

            Layer existingPattern = ctx.mapStyle.getLayer(polyPatternId);
            if (existingPattern instanceof FillLayer) {
                patternFillLayer = (FillLayer) existingPattern;
            } else if (existingPattern != null) {
                ctx.mapStyle.removeLayer(existingPattern);
            }
            if (patternFillLayer == null) {
                patternFillLayer = new FillLayer(polyPatternId, ctx.layerPath);
            }
            patternFillLayer.setFilter(PolygonPatternRegistry.patternedFillFilter(effectiveDefaultPattern));
            patternFillLayer.setProperties(
                    PropertyFactory.fillOpacity(fillOpacityExpr),
                    PropertyFactory.fillAntialias(true),
                    PropertyFactory.fillSortKey(fillSortKeyExpr),
                    PropertyFactory.fillTranslate(fillTranslate),
                    PropertyFactory.fillPattern(
                            Expression.coalesce(
                                    Expression.get(MplFeatureStyleProps.FILL_PATTERN_IMAGE),
                                    PolygonPatternRegistry.patternImageExpression(
                                            effectiveDefaultPattern,
                                            hasCustomPatternImage
                                                    ? vars.fillPatternImage
                                                    : null))));
        } else {
            // MapLibre's Layer.setFilter(@NonNull) calls filter.toArray(); passing null crashes with
            // "Expression.toArray() on a null object reference". Use an always-true filter to clear any
            // leftover rule-style filter (e.g. solidFillFilter) instead.
            fillLayer.setFilter(clearFilter());
            fillLayer.setProperties(
                    PropertyFactory.fillColor(fillColorExpr),
                    PropertyFactory.fillOpacity(fillOpacityExpr),
                    PropertyFactory.fillAntialias(true),
                    PropertyFactory.fillSortKey(fillSortKeyExpr),
                    PropertyFactory.fillTranslate(fillTranslate));

            if (effectiveDefaultPattern > PolygonPatternRegistry.FILL_PATTERN_NONE) {
                Layer existingPattern = ctx.mapStyle.getLayer(polyPatternId);
                if (existingPattern instanceof FillLayer) {
                    patternFillLayer = (FillLayer) existingPattern;
                } else if (existingPattern != null) {
                    ctx.mapStyle.removeLayer(existingPattern);
                }
                if (patternFillLayer == null) {
                    patternFillLayer = new FillLayer(polyPatternId, ctx.layerPath);
                }
                patternFillLayer.setFilter(PolygonPatternRegistry.patternedFillFilter(effectiveDefaultPattern));
                patternFillLayer.setProperties(
                        PropertyFactory.fillOpacity(fillOpacityExpr),
                        PropertyFactory.fillAntialias(true),
                        PropertyFactory.fillSortKey(fillSortKeyExpr),
                        PropertyFactory.fillTranslate(fillTranslate),
                        PropertyFactory.fillPattern(
                                PolygonPatternRegistry.patternImageExpression(
                                        effectiveDefaultPattern,
                                        vars.fillPatternImage)));
            } else {
                Layer existingPattern = ctx.mapStyle.getLayer(polyPatternId);
                if (existingPattern != null) {
                    ctx.mapStyle.removeLayer(existingPattern);
                }
            }
        }

        newLayer2.setProperties(
                PropertyFactory.lineColor(
                        Expression.coalesce(
                                Expression.get(MplFeatureStyleProps.COLOR_STROKE),
                                Expression.literal(getColorName(vars.outlineColor)))),
                PropertyFactory.lineWidth(MplStyleMapper.zoomScaleExpression(
                        Expression.coalesce(
                                Expression.get(MplFeatureStyleProps.THICKNESS),
                                Expression.literal(getMPLThinkness(vars.thickness))),
                        ctx.ruleStyling
                                ? Expression.get(MplFeatureStyleProps.SCALE_SIZE_WITH_ZOOM)
                                : null,
                        vars.scaleSizeWithZoom,
                        vars.sizeZoomScaleStops)),
                PropertyFactory.lineSortKey(Expression.toNumber(Expression.get(MplFeatureStyleProps.ORDER))),
                PropertyFactory.lineOpacity(
                        MplStyleMapper.fillOpacityExpression(
                                MplFeatureStyleProps.STROKE_OPACITY,
                                vars.strokeOpacity,
                                ctx.layerOpacityFactor)));

        result.mainLayer = newLayer;
        result.outlineLayer = newLayer2;
        result.patternFillLayer = patternFillLayer;
    }

    /** Canonical always-true filter ({@code ["all"]}); clears a previous filter without passing null. */
    private static Expression clearFilter() {
        return Expression.all();
    }
}

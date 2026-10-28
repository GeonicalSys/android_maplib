package com.nextgis.maplib.map.mpl;

import com.nextgis.maplib.display.*;
import com.nextgis.maplib.map.*;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.*;
import java.util.*;

/** Data-constant dash/cap/join groups. Children share the logical polygon source and lifecycle. */
public final class PolygonOutlineLayerFactory {
    public static final String KEY = "polygon_outline_key";
    private PolygonOutlineLayerFactory() { }
    public static List<SimplePolygonStyle> effectiveStyles(MplLayerBuildContext ctx, Style fallback) {
        List<SimplePolygonStyle> styles = new ArrayList<>();
        FieldStyleRule rules = null;
        if (ctx.ruleStyling && ctx.iLayer instanceof VectorLayer) {
            RuleFeatureRenderer renderer = (RuleFeatureRenderer) ((VectorLayer) ctx.iLayer).getRenderer();
            if (renderer.getStyleRule() instanceof FieldStyleRule) rules = (FieldStyleRule) renderer.getStyleRule();
        }
        Style other = rules == null ? fallback : rules.resolveOtherStyle(fallback);
        if (other instanceof SimplePolygonStyle) styles.add((SimplePolygonStyle) other);
        if (rules != null) for (String category : rules.getStyleRules().keySet()) {
            Style effective = rules.resolveEffectiveStyle(category, fallback);
            if (effective instanceof SimplePolygonStyle) styles.add((SimplePolygonStyle) effective);
        }
        return styles;
    }
    public static void sync(MplLayerBuildContext ctx, LineLayer base, FieldStyleRule rules, Style fallback) {
        if (base == null) return;
        RuleLabelLayouts.remove(ctx.mapStyle, base.getId());
        Map<String, PolygonOutlineOptions> groups = new LinkedHashMap<>();
        for (SimplePolygonStyle polygon : effectiveStyles(ctx, fallback)) if (polygon.getOutlineOptions() != null && polygon.getOutlineOptions().isEnabled())
            groups.put(polygon.getOutlineOptions().key(), polygon.getOutlineOptions());
        Style other = rules == null ? fallback : rules.resolveOtherStyle(fallback);
        String defaultKey = other instanceof SimplePolygonStyle && ((SimplePolygonStyle) other).getOutlineOptions() != null && ((SimplePolygonStyle) other).getOutlineOptions().isEnabled()
                ? ((SimplePolygonStyle) other).getOutlineOptions().key() : "";
        Expression key = Expression.coalesce(Expression.get(KEY), Expression.literal(defaultKey));
        base.setFilter(Expression.eq(key, Expression.literal("")));
        String anchor = base.getId(); int index = 0;
        for (Map.Entry<String, PolygonOutlineOptions> entry : groups.entrySet()) {
            PolygonOutlineOptions outline = entry.getValue();
            String prefix = base.getId() + "-layout-contour-" + (++index);
            Expression filter = Expression.eq(key, Expression.literal(entry.getKey()));
            if (outline.isCasing() && outline.getCasingWidth() > 0) {
                LineLayer casing = create(ctx, base, prefix + "-casing", outline, true);
                casing.setFilter(filter); ctx.mapStyle.addLayerAbove(casing, anchor); anchor = casing.getId();
            }
            LineLayer primary = create(ctx, base, prefix + "-primary", outline, false);
            primary.setFilter(filter); ctx.mapStyle.addLayerAbove(primary, anchor); anchor = primary.getId();
        }
    }
    private static LineLayer create(MplLayerBuildContext ctx, LineLayer base, String id,
                                    PolygonOutlineOptions outline, boolean casing) {
        LineLayer layer = new LineLayer(id, ctx.layerPath);
        MPLFeaturesUtils.inheritNativeSourceLayer(base, layer);
        Expression width = Expression.coalesce(Expression.get(MplFeatureStyleProps.THICKNESS),
                Expression.literal(MPLFeaturesUtils.getMPLThinkness(ctx.vars.thickness)));
        if (casing) width = Expression.sum(width, Expression.literal(2 * outline.getCasingWidth()));
        width = ctx.scaleSize(width, ctx.ruleStyling ? Expression.get(MplFeatureStyleProps.SCALE_SIZE_WITH_ZOOM) : null,
                ctx.vars.scaleSizeWithZoom, ctx.vars.sizeZoomScaleStops);
        Expression gap = ctx.scaleSize(Expression.literal(outline.getGap()),
                ctx.ruleStyling ? Expression.get(MplFeatureStyleProps.SCALE_SIZE_WITH_ZOOM) : null,
                ctx.vars.scaleSizeWithZoom, ctx.vars.sizeZoomScaleStops);
        layer.setProperties(
                PropertyFactory.lineColor(casing ? Expression.literal(MPLFeaturesUtils.getColorName(outline.getCasingColor()))
                        : Expression.coalesce(Expression.get(MplFeatureStyleProps.COLOR_STROKE),
                        Expression.literal(MPLFeaturesUtils.getColorName(ctx.vars.outlineColor)))),
                PropertyFactory.lineWidth(width), PropertyFactory.lineGapWidth(gap),
                PropertyFactory.lineOffset(outline.getOffset()),
                PropertyFactory.lineOpacity(casing ? Expression.literal(outline.getCasingAlpha() / 255f * ctx.layerOpacityFactor)
                        : MplStyleMapper.fillOpacityExpression(MplFeatureStyleProps.STROKE_OPACITY, ctx.vars.strokeOpacity, ctx.layerOpacityFactor)),
                PropertyFactory.lineCap(MplStyleMapper.lineCapValue(outline.getCap())),
                PropertyFactory.lineJoin(MplStyleMapper.lineJoinValue(outline.getJoin())),
                PropertyFactory.lineMiterLimit(MplStyleMapper.lineMiterLimit(outline.getJoin(), outline.getMiter())),
                PropertyFactory.lineDasharray(casing ? (Float[]) null : outline.dashes()),
                PropertyFactory.lineSortKey(Expression.toNumber(Expression.get(MplFeatureStyleProps.ORDER))),
                PropertyFactory.visibility(base.getVisibility().getValue()));
        layer.setMinZoom(base.getMinZoom()); layer.setMaxZoom(base.getMaxZoom()); return layer;
    }
}

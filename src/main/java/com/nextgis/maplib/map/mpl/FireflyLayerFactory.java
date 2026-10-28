package com.nextgis.maplib.map.mpl;

import com.nextgis.maplib.display.FieldStyleRule;
import com.nextgis.maplib.display.FireflyMarkerRegistry;
import com.nextgis.maplib.display.SimpleMarkerStyle;
import com.nextgis.maplib.display.Style;
import com.nextgis.maplib.map.MplFeatureStyleProps;
import com.nextgis.maplib.map.MPLFeaturesUtils;
import com.nextgis.maplib.map.RuleGeometryLayouts;

import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.layers.SymbolLayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Separate non-SDF rendering children, sharing the ordinary point source and lifecycle. */
public final class FireflyLayerFactory {
    private FireflyLayerFactory() { }

    public static void sync(MplLayerBuildContext ctx, Layer base, FieldStyleRule rules, Style fallback) {
        if (base == null) return;
        List<SimpleMarkerStyle> markers = new ArrayList<>();
        if (FireflyMarkerRegistry.isFirefly(fallback)) markers.add((SimpleMarkerStyle) fallback);
        if (rules != null) for (String category : rules.getStyleRules().keySet()) {
            Style effective = rules.resolveEffectiveStyle(category, fallback);
            if (FireflyMarkerRegistry.isFirefly(effective)) markers.add((SimpleMarkerStyle) effective);
        }
        FireflyMarkerRegistry.sync(ctx.mapStyle, base.getId(), markers);
        Map<String, SimpleMarkerStyle> layouts = new LinkedHashMap<>();
        for (SimpleMarkerStyle marker : markers) layouts.put(RuleGeometryLayouts.key(marker), marker);
        int index = 0;
        for (Map.Entry<String, SimpleMarkerStyle> entry : layouts.entrySet()) {
            SimpleMarkerStyle marker = entry.getValue();
            FireflyMarkerRegistry.Sprite sprite = FireflyMarkerRegistry.sprite(marker);
            SymbolLayer child = new SymbolLayer(base.getId() + "-layout-firefly-" + (++index), ctx.layerPath);
            MPLFeaturesUtils.inheritNativeSourceLayer(base, child);
            Expression isFirefly = Expression.eq(Expression.coalesce(Expression.get(MplFeatureStyleProps.FILL_TYPE),
                    Expression.literal(ctx.vars.type)), Expression.literal(SimpleMarkerStyle.MarkerStyleFirefly));
            Expression layout = rules == null ? Expression.literal(true) : Expression.any(
                    Expression.eq(Expression.get(RuleGeometryLayouts.KEY), Expression.literal(entry.getKey())),
                    Expression.all(Expression.not(Expression.has(RuleGeometryLayouts.KEY)),
                            Expression.literal(entry.getKey().equals(RuleGeometryLayouts.key(fallback)))));
            child.setFilter(Expression.all(isFirefly, layout));
            child.setProperties(
                    PropertyFactory.iconImage(Expression.coalesce(Expression.get(FireflyMarkerRegistry.IMAGE_PROPERTY),
                            Expression.literal(sprite.imageName))),
                    PropertyFactory.iconSize(ctx.scaleSize(Expression.coalesce(
                                    Expression.get(FireflyMarkerRegistry.SCALE_PROPERTY), Expression.literal(sprite.iconScale)),
                            ctx.ruleStyling ? Expression.get(MplFeatureStyleProps.SCALE_SIZE_WITH_ZOOM) : null,
                            ctx.vars.scaleSizeWithZoom, ctx.vars.sizeZoomScaleStops)),
                    // Core and glow alpha are already independent in the sprite. Apply layer opacity once.
                    PropertyFactory.iconOpacity(ctx.layerOpacityFactor),
                    PropertyFactory.iconAllowOverlap(marker.isIconAllowOverlap()),
                    PropertyFactory.iconIgnorePlacement(marker.isIconIgnorePlacement()),
                    PropertyFactory.symbolSortKey(Expression.toNumber(Expression.get(MplFeatureStyleProps.ORDER))),
                    PropertyFactory.visibility(base.getVisibility().getValue()));
            child.setMinZoom(base.getMinZoom());
            child.setMaxZoom(base.getMaxZoom());
            ctx.mapStyle.addLayerAbove(child, base.getId());
        }
    }
}

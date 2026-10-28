package com.nextgis.maplib.map;

import com.nextgis.maplib.display.FieldStyleRule;
import com.nextgis.maplib.display.MplStyleMapper;
import com.nextgis.maplib.display.SimpleLineStyle;
import com.nextgis.maplib.display.SimpleMarkerStyle;
import com.nextgis.maplib.display.SimplePolygonStyle;
import com.nextgis.maplib.display.Style;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.FillLayer;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.layers.PropertyValue;
import org.maplibre.android.style.layers.PaintPropertyValue;
import org.maplibre.android.style.layers.LayoutPropertyValue;
import org.maplibre.android.style.layers.SymbolLayer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Constant icon collision and fill translation settings for ordinary GeoJSON rule styles. */
public final class RuleGeometryLayouts {
    public static final String KEY = "geometry_layout_key";
    private RuleGeometryLayouts() { }

    public static String key(Style style) {
        if (style instanceof SimpleMarkerStyle) {
            SimpleMarkerStyle marker = (SimpleMarkerStyle) style;
            return marker.isIconAllowOverlap() + "|" + marker.isIconIgnorePlacement();
        }
        if (style instanceof SimplePolygonStyle) {
            SimplePolygonStyle fill = (SimplePolygonStyle) style;
            return fill.getFillTranslateX() + "|" + fill.getFillTranslateY();
        }
        if (style instanceof SimpleLineStyle) {
            SimpleLineStyle line = (SimpleLineStyle) style;
            return MplStyleMapper.lineCapValue(line.getLineCap()) + "|" + MplStyleMapper.lineJoinValue(line.getLineJoin())
                    + "|" + MplStyleMapper.lineMiterLimit(line.getLineJoin(), line.getLineMiterLimit());
        }
        return "";
    }

    private static void constants(Layer layer, Style style) {
        if (layer instanceof SymbolLayer && style instanceof SimpleMarkerStyle) {
            SimpleMarkerStyle marker = (SimpleMarkerStyle) style;
            layer.setProperties(PropertyFactory.iconAllowOverlap(marker.isIconAllowOverlap()),
                    PropertyFactory.iconIgnorePlacement(marker.isIconIgnorePlacement()));
        } else if (layer instanceof FillLayer && style instanceof SimplePolygonStyle) {
            SimplePolygonStyle fill = (SimplePolygonStyle) style;
            layer.setProperties(PropertyFactory.fillTranslate(new Float[]{fill.getFillTranslateX(), fill.getFillTranslateY()}));
        } else if (layer instanceof LineLayer && style instanceof SimpleLineStyle) {
            SimpleLineStyle line = (SimpleLineStyle) style;
            layer.setProperties(PropertyFactory.lineCap(MplStyleMapper.lineCapValue(line.getLineCap())),
                    PropertyFactory.lineJoin(MplStyleMapper.lineJoinValue(line.getLineJoin())),
                    PropertyFactory.lineMiterLimit(MplStyleMapper.lineMiterLimit(line.getLineJoin(), line.getLineMiterLimit())));
        }
    }

    private static void copy(List<PropertyValue<?>> properties, String name, PropertyValue<?> value) {
        Object data = value.isExpression() ? value.getExpression() : value.getValue();
        if (data != null) {
            boolean paint = (name.startsWith("fill-") || name.startsWith("line-")) && !name.endsWith("sort-key")
                    || name.equals("icon-color") || name.equals("icon-opacity");
            properties.add(paint ? new PaintPropertyValue<>(name, data) : new LayoutPropertyValue<>(name, data));
        }
    }

    private static Layer cloneLayer(Layer base, String id) {
        List<PropertyValue<?>> properties = new ArrayList<>();
        Layer child;
        if (base instanceof SymbolLayer) {
            SymbolLayer symbol = (SymbolLayer) base;
            child = new SymbolLayer(id, symbol.getSourceId());
            copy(properties, "icon-image", symbol.getIconImage()); copy(properties, "icon-size", symbol.getIconSize());
            copy(properties, "icon-color", symbol.getIconColor()); copy(properties, "icon-opacity", symbol.getIconOpacity());
            copy(properties, "icon-rotate", symbol.getIconRotate()); copy(properties, "icon-offset", symbol.getIconOffset());
            copy(properties, "icon-anchor", symbol.getIconAnchor()); copy(properties, "symbol-sort-key", symbol.getSymbolSortKey());
        } else if (base instanceof FillLayer) {
            FillLayer fill = (FillLayer) base;
            child = new FillLayer(id, fill.getSourceId());
            copy(properties, "fill-color", fill.getFillColor()); copy(properties, "fill-opacity", fill.getFillOpacity());
            copy(properties, "fill-antialias", fill.getFillAntialias()); copy(properties, "fill-sort-key", fill.getFillSortKey());
            copy(properties, "fill-pattern", fill.getFillPattern());
        } else {
            LineLayer line = (LineLayer) base;
            child = new LineLayer(id, line.getSourceId());
            copy(properties, "line-color", line.getLineColor()); copy(properties, "line-width", line.getLineWidth());
            copy(properties, "line-opacity", line.getLineOpacity()); copy(properties, "line-blur", line.getLineBlur());
            copy(properties, "line-offset", line.getLineOffset()); copy(properties, "line-gap-width", line.getLineGapWidth());
            copy(properties, "line-sort-key", line.getLineSortKey()); copy(properties, "line-dasharray", line.getLineDasharray());
        }
        MPLFeaturesUtils.inheritNativeSourceLayer(base, child);
        copy(properties, "visibility", base.getVisibility());
        child.setProperties(properties.toArray(new PropertyValue<?>[0]));
        child.setMinZoom(base.getMinZoom()); child.setMaxZoom(base.getMaxZoom());
        return child;
    }

    private static Expression filter(Layer layer) {
        if (layer instanceof SymbolLayer) return ((SymbolLayer) layer).getFilter();
        if (layer instanceof FillLayer) return ((FillLayer) layer).getFilter();
        return ((LineLayer) layer).getFilter();
    }
    private static void filter(Layer layer, Expression filter) {
        if (layer instanceof SymbolLayer) ((SymbolLayer) layer).setFilter(filter);
        else if (layer instanceof FillLayer) ((FillLayer) layer).setFilter(filter);
        else ((LineLayer) layer).setFilter(filter);
    }

    public static void sync(org.maplibre.android.maps.Style nativeStyle, Layer base, FieldStyleRule rules, Style fallback) {
        if (base == null) return;
        RuleLabelLayouts.remove(nativeStyle, base.getId());
        Style other = rules == null ? fallback : rules.resolveOtherStyle(fallback);
        if (!(base instanceof FillLayer && other instanceof SimplePolygonStyle)
                && !(base instanceof SymbolLayer && other instanceof SimpleMarkerStyle)
                && !(base instanceof LineLayer && other instanceof SimpleLineStyle)) return;
        constants(base, other);
        if (rules == null) return;
        Expression original = filter(base);
        if (original == null) original = Expression.literal(true);
        String defaultKey = key(other);
        filter(base, Expression.all(original, Expression.any(Expression.not(Expression.has(KEY)),
                Expression.eq(Expression.get(KEY), Expression.literal(defaultKey)))));
        Map<String, Style> layouts = new LinkedHashMap<>();
        for (String category : rules.getStyleRules().keySet()) {
            Style effective = rules.resolveEffectiveStyle(category, fallback);
            String key = key(effective);
            if (!key.equals(defaultKey)) layouts.put(key, effective);
        }
        int index = 0;
        for (Map.Entry<String, Style> layout : layouts.entrySet()) {
            Layer child = cloneLayer(base, base.getId() + "-layout-" + (++index));
            constants(child, layout.getValue());
            filter(child, Expression.all(original, Expression.eq(Expression.get(KEY), Expression.literal(layout.getKey()))));
            nativeStyle.addLayerAbove(child, base.getId());
        }
    }
}

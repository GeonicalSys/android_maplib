package com.nextgis.maplib.map;

import com.nextgis.maplib.display.FieldStyleRule;
import com.nextgis.maplib.display.LabelAttributes;
import com.nextgis.maplib.display.Style;
import com.nextgis.maplib.util.GeoConstants;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.layers.PropertyValue;
import org.maplibre.android.style.layers.PaintPropertyValue;
import org.maplibre.android.style.layers.LayoutPropertyValue;
import org.maplibre.android.style.layers.SymbolLayer;
import java.util.LinkedHashMap;
import java.util.Map;

/** Classic labels only: MapLibre data-constant layout settings require distinct native layers. */
public final class RuleLabelLayouts {
    public static final String KEY = "label_layout_key";
    private RuleLabelLayouts() { }

    private static boolean line(int type) {
        return type == GeoConstants.GTLineString || type == GeoConstants.GTMultiLineString;
    }
    private static String placement(LabelAttributes labels, int type) {
        return line(type) ? labels.isLineLabelRepeat() ? "line" : "line-center" : "point";
    }
    private static String rotation(LabelAttributes labels, int type) {
        return line(type) ? labels.isLineLabelHorizontal() ? "viewport" : "map" : "auto";
    }
    public static String key(LabelAttributes labels, int type) {
        return labels.getTextLineHeight() + "|" + labels.getTextPadding() + "|"
                + (labels.getTextKeepUpright() == null || labels.getTextKeepUpright()) + "|"
                + labels.getTextMaxAngle() + "|" + Boolean.TRUE.equals(labels.getTextAllowOverlap()) + "|"
                + labels.isTextOptional() + "|" + labels.getSymbolSpacing() + "|"
                + placement(labels, type) + "|" + rotation(labels, type) + "|"
                + java.util.Arrays.toString(labels.getTextFontStack());
    }
    public static void constants(SymbolLayer layer, LabelAttributes labels, int type) {
        boolean overlap = Boolean.TRUE.equals(labels.getTextAllowOverlap());
        // Native font discovery requires literal font stacks; a generic feature
        // get is rejected even when the Android property getter retains it.
        layer.setProperties(PropertyFactory.textFont(labels.getTextFontStack()),
                PropertyFactory.textLineHeight(labels.getTextLineHeight()),
                PropertyFactory.textPadding(labels.getTextPadding()),
                PropertyFactory.textKeepUpright(labels.getTextKeepUpright() == null || labels.getTextKeepUpright()),
                PropertyFactory.textMaxAngle(labels.getTextMaxAngle()),
                PropertyFactory.textAllowOverlap(overlap), PropertyFactory.textOptional(labels.isTextOptional()),
                PropertyFactory.textIgnorePlacement(overlap && !labels.isTextOptional()),
                PropertyFactory.symbolSpacing(labels.getSymbolSpacing()),
                PropertyFactory.symbolPlacement(placement(labels, type)),
                PropertyFactory.textRotationAlignment(rotation(labels, type)));
    }
    public static void remove(org.maplibre.android.maps.Style style, String baseId) {
        String prefix = baseId + "-layout-";
        for (Layer layer : style.getLayers()) if (layer.getId().startsWith(prefix)) style.removeLayer(layer);
    }
    public static void visibility(org.maplibre.android.maps.Style style, String baseId, String visibility) {
        String prefix = baseId + "-layout-";
        for (Layer layer : style.getLayers()) if (layer.getId().startsWith(prefix))
            layer.setProperties(PropertyFactory.visibility(visibility));
    }
    private static PropertyValue<?> copy(String name, PropertyValue<?> value) {
        Object data = value.isExpression() ? value.getExpression() : value.getValue();
        return name.equals("text-color") || name.equals("text-opacity") || name.startsWith("text-halo-")
                ? new PaintPropertyValue<>(name, data) : new LayoutPropertyValue<>(name, data);
    }

    public static void sync(org.maplibre.android.maps.Style style, SymbolLayer base, FieldStyleRule rules,
                            Style fallback, int type) {
        remove(style, base.getId());
        LabelAttributes other = LabelAttributes.fromStyle(rules == null ? fallback : rules.resolveOtherStyle(fallback));
        constants(base, other, type);
        if (rules == null) { base.setFilter(Expression.literal(true)); return; }
        String defaultKey = key(other, type);
        base.setFilter(Expression.any(Expression.not(Expression.has(KEY)),
                Expression.eq(Expression.get(KEY), Expression.literal(defaultKey))));
        Map<String, LabelAttributes> layouts = new LinkedHashMap<>();
        for (String category : rules.getStyleRules().keySet()) {
            LabelAttributes labels = LabelAttributes.fromStyle(rules.resolveEffectiveStyle(category, fallback));
            String key = key(labels, type);
            if (!key.equals(defaultKey)) layouts.put(key, labels);
        }
        int index = 0;
        for (Map.Entry<String, LabelAttributes> entry : layouts.entrySet()) {
            SymbolLayer layer = new SymbolLayer(base.getId() + "-layout-" + (++index), base.getSourceId());
            layer.setProperties(copy("text-field", base.getTextField()), copy("text-size", base.getTextSize()),
                    copy("text-opacity", base.getTextOpacity()), copy("text-color", base.getTextColor()),
                    copy("text-halo-color", base.getTextHaloColor()), copy("text-halo-width", base.getTextHaloWidth()),
                    copy("text-halo-blur", base.getTextHaloBlur()), copy("text-font", base.getTextFont()),
                    copy("text-anchor", base.getTextAnchor()), copy("text-offset", base.getTextOffset()),
                    copy("text-justify", base.getTextJustify()), copy("text-transform", base.getTextTransform()),
                    copy("text-letter-spacing", base.getTextLetterSpacing()), copy("text-max-width", base.getTextMaxWidth()),
                    copy("symbol-sort-key", base.getSymbolSortKey()), copy("visibility", base.getVisibility()));
            constants(layer, entry.getValue(), type);
            layer.setFilter(Expression.eq(Expression.get(KEY), Expression.literal(entry.getKey())));
            layer.setMinZoom(base.getMinZoom()); layer.setMaxZoom(base.getMaxZoom());
            style.addLayerAbove(layer, base.getId());
        }
    }
}

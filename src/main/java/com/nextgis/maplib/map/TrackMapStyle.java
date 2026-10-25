package com.nextgis.maplib.map;

import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.maps.Style;
import org.maplibre.geojson.Feature;

import java.util.Locale;

import static org.maplibre.android.style.expressions.Expression.*;
import static org.maplibre.android.style.layers.PropertyFactory.*;

/** A fixed number of style layers for all tracks, including all their disconnected segments. */
public final class TrackMapStyle {
    public static final String TRACK_ID = "track_id";
    public static final String COLOR = "track_color";
    public static final String NAME = "track_name";
    private static final String[] PREFIXES = {"track-line", "track-inprogress"};
    private static final String[] SUFFIXES = {"-glow", "-selection", "-layer"};

    private TrackMapStyle() { }

    public static void properties(Feature feature, long id, String name, int color, int layerId) {
        feature.addNumberProperty(TRACK_ID, id);
        feature.addStringProperty(NAME, name);
        feature.addStringProperty(COLOR, String.format(Locale.ROOT, "#%06X", color & 0xffffff));
        feature.addStringProperty(MPLFeaturesUtils.prop_layerid, String.valueOf(layerId));
    }

    public static void install(Style style) {
        for (String prefix : PREFIXES) {
            String source = prefix + "-source";
            style.addLayer(new LineLayer(prefix + "-glow", source).withProperties(
                    lineColor(get(COLOR)), lineWidth(10f), lineBlur(3f), lineOpacity(0.55f),
                    lineCap("round"), lineJoin("round")));
            LineLayer selection = new LineLayer(prefix + "-selection", source).withProperties(
                    lineColor("#FFFFFF"), lineWidth(7f), lineCap("round"), lineJoin("round"));
            selection.setFilter(eq(get(TRACK_ID), literal(-1)));
            style.addLayer(selection);
            style.addLayer(new LineLayer(prefix + "-layer", source).withProperties(
                    lineColor(get(COLOR)), lineWidth(4f), lineCap("round"), lineJoin("round")));
        }
    }

    public static void select(Style style, long id) {
        if (style == null) return;
        for (String prefix : PREFIXES) {
            LineLayer layer = style.getLayerAs(prefix + "-selection");
            if (layer != null) layer.setFilter(eq(get(TRACK_ID), literal(id)));
        }
    }

    public static void visibility(Style style, boolean visible) {
        for (String prefix : PREFIXES) for (String suffix : SUFFIXES) {
            Layer layer = style.getLayer(prefix + suffix);
            if (layer != null) layer.setProperties(
                    org.maplibre.android.style.layers.PropertyFactory.visibility(visible ? "visible" : "none"));
        }
    }
}

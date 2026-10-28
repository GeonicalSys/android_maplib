package com.nextgis.maplib.map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.maplibre.geojson.Feature;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.nextgis.maplib.map.MplFeatureStyleProps.*;
import static com.nextgis.maplib.util.GeoConstants.*;

/** Native-only copies: editor features and their complete geometry remain authoritative. */
public final class NativeFeaturePayload {
    // These settings are literal layer constants, grouped by the layout keys below.
    private static final Set<String> NATIVE_UNUSED = keys(TEXT_FONT, TEXT_LINE_HEIGHT, TEXT_PADDING,
            TEXT_KEEP_UPRIGHT, TEXT_MAX_ANGLE, TEXT_ALLOW_OVERLAP, TEXT_OPTIONAL, SYMBOL_SPACING,
            SYMBOL_PLACEMENT, TEXT_ROTATION_ALIGNMENT, ICON_ALLOW_OVERLAP, ICON_IGNORE_PLACEMENT,
            LINE_CAP, LINE_JOIN, LINE_MITER_LIMIT, LINE_DASH_ARRAY, DASH_PRESET, FILL_TRANSLATE);
    private static final Set<String> TEXT = keys(SIGNATURE, TEXT_COLOR, TEXT_SIZE, TEXT_SCALE_WITH_ZOOM,
            TEXT_ANCHOR, TEXT_OFFSETS, TEXT_HALO_COLOR, TEXT_HALO_WIDTH, TEXT_HALO_BLUR, TEXT_OPACITY,
            TEXT_FONT, TEXT_JUSTIFY, TEXT_TRANSFORM, TEXT_LETTER_SPACING, TEXT_LINE_HEIGHT, TEXT_PADDING,
            TEXT_KEEP_UPRIGHT, TEXT_MAX_ANGLE, TEXT_MAX_WIDTH, TEXT_ALLOW_OVERLAP, TEXT_OPTIONAL,
            TEXT_ROTATION_ALIGNMENT, SYMBOL_SPACING, SYMBOL_PLACEMENT, LABEL_MIN_ZOOM, LABEL_MAX_ZOOM,
            TEXT_ZOOM_STOPS, RuleLabelLayouts.KEY);
    private static final Set<String> GEOMETRY = keys(COLOR_FILL_RULE, COLOR_FILL, COLOR_STROKE, SIZE,
            THICKNESS, SCALE_SIZE_WITH_ZOOM, OPACITY, STROKE_OPACITY, CIRCLE_BLUR, FILL_TYPE, FILL_TYPE2,
            DASH_PRESET, FILL_PATTERN, FILL_PATTERN_IMAGE, ICON_IMAGE, ICON_SIZE, ICON_ROTATE, ICON_OFFSET,
            ICON_ANCHOR, ICON_ALLOW_OVERLAP, ICON_IGNORE_PLACEMENT, LINE_CAP, LINE_JOIN, LINE_MITER_LIMIT,
            LINE_BLUR, LINE_OFFSET, LINE_GAP_WIDTH, LINE_OUTLINE_MULTIPLIER, LINE_DASH_ARRAY, FILL_TRANSLATE,
            SIZE_ZOOM_STOPS, RuleGeometryLayouts.KEY, "line_dash_key", "polygon_outline_key",
            "polygon_pattern_opacity", "firefly_image", "firefly_scale");
    // Every member has the same literal/coalesce default in PolygonLayerFactory/outline factory.
    private static final Set<String> UNIFORM_POLYGON = keys(COLOR_FILL_RULE, COLOR_STROKE, THICKNESS,
            OPACITY, STROKE_OPACITY, FILL_PATTERN, FILL_PATTERN_IMAGE, "polygon_pattern_opacity",
            "polygon_outline_key", SCALE_SIZE_WITH_ZOOM, SIZE_ZOOM_STOPS, RuleGeometryLayouts.KEY);

    private NativeFeaturePayload() { }

    public static List<Feature> geometry(List<Feature> features, int geometryType) {
        return geometry(features, geometryType, null);
    }

    /** Defaults must come from the same uniform SimplePolygonStyle used by the native factories. */
    static List<Feature> geometry(List<Feature> features, int geometryType, JsonObject uniformPolygonDefaults) {
        List<Feature> result = new ArrayList<>(features.size());
        boolean polygon = geometryType == GTPolygon || geometryType == GTMultiPolygon;
        for (Feature original : features) {
            if (original == null) continue;
            // Lines retain label inputs for VisibleLineLabelController's native-query fallback.
            // Points share their native source with symbols; polygons have a separate label source.
            JsonObject properties = project(original, false, polygon || !hasLabel(original),
                    polygon ? uniformPolygonDefaults : null);
            result.add(Feature.fromGeometry(original.geometry(), properties, original.id(), original.bbox()));
        }
        return result;
    }

    /** Also used before polygon anchor calculation so unlabelled objects allocate no label points. */
    public static List<Feature> labels(List<Feature> features) {
        List<Feature> result = new ArrayList<>();
        for (Feature original : features) {
            if (original == null || !hasLabel(original)) continue;
            JsonObject properties = project(original, true, false, null);
            result.add(Feature.fromGeometry(original.geometry(), properties, original.id(), original.bbox()));
        }
        return result;
    }

    public static long propertyCount(List<Feature> features) {
        long count = 0;
        for (Feature feature : features) if (feature != null && feature.properties() != null)
            count += feature.properties().size();
        return count;
    }

    public static int labelCount(List<Feature> features) {
        int count = 0;
        for (Feature feature : features) if (feature != null && hasLabel(feature)) count++;
        return count;
    }

    private static boolean hasLabel(Feature feature) {
        JsonElement signature = feature.properties() == null ? null : feature.properties().get(SIGNATURE);
        return signature != null && !signature.isJsonNull() && signature.isJsonPrimitive()
                && !signature.getAsString().trim().isEmpty();
    }

    private static JsonObject project(Feature feature, boolean labelOnly, boolean omitText, JsonObject defaults) {
        JsonObject result = new JsonObject(), original = feature.properties();
        if (original == null) return result;
        // Do not allocate then discard the full property graph, especially font/dash/offset arrays.
        for (Map.Entry<String, JsonElement> entry : original.entrySet()) {
            String key = entry.getKey(); JsonElement value = entry.getValue();
            if (NATIVE_UNUSED.contains(key) || labelOnly && GEOMETRY.contains(key) || omitText && TEXT.contains(key))
                continue;
            if (defaults != null && UNIFORM_POLYGON.contains(key) && !value.isJsonNull()
                    && value.equals(defaults.get(key))
                    && !(COLOR_FILL_RULE.equals(key) && original.has(COLOR_FILL))) continue;
            result.add(key, value.deepCopy());
        }
        return result;
    }

    private static Set<String> keys(String... keys) { return new HashSet<>(Arrays.asList(keys)); }
}

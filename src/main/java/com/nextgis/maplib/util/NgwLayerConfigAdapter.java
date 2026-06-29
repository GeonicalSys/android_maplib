/*
 * Normalizes NGW / collector layer description JSON into the mobile VectorLayer schema.
 *
 * <p>Handles extended MapLibre label settings (halo, template, opacity), rule-style maps from
 * web exports, and legacy key aliases so {@link com.nextgis.maplib.map.VectorLayer#setRenderer}
 * and soft config sync apply server styles without manual editing in the app.
 */
package com.nextgis.maplib.util;

import com.nextgis.maplib.display.FieldStyleRule;
import com.nextgis.maplib.display.SimpleFeatureRenderer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

import static com.nextgis.maplib.util.Constants.JSON_DISPLAY_NAME;
import static com.nextgis.maplib.util.Constants.JSON_LABEL_HALO_BLUR_KEY;
import static com.nextgis.maplib.util.Constants.JSON_LABEL_HALO_COLOR_KEY;
import static com.nextgis.maplib.util.Constants.JSON_LABEL_HALO_WIDTH_KEY;
import static com.nextgis.maplib.util.Constants.JSON_LABEL_TEMPLATE_KEY;
import static com.nextgis.maplib.util.Constants.JSON_LABEL_TEXT_OPACITY_KEY;
import static com.nextgis.maplib.util.Constants.JSON_LAYER_OPACITY_KEY;
import static com.nextgis.maplib.util.Constants.JSON_NAME_KEY;
import static com.nextgis.maplib.util.Constants.JSON_RENDERERPROPS_KEY;
import static com.nextgis.maplib.util.Constants.JSON_SCALE_SIZE_WITH_ZOOM_KEY;
import static com.nextgis.maplib.util.Constants.JSON_STYLE_RULE_KEY;
import static com.nextgis.maplib.util.Constants.JSON_VALUE_KEY;

public final class NgwLayerConfigAdapter {

    private static final String KEY_LABEL_ATTRIBUTES = "label_attributes";

    private NgwLayerConfigAdapter() {
    }

    /** Adapt full layer config object (fields, renderer, layer_opacity, …). */
    public static void adaptLayerConfig(JSONObject cfg) throws JSONException {
        if (cfg == null) {
            return;
        }
        normalizeOpacity0to1(cfg, JSON_LAYER_OPACITY_KEY, 255);
        if (cfg.has(JSON_RENDERERPROPS_KEY)) {
            adaptRenderer(cfg.getJSONObject(JSON_RENDERERPROPS_KEY));
        }
    }

    /** Adapt renderer_properties block before {@link com.nextgis.maplib.map.VectorLayer#setRenderer}. */
    public static void adaptRenderer(JSONObject renderer) throws JSONException {
        if (renderer == null) {
            return;
        }
        normalizeRendererName(renderer);
        if (renderer.has(SimpleFeatureRenderer.JSON_STYLE_KEY)) {
            adaptStyleJson(renderer.getJSONObject(SimpleFeatureRenderer.JSON_STYLE_KEY));
        }
        if (renderer.has(JSON_STYLE_RULE_KEY)) {
            adaptStyleRule(renderer.getJSONObject(JSON_STYLE_RULE_KEY));
        }
    }

    private static void normalizeRendererName(JSONObject renderer) throws JSONException {
        String name = renderer.optString(JSON_NAME_KEY, "");
        if ("RuleRenderer".equals(name) || "rule".equalsIgnoreCase(name)) {
            renderer.put(JSON_NAME_KEY, "RuleFeatureRenderer");
        } else if ("SimpleRenderer".equals(name) || "simple".equalsIgnoreCase(name)) {
            renderer.put(JSON_NAME_KEY, "SimpleFeatureRenderer");
        }
    }

    private static void adaptStyleRule(JSONObject rule) throws JSONException {
        convertRulesMapToArray(rule);
        if (rule.has(FieldStyleRule.JSON_RULES_KEY)) {
            Object rules = rule.get(FieldStyleRule.JSON_RULES_KEY);
            if (rules instanceof JSONArray) {
                JSONArray arr = (JSONArray) rules;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject item = arr.getJSONObject(i);
                    if (item.has(SimpleFeatureRenderer.JSON_STYLE_KEY)) {
                        adaptStyleJson(item.getJSONObject(SimpleFeatureRenderer.JSON_STYLE_KEY));
                    } else if (item.has(JSON_NAME_KEY)) {
                        adaptStyleJson(item);
                    }
                }
            }
        }
        if (rule.has(FieldStyleRule.JSON_OTHER_STYLE_KEY)) {
            adaptStyleJson(rule.getJSONObject(FieldStyleRule.JSON_OTHER_STYLE_KEY));
        }
    }

    /**
     * Web / collector exports may use {@code rules: { "value": { style } }} instead of a JSONArray.
     */
    private static void convertRulesMapToArray(JSONObject rule) throws JSONException {
        if (!rule.has(FieldStyleRule.JSON_RULES_KEY)) {
            return;
        }
        Object rules = rule.get(FieldStyleRule.JSON_RULES_KEY);
        if (!(rules instanceof JSONObject)) {
            return;
        }
        JSONObject map = (JSONObject) rules;
        JSONArray arr = new JSONArray();
        Iterator<String> keys = map.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object styleVal = map.get(key);
            JSONObject item = new JSONObject();
            item.put(JSON_VALUE_KEY, key);
            if (styleVal instanceof JSONObject) {
                JSONObject styleObj = (JSONObject) styleVal;
                if (styleObj.has(SimpleFeatureRenderer.JSON_STYLE_KEY)) {
                    styleVal = styleObj.getJSONObject(SimpleFeatureRenderer.JSON_STYLE_KEY);
                } else if (styleObj.has("style") && styleObj.get("style") instanceof JSONObject) {
                    styleVal = styleObj.getJSONObject("style");
                }
                adaptStyleJson((JSONObject) styleVal);
                item.put(SimpleFeatureRenderer.JSON_STYLE_KEY, styleVal);
            }
            arr.put(item);
        }
        rule.put(FieldStyleRule.JSON_RULES_KEY, arr);
    }

    private static void adaptStyleJson(JSONObject style) throws JSONException {
        if (style == null) {
            return;
        }
        hoistLabelAttributes(style);
        mapLabelAliases(style);
        if (style.has("scale_with_zoom") && !style.has(JSON_SCALE_SIZE_WITH_ZOOM_KEY)) {
            style.put(JSON_SCALE_SIZE_WITH_ZOOM_KEY, style.optBoolean("scale_with_zoom"));
        }
        normalizeOpacity0to1(style, JSON_LABEL_TEXT_OPACITY_KEY, 255);
    }

    private static void hoistLabelAttributes(JSONObject style) throws JSONException {
        if (!style.has(KEY_LABEL_ATTRIBUTES)) {
            return;
        }
        JSONObject nested = style.getJSONObject(KEY_LABEL_ATTRIBUTES);
        Iterator<String> keys = nested.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            if (!style.has(k)) {
                style.put(k, nested.get(k));
            }
        }
        style.remove(KEY_LABEL_ATTRIBUTES);
    }

    private static void mapLabelAliases(JSONObject style) throws JSONException {
        copyIfMissing(style, "label", JSON_DISPLAY_NAME);
        copyIfMissing(style, "label_field", JSON_VALUE_KEY);
        if (!style.has(JSON_VALUE_KEY) && style.has("field")) {
            style.put(JSON_VALUE_KEY, style.get("field"));
        }
        copyIfMissing(style, "halo_color", JSON_LABEL_HALO_COLOR_KEY);
        copyIfMissing(style, "halo_width", JSON_LABEL_HALO_WIDTH_KEY);
        copyIfMissing(style, "halo_blur", JSON_LABEL_HALO_BLUR_KEY);
        copyIfMissing(style, "template", JSON_LABEL_TEMPLATE_KEY);
    }

    private static void copyIfMissing(JSONObject style, String from, String to) throws JSONException {
        if (style.has(from) && !style.has(to)) {
            style.put(to, style.get(from));
        }
    }

    /** Convert fractional opacity (0–1) from web exports to mobile 0–255 integer. */
    private static void normalizeOpacity0to1(JSONObject obj, String key, int max) throws JSONException {
        if (!obj.has(key)) {
            return;
        }
        Object raw = obj.opt(key);
        if (raw instanceof Number) {
            double v = ((Number) raw).doubleValue();
            if (v >= 0.0 && v <= 1.0) {
                obj.put(key, Math.round(v * max));
            }
        }
    }
}

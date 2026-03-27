package com.nextgis.maplib.util;

import android.os.Build;
import android.text.Html;
import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

import com.nextgis.maplib.util.GeoConstants;

/**
 * Utilities for parsing NGW resource description JSON that contains mobile layer config.
 * Extracted from LayerFillService so the same logic can be used from NGWVectorLayer during sync.
 */
public final class LayerConfigUtil {

    private LayerConfigUtil() {
    }

    /**
     * Extract config description text from a full NGW resource meta JSON.
     * Tries resource.description, then top-level, then vector_layer, then feature_layer.
     */
    public static String extractNgwResourceDescriptionJson(JSONObject root) {
        if (root == null) {
            return null;
        }
        JSONObject res = root.optJSONObject("resource");
        if (res != null) {
            String d = readDescriptionField(res);
            if (!TextUtils.isEmpty(d)) {
                return d;
            }
        }
        String top = readDescriptionField(root);
        if (!TextUtils.isEmpty(top)) {
            return top;
        }
        JSONObject vl = root.optJSONObject("vector_layer");
        if (vl != null) {
            String d = readDescriptionField(vl);
            if (!TextUtils.isEmpty(d)) return d;
        }
        JSONObject fl = root.optJSONObject("feature_layer");
        if (fl != null) {
            return readDescriptionField(fl);
        }
        return null;
    }

    private static String readDescriptionField(JSONObject o) {
        if (o == null || !o.has("description") || o.isNull("description")) {
            return null;
        }
        Object raw = o.opt("description");
        if (raw instanceof String) {
            return (String) raw;
        }
        return raw != null ? raw.toString() : null;
    }

    public static JSONObject parseLayerConfigObject(String raw) throws JSONException {
        return parseLayerConfigObject(raw, 0);
    }

    private static void mergeCollectorNgwMetadataOntoLayerJson(JSONObject wrapper, JSONObject layerJson)
            throws JSONException {
        if (wrapper == null || layerJson == null) {
            return;
        }
        final String[] keys = {
                "account",
                "sync_type",
                "sync_direction",
                "ngw_version_major",
                "ngw_version_minor",
                "id",
                "server_where",
                "tracked",
                "ngw_layer_type",
                GeoConstants.GEOJSON_CRS,
        };
        for (String k : keys) {
            if (wrapper.has(k) && !wrapper.isNull(k) && !layerJson.has(k)) {
                layerJson.put(k, wrapper.opt(k));
            }
        }
    }

    private static JSONObject parseLayerConfigObject(String raw, int depth) throws JSONException {
        if (depth > 6) {
            throw new JSONException("layer config: too many unwrap levels");
        }
        String s = unwrapLayerConfigJsonText(raw);
        if (s.isEmpty()) {
            throw new JSONException("layer config: empty after unwrap");
        }
        JSONObject root = new JSONObject(s);
        if (root.length() == 1) {
            Iterator<String> it = root.keys();
            String k = it.next();
            if ("layer".equals(k) || "config".equals(k) || "vector_layer".equals(k)) {
                if (root.isNull(k)) {
                    throw new JSONException("layer config: null wrapper " + k);
                }
                Object v = root.opt(k);
                if (v instanceof JSONObject) {
                    JSONObject inner = (JSONObject) v;
                    mergeCollectorNgwMetadataOntoLayerJson(root, inner);
                    return inner;
                }
                if (v instanceof String) {
                    return parseLayerConfigObject((String) v, depth + 1);
                }
                throw new JSONException("layer config: wrapper " + k + " must be object or string");
            }
        }
        return root;
    }

    public static String unwrapLayerConfigJsonText(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.startsWith("\uFEFF")) {
            s = s.substring(1).trim();
        }
        if (s.startsWith("<")) {
            s = stripSimpleHtmlToText(s);
        }
        if (s.contains("&quot;") || s.contains("&lt;") || s.contains("&#")) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                s = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString();
            } else {
                s = Html.fromHtml(s).toString();
            }
            s = s.trim();
        }
        s = extractBalancedJsonObject(s);
        return s.trim();
    }

    private static String stripSimpleHtmlToText(String html) {
        if (html == null) {
            return "";
        }
        String t = html;
        t = t.replaceAll("(?is)<\\s*head\\s*>.*?</\\s*head\\s*>", "");
        t = t.replaceAll("(?is)<\\s*br\\s*/?>", "\n");
        t = t.replaceAll("(?is)</\\s*p\\s*>", "\n");
        t = t.replaceAll("(?is)<\\s*p[^>]*>", "");
        t = t.replaceAll("<[^>]+>", "");
        return t.trim();
    }

    private static String extractBalancedJsonObject(String s) {
        int start = s.indexOf('{');
        if (start < 0) {
            return s;
        }
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return s.substring(start, i + 1);
                }
            }
        }
        return s.substring(start);
    }
}

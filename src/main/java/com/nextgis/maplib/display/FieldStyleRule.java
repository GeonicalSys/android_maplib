/*
 * Project:  NextGIS Mobile
 * Purpose:  Mobile GIS for Android.
 * Author:   Stanislav Petriakov, becomeglory@gmail.com
 * *****************************************************************************
 * Copyright (c) 2016-2017 NextGIS, info@nextgis.com
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.nextgis.maplib.display;

import com.nextgis.maplib.api.IJSONStore;
import com.nextgis.maplib.api.IStyleRule;
import com.nextgis.maplib.datasource.Feature;
import com.nextgis.maplib.map.VectorLayer;
import com.nextgis.maplib.util.Constants;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class FieldStyleRule implements IStyleRule, IJSONStore {
    public static final String JSON_RULES_KEY = "rules";
    public static final String JSON_FIELD_KEY = "field";
    public static final String JSON_OTHER_STYLE_KEY = "other_style";

    VectorLayer mLayer;
    Map<String, Style> mStyleRules;
    String mKey;
    boolean mKeyIgnoreCase;
    Style mOtherStyle;

    public FieldStyleRule(VectorLayer layer) {
        mLayer = layer;
        mStyleRules = new LinkedHashMap<>();
    }

    public void setKey(String key) {
        mKey = key;
    }

    public String getKey() {
        return mKey;
    }

    public boolean isKeyIgnoreCase() {
        return mKeyIgnoreCase;
    }

    public void setKeyIgnoreCase(boolean keyIgnoreCase) {
        mKeyIgnoreCase = keyIgnoreCase;
    }

    /** Trim whitespace; optionally fold case for rule lookup keys. */
    public String normalizeKey(String rawKey) {
        if (rawKey == null) {
            return null;
        }
        String key = rawKey.trim();
        if (mKeyIgnoreCase) {
            key = key.toLowerCase(Locale.ROOT);
        }
        return key;
    }

    public void setStyle(String value, Style style) {
        mStyleRules.put(normalizeKey(value), style);
    }

    public Style getStyle(String value) {
        return mStyleRules.get(normalizeKey(value));
    }

    public void removeStyle(String value) {
        mStyleRules.remove(normalizeKey(value));
    }

    public Map<String, Style> getStyleRules() {
        return mStyleRules;
    }

    public Style getOtherStyle() {
        return mOtherStyle;
    }

    public void setOtherStyle(Style otherStyle) {
        mOtherStyle = otherStyle;
    }

    /** Style for features that do not match any rule category. */
    public Style resolveOtherStyle(Style rendererFallback) {
        return mOtherStyle != null ? mOtherStyle : rendererFallback;
    }

    /**
     * Effective style for a matched rule value: rule paint fields win; unset optional fields are
     * filled from {@code other} (style for others / default).
     */
    public static Style mergeRuleWithOther(Style rule, Style other) {
        if (rule == null) {
            return other;
        }
        if (other == null) {
            return rule;
        }
        try {
            Style effective = rule.clone();
            fillUnsetOptionalFrom(effective, other);
            return effective;
        } catch (CloneNotSupportedException e) {
            fillUnsetOptionalFrom(rule, other);
            return rule;
        }
    }

    /**
     * Resolves style for a field value: unmatched → other/default; matched → rule with unset
     * optionals filled from other/default.
     */
    public Style resolveEffectiveStyle(String value, Style rendererFallback) {
        Style other = resolveOtherStyle(rendererFallback);
        Style rule = value != null ? getStyle(value) : null;
        if (rule == null) {
            return other;
        }
        return mergeRuleWithOther(rule, other);
    }

    private static void fillUnsetOptionalFrom(Style target, Style source) {
        if (target == null || source == null) {
            return;
        }
        if (isBlank(target.getSizeZoomScaleStops()) && !isBlank(source.getSizeZoomScaleStops())) {
            target.setSizeZoomScaleStops(source.getSizeZoomScaleStops());
        }
        if (!target.isScaleSizeWithZoom() && source.isScaleSizeWithZoom()) {
            target.setScaleSizeWithZoom(true);
        }
        if (target instanceof SimpleMarkerStyle && source instanceof SimpleMarkerStyle) {
            SimpleMarkerStyle t = (SimpleMarkerStyle) target;
            SimpleMarkerStyle s = (SimpleMarkerStyle) source;
            if (t.getField() == null && s.getField() != null) {
                t.setField(s.getField());
            }
            if (t.getText() == null && s.getText() != null) {
                t.setText(s.getText());
            }
            if (isBlank(t.getIconImage()) && !isBlank(s.getIconImage())) {
                t.setIconImage(s.getIconImage());
            }
            if (t.getIconSize() <= 0f && s.getIconSize() > 0f) {
                t.setIconSize(s.getIconSize());
            }
            LabelAttributes labels = t.getLabelAttributes();
            if (labels != null) {
                labels.fillUnsetFrom(s.getLabelAttributes());
            }
        } else if (target instanceof SimpleLineStyle && source instanceof SimpleLineStyle) {
            SimpleLineStyle t = (SimpleLineStyle) target;
            SimpleLineStyle s = (SimpleLineStyle) source;
            if (t.getField() == null && s.getField() != null) {
                t.setField(s.getField());
            }
            if (t.getText() == null && s.getText() != null) {
                t.setText(s.getText());
            }
            if (t.getDashArray() == null && s.getDashArray() != null) {
                t.setDashArray(s.getDashArray());
            }
            LabelAttributes labels = t.getLabelAttributes();
            if (labels != null) {
                labels.fillUnsetFrom(s.getLabelAttributes());
            }
        } else if (target instanceof SimplePolygonStyle && source instanceof SimplePolygonStyle) {
            SimplePolygonStyle t = (SimplePolygonStyle) target;
            SimplePolygonStyle s = (SimplePolygonStyle) source;
            if (t.getField() == null && s.getField() != null) {
                t.setField(s.getField());
            }
            if (t.getText() == null && s.getText() != null) {
                t.setText(s.getText());
            }
            if (isBlank(t.getFillPatternImage()) && !isBlank(s.getFillPatternImage())) {
                t.setFillPatternImage(s.getFillPatternImage());
            }
            LabelAttributes labels = t.getLabelAttributes();
            if (labels != null) {
                labels.fillUnsetFrom(s.getLabelAttributes());
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public void applyStyleForFeatureId(Style target, long featureId, Style rendererFallback) {
        if (mKey == null) {
            return;
        }

        Feature feature = mLayer.getFeature(featureId);
        String value = mKey.equals(Constants.FIELD_ID)
                ? feature.getId() + ""
                : feature.getFieldValueAsString(mKey);

        Style source = resolveEffectiveStyle(value, rendererFallback);
        if (source != null) {
            copyStyleParams(target, source);
        }
    }

    public static void copyStyleParams(Style target, Style source) {
        if (target == null || source == null) {
            return;
        }
        if (target instanceof SimpleMarkerStyle && source instanceof SimpleMarkerStyle) {
            SimpleMarkerStyle markerStyle = (SimpleMarkerStyle) target;
            SimpleMarkerStyle ruleStyle = (SimpleMarkerStyle) source;
            markerStyle.setColor(ruleStyle.getColor());
            markerStyle.setOutColor(ruleStyle.getOutColor());
            markerStyle.setType(ruleStyle.getType());
            markerStyle.setSize(ruleStyle.getSize());
            markerStyle.setWidth(ruleStyle.getWidth());
            markerStyle.setText(ruleStyle.getText());
            markerStyle.setField(ruleStyle.getField());
            markerStyle.setTextSize(ruleStyle.getTextSize());
            markerStyle.setTextAlignment(ruleStyle.getTextAlignment());
            markerStyle.setTextColor(ruleStyle.getTextColor());
            markerStyle.setAlpha(ruleStyle.getAlpha());
            markerStyle.setOutAlpha(ruleStyle.getOutAlpha());
            markerStyle.setScaleSizeWithZoom(ruleStyle.isScaleSizeWithZoom());
            markerStyle.setSizeZoomScaleStops(ruleStyle.getSizeZoomScaleStops());
            markerStyle.setCircleBlur(ruleStyle.getCircleBlur());
            markerStyle.setIconImage(ruleStyle.getIconImage());
            markerStyle.setIconSize(ruleStyle.getIconSize());
            markerStyle.setIconRotate(ruleStyle.getIconRotate());
            markerStyle.setIconOffsetX(ruleStyle.getIconOffsetX());
            markerStyle.setIconOffsetY(ruleStyle.getIconOffsetY());
            markerStyle.setIconAnchor(ruleStyle.getIconAnchor());
            markerStyle.setIconAllowOverlap(ruleStyle.isIconAllowOverlap());
            markerStyle.setIconIgnorePlacement(ruleStyle.isIconIgnorePlacement());
            markerStyle.setLabelAttributes(ruleStyle.getLabelAttributes());
        } else if (target instanceof SimpleLineStyle && source instanceof SimpleLineStyle) {
            SimpleLineStyle lineStyle = (SimpleLineStyle) target;
            SimpleLineStyle ruleStyle = (SimpleLineStyle) source;
            lineStyle.setColor(ruleStyle.getColor());
            lineStyle.setOutColor(ruleStyle.getOutColor());
            lineStyle.setType(ruleStyle.getType());
            lineStyle.setWidth(ruleStyle.getWidth());
            lineStyle.setText(ruleStyle.getText());
            lineStyle.setField(ruleStyle.getField());
            lineStyle.setTextSize(ruleStyle.getTextSize());
            lineStyle.setTextColor(ruleStyle.getTextColor());
            lineStyle.setLineCap(ruleStyle.getLineCap());
            lineStyle.setLineJoin(ruleStyle.getLineJoin());
            lineStyle.setLineMiterLimit(ruleStyle.getLineMiterLimit());
            lineStyle.setLineBlur(ruleStyle.getLineBlur());
            lineStyle.setDashPreset(ruleStyle.getDashPreset());
            lineStyle.setDashArray(ruleStyle.getDashArray());
            lineStyle.setLineOffset(ruleStyle.getLineOffset());
            lineStyle.setLineGapWidth(ruleStyle.getLineGapWidth());
            lineStyle.setLineOutlineMultiplier(ruleStyle.getLineOutlineMultiplier());
            lineStyle.setAlpha(ruleStyle.getAlpha());
            lineStyle.setOutAlpha(ruleStyle.getOutAlpha());
            lineStyle.setScaleSizeWithZoom(ruleStyle.isScaleSizeWithZoom());
            lineStyle.setSizeZoomScaleStops(ruleStyle.getSizeZoomScaleStops());
            lineStyle.setLabelAttributes(ruleStyle.getLabelAttributes());
        } else if (target instanceof SimplePolygonStyle && source instanceof SimplePolygonStyle) {
            SimplePolygonStyle polygonStyle = (SimplePolygonStyle) target;
            SimplePolygonStyle ruleStyle = (SimplePolygonStyle) source;
            polygonStyle.setColor(ruleStyle.getColor());
            polygonStyle.setOutColor(ruleStyle.getOutColor());
            polygonStyle.setWidth(ruleStyle.getWidth());
            polygonStyle.setFill(ruleStyle.isFill());
            polygonStyle.setFillPattern(ruleStyle.getFillPattern());
            polygonStyle.setText(ruleStyle.getText());
            polygonStyle.setTextSize(ruleStyle.getTextSize());
            polygonStyle.setTextColor(ruleStyle.getTextColor());
            polygonStyle.setField(ruleStyle.getField());
            polygonStyle.setAlpha(ruleStyle.getAlpha());
            polygonStyle.setOutAlpha(ruleStyle.getOutAlpha());
            polygonStyle.setScaleSizeWithZoom(ruleStyle.isScaleSizeWithZoom());
            polygonStyle.setSizeZoomScaleStops(ruleStyle.getSizeZoomScaleStops());
            polygonStyle.setFillPatternImage(ruleStyle.getFillPatternImage());
            polygonStyle.setFillTranslateX(ruleStyle.getFillTranslateX());
            polygonStyle.setFillTranslateY(ruleStyle.getFillTranslateY());
            polygonStyle.setLabelAttributes(ruleStyle.getLabelAttributes());
        }
    }

    @Override
    public void setStyleParams(Style style, long featureId) {
        if (mKey == null)
            return;

        Feature feature = mLayer.getFeature(featureId);
        String value = mKey.equals(Constants.FIELD_ID) ? feature.getId() + "" : feature.getFieldValueAsString(mKey);

        // Unmatched values use other/default style (same as applyStyleForFeatureId).
        Style source = resolveEffectiveStyle(value, null);
        if (source != null) {
            copyStyleParams(style, source);
        }
    }

    @Override
    public JSONObject toJSON() throws JSONException {
        JSONObject result = new JSONObject();
        JSONArray rules = new JSONArray();
        for (Map.Entry<String, Style> rule : mStyleRules.entrySet()) {
            JSONObject item = new JSONObject();
            item.put(Constants.JSON_VALUE_KEY, rule.getKey());
            item.put(SimpleFeatureRenderer.JSON_STYLE_KEY, rule.getValue().toJSON());
            rules.put(item);
        }

        result.put(JSON_FIELD_KEY, mKey);
        result.put(JSON_RULES_KEY, rules);
        if (mKeyIgnoreCase) {
            result.put(Constants.JSON_RULE_KEY_IGNORE_CASE_KEY, true);
        }
        if (mOtherStyle != null) {
            result.put(JSON_OTHER_STYLE_KEY, mOtherStyle.toJSON());
        }

        return result;
    }

    @Override
    public void fromJSON(JSONObject jsonObject) throws JSONException {
        if (jsonObject.has(JSON_FIELD_KEY))
            mKey = jsonObject.getString(JSON_FIELD_KEY);

        mKeyIgnoreCase = jsonObject.optBoolean(Constants.JSON_RULE_KEY_IGNORE_CASE_KEY, false);
        mStyleRules.clear();

        if (jsonObject.has(JSON_RULES_KEY)) {
            JSONArray rules = jsonObject.getJSONArray(JSON_RULES_KEY);
            try {
                for (int i = 0; i < rules.length(); i++) {
                    JSONObject ruleObject = rules.getJSONObject(i);
                    AtomicReference<Style> reference = new AtomicReference<>();
                    SimpleFeatureRenderer.fromJSON(ruleObject, reference);

                    if (reference.get() != null) {
                        String value = ruleObject.getString(Constants.JSON_VALUE_KEY);
                        setStyle(value, reference.get());
                    }
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        if (jsonObject.has(JSON_OTHER_STYLE_KEY)) {
            AtomicReference<Style> otherReference = new AtomicReference<>();
            JSONObject otherWrapper = new JSONObject();
            otherWrapper.put(SimpleFeatureRenderer.JSON_STYLE_KEY,
                    jsonObject.getJSONObject(JSON_OTHER_STYLE_KEY));
            SimpleFeatureRenderer.fromJSON(otherWrapper, otherReference);
            mOtherStyle = otherReference.get();
        } else {
            mOtherStyle = null;
        }
    }

    public void clearRules() {
        mStyleRules.clear();
    }

    public void renormalizeRuleKeys() {
        Map<String, Style> snapshot = new LinkedHashMap<>(mStyleRules);
        mStyleRules.clear();
        for (Map.Entry<String, Style> entry : snapshot.entrySet()) {
            mStyleRules.put(normalizeKey(entry.getKey()), entry.getValue());
        }
    }

    public int size() {
        return mStyleRules.size();
    }
}

/*
 * Extended label settings for MapLibre SymbolLayer (halo, zoom scale, collisions, template).
 */
package com.nextgis.maplib.display;

import android.graphics.Color;

import com.nextgis.maplib.api.IJSONStore;

import org.json.JSONException;
import org.json.JSONObject;

import static com.nextgis.maplib.util.Constants.JSON_LINE_LABEL_HORIZONTAL_KEY;
import static com.nextgis.maplib.util.Constants.JSON_LINE_LABEL_REPEAT_KEY;
import static com.nextgis.maplib.util.Constants.JSON_LABEL_MAX_ZOOM_KEY;
import static com.nextgis.maplib.util.Constants.JSON_LABEL_MIN_ZOOM_KEY;
import static com.nextgis.maplib.util.Constants.JSON_LABEL_ALLOW_OVERLAP_KEY;
import static com.nextgis.maplib.util.Constants.JSON_LABEL_HALO_BLUR_KEY;
import static com.nextgis.maplib.util.Constants.JSON_LABEL_HALO_COLOR_KEY;
import static com.nextgis.maplib.util.Constants.JSON_LABEL_HALO_WIDTH_KEY;
import static com.nextgis.maplib.util.Constants.JSON_LABEL_FONT_KEY;
import static com.nextgis.maplib.util.Constants.JSON_LABEL_JUSTIFY_KEY;
import static com.nextgis.maplib.util.Constants.JSON_LABEL_KEEP_UPRIGHT_KEY;
import static com.nextgis.maplib.util.Constants.JSON_LABEL_LETTER_SPACING_KEY;
import static com.nextgis.maplib.util.Constants.JSON_LABEL_LINE_HEIGHT_KEY;
import static com.nextgis.maplib.util.Constants.JSON_LABEL_MAX_ANGLE_KEY;
import static com.nextgis.maplib.util.Constants.JSON_LABEL_PADDING_KEY;
import static com.nextgis.maplib.util.Constants.JSON_LABEL_OPTIONAL_KEY;
import static com.nextgis.maplib.util.Constants.JSON_LABEL_SCALE_WITH_ZOOM_KEY;
import static com.nextgis.maplib.util.Constants.JSON_LABEL_SPACING_KEY;
import static com.nextgis.maplib.util.Constants.JSON_LABEL_TEMPLATE_KEY;
import static com.nextgis.maplib.util.Constants.JSON_LABEL_TEXT_OPACITY_KEY;
import static com.nextgis.maplib.util.Constants.JSON_LABEL_TRANSFORM_KEY;
import static com.nextgis.maplib.util.Constants.JSON_LABEL_ZOOM_STOPS_KEY;
import static com.nextgis.maplib.util.Constants.JSON_TEXT_MAX_WIDTH_KEY;

public class LabelAttributes implements IJSONStore, Cloneable {

    public static final int DEFAULT_HALO_COLOR = Color.WHITE;
    public static final float DEFAULT_HALO_WIDTH = 1.5f;
    public static final float DEFAULT_SYMBOL_SPACING = 15f;
    public static final boolean DEFAULT_LINE_LABEL_REPEAT = true;
    public static final int DEFAULT_TEXT_OPACITY = 255;
    public static final String DEFAULT_TEXT_FONT = "Open Sans Regular";
    public static final String DEFAULT_TEXT_JUSTIFY = "auto";
    public static final String DEFAULT_TEXT_TRANSFORM = "none";
    public static final float DEFAULT_TEXT_LINE_HEIGHT = 1.2f;
    public static final float DEFAULT_TEXT_PADDING = 2f;
    public static final float DEFAULT_TEXT_MAX_ANGLE = 45f;

    protected int mTextHaloColor = DEFAULT_HALO_COLOR;
    protected float mTextHaloWidth = DEFAULT_HALO_WIDTH;
    protected float mTextHaloBlur;
    protected int mTextOpacity = DEFAULT_TEXT_OPACITY;
    protected boolean mTextScaleWithZoom;
    protected String mTextZoomScaleStops;
    protected Boolean mTextAllowOverlap;
    protected boolean mTextOptional = true;
    protected float mSymbolSpacing = DEFAULT_SYMBOL_SPACING;
    protected float mTextMaxWidth;
    protected String mTextFont = DEFAULT_TEXT_FONT;
    protected String mTextJustify = DEFAULT_TEXT_JUSTIFY;
    protected String mTextTransform = DEFAULT_TEXT_TRANSFORM;
    protected float mTextLetterSpacing;
    protected float mTextLineHeight = DEFAULT_TEXT_LINE_HEIGHT;
    protected float mTextPadding = DEFAULT_TEXT_PADDING;
    protected Boolean mTextKeepUpright;
    protected float mTextMaxAngle = DEFAULT_TEXT_MAX_ANGLE;
    protected String mLabelTemplate;
    protected float mLabelMinZoom = -1f;
    protected float mLabelMaxZoom = -1f;
    protected boolean mLineLabelRepeat = DEFAULT_LINE_LABEL_REPEAT;
    protected boolean mLineLabelHorizontal;

    public static LabelAttributes defaults() {
        return new LabelAttributes();
    }

    public static LabelAttributes fromStyle(Style style) {
        if (style instanceof SimpleMarkerStyle) {
            return ((SimpleMarkerStyle) style).getLabelAttributes();
        }
        if (style instanceof SimpleLineStyle) {
            return ((SimpleLineStyle) style).getLabelAttributes();
        }
        if (style instanceof SimplePolygonStyle) {
            return ((SimplePolygonStyle) style).getLabelAttributes();
        }
        return defaults();
    }

    @Override
    public LabelAttributes clone() throws CloneNotSupportedException {
        LabelAttributes copy = (LabelAttributes) super.clone();
        copy.mTextHaloColor = mTextHaloColor;
        copy.mTextHaloWidth = mTextHaloWidth;
        copy.mTextHaloBlur = mTextHaloBlur;
        copy.mTextOpacity = mTextOpacity;
        copy.mTextScaleWithZoom = mTextScaleWithZoom;
        copy.mTextZoomScaleStops = mTextZoomScaleStops;
        copy.mTextAllowOverlap = mTextAllowOverlap;
        copy.mTextOptional = mTextOptional;
        copy.mSymbolSpacing = mSymbolSpacing;
        copy.mTextMaxWidth = mTextMaxWidth;
        copy.mTextFont = mTextFont;
        copy.mTextJustify = mTextJustify;
        copy.mTextTransform = mTextTransform;
        copy.mTextLetterSpacing = mTextLetterSpacing;
        copy.mTextLineHeight = mTextLineHeight;
        copy.mTextPadding = mTextPadding;
        copy.mTextKeepUpright = mTextKeepUpright;
        copy.mTextMaxAngle = mTextMaxAngle;
        copy.mLabelTemplate = mLabelTemplate;
        copy.mLabelMinZoom = mLabelMinZoom;
        copy.mLabelMaxZoom = mLabelMaxZoom;
        copy.mLineLabelRepeat = mLineLabelRepeat;
        copy.mLineLabelHorizontal = mLineLabelHorizontal;
        return copy;
    }

    public int getTextHaloColor() {
        return mTextHaloColor;
    }

    public void setTextHaloColor(int textHaloColor) {
        mTextHaloColor = textHaloColor;
    }

    public float getTextHaloWidth() {
        return mTextHaloWidth;
    }

    public void setTextHaloWidth(float textHaloWidth) {
        mTextHaloWidth = textHaloWidth;
    }

    public float getTextHaloBlur() {
        return mTextHaloBlur;
    }

    public void setTextHaloBlur(float textHaloBlur) {
        mTextHaloBlur = textHaloBlur;
    }

    public int getTextOpacity() {
        return mTextOpacity;
    }

    public void setTextOpacity(int textOpacity) {
        mTextOpacity = Math.max(0, Math.min(255, textOpacity));
    }

    public float textOpacityFloat() {
        return Math.max(0f, Math.min(1f, mTextOpacity / 255f));
    }

    public boolean isTextScaleWithZoom() {
        return mTextScaleWithZoom;
    }

    public void setTextScaleWithZoom(boolean textScaleWithZoom) {
        mTextScaleWithZoom = textScaleWithZoom;
    }

    public String getTextZoomScaleStops() {
        return mTextZoomScaleStops;
    }

    public void setTextZoomScaleStops(String textZoomScaleStops) {
        mTextZoomScaleStops = textZoomScaleStops != null && !textZoomScaleStops.trim().isEmpty()
                ? textZoomScaleStops.trim()
                : null;
    }

    public Boolean getTextAllowOverlap() {
        return mTextAllowOverlap;
    }

    public void setTextAllowOverlap(Boolean textAllowOverlap) {
        mTextAllowOverlap = textAllowOverlap;
    }

    public boolean isTextOptional() {
        return mTextOptional;
    }

    public void setTextOptional(boolean textOptional) {
        mTextOptional = textOptional;
    }

    public float getSymbolSpacing() {
        return mSymbolSpacing;
    }

    public void setSymbolSpacing(float symbolSpacing) {
        mSymbolSpacing = symbolSpacing;
    }

    public float getTextMaxWidth() {
        return mTextMaxWidth;
    }

    public void setTextMaxWidth(float textMaxWidth) {
        mTextMaxWidth = textMaxWidth;
    }

    public String getTextFont() {
        return mTextFont != null && !mTextFont.trim().isEmpty()
                ? mTextFont.trim()
                : DEFAULT_TEXT_FONT;
    }

    public void setTextFont(String textFont) {
        mTextFont = textFont != null && !textFont.trim().isEmpty()
                ? textFont.trim()
                : DEFAULT_TEXT_FONT;
    }

    public String[] getTextFontStack() {
        String font = getTextFont();
        String[] parts = font.split(",");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
            if (parts[i].isEmpty()) {
                parts[i] = DEFAULT_TEXT_FONT;
            }
        }
        return parts;
    }

    public String getTextJustify() {
        return normalizeTextChoice(mTextJustify, DEFAULT_TEXT_JUSTIFY);
    }

    public void setTextJustify(String textJustify) {
        mTextJustify = normalizeTextChoice(textJustify, DEFAULT_TEXT_JUSTIFY);
    }

    public String getTextTransform() {
        return normalizeTextChoice(mTextTransform, DEFAULT_TEXT_TRANSFORM);
    }

    public void setTextTransform(String textTransform) {
        mTextTransform = normalizeTextChoice(textTransform, DEFAULT_TEXT_TRANSFORM);
    }

    public float getTextLetterSpacing() {
        return mTextLetterSpacing;
    }

    public void setTextLetterSpacing(float textLetterSpacing) {
        mTextLetterSpacing = textLetterSpacing;
    }

    public float getTextLineHeight() {
        return mTextLineHeight > 0f ? mTextLineHeight : DEFAULT_TEXT_LINE_HEIGHT;
    }

    public void setTextLineHeight(float textLineHeight) {
        mTextLineHeight = textLineHeight > 0f ? textLineHeight : DEFAULT_TEXT_LINE_HEIGHT;
    }

    public float getTextPadding() {
        return mTextPadding >= 0f ? mTextPadding : DEFAULT_TEXT_PADDING;
    }

    public void setTextPadding(float textPadding) {
        mTextPadding = Math.max(0f, textPadding);
    }

    public Boolean getTextKeepUpright() {
        return mTextKeepUpright;
    }

    public void setTextKeepUpright(Boolean textKeepUpright) {
        mTextKeepUpright = textKeepUpright;
    }

    public float getTextMaxAngle() {
        return mTextMaxAngle > 0f ? mTextMaxAngle : DEFAULT_TEXT_MAX_ANGLE;
    }

    public void setTextMaxAngle(float textMaxAngle) {
        mTextMaxAngle = textMaxAngle > 0f ? textMaxAngle : DEFAULT_TEXT_MAX_ANGLE;
    }

    public String getLabelTemplate() {
        return mLabelTemplate;
    }

    public void setLabelTemplate(String labelTemplate) {
        mLabelTemplate = labelTemplate;
    }

    public float getLabelMinZoom() {
        return mLabelMinZoom;
    }

    public void setLabelMinZoom(float labelMinZoom) {
        mLabelMinZoom = labelMinZoom;
    }

    public float getLabelMaxZoom() {
        return mLabelMaxZoom;
    }

    public void setLabelMaxZoom(float labelMaxZoom) {
        mLabelMaxZoom = labelMaxZoom;
    }

    public boolean isLineLabelRepeat() {
        return mLineLabelRepeat;
    }

    public void setLineLabelRepeat(boolean lineLabelRepeat) {
        mLineLabelRepeat = lineLabelRepeat;
    }

    public boolean isLineLabelHorizontal() {
        return mLineLabelHorizontal;
    }

    public void setLineLabelHorizontal(boolean lineLabelHorizontal) {
        mLineLabelHorizontal = lineLabelHorizontal;
    }

    /**
     * Fills unset optional fields from {@code other}. Does not overwrite values already set on this
     * instance (sentinel/empty checks per FieldStyleRule merge contract).
     */
    public void fillUnsetFrom(LabelAttributes other) {
        if (other == null) {
            return;
        }
        if (mLabelMinZoom < 0f && other.mLabelMinZoom >= 0f) {
            mLabelMinZoom = other.mLabelMinZoom;
        }
        if (mLabelMaxZoom < 0f && other.mLabelMaxZoom >= 0f) {
            mLabelMaxZoom = other.mLabelMaxZoom;
        }
        if (isBlank(mTextZoomScaleStops) && !isBlank(other.mTextZoomScaleStops)) {
            mTextZoomScaleStops = other.mTextZoomScaleStops;
        }
        if (isBlank(mLabelTemplate) && !isBlank(other.mLabelTemplate)) {
            mLabelTemplate = other.mLabelTemplate;
        }
        if (mTextAllowOverlap == null && other.mTextAllowOverlap != null) {
            mTextAllowOverlap = other.mTextAllowOverlap;
        }
        if (mTextKeepUpright == null && other.mTextKeepUpright != null) {
            mTextKeepUpright = other.mTextKeepUpright;
        }
        // false / DEFAULT opacity are type-defaults (same as mergeInto omit rules)
        if (!mTextScaleWithZoom && other.mTextScaleWithZoom) {
            mTextScaleWithZoom = true;
        }
        if (mTextOpacity == DEFAULT_TEXT_OPACITY
                && other.mTextOpacity != DEFAULT_TEXT_OPACITY) {
            mTextOpacity = other.mTextOpacity;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public void mergeInto(JSONObject rootConfig) throws JSONException {
        if (mTextHaloColor != DEFAULT_HALO_COLOR) {
            rootConfig.put(JSON_LABEL_HALO_COLOR_KEY, mTextHaloColor);
        }
        if (mTextHaloWidth != DEFAULT_HALO_WIDTH) {
            rootConfig.put(JSON_LABEL_HALO_WIDTH_KEY, mTextHaloWidth);
        }
        if (mTextHaloBlur > 0f) {
            rootConfig.put(JSON_LABEL_HALO_BLUR_KEY, mTextHaloBlur);
        }
        if (mTextOpacity != DEFAULT_TEXT_OPACITY) {
            rootConfig.put(JSON_LABEL_TEXT_OPACITY_KEY, mTextOpacity);
        }
        if (mTextScaleWithZoom) {
            rootConfig.put(JSON_LABEL_SCALE_WITH_ZOOM_KEY, true);
        }
        if (mTextZoomScaleStops != null && !mTextZoomScaleStops.trim().isEmpty()) {
            rootConfig.put(JSON_LABEL_ZOOM_STOPS_KEY, mTextZoomScaleStops);
        }
        if (mTextAllowOverlap != null) {
            rootConfig.put(JSON_LABEL_ALLOW_OVERLAP_KEY, mTextAllowOverlap);
        }
        if (!mTextOptional) {
            rootConfig.put(JSON_LABEL_OPTIONAL_KEY, false);
        }
        if (mSymbolSpacing != DEFAULT_SYMBOL_SPACING) {
            rootConfig.put(JSON_LABEL_SPACING_KEY, mSymbolSpacing);
        }
        if (mTextMaxWidth > 0f) {
            rootConfig.put(JSON_TEXT_MAX_WIDTH_KEY, mTextMaxWidth);
        }
        if (!DEFAULT_TEXT_FONT.equals(getTextFont())) {
            rootConfig.put(JSON_LABEL_FONT_KEY, getTextFont());
        }
        if (!DEFAULT_TEXT_JUSTIFY.equals(getTextJustify())) {
            rootConfig.put(JSON_LABEL_JUSTIFY_KEY, getTextJustify());
        }
        if (!DEFAULT_TEXT_TRANSFORM.equals(getTextTransform())) {
            rootConfig.put(JSON_LABEL_TRANSFORM_KEY, getTextTransform());
        }
        if (mTextLetterSpacing != 0f) {
            rootConfig.put(JSON_LABEL_LETTER_SPACING_KEY, mTextLetterSpacing);
        }
        if (getTextLineHeight() != DEFAULT_TEXT_LINE_HEIGHT) {
            rootConfig.put(JSON_LABEL_LINE_HEIGHT_KEY, getTextLineHeight());
        }
        if (getTextPadding() != DEFAULT_TEXT_PADDING) {
            rootConfig.put(JSON_LABEL_PADDING_KEY, getTextPadding());
        }
        if (mTextKeepUpright != null) {
            rootConfig.put(JSON_LABEL_KEEP_UPRIGHT_KEY, mTextKeepUpright);
        }
        if (getTextMaxAngle() != DEFAULT_TEXT_MAX_ANGLE) {
            rootConfig.put(JSON_LABEL_MAX_ANGLE_KEY, getTextMaxAngle());
        }
        if (mLabelTemplate != null && !mLabelTemplate.isEmpty()) {
            rootConfig.put(JSON_LABEL_TEMPLATE_KEY, mLabelTemplate);
        }
        if (mLabelMinZoom >= 0f) {
            rootConfig.put(JSON_LABEL_MIN_ZOOM_KEY, mLabelMinZoom);
        }
        if (mLabelMaxZoom >= 0f) {
            rootConfig.put(JSON_LABEL_MAX_ZOOM_KEY, mLabelMaxZoom);
        }
        if (!mLineLabelRepeat) {
            rootConfig.put(JSON_LINE_LABEL_REPEAT_KEY, false);
        }
        if (mLineLabelHorizontal) {
            rootConfig.put(JSON_LINE_LABEL_HORIZONTAL_KEY, true);
        }
    }

    public void readFrom(JSONObject jsonObject) {
        mTextHaloColor = jsonObject.optInt(JSON_LABEL_HALO_COLOR_KEY, DEFAULT_HALO_COLOR);
        mTextHaloWidth = (float) jsonObject.optDouble(JSON_LABEL_HALO_WIDTH_KEY, DEFAULT_HALO_WIDTH);
        mTextHaloBlur = (float) jsonObject.optDouble(JSON_LABEL_HALO_BLUR_KEY, 0);
        mTextOpacity = jsonObject.optInt(JSON_LABEL_TEXT_OPACITY_KEY, DEFAULT_TEXT_OPACITY);
        mTextScaleWithZoom = jsonObject.optBoolean(JSON_LABEL_SCALE_WITH_ZOOM_KEY, false);
        mTextZoomScaleStops = jsonObject.optString(JSON_LABEL_ZOOM_STOPS_KEY, null);
        if (mTextZoomScaleStops != null && mTextZoomScaleStops.trim().isEmpty()) {
            mTextZoomScaleStops = null;
        }
        if (jsonObject.has(JSON_LABEL_ALLOW_OVERLAP_KEY)) {
            mTextAllowOverlap = jsonObject.optBoolean(JSON_LABEL_ALLOW_OVERLAP_KEY);
        } else {
            mTextAllowOverlap = null;
        }
        mTextOptional = jsonObject.optBoolean(JSON_LABEL_OPTIONAL_KEY, true);
        mSymbolSpacing = (float) jsonObject.optDouble(JSON_LABEL_SPACING_KEY, DEFAULT_SYMBOL_SPACING);
        mTextMaxWidth = (float) jsonObject.optDouble(JSON_TEXT_MAX_WIDTH_KEY, 0);
        setTextFont(jsonObject.optString(JSON_LABEL_FONT_KEY, DEFAULT_TEXT_FONT));
        setTextJustify(jsonObject.optString(JSON_LABEL_JUSTIFY_KEY, DEFAULT_TEXT_JUSTIFY));
        setTextTransform(jsonObject.optString(JSON_LABEL_TRANSFORM_KEY, DEFAULT_TEXT_TRANSFORM));
        mTextLetterSpacing = (float) jsonObject.optDouble(JSON_LABEL_LETTER_SPACING_KEY, 0);
        setTextLineHeight((float) jsonObject.optDouble(
                JSON_LABEL_LINE_HEIGHT_KEY, DEFAULT_TEXT_LINE_HEIGHT));
        setTextPadding((float) jsonObject.optDouble(JSON_LABEL_PADDING_KEY, DEFAULT_TEXT_PADDING));
        if (jsonObject.has(JSON_LABEL_KEEP_UPRIGHT_KEY)) {
            mTextKeepUpright = jsonObject.optBoolean(JSON_LABEL_KEEP_UPRIGHT_KEY);
        } else {
            mTextKeepUpright = null;
        }
        setTextMaxAngle((float) jsonObject.optDouble(JSON_LABEL_MAX_ANGLE_KEY, DEFAULT_TEXT_MAX_ANGLE));
        if (jsonObject.has(JSON_LABEL_TEMPLATE_KEY)) {
            mLabelTemplate = jsonObject.optString(JSON_LABEL_TEMPLATE_KEY, null);
        } else {
            mLabelTemplate = null;
        }
        mLabelMinZoom = (float) jsonObject.optDouble(JSON_LABEL_MIN_ZOOM_KEY, -1);
        mLabelMaxZoom = (float) jsonObject.optDouble(JSON_LABEL_MAX_ZOOM_KEY, -1);
        mLineLabelRepeat = jsonObject.optBoolean(JSON_LINE_LABEL_REPEAT_KEY, DEFAULT_LINE_LABEL_REPEAT);
        mLineLabelHorizontal = jsonObject.optBoolean(JSON_LINE_LABEL_HORIZONTAL_KEY, false);
    }

    @Override
    public JSONObject toJSON() throws JSONException {
        JSONObject rootConfig = new JSONObject();
        mergeInto(rootConfig);
        return rootConfig;
    }

    @Override
    public void fromJSON(JSONObject jsonObject) throws JSONException {
        readFrom(jsonObject);
    }

    private static String normalizeTextChoice(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}

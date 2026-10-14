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

    private java.util.Set<String> mExplicitKeys = new java.util.HashSet<>();

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
        copy.mExplicitKeys = new java.util.HashSet<>(mExplicitKeys);
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
        mExplicitKeys.add(JSON_LABEL_HALO_COLOR_KEY);
        mTextHaloColor = textHaloColor;
    }

    public float getTextHaloWidth() {
        return mTextHaloWidth;
    }

    public void setTextHaloWidth(float textHaloWidth) {
        mExplicitKeys.add(JSON_LABEL_HALO_WIDTH_KEY);
        mTextHaloWidth = textHaloWidth;
    }

    public float getTextHaloBlur() {
        return mTextHaloBlur;
    }

    public void setTextHaloBlur(float textHaloBlur) {
        mExplicitKeys.add(JSON_LABEL_HALO_BLUR_KEY);
        mTextHaloBlur = textHaloBlur;
    }

    public int getTextOpacity() {
        return mTextOpacity;
    }

    public void setTextOpacity(int textOpacity) {
        mExplicitKeys.add(JSON_LABEL_TEXT_OPACITY_KEY);
        mTextOpacity = Math.max(0, Math.min(255, textOpacity));
    }

    public float textOpacityFloat() {
        return Math.max(0f, Math.min(1f, mTextOpacity / 255f));
    }

    public boolean isTextScaleWithZoom() {
        return mTextScaleWithZoom;
    }

    public void setTextScaleWithZoom(boolean textScaleWithZoom) {
        mExplicitKeys.add(JSON_LABEL_SCALE_WITH_ZOOM_KEY);
        mTextScaleWithZoom = textScaleWithZoom;
    }

    public String getTextZoomScaleStops() {
        return mTextZoomScaleStops;
    }

    public void setTextZoomScaleStops(String textZoomScaleStops) {
        mExplicitKeys.add(JSON_LABEL_ZOOM_STOPS_KEY);
        mTextZoomScaleStops = textZoomScaleStops != null && !textZoomScaleStops.trim().isEmpty()
                ? textZoomScaleStops.trim()
                : null;
    }

    public Boolean getTextAllowOverlap() {
        return mTextAllowOverlap;
    }

    public void setTextAllowOverlap(Boolean textAllowOverlap) {
        mExplicitKeys.add(JSON_LABEL_ALLOW_OVERLAP_KEY);
        mTextAllowOverlap = textAllowOverlap;
    }

    public boolean isTextOptional() {
        return mTextOptional;
    }

    public void setTextOptional(boolean textOptional) {
        mExplicitKeys.add(JSON_LABEL_OPTIONAL_KEY);
        mTextOptional = textOptional;
    }

    public float getSymbolSpacing() {
        return mSymbolSpacing;
    }

    public void setSymbolSpacing(float symbolSpacing) {
        mExplicitKeys.add(JSON_LABEL_SPACING_KEY);
        mSymbolSpacing = symbolSpacing;
    }

    public float getTextMaxWidth() {
        return mTextMaxWidth;
    }

    public void setTextMaxWidth(float textMaxWidth) {
        mExplicitKeys.add(JSON_TEXT_MAX_WIDTH_KEY);
        mTextMaxWidth = textMaxWidth;
    }

    public String getTextFont() {
        return mTextFont != null && !mTextFont.trim().isEmpty()
                ? mTextFont.trim()
                : DEFAULT_TEXT_FONT;
    }

    public void setTextFont(String textFont) {
        mExplicitKeys.add(JSON_LABEL_FONT_KEY);
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
        mExplicitKeys.add(JSON_LABEL_JUSTIFY_KEY);
        mTextJustify = normalizeTextChoice(textJustify, DEFAULT_TEXT_JUSTIFY);
    }

    public String getTextTransform() {
        return normalizeTextChoice(mTextTransform, DEFAULT_TEXT_TRANSFORM);
    }

    public void setTextTransform(String textTransform) {
        mExplicitKeys.add(JSON_LABEL_TRANSFORM_KEY);
        mTextTransform = normalizeTextChoice(textTransform, DEFAULT_TEXT_TRANSFORM);
    }

    public float getTextLetterSpacing() {
        return mTextLetterSpacing;
    }

    public void setTextLetterSpacing(float textLetterSpacing) {
        mExplicitKeys.add(JSON_LABEL_LETTER_SPACING_KEY);
        mTextLetterSpacing = textLetterSpacing;
    }

    public float getTextLineHeight() {
        return mTextLineHeight > 0f ? mTextLineHeight : DEFAULT_TEXT_LINE_HEIGHT;
    }

    public void setTextLineHeight(float textLineHeight) {
        mExplicitKeys.add(JSON_LABEL_LINE_HEIGHT_KEY);
        mTextLineHeight = textLineHeight > 0f ? textLineHeight : DEFAULT_TEXT_LINE_HEIGHT;
    }

    public float getTextPadding() {
        return mTextPadding >= 0f ? mTextPadding : DEFAULT_TEXT_PADDING;
    }

    public void setTextPadding(float textPadding) {
        mExplicitKeys.add(JSON_LABEL_PADDING_KEY);
        mTextPadding = Math.max(0f, textPadding);
    }

    public Boolean getTextKeepUpright() {
        return mTextKeepUpright;
    }

    public void setTextKeepUpright(Boolean textKeepUpright) {
        mExplicitKeys.add(JSON_LABEL_KEEP_UPRIGHT_KEY);
        mTextKeepUpright = textKeepUpright;
    }

    public float getTextMaxAngle() {
        return mTextMaxAngle > 0f ? mTextMaxAngle : DEFAULT_TEXT_MAX_ANGLE;
    }

    public void setTextMaxAngle(float textMaxAngle) {
        mExplicitKeys.add(JSON_LABEL_MAX_ANGLE_KEY);
        mTextMaxAngle = textMaxAngle > 0f ? textMaxAngle : DEFAULT_TEXT_MAX_ANGLE;
    }

    public String getLabelTemplate() {
        return mLabelTemplate;
    }

    public void setLabelTemplate(String labelTemplate) {
        mExplicitKeys.add(JSON_LABEL_TEMPLATE_KEY);
        mLabelTemplate = labelTemplate;
    }

    public float getLabelMinZoom() {
        return mLabelMinZoom;
    }

    public void setLabelMinZoom(float labelMinZoom) {
        mExplicitKeys.add(JSON_LABEL_MIN_ZOOM_KEY);
        mLabelMinZoom = labelMinZoom;
    }

    public float getLabelMaxZoom() {
        return mLabelMaxZoom;
    }

    public void setLabelMaxZoom(float labelMaxZoom) {
        mExplicitKeys.add(JSON_LABEL_MAX_ZOOM_KEY);
        mLabelMaxZoom = labelMaxZoom;
    }

    public boolean isLineLabelRepeat() {
        return mLineLabelRepeat;
    }

    public void setLineLabelRepeat(boolean lineLabelRepeat) {
        mExplicitKeys.add(JSON_LINE_LABEL_REPEAT_KEY);
        mLineLabelRepeat = lineLabelRepeat;
    }

    public boolean isLineLabelHorizontal() {
        return mLineLabelHorizontal;
    }

    public void setLineLabelHorizontal(boolean lineLabelHorizontal) {
        mExplicitKeys.add(JSON_LINE_LABEL_HORIZONTAL_KEY);
        mLineLabelHorizontal = lineLabelHorizontal;
    }

    /**
     * Fills unset optional fields from {@code other}. Does not overwrite values already set on this
     * instance (explicit JSON/setter presence is retained, including false and type-default values).
     */
    public void fillUnsetFrom(LabelAttributes other) {
        if (other == null) return;
        if (!mExplicitKeys.contains(JSON_LABEL_HALO_COLOR_KEY)) {
            mTextHaloColor = other.mTextHaloColor;
            if (other.mExplicitKeys.contains(JSON_LABEL_HALO_COLOR_KEY)) mExplicitKeys.add(JSON_LABEL_HALO_COLOR_KEY);
        }
        if (!mExplicitKeys.contains(JSON_LABEL_HALO_WIDTH_KEY)) {
            mTextHaloWidth = other.mTextHaloWidth;
            if (other.mExplicitKeys.contains(JSON_LABEL_HALO_WIDTH_KEY)) mExplicitKeys.add(JSON_LABEL_HALO_WIDTH_KEY);
        }
        if (!mExplicitKeys.contains(JSON_LABEL_HALO_BLUR_KEY)) {
            mTextHaloBlur = other.mTextHaloBlur;
            if (other.mExplicitKeys.contains(JSON_LABEL_HALO_BLUR_KEY)) mExplicitKeys.add(JSON_LABEL_HALO_BLUR_KEY);
        }
        if (!mExplicitKeys.contains(JSON_LABEL_TEXT_OPACITY_KEY)) {
            mTextOpacity = other.mTextOpacity;
            if (other.mExplicitKeys.contains(JSON_LABEL_TEXT_OPACITY_KEY)) mExplicitKeys.add(JSON_LABEL_TEXT_OPACITY_KEY);
        }
        if (!mExplicitKeys.contains(JSON_LABEL_SCALE_WITH_ZOOM_KEY)) {
            mTextScaleWithZoom = other.mTextScaleWithZoom;
            if (other.mExplicitKeys.contains(JSON_LABEL_SCALE_WITH_ZOOM_KEY)) mExplicitKeys.add(JSON_LABEL_SCALE_WITH_ZOOM_KEY);
        }
        if (!mExplicitKeys.contains(JSON_LABEL_ZOOM_STOPS_KEY)) {
            mTextZoomScaleStops = other.mTextZoomScaleStops;
            if (other.mExplicitKeys.contains(JSON_LABEL_ZOOM_STOPS_KEY)) mExplicitKeys.add(JSON_LABEL_ZOOM_STOPS_KEY);
        }
        if (!mExplicitKeys.contains(JSON_LABEL_ALLOW_OVERLAP_KEY)) {
            mTextAllowOverlap = other.mTextAllowOverlap;
            if (other.mExplicitKeys.contains(JSON_LABEL_ALLOW_OVERLAP_KEY)) mExplicitKeys.add(JSON_LABEL_ALLOW_OVERLAP_KEY);
        }
        if (!mExplicitKeys.contains(JSON_LABEL_OPTIONAL_KEY)) {
            mTextOptional = other.mTextOptional;
            if (other.mExplicitKeys.contains(JSON_LABEL_OPTIONAL_KEY)) mExplicitKeys.add(JSON_LABEL_OPTIONAL_KEY);
        }
        if (!mExplicitKeys.contains(JSON_LABEL_SPACING_KEY)) {
            mSymbolSpacing = other.mSymbolSpacing;
            if (other.mExplicitKeys.contains(JSON_LABEL_SPACING_KEY)) mExplicitKeys.add(JSON_LABEL_SPACING_KEY);
        }
        if (!mExplicitKeys.contains(JSON_TEXT_MAX_WIDTH_KEY)) {
            mTextMaxWidth = other.mTextMaxWidth;
            if (other.mExplicitKeys.contains(JSON_TEXT_MAX_WIDTH_KEY)) mExplicitKeys.add(JSON_TEXT_MAX_WIDTH_KEY);
        }
        if (!mExplicitKeys.contains(JSON_LABEL_FONT_KEY)) {
            mTextFont = other.mTextFont;
            if (other.mExplicitKeys.contains(JSON_LABEL_FONT_KEY)) mExplicitKeys.add(JSON_LABEL_FONT_KEY);
        }
        if (!mExplicitKeys.contains(JSON_LABEL_JUSTIFY_KEY)) {
            mTextJustify = other.mTextJustify;
            if (other.mExplicitKeys.contains(JSON_LABEL_JUSTIFY_KEY)) mExplicitKeys.add(JSON_LABEL_JUSTIFY_KEY);
        }
        if (!mExplicitKeys.contains(JSON_LABEL_TRANSFORM_KEY)) {
            mTextTransform = other.mTextTransform;
            if (other.mExplicitKeys.contains(JSON_LABEL_TRANSFORM_KEY)) mExplicitKeys.add(JSON_LABEL_TRANSFORM_KEY);
        }
        if (!mExplicitKeys.contains(JSON_LABEL_LETTER_SPACING_KEY)) {
            mTextLetterSpacing = other.mTextLetterSpacing;
            if (other.mExplicitKeys.contains(JSON_LABEL_LETTER_SPACING_KEY)) mExplicitKeys.add(JSON_LABEL_LETTER_SPACING_KEY);
        }
        if (!mExplicitKeys.contains(JSON_LABEL_LINE_HEIGHT_KEY)) {
            mTextLineHeight = other.mTextLineHeight;
            if (other.mExplicitKeys.contains(JSON_LABEL_LINE_HEIGHT_KEY)) mExplicitKeys.add(JSON_LABEL_LINE_HEIGHT_KEY);
        }
        if (!mExplicitKeys.contains(JSON_LABEL_PADDING_KEY)) {
            mTextPadding = other.mTextPadding;
            if (other.mExplicitKeys.contains(JSON_LABEL_PADDING_KEY)) mExplicitKeys.add(JSON_LABEL_PADDING_KEY);
        }
        if (!mExplicitKeys.contains(JSON_LABEL_KEEP_UPRIGHT_KEY)) {
            mTextKeepUpright = other.mTextKeepUpright;
            if (other.mExplicitKeys.contains(JSON_LABEL_KEEP_UPRIGHT_KEY)) mExplicitKeys.add(JSON_LABEL_KEEP_UPRIGHT_KEY);
        }
        if (!mExplicitKeys.contains(JSON_LABEL_MAX_ANGLE_KEY)) {
            mTextMaxAngle = other.mTextMaxAngle;
            if (other.mExplicitKeys.contains(JSON_LABEL_MAX_ANGLE_KEY)) mExplicitKeys.add(JSON_LABEL_MAX_ANGLE_KEY);
        }
        if (!mExplicitKeys.contains(JSON_LABEL_TEMPLATE_KEY)) {
            mLabelTemplate = other.mLabelTemplate;
            if (other.mExplicitKeys.contains(JSON_LABEL_TEMPLATE_KEY)) mExplicitKeys.add(JSON_LABEL_TEMPLATE_KEY);
        }
        if (!mExplicitKeys.contains(JSON_LABEL_MIN_ZOOM_KEY)) {
            mLabelMinZoom = other.mLabelMinZoom;
            if (other.mExplicitKeys.contains(JSON_LABEL_MIN_ZOOM_KEY)) mExplicitKeys.add(JSON_LABEL_MIN_ZOOM_KEY);
        }
        if (!mExplicitKeys.contains(JSON_LABEL_MAX_ZOOM_KEY)) {
            mLabelMaxZoom = other.mLabelMaxZoom;
            if (other.mExplicitKeys.contains(JSON_LABEL_MAX_ZOOM_KEY)) mExplicitKeys.add(JSON_LABEL_MAX_ZOOM_KEY);
        }
        if (!mExplicitKeys.contains(JSON_LINE_LABEL_REPEAT_KEY)) {
            mLineLabelRepeat = other.mLineLabelRepeat;
            if (other.mExplicitKeys.contains(JSON_LINE_LABEL_REPEAT_KEY)) mExplicitKeys.add(JSON_LINE_LABEL_REPEAT_KEY);
        }
        if (!mExplicitKeys.contains(JSON_LINE_LABEL_HORIZONTAL_KEY)) {
            mLineLabelHorizontal = other.mLineLabelHorizontal;
            if (other.mExplicitKeys.contains(JSON_LINE_LABEL_HORIZONTAL_KEY)) mExplicitKeys.add(JSON_LINE_LABEL_HORIZONTAL_KEY);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public void mergeInto(JSONObject rootConfig) throws JSONException {
        if (mExplicitKeys.contains(JSON_LABEL_HALO_COLOR_KEY) || (mTextHaloColor != DEFAULT_HALO_COLOR)) {
            rootConfig.put(JSON_LABEL_HALO_COLOR_KEY, mTextHaloColor);
        }
        if (mExplicitKeys.contains(JSON_LABEL_HALO_WIDTH_KEY) || (mTextHaloWidth != DEFAULT_HALO_WIDTH)) {
            rootConfig.put(JSON_LABEL_HALO_WIDTH_KEY, mTextHaloWidth);
        }
        if (mExplicitKeys.contains(JSON_LABEL_HALO_BLUR_KEY) || (mTextHaloBlur > 0f)) {
            rootConfig.put(JSON_LABEL_HALO_BLUR_KEY, mTextHaloBlur);
        }
        if (mExplicitKeys.contains(JSON_LABEL_TEXT_OPACITY_KEY) || (mTextOpacity != DEFAULT_TEXT_OPACITY)) {
            rootConfig.put(JSON_LABEL_TEXT_OPACITY_KEY, mTextOpacity);
        }
        if (mExplicitKeys.contains(JSON_LABEL_SCALE_WITH_ZOOM_KEY) || (mTextScaleWithZoom)) {
            rootConfig.put(JSON_LABEL_SCALE_WITH_ZOOM_KEY, mTextScaleWithZoom);
        }
        if (mExplicitKeys.contains(JSON_LABEL_ZOOM_STOPS_KEY) || (mTextZoomScaleStops != null && !mTextZoomScaleStops.trim().isEmpty())) {
            rootConfig.put(JSON_LABEL_ZOOM_STOPS_KEY, mTextZoomScaleStops == null ? JSONObject.NULL : mTextZoomScaleStops);
        }
        if (mExplicitKeys.contains(JSON_LABEL_ALLOW_OVERLAP_KEY) || (mTextAllowOverlap != null)) {
            rootConfig.put(JSON_LABEL_ALLOW_OVERLAP_KEY, mTextAllowOverlap == null ? JSONObject.NULL : mTextAllowOverlap);
        }
        if (mExplicitKeys.contains(JSON_LABEL_OPTIONAL_KEY) || (!mTextOptional)) {
            rootConfig.put(JSON_LABEL_OPTIONAL_KEY, mTextOptional);
        }
        if (mExplicitKeys.contains(JSON_LABEL_SPACING_KEY) || (mSymbolSpacing != DEFAULT_SYMBOL_SPACING)) {
            rootConfig.put(JSON_LABEL_SPACING_KEY, mSymbolSpacing);
        }
        if (mExplicitKeys.contains(JSON_TEXT_MAX_WIDTH_KEY) || (mTextMaxWidth > 0f)) {
            rootConfig.put(JSON_TEXT_MAX_WIDTH_KEY, mTextMaxWidth);
        }
        if (mExplicitKeys.contains(JSON_LABEL_FONT_KEY) || (!DEFAULT_TEXT_FONT.equals(getTextFont()))) {
            rootConfig.put(JSON_LABEL_FONT_KEY, getTextFont());
        }
        if (mExplicitKeys.contains(JSON_LABEL_JUSTIFY_KEY) || (!DEFAULT_TEXT_JUSTIFY.equals(getTextJustify()))) {
            rootConfig.put(JSON_LABEL_JUSTIFY_KEY, getTextJustify());
        }
        if (mExplicitKeys.contains(JSON_LABEL_TRANSFORM_KEY) || (!DEFAULT_TEXT_TRANSFORM.equals(getTextTransform()))) {
            rootConfig.put(JSON_LABEL_TRANSFORM_KEY, getTextTransform());
        }
        if (mExplicitKeys.contains(JSON_LABEL_LETTER_SPACING_KEY) || (mTextLetterSpacing != 0f)) {
            rootConfig.put(JSON_LABEL_LETTER_SPACING_KEY, mTextLetterSpacing);
        }
        if (mExplicitKeys.contains(JSON_LABEL_LINE_HEIGHT_KEY) || (getTextLineHeight() != DEFAULT_TEXT_LINE_HEIGHT)) {
            rootConfig.put(JSON_LABEL_LINE_HEIGHT_KEY, getTextLineHeight());
        }
        if (mExplicitKeys.contains(JSON_LABEL_PADDING_KEY) || (getTextPadding() != DEFAULT_TEXT_PADDING)) {
            rootConfig.put(JSON_LABEL_PADDING_KEY, getTextPadding());
        }
        if (mExplicitKeys.contains(JSON_LABEL_KEEP_UPRIGHT_KEY) || (mTextKeepUpright != null)) {
            rootConfig.put(JSON_LABEL_KEEP_UPRIGHT_KEY, mTextKeepUpright == null ? JSONObject.NULL : mTextKeepUpright);
        }
        if (mExplicitKeys.contains(JSON_LABEL_MAX_ANGLE_KEY) || (getTextMaxAngle() != DEFAULT_TEXT_MAX_ANGLE)) {
            rootConfig.put(JSON_LABEL_MAX_ANGLE_KEY, getTextMaxAngle());
        }
        if (mExplicitKeys.contains(JSON_LABEL_TEMPLATE_KEY) || (mLabelTemplate != null && !mLabelTemplate.isEmpty())) {
            rootConfig.put(JSON_LABEL_TEMPLATE_KEY, mLabelTemplate == null ? JSONObject.NULL : mLabelTemplate);
        }
        if (mExplicitKeys.contains(JSON_LABEL_MIN_ZOOM_KEY) || (mLabelMinZoom >= 0f)) {
            rootConfig.put(JSON_LABEL_MIN_ZOOM_KEY, mLabelMinZoom);
        }
        if (mExplicitKeys.contains(JSON_LABEL_MAX_ZOOM_KEY) || (mLabelMaxZoom >= 0f)) {
            rootConfig.put(JSON_LABEL_MAX_ZOOM_KEY, mLabelMaxZoom);
        }
        if (mExplicitKeys.contains(JSON_LINE_LABEL_REPEAT_KEY) || (!mLineLabelRepeat)) {
            rootConfig.put(JSON_LINE_LABEL_REPEAT_KEY, mLineLabelRepeat);
        }
        if (mExplicitKeys.contains(JSON_LINE_LABEL_HORIZONTAL_KEY) || (mLineLabelHorizontal)) {
            rootConfig.put(JSON_LINE_LABEL_HORIZONTAL_KEY, mLineLabelHorizontal);
        }
    }

    public void readFrom(JSONObject jsonObject) {
        mTextHaloColor = jsonObject.optInt(JSON_LABEL_HALO_COLOR_KEY, DEFAULT_HALO_COLOR);
        mTextHaloWidth = (float) jsonObject.optDouble(JSON_LABEL_HALO_WIDTH_KEY, DEFAULT_HALO_WIDTH);
        mTextHaloBlur = (float) jsonObject.optDouble(JSON_LABEL_HALO_BLUR_KEY, 0);
        mTextOpacity = jsonObject.optInt(JSON_LABEL_TEXT_OPACITY_KEY, DEFAULT_TEXT_OPACITY);
        mTextScaleWithZoom = jsonObject.optBoolean(JSON_LABEL_SCALE_WITH_ZOOM_KEY, false);
        mTextZoomScaleStops = jsonObject.isNull(JSON_LABEL_ZOOM_STOPS_KEY) ? null : jsonObject.optString(JSON_LABEL_ZOOM_STOPS_KEY, null);
        if (mTextZoomScaleStops != null && mTextZoomScaleStops.trim().isEmpty()) {
            mTextZoomScaleStops = null;
        }
        if (jsonObject.has(JSON_LABEL_ALLOW_OVERLAP_KEY) && !jsonObject.isNull(JSON_LABEL_ALLOW_OVERLAP_KEY)) {
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
        if (jsonObject.has(JSON_LABEL_KEEP_UPRIGHT_KEY) && !jsonObject.isNull(JSON_LABEL_KEEP_UPRIGHT_KEY)) {
            mTextKeepUpright = jsonObject.optBoolean(JSON_LABEL_KEEP_UPRIGHT_KEY);
        } else {
            mTextKeepUpright = null;
        }
        setTextMaxAngle((float) jsonObject.optDouble(JSON_LABEL_MAX_ANGLE_KEY, DEFAULT_TEXT_MAX_ANGLE));
        if (jsonObject.has(JSON_LABEL_TEMPLATE_KEY)) {
            mLabelTemplate = jsonObject.isNull(JSON_LABEL_TEMPLATE_KEY) ? null : jsonObject.optString(JSON_LABEL_TEMPLATE_KEY, null);
        } else {
            mLabelTemplate = null;
        }
        mLabelMinZoom = (float) jsonObject.optDouble(JSON_LABEL_MIN_ZOOM_KEY, -1);
        mLabelMaxZoom = (float) jsonObject.optDouble(JSON_LABEL_MAX_ZOOM_KEY, -1);
        mLineLabelRepeat = jsonObject.optBoolean(JSON_LINE_LABEL_REPEAT_KEY, DEFAULT_LINE_LABEL_REPEAT);
        mLineLabelHorizontal = jsonObject.optBoolean(JSON_LINE_LABEL_HORIZONTAL_KEY, false);
        mExplicitKeys.clear();
        for (String key : new String[]{JSON_LABEL_HALO_COLOR_KEY, JSON_LABEL_HALO_WIDTH_KEY, JSON_LABEL_HALO_BLUR_KEY, JSON_LABEL_TEXT_OPACITY_KEY, JSON_LABEL_SCALE_WITH_ZOOM_KEY, JSON_LABEL_ZOOM_STOPS_KEY, JSON_LABEL_ALLOW_OVERLAP_KEY, JSON_LABEL_OPTIONAL_KEY, JSON_LABEL_SPACING_KEY, JSON_TEXT_MAX_WIDTH_KEY, JSON_LABEL_FONT_KEY, JSON_LABEL_JUSTIFY_KEY, JSON_LABEL_TRANSFORM_KEY, JSON_LABEL_LETTER_SPACING_KEY, JSON_LABEL_LINE_HEIGHT_KEY, JSON_LABEL_PADDING_KEY, JSON_LABEL_KEEP_UPRIGHT_KEY, JSON_LABEL_MAX_ANGLE_KEY, JSON_LABEL_TEMPLATE_KEY, JSON_LABEL_MIN_ZOOM_KEY, JSON_LABEL_MAX_ZOOM_KEY, JSON_LINE_LABEL_REPEAT_KEY, JSON_LINE_LABEL_HORIZONTAL_KEY})
            if (jsonObject.has(key)) mExplicitKeys.add(key);
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

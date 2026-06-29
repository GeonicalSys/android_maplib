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
import static com.nextgis.maplib.util.Constants.JSON_LABEL_OPTIONAL_KEY;
import static com.nextgis.maplib.util.Constants.JSON_LABEL_SCALE_WITH_ZOOM_KEY;
import static com.nextgis.maplib.util.Constants.JSON_LABEL_SPACING_KEY;
import static com.nextgis.maplib.util.Constants.JSON_LABEL_TEMPLATE_KEY;
import static com.nextgis.maplib.util.Constants.JSON_LABEL_TEXT_OPACITY_KEY;
import static com.nextgis.maplib.util.Constants.JSON_TEXT_MAX_WIDTH_KEY;

public class LabelAttributes implements IJSONStore, Cloneable {

    public static final int DEFAULT_HALO_COLOR = Color.WHITE;
    public static final float DEFAULT_HALO_WIDTH = 1.5f;
    public static final float DEFAULT_SYMBOL_SPACING = 15f;
    public static final boolean DEFAULT_LINE_LABEL_REPEAT = true;
    public static final int DEFAULT_TEXT_OPACITY = 255;

    protected int mTextHaloColor = DEFAULT_HALO_COLOR;
    protected float mTextHaloWidth = DEFAULT_HALO_WIDTH;
    protected float mTextHaloBlur;
    protected int mTextOpacity = DEFAULT_TEXT_OPACITY;
    protected boolean mTextScaleWithZoom;
    protected Boolean mTextAllowOverlap;
    protected boolean mTextOptional = true;
    protected float mSymbolSpacing = DEFAULT_SYMBOL_SPACING;
    protected float mTextMaxWidth;
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
        copy.mTextAllowOverlap = mTextAllowOverlap;
        copy.mTextOptional = mTextOptional;
        copy.mSymbolSpacing = mSymbolSpacing;
        copy.mTextMaxWidth = mTextMaxWidth;
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
        if (jsonObject.has(JSON_LABEL_ALLOW_OVERLAP_KEY)) {
            mTextAllowOverlap = jsonObject.optBoolean(JSON_LABEL_ALLOW_OVERLAP_KEY);
        } else {
            mTextAllowOverlap = null;
        }
        mTextOptional = jsonObject.optBoolean(JSON_LABEL_OPTIONAL_KEY, true);
        mSymbolSpacing = (float) jsonObject.optDouble(JSON_LABEL_SPACING_KEY, DEFAULT_SYMBOL_SPACING);
        mTextMaxWidth = (float) jsonObject.optDouble(JSON_TEXT_MAX_WIDTH_KEY, 0);
        if (jsonObject.has(JSON_LABEL_TEMPLATE_KEY)) {
            mLabelTemplate = jsonObject.optString(JSON_LABEL_TEMPLATE_KEY, null);
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
}

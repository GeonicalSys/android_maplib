/*
 * Project:  NextGIS Mobile
 * Purpose:  Mobile GIS for Android.
 * Author:   Dmitry Baryshnikov (aka Bishop), bishop.dev@gmail.com
 * Author:   NikitaFeodonit, nfeodonit@yandex.com
 * Author:   Stanislav Petriakov, becomeglory@gmail.com
 * *****************************************************************************
 * Copyright (c) 2012-2018 NextGIS, info@nextgis.com
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

import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.text.TextUtils;

import com.nextgis.maplib.api.ITextStyle;
import com.nextgis.maplib.datasource.GeoGeometry;
import com.nextgis.maplib.datasource.GeoMultiPolygon;
import com.nextgis.maplib.datasource.GeoPoint;
import com.nextgis.maplib.datasource.GeoPolygon;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

import static com.nextgis.maplib.util.Constants.*;
import static com.nextgis.maplib.util.GeoConstants.GTMultiPolygon;
import static com.nextgis.maplib.util.GeoConstants.GTPolygon;

public class SimplePolygonStyle extends Style implements ITextStyle {
    protected boolean mFill;
    protected int mFillPattern = PolygonPatternRegistry.FILL_PATTERN_NONE;
    protected String mFillPatternImage;
    protected float mFillTranslateX;
    protected float mFillTranslateY;
    protected String mField;
    protected String mText;
    protected Float mTextSize = 12f;
    protected int mTextColor = Color.BLACK;
    protected LabelAttributes mLabelAttributes = LabelAttributes.defaults();

    protected static final String JSON_FILL_KEY = "fill";

    public LabelAttributes getLabelAttributes() {
        return mLabelAttributes;
    }

    public void setLabelAttributes(LabelAttributes labelAttributes) {
        mLabelAttributes = labelAttributes != null ? labelAttributes : LabelAttributes.defaults();
    }

    public SimplePolygonStyle() {
        super();
    }

    public SimplePolygonStyle(int color, int outColor) {
        super(color, outColor);
        mOuterAlpha = 128;
        mInnerAlpha = 64;
        mWidth = 3;
        mFill = true;
    }

    @Override
    public SimplePolygonStyle clone() throws CloneNotSupportedException {
        SimplePolygonStyle obj = (SimplePolygonStyle) super.clone();
        obj.mFill = mFill;
        obj.mFillPattern = mFillPattern;
        obj.mFillPatternImage = mFillPatternImage;
        obj.mFillTranslateX = mFillTranslateX;
        obj.mFillTranslateY = mFillTranslateY;
        obj.mText = mText;
        obj.mTextSize = mTextSize;
        obj.mTextColor = mTextColor;
        obj.mField = mField;
        obj.mLabelAttributes = mLabelAttributes.clone();
        return obj;
    }

    public boolean isFill() {
        return mFill;
    }

    public void setFill(boolean fill) {
        mFill = fill;
    }

    public int getFillPattern() {
        return mFillPattern;
    }

    public void setFillPattern(int fillPattern) {
        mFillPattern = fillPattern;
    }

    public String getFillPatternImage() {
        return mFillPatternImage;
    }

    public void setFillPatternImage(String fillPatternImage) {
        mFillPatternImage = fillPatternImage != null && !fillPatternImage.trim().isEmpty()
                ? fillPatternImage.trim()
                : null;
    }

    public float getFillTranslateX() {
        return mFillTranslateX;
    }

    public void setFillTranslateX(float fillTranslateX) {
        mFillTranslateX = fillTranslateX;
    }

    public float getFillTranslateY() {
        return mFillTranslateY;
    }

    public void setFillTranslateY(float fillTranslateY) {
        mFillTranslateY = fillTranslateY;
    }

    @Override
    public void onDraw(GeoGeometry geoGeometry, GISDisplay display) {
        Float textSize = (null == mTextSize) ? null : 12f;
        float scaledTextSize = (float) (mTextSize * (mWidth / display.getScale()));
        GeoPoint center = geoGeometry.getEnvelope().getCenter();
        switch (geoGeometry.getType()) {
            case GTPolygon:
                drawPolygon((GeoPolygon) geoGeometry, display);
                drawText(scaledTextSize, center, display);
                break;
            case GTMultiPolygon:
                GeoMultiPolygon multiPolygon = (GeoMultiPolygon) geoGeometry;

                for (int i = 0; i < multiPolygon.size(); i++) {
                    drawPolygon(multiPolygon.get(i), display);
                    drawText(scaledTextSize, center, display);
                }
                break;

            //throw new IllegalArgumentException(
            //        "The input geometry type is not support by this style");
        }
    }

    protected void drawText(Float scaledTextSize, GeoPoint center, GISDisplay display) {
        if (TextUtils.isEmpty(mText) || null == scaledTextSize) { return; }

        Paint textPaint = new Paint();
        textPaint.setColor(mTextColor);
        textPaint.setAntiAlias(true);
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setStrokeCap(Paint.Cap.ROUND);
        textPaint.setAlpha(128);

        Rect textRect = new Rect();
        textPaint.setTextSize(scaledTextSize);
        textPaint.getTextBounds(mText, 0, mText.length(), textRect);

        float halfW = textRect.width() / 2;
        float halfH = textRect.height() / 2;

        float textX = (float) (center.getX() - halfW);
        float textY = (float) (center.getY() + halfH);

        Path textPath = new Path();
        textPaint.getTextPath(mText, 0, mText.length(), textX, textY, textPath);
        textPath.close();

        Matrix matrix = new Matrix();
        matrix.reset();
        matrix.setScale(1, -1, (float) center.getX(), (float) center.getY());
        textPath.transform(matrix);

        display.drawPath(textPath, textPaint);
    }

    public void drawPolygon(GeoPolygon polygon, GISDisplay display) {
        float scaledWidth = (float) (mWidth / display.getScale());

        Paint lnPaint = new Paint();
        lnPaint.setColor(mOutColor);
        lnPaint.setStrokeWidth(scaledWidth);
        lnPaint.setStrokeCap(Paint.Cap.ROUND);
        lnPaint.setAntiAlias(true);

        Path polygonPath = getPath(polygon);

        lnPaint.setStyle(Paint.Style.STROKE);
        lnPaint.setAlpha(mOuterAlpha);
        display.drawPath(polygonPath, lnPaint);

        if (mFill) {
            lnPaint.setStyle(Paint.Style.FILL);
            lnPaint.setColor(mColor);
            lnPaint.setAlpha(mInnerAlpha);
            display.drawPath(polygonPath, lnPaint);
        }
    }

    @Override
    public String getField() {
        return mField;
    }

    public void setField(String field) {
        mField = field;
    }

    @Override
    public String getText() {
        return mText;
    }

    public void setText(String text) {
        if (!TextUtils.isEmpty(text))
            mText = text;
        else
            mText = null;
    }

    public Float getTextSize() {
        return mTextSize;
    }

    public void setTextSize(Float textSize) {
        mTextSize = textSize;
    }

    public int getTextColor() {
        return mTextColor;
    }

    public void setTextColor(int textColor) {
        mTextColor = textColor;
    }

    @Override
    public JSONObject toJSON() throws JSONException {
        JSONObject rootConfig = super.toJSON();
        rootConfig.put(JSON_FILL_KEY, mFill);
        if (mFillPattern != PolygonPatternRegistry.FILL_PATTERN_NONE) {
            rootConfig.put(JSON_FILL_PATTERN_KEY, mFillPattern);
        }
        if (mFillPatternImage != null) {
            rootConfig.put(JSON_FILL_PATTERN_IMAGE_KEY, mFillPatternImage);
        }
        if (mFillTranslateX != 0f) {
            rootConfig.put(JSON_FILL_TRANSLATE_X_KEY, mFillTranslateX);
        }
        if (mFillTranslateY != 0f) {
            rootConfig.put(JSON_FILL_TRANSLATE_Y_KEY, mFillTranslateY);
        }
        rootConfig.put(JSON_NAME_KEY, "SimplePolygonStyle");

        if (null != mText) {
            rootConfig.put(JSON_DISPLAY_NAME, mText);
        }
        if (null != mTextSize) {
            rootConfig.put(JSON_TEXT_SIZE_KEY, mTextSize);
        }
        rootConfig.put(JSON_TEXT_COLOR_KEY, mTextColor);
        if (null != mField) {
            rootConfig.put(JSON_VALUE_KEY, mField);
        }
        mLabelAttributes.mergeInto(rootConfig);

        return rootConfig;
    }

    @Override
    public void fromJSON(JSONObject jsonObject) throws JSONException {
        super.fromJSON(jsonObject);
        mFill = jsonObject.optBoolean(JSON_FILL_KEY, true);
        mFillPattern = jsonObject.optInt(
                JSON_FILL_PATTERN_KEY, PolygonPatternRegistry.FILL_PATTERN_NONE);
        setFillPatternImage(jsonObject.optString(JSON_FILL_PATTERN_IMAGE_KEY, null));
        mFillTranslateX = (float) jsonObject.optDouble(JSON_FILL_TRANSLATE_X_KEY, 0);
        mFillTranslateY = (float) jsonObject.optDouble(JSON_FILL_TRANSLATE_Y_KEY, 0);

        if (jsonObject.has(JSON_DISPLAY_NAME)) {
            mText = jsonObject.getString(JSON_DISPLAY_NAME);
        }
        if (jsonObject.has(JSON_TEXT_SIZE_KEY)) {
            mTextSize = (float) jsonObject.optDouble(JSON_TEXT_SIZE_KEY, 12);
        }
        mTextColor = jsonObject.optInt(JSON_TEXT_COLOR_KEY, Color.BLACK);
        if (jsonObject.has(JSON_VALUE_KEY)) {
            mField = jsonObject.getString(JSON_VALUE_KEY);
        }
        mLabelAttributes.readFrom(jsonObject);
    }

    protected Path getPath(GeoPolygon polygon) {
        List<GeoPoint> points = polygon.getOuterRing().getPoints();
        Path polygonPath = new Path();
        appendPath(polygonPath, points);

        for (int i = 0; i < polygon.getInnerRingCount(); i++) {
            points = polygon.getInnerRing(i).getPoints();
            appendPath(polygonPath, points);
        }

        polygonPath.setFillType(Path.FillType.EVEN_ODD);

        return polygonPath;
    }

    protected void appendPath(Path polygonPath, List<GeoPoint> points) {
        float x0, y0;

        if (points.size() > 0) {
            x0 = (float) points.get(0).getX();
            y0 = (float) points.get(0).getY();
            polygonPath.moveTo(x0, y0);

            for (int i = 1; i < points.size(); i++) {
                x0 = (float) points.get(i).getX();
                y0 = (float) points.get(i).getY();

                polygonPath.lineTo(x0, y0);
            }

            polygonPath.close();
        }
    }
}

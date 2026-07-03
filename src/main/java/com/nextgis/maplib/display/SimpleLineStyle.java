/*
 * Project:  NextGIS Mobile
 * Purpose:  Mobile GIS for Android.
 * Author:   Dmitry Baryshnikov (aka Bishop), bishop.dev@gmail.com
 * Author:   NikitaFeodonit, nfeodonit@yandex.com
 * Author:   Stanislav Petriakov, becomeglory@gmail.com
 * *****************************************************************************
 * Copyright (c) 2012-2017 NextGIS, info@nextgis.com
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
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.text.TextUtils;

import com.nextgis.maplib.api.ITextStyle;
import com.nextgis.maplib.datasource.GeoGeometry;
import com.nextgis.maplib.datasource.GeoLineString;
import com.nextgis.maplib.datasource.GeoMultiLineString;
import com.nextgis.maplib.datasource.GeoPoint;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

import static com.nextgis.maplib.util.Constants.JSON_DISPLAY_NAME;
import static com.nextgis.maplib.util.Constants.JSON_LINE_DASH_ARRAY_KEY;
import static com.nextgis.maplib.util.Constants.JSON_LINE_BLUR_KEY;
import static com.nextgis.maplib.util.Constants.JSON_LINE_CAP_KEY;
import static com.nextgis.maplib.util.Constants.JSON_LINE_DASH_PRESET_KEY;
import static com.nextgis.maplib.util.Constants.JSON_LINE_GAP_WIDTH_KEY;
import static com.nextgis.maplib.util.Constants.JSON_LINE_JOIN_KEY;
import static com.nextgis.maplib.util.Constants.JSON_LINE_MITER_LIMIT_KEY;
import static com.nextgis.maplib.util.Constants.JSON_LINE_OFFSET_KEY;
import static com.nextgis.maplib.util.Constants.JSON_LINE_OUTLINE_MULTIPLIER_KEY;
import static com.nextgis.maplib.util.Constants.JSON_NAME_KEY;
import static com.nextgis.maplib.util.Constants.JSON_TEXT_COLOR_KEY;
import static com.nextgis.maplib.util.Constants.JSON_TEXT_SIZE_KEY;
import static com.nextgis.maplib.util.Constants.JSON_TYPE_KEY;
import static com.nextgis.maplib.util.Constants.JSON_VALUE_KEY;
import static com.nextgis.maplib.util.GeoConstants.GTLineString;
import static com.nextgis.maplib.util.GeoConstants.GTMultiLineString;

public class SimpleLineStyle extends Style implements ITextStyle {
    public final static int LineStyleSolid = 1;
    public final static int LineStyleDash = 2;
    public final static int LineStyleEdgingSolid = 3;
    public final static int LineStyleEdgingDash = 4;

    protected int mType;
    protected Paint.Cap mStrokeCap;
    protected int mLineCap = MplStyleMapper.LINE_CAP_ROUND;
    protected int mLineJoin = MplStyleMapper.LINE_JOIN_ROUND;
    protected float mLineMiterLimit = MplStyleMapper.DEFAULT_LINE_MITER_LIMIT;
    protected float mLineBlur = MplStyleMapper.DEFAULT_LINE_BLUR;
    protected int mDashPreset = MplStyleMapper.DASH_PRESET_SHORT;
    protected String mDashArray;
    protected float mLineOffset;
    protected float mLineGapWidth;
    protected float mLineOutlineMultiplier = 3f;
    protected String mField;
    protected String mText;
    protected float mTextSize = 3f;
    protected int mTextColor = Color.BLACK;
    protected LabelAttributes mLabelAttributes = LabelAttributes.defaults();

    public LabelAttributes getLabelAttributes() {
        return mLabelAttributes;
    }

    public void setLabelAttributes(LabelAttributes labelAttributes) {
        mLabelAttributes = labelAttributes != null ? labelAttributes : LabelAttributes.defaults();
    }

    public SimpleLineStyle() {
        super();
        mStrokeCap = Paint.Cap.BUTT;
    }

    public SimpleLineStyle(int fillColor, int outColor, int type) {
        super(fillColor, outColor);
        mType = type;
        mStrokeCap = Paint.Cap.BUTT;
        mTextColor = outColor;
    }

    @Override
    public SimpleLineStyle clone() throws CloneNotSupportedException {
        SimpleLineStyle obj = (SimpleLineStyle) super.clone();
        obj.mType = mType;
        obj.mStrokeCap = mStrokeCap;
        obj.mLineCap = mLineCap;
        obj.mLineJoin = mLineJoin;
        obj.mLineMiterLimit = mLineMiterLimit;
        obj.mLineBlur = mLineBlur;
        obj.mDashPreset = mDashPreset;
        obj.mDashArray = mDashArray;
        obj.mLineOffset = mLineOffset;
        obj.mLineGapWidth = mLineGapWidth;
        obj.mLineOutlineMultiplier = mLineOutlineMultiplier;
        obj.mText = mText;
        obj.mField = mField;
        obj.mTextSize = mTextSize;
        obj.mTextColor = mTextColor;
        obj.mLabelAttributes = mLabelAttributes.clone();
        return obj;
    }

    public void onDraw(GeoLineString lineString, GISDisplay display) {
        if (null == lineString) {
            return;
        }

        float scaledWidth = (float) (mWidth / display.getScale());
        Path mainPath = null;
        switch (mType) {
            case LineStyleSolid:
                mainPath = drawSolidLine(scaledWidth, lineString, display);
                break;

            case LineStyleDash:
                mainPath = drawDashLine(scaledWidth, lineString, display);
                break;

            case LineStyleEdgingSolid:
                mainPath = drawSolidEdgingLine(scaledWidth, lineString, display);
                break;

            case LineStyleEdgingDash:
                mainPath = drawDashEdgingLine(scaledWidth, lineString, display);
                break;
        }

        drawText(scaledWidth, mainPath, display);
    }

    protected void drawText(float scaledWidth, Path mainPath, GISDisplay display) {
        if (TextUtils.isEmpty(mText) || mainPath == null)
            return;

        Paint textPaint = new Paint();
        textPaint.setColor(mTextColor);
        textPaint.setAntiAlias(true);
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setStrokeCap(Paint.Cap.ROUND);
        textPaint.setStrokeWidth(scaledWidth);

        float textSize = 12 * scaledWidth;
        textPaint.setTextSize(textSize);
        float textWidth = textPaint.measureText(mText);
        float vOffset = (float) (textSize / 2.7);

        // draw text along the main path
        PathMeasure pm = new PathMeasure(mainPath, false);
        float length = pm.getLength();
        float gap = textPaint.measureText("_");
        float period = textWidth + gap;
        float startD = gap;
        float stopD = startD + period;

        Path textPath = new Path();

        while (stopD < length) {
            textPath.reset();
            pm.getSegment(startD, stopD, textPath, true);
            textPath.rLineTo(0, 0); // workaround for API <= 19

            display.drawTextOnPath(mText, textPath, 0, vOffset, textPaint);

            startD += period;
            stopD += period;
        }

        stopD = startD;
        float rest = length - stopD;

        if (rest > gap * 2) {
            stopD = length - gap;

            textPath.reset();
            pm.getSegment(startD, stopD, textPath, true);
            textPath.rLineTo(0, 0); // workaround for API <= 19

            display.drawTextOnPath(mText, textPath, 0, vOffset, textPaint);
        }
    }

    @Override
    public void onDraw(GeoGeometry geoGeometry, GISDisplay display) {
        mColor = Color.argb(mInnerAlpha, Color.red(mColor), Color.green(mColor), Color.blue(mColor));
        mOutColor = Color.argb(mOuterAlpha, Color.red(mOutColor), Color.green(mOutColor), Color.blue(mOutColor));

        switch (geoGeometry.getType()) {
            case GTLineString:
                onDraw((GeoLineString) geoGeometry, display);
                break;

            case GTMultiLineString:
                GeoMultiLineString multiLineString = (GeoMultiLineString) geoGeometry;
                for (int i = 0; i < multiLineString.size(); i++) {
                    onDraw(multiLineString.get(i), display);
                }
                break;

            //throw new IllegalArgumentException(
            //        "The input geometry type is not support by this style");
        }

    }

    protected Path drawSolidLine(float scaledWidth, GeoLineString lineString, GISDisplay display) {
        Paint paint = new Paint();
        paint.setColor(mColor);
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(mStrokeCap);
        paint.setStrokeWidth(scaledWidth);

        List<GeoPoint> points = lineString.getPoints();

        Path path = new Path();
        path.incReserve(points.size());

        path.moveTo((float) points.get(0).getX(), (float) points.get(0).getY());

        for (int i = 1; i < points.size(); ++i) {
            path.lineTo((float) points.get(i).getX(), (float) points.get(i).getY());
        }

        display.drawPath(path, paint);

        return path;
    }

    protected Path drawDashLine(float scaledWidth, GeoLineString lineString, GISDisplay display) {
        Paint paint = new Paint();
        paint.setColor(mColor);
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeWidth(scaledWidth);

        List<GeoPoint> points = lineString.getPoints();

        // workaround for "DashPathEffect/drawLine not working properly when hardwareAccelerated="true""
        // https://code.google.com/p/android/issues/detail?id=29944

        // get all points to the main path
        Path mainPath = new Path();
        mainPath.incReserve(points.size());

        mainPath.moveTo((float) points.get(0).getX(), (float) points.get(0).getY());

        for (int i = 1; i < points.size(); ++i) {
            mainPath.lineTo((float) points.get(i).getX(), (float) points.get(i).getY());
        }

        // draw along the main path
        PathMeasure pm = new PathMeasure(mainPath, false);
        float[] coordinates = new float[2];
        float length = pm.getLength();
        float dash = (float) (10 / display.getScale());
        float gap = (float) (5 / display.getScale());
        float distance = dash;
        boolean isDash = true;

        Path dashPath = new Path();
        dashPath.incReserve((int) (2 * length / (dash + gap)));

        dashPath.moveTo((float) points.get(0).getX(), (float) points.get(0).getY());

        while (distance < length) {
            // get a point from the main path
            pm.getPosTan(distance, coordinates, null);

            if (isDash) {
                dashPath.lineTo(coordinates[0], coordinates[1]);
                distance += gap;
            } else {
                dashPath.moveTo(coordinates[0], coordinates[1]);
                distance += dash;
            }

            isDash = !isDash;
        }

        // add a rest from the main path
        if (isDash) {
            distance = distance - dash;
            float rest = length - distance;

            if (rest > (float) (1 / display.getScale())) {
                distance = length - 1;
                pm.getPosTan(distance, coordinates, null);
                dashPath.lineTo(coordinates[0], coordinates[1]);
            }
        }

        display.drawPath(dashPath, paint);

        return mainPath;
    }

    protected Path drawSolidEdgingLine(float scaledWidth, GeoLineString lineString, GISDisplay display) {
        Paint mainPaint = new Paint();
        mainPaint.setColor(mColor);
        mainPaint.setAntiAlias(true);
        mainPaint.setStyle(Paint.Style.STROKE);
        mainPaint.setStrokeCap(Paint.Cap.BUTT);
        mainPaint.setStrokeWidth(scaledWidth);

        Paint edgingPaint = new Paint(mainPaint);
        edgingPaint.setColor(mOutColor);
        edgingPaint.setStrokeCap(Paint.Cap.BUTT);
        edgingPaint.setStrokeWidth(scaledWidth * 3);

        List<GeoPoint> points = lineString.getPoints();

        Path path = new Path();
        path.incReserve(points.size());

        path.moveTo((float) points.get(0).getX(), (float) points.get(0).getY());

        for (int i = 1; i < points.size(); ++i) {
            path.lineTo((float) points.get(i).getX(), (float) points.get(i).getY());
        }

        display.drawPath(path, edgingPaint);
        display.drawPath(path, mainPaint);

        return path;
    }

    protected Path drawDashEdgingLine(float scaledWidth, GeoLineString lineString, GISDisplay display) {
        List<GeoPoint> points = lineString.getPoints();

        Path mainPath = new Path();
        mainPath.incReserve(points.size());
        mainPath.moveTo((float) points.get(0).getX(), (float) points.get(0).getY());
        for (int i = 1; i < points.size(); ++i) {
            mainPath.lineTo((float) points.get(i).getX(), (float) points.get(i).getY());
        }

        Paint edgingPaint = new Paint();
        edgingPaint.setColor(mOutColor);
        edgingPaint.setAntiAlias(true);
        edgingPaint.setStyle(Paint.Style.STROKE);
        edgingPaint.setStrokeCap(Paint.Cap.BUTT);
        edgingPaint.setStrokeWidth(scaledWidth * 3);
        display.drawPath(mainPath, edgingPaint);

        Paint dashPaint = new Paint();
        dashPaint.setColor(mColor);
        dashPaint.setAntiAlias(true);
        dashPaint.setStyle(Paint.Style.STROKE);
        dashPaint.setStrokeCap(Paint.Cap.BUTT);
        dashPaint.setStrokeWidth(scaledWidth);

        PathMeasure pm = new PathMeasure(mainPath, false);
        float[] coordinates = new float[2];
        float length = pm.getLength();
        float dash = (float) (10 / display.getScale());
        float gap = (float) (5 / display.getScale());
        float distance = dash;
        boolean isDash = true;

        Path dashPath = new Path();
        dashPath.incReserve((int) (2 * length / (dash + gap)));
        dashPath.moveTo((float) points.get(0).getX(), (float) points.get(0).getY());

        while (distance < length) {
            pm.getPosTan(distance, coordinates, null);
            if (isDash) {
                dashPath.lineTo(coordinates[0], coordinates[1]);
                distance += gap;
            } else {
                dashPath.moveTo(coordinates[0], coordinates[1]);
                distance += dash;
            }
            isDash = !isDash;
        }

        if (isDash) {
            distance = distance - dash;
            float rest = length - distance;
            if (rest > (float) (1 / display.getScale())) {
                distance = length - 1;
                pm.getPosTan(distance, coordinates, null);
                dashPath.lineTo(coordinates[0], coordinates[1]);
            }
        }

        display.drawPath(dashPath, dashPaint);

        return mainPath;
    }

    public int getType() {
        return mType;
    }

    public void setType(int type) {
        mType = type;
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

    public float getTextSize() {
        return mTextSize;
    }

    public void setTextSize(float textSize) {
        mTextSize = textSize;
    }

    public int getTextColor() {
        return mTextColor;
    }

    public void setTextColor(int textColor) {
        mTextColor = textColor;
    }

    public int getLineCap() {
        return mLineCap;
    }

    public void setLineCap(int lineCap) {
        mLineCap = lineCap;
    }

    public int getLineJoin() {
        return mLineJoin;
    }

    public void setLineJoin(int lineJoin) {
        mLineJoin = lineJoin;
    }

    public float getLineMiterLimit() {
        return mLineMiterLimit;
    }

    public void setLineMiterLimit(float lineMiterLimit) {
        mLineMiterLimit = lineMiterLimit;
    }

    public float getLineBlur() {
        return mLineBlur;
    }

    public void setLineBlur(float lineBlur) {
        mLineBlur = Math.max(0f, lineBlur);
    }

    public int getDashPreset() {
        return mDashPreset;
    }

    public void setDashPreset(int dashPreset) {
        mDashPreset = dashPreset;
    }

    public String getDashArray() {
        return mDashArray;
    }

    public void setDashArray(String dashArray) {
        mDashArray = dashArray != null && !dashArray.trim().isEmpty()
                ? dashArray.trim()
                : null;
    }

    public float getLineOffset() {
        return mLineOffset;
    }

    public void setLineOffset(float lineOffset) {
        mLineOffset = lineOffset;
    }

    public float getLineGapWidth() {
        return mLineGapWidth;
    }

    public void setLineGapWidth(float lineGapWidth) {
        mLineGapWidth = Math.max(0f, lineGapWidth);
    }

    public float getLineOutlineMultiplier() {
        return mLineOutlineMultiplier > 0f ? mLineOutlineMultiplier : 3f;
    }

    public void setLineOutlineMultiplier(float lineOutlineMultiplier) {
        mLineOutlineMultiplier = lineOutlineMultiplier > 0f ? lineOutlineMultiplier : 3f;
    }

    @Override
    public JSONObject toJSON() throws JSONException {
        JSONObject rootConfig = super.toJSON();
        rootConfig.put(JSON_NAME_KEY, "SimpleLineStyle");
        rootConfig.put(JSON_TYPE_KEY, mType);
        rootConfig.put(JSON_TEXT_SIZE_KEY, mTextSize);
        rootConfig.put(JSON_TEXT_COLOR_KEY, mTextColor);
        if (mLineCap != MplStyleMapper.LINE_CAP_ROUND) {
            rootConfig.put(JSON_LINE_CAP_KEY, mLineCap);
        }
        if (mLineJoin != MplStyleMapper.LINE_JOIN_ROUND) {
            rootConfig.put(JSON_LINE_JOIN_KEY, mLineJoin);
        }
        if (mLineJoin == MplStyleMapper.LINE_JOIN_MITER
                && mLineMiterLimit != MplStyleMapper.DEFAULT_LINE_MITER_LIMIT) {
            rootConfig.put(JSON_LINE_MITER_LIMIT_KEY, mLineMiterLimit);
        }
        if (mDashPreset != MplStyleMapper.DASH_PRESET_SHORT) {
            rootConfig.put(JSON_LINE_DASH_PRESET_KEY, mDashPreset);
        }
        if (mDashArray != null) {
            rootConfig.put(JSON_LINE_DASH_ARRAY_KEY, mDashArray);
        }
        if (mLineOffset != 0f) {
            rootConfig.put(JSON_LINE_OFFSET_KEY, mLineOffset);
        }
        if (mLineGapWidth > 0f) {
            rootConfig.put(JSON_LINE_GAP_WIDTH_KEY, mLineGapWidth);
        }
        if (getLineOutlineMultiplier() != 3f) {
            rootConfig.put(JSON_LINE_OUTLINE_MULTIPLIER_KEY, getLineOutlineMultiplier());
        }
        if (mLineBlur > MplStyleMapper.DEFAULT_LINE_BLUR) {
            rootConfig.put(JSON_LINE_BLUR_KEY, mLineBlur);
        }

        if (null != mText) {
            rootConfig.put(JSON_DISPLAY_NAME, mText);
        }
        if (null != mField) {
            rootConfig.put(JSON_VALUE_KEY, mField);
        }
        mLabelAttributes.mergeInto(rootConfig);

        return rootConfig;
    }

    @Override
    public void fromJSON(JSONObject jsonObject) throws JSONException {
        super.fromJSON(jsonObject);
        mType = jsonObject.getInt(JSON_TYPE_KEY);
        mTextSize = (float) jsonObject.optDouble(JSON_TEXT_SIZE_KEY, 3);
        if (jsonObject.has(JSON_TEXT_COLOR_KEY)) {
            mTextColor = jsonObject.getInt(JSON_TEXT_COLOR_KEY);
        } else {
            mTextColor = mOutColor;
        }
        mLineCap = jsonObject.optInt(JSON_LINE_CAP_KEY, MplStyleMapper.LINE_CAP_ROUND);
        mLineJoin = jsonObject.optInt(JSON_LINE_JOIN_KEY, MplStyleMapper.LINE_JOIN_ROUND);
        mLineMiterLimit = (float) jsonObject.optDouble(
                JSON_LINE_MITER_LIMIT_KEY, MplStyleMapper.DEFAULT_LINE_MITER_LIMIT);
        mDashPreset = jsonObject.optInt(JSON_LINE_DASH_PRESET_KEY, MplStyleMapper.DASH_PRESET_SHORT);
        setDashArray(jsonObject.optString(JSON_LINE_DASH_ARRAY_KEY, null));
        mLineOffset = (float) jsonObject.optDouble(JSON_LINE_OFFSET_KEY, 0);
        mLineGapWidth = (float) jsonObject.optDouble(JSON_LINE_GAP_WIDTH_KEY, 0);
        setLineOutlineMultiplier((float) jsonObject.optDouble(
                JSON_LINE_OUTLINE_MULTIPLIER_KEY, 3f));
        mLineBlur = (float) jsonObject.optDouble(
                JSON_LINE_BLUR_KEY, MplStyleMapper.DEFAULT_LINE_BLUR);

        if (jsonObject.has(JSON_DISPLAY_NAME)) {
            mText = jsonObject.getString(JSON_DISPLAY_NAME);
        }
        if (jsonObject.has(JSON_VALUE_KEY)) {
            mField = jsonObject.getString(JSON_VALUE_KEY);
        }
        mLabelAttributes.readFrom(jsonObject);
    }

}

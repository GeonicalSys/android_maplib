package com.nextgis.maplib.map.mpl;

import static com.nextgis.maplib.display.SimpleMarkerStyle.ALIGN_TOP;

import com.nextgis.maplib.display.LabelAttributes;
import com.nextgis.maplib.display.MplStyleMapper;
import com.nextgis.maplib.display.PolygonPatternRegistry;
import com.nextgis.maplib.display.SimpleLineStyle;
import com.nextgis.maplib.display.SimpleMarkerStyle;
import com.nextgis.maplib.display.SimplePolygonStyle;
import com.nextgis.maplib.display.Style;
import com.nextgis.maplib.display.TextStyleUtil;
import com.nextgis.maplib.util.GeoConstants;

/**
 * Layer-default style values parsed from NG {@link Style} for MapLibre layer factories.
 */
public final class MplLayerStyleVars {

    public int fillColor;
    public int outlineColor;
    public float fillOpacity = 1f;
    public float strokeOpacity = 1f;
    public float thickness = 3f;
    public float markerRadius = 6f;
    public int type;
    public int lineCap = MplStyleMapper.LINE_CAP_ROUND;
    public int lineJoin = MplStyleMapper.LINE_JOIN_ROUND;
    public int dashPreset = MplStyleMapper.DASH_PRESET_SHORT;
    public String dashArray;
    public float lineOffset;
    public float lineGapWidth;
    public float lineOutlineMultiplier = 3f;
    public boolean scaleSizeWithZoom;
    public String sizeZoomScaleStops;
    public int fillPattern = PolygonPatternRegistry.FILL_PATTERN_NONE;
    public String fillPatternImage;
    public float fillTranslateX;
    public float fillTranslateY;
    public int textAlignment = ALIGN_TOP;
    public float textSize = 3f;
    public int textColor;
    public float lineMiterLimit = MplStyleMapper.DEFAULT_LINE_MITER_LIMIT;
    public float circleBlur = MplStyleMapper.DEFAULT_CIRCLE_BLUR;
    public float lineBlur = MplStyleMapper.DEFAULT_LINE_BLUR;
    public String markerIconImage;
    public float markerIconSize;
    public float markerIconRotate;
    public float markerIconOffsetX;
    public float markerIconOffsetY;
    public int markerIconAnchor = SimpleMarkerStyle.ICON_ANCHOR_CENTER;
    public boolean markerIconAllowOverlap = true;
    public boolean markerIconIgnorePlacement;

    public static MplLayerStyleVars from(Style layerStyle, int layerType) {
        MplLayerStyleVars vars = new MplLayerStyleVars();
        if (layerStyle == null) {
            return vars;
        }
        vars.fillColor = layerStyle.getColor();
        vars.outlineColor = layerStyle.getOutColor();
        vars.fillOpacity = MplStyleMapper.alphaToOpacity(layerStyle.getAlpha());
        vars.strokeOpacity = MplStyleMapper.alphaToOpacity(layerStyle.getOutAlpha());
        vars.thickness = layerStyle.getWidth();
        vars.scaleSizeWithZoom = layerStyle.isScaleSizeWithZoom();
        vars.sizeZoomScaleStops = layerStyle.getSizeZoomScaleStops();

        if (layerStyle instanceof SimpleMarkerStyle) {
            SimpleMarkerStyle markerStyle = (SimpleMarkerStyle) layerStyle;
            vars.markerRadius = markerStyle.getSize();
            vars.type = markerStyle.getType();
            vars.textAlignment = markerStyle.getTextAlignment();
            vars.textSize = markerStyle.getTextSize();
            vars.textColor = markerStyle.getTextColor();
            vars.circleBlur = markerStyle.getCircleBlur();
            vars.markerIconImage = markerStyle.getIconImage();
            vars.markerIconSize = markerStyle.getIconSize();
            vars.markerIconRotate = markerStyle.getIconRotate();
            vars.markerIconOffsetX = markerStyle.getIconOffsetX();
            vars.markerIconOffsetY = markerStyle.getIconOffsetY();
            vars.markerIconAnchor = markerStyle.getIconAnchor();
            vars.markerIconAllowOverlap = markerStyle.isIconAllowOverlap();
            vars.markerIconIgnorePlacement = markerStyle.isIconIgnorePlacement();
        }
        if (layerStyle instanceof SimpleLineStyle) {
            SimpleLineStyle lineStyle = (SimpleLineStyle) layerStyle;
            vars.type = lineStyle.getType();
            vars.lineCap = lineStyle.getLineCap();
            vars.lineJoin = lineStyle.getLineJoin();
            vars.lineMiterLimit = lineStyle.getLineMiterLimit();
            vars.lineBlur = lineStyle.getLineBlur();
            vars.dashPreset = lineStyle.getDashPreset();
            vars.dashArray = lineStyle.getDashArray();
            vars.lineOffset = lineStyle.getLineOffset();
            vars.lineGapWidth = lineStyle.getLineGapWidth();
            vars.lineOutlineMultiplier = lineStyle.getLineOutlineMultiplier();
            vars.textColor = TextStyleUtil.getTextColor(layerStyle);
            vars.textSize = lineStyle.getTextSize();
        }
        if (layerStyle instanceof SimplePolygonStyle) {
            SimplePolygonStyle polygonStyle = (SimplePolygonStyle) layerStyle;
            vars.fillPattern = polygonStyle.getFillPattern();
            vars.fillPatternImage = polygonStyle.getFillPatternImage();
            vars.fillTranslateX = polygonStyle.getFillTranslateX();
            vars.fillTranslateY = polygonStyle.getFillTranslateY();
            vars.fillOpacity = MplStyleMapper.polygonFillOpacity(layerStyle);
            Float polygonTextSize = polygonStyle.getTextSize();
            if (polygonTextSize != null) {
                vars.textSize = polygonTextSize;
            }
            vars.textColor = TextStyleUtil.getTextColor(layerStyle);
            vars.textAlignment = ALIGN_TOP;
        }
        return vars;
    }

    public LabelAttributes labelAttributes(Style layerStyle) {
        return LabelAttributes.fromStyle(layerStyle);
    }

    public boolean isPolygon(int layerType) {
        return layerType == GeoConstants.GTPolygon || layerType == GeoConstants.GTMultiPolygon;
    }
}

package com.nextgis.maplib.map;

import static com.nextgis.maplib.display.SimpleMarkerStyle.ALIGN_TOP;
import static com.nextgis.maplib.util.GeoConstants.GTLineString;
import static com.nextgis.maplib.util.GeoConstants.GTMultiLineString;
import static com.nextgis.maplib.util.GeoConstants.GTMultiPoint;
import static com.nextgis.maplib.util.GeoConstants.GTMultiPolygon;
import static com.nextgis.maplib.util.GeoConstants.GTPoint;
import static com.nextgis.maplib.util.GeoConstants.GTPolygon;

import com.google.gson.JsonArray;
import com.nextgis.maplib.api.ITextStyle;
import com.nextgis.maplib.display.LabelAttributes;
import com.nextgis.maplib.display.MarkerIconRegistry;
import com.nextgis.maplib.display.MplStyleMapper;
import com.nextgis.maplib.display.PolygonPatternRegistry;
import com.nextgis.maplib.display.SimpleLineStyle;
import com.nextgis.maplib.display.SimpleMarkerStyle;
import com.nextgis.maplib.display.SimplePolygonStyle;
import com.nextgis.maplib.display.Style;

import org.maplibre.geojson.Feature;
import org.maplibre.android.style.layers.Property;

/**
 * Canonical GeoJSON property names and per-geometry apply/clear matrix for MapLibre
 * data-driven styling. Used when loading NG features and when clearing rule overrides.
 */
public final class MplFeatureStyleProps {

    private MplFeatureStyleProps() {
    }

    // --- property names (GeoJSON feature properties) ---

    public static final String COLOR_FILL_RULE = "fillcolor";
    public static final String TEXT_COLOR = "textcolor";
    public static final String TEXT_SIZE = "textsize";
    public static final String TEXT_SCALE_WITH_ZOOM = "textscalewithzoom";
    public static final String TEXT_ANCHOR = "textanchor";
    public static final String TEXT_OFFSETS = "textoffset";
    public static final String TEXT_HALO_COLOR = "texthalo_color";
    public static final String TEXT_HALO_WIDTH = "texthalo_width";
    public static final String TEXT_HALO_BLUR = "texthalo_blur";
    public static final String TEXT_OPACITY = MplStyleMapper.PROP_TEXT_OPACITY;
    public static final String TEXT_FONT = "textfont";
    public static final String TEXT_JUSTIFY = "textjustify";
    public static final String TEXT_TRANSFORM = "texttransform";
    public static final String TEXT_LETTER_SPACING = "textletterspacing";
    public static final String TEXT_LINE_HEIGHT = "textlineheight";
    public static final String TEXT_PADDING = "textpadding";
    public static final String TEXT_KEEP_UPRIGHT = "textkeepupright";
    public static final String TEXT_MAX_ANGLE = "textmaxangle";
    public static final String TEXT_MAX_WIDTH = "textmaxwidth";
    public static final String TEXT_ALLOW_OVERLAP = "textallowoverlap";
    public static final String TEXT_OPTIONAL = "textoptional";
    public static final String TEXT_ROTATION_ALIGNMENT = "textrotationalignment";
    public static final String SYMBOL_SPACING = "symbolspacing";
    public static final String SYMBOL_PLACEMENT = "symbolplacement";

    public static final String COLOR_FILL = "colorfill";
    public static final String COLOR_STROKE = "colorstroke";
    public static final String SIZE = "size";
    public static final String THICKNESS = "thinkness";
    public static final String SCALE_SIZE_WITH_ZOOM = "scalesizewithzoom";
    public static final String OPACITY = "opacity";
    public static final String STROKE_OPACITY = MplStyleMapper.PROP_STROKE_OPACITY;
    public static final String CIRCLE_BLUR = "circleblur";

    public static final String FILL_TYPE = "filltype";
    public static final String FILL_TYPE2 = "filltype2";
    public static final String DASH_PRESET = "dashpreset";
    public static final String FILL_PATTERN = PolygonPatternRegistry.PROP_FILL_PATTERN;
    public static final String FILL_PATTERN_IMAGE = PolygonPatternRegistry.PROP_FILL_PATTERN_IMAGE;
    public static final String ICON_IMAGE = "iconimage";
    public static final String ICON_SIZE = "iconsize";
    public static final String ICON_ROTATE = "iconrotate";
    public static final String ICON_OFFSET = "iconoffset";
    public static final String ICON_ANCHOR = "iconanchor";
    public static final String ICON_ALLOW_OVERLAP = "iconallowoverlap";
    public static final String ICON_IGNORE_PLACEMENT = "iconignoreplacement";
    public static final String LINE_CAP = "linecap";
    public static final String LINE_JOIN = "linejoin";
    public static final String LINE_MITER_LIMIT = "linemiterlimit";
    public static final String LINE_BLUR = "lineblur";
    public static final String LINE_OFFSET = "lineoffset";
    public static final String LINE_GAP_WIDTH = "linegapwidth";
    public static final String LINE_OUTLINE_MULTIPLIER = "lineoutlinemultiplier";
    public static final String LINE_DASH_ARRAY = "linedasharray";
    public static final String FILL_TRANSLATE = "filltranslate";

    public static final String FEATURE_ID = "featureid";
    public static final String LAYER_ID = "layerid";
    public static final String ORDER = "order";
    public static final String COLOR = "color";
    public static final String SIGNATURE = "signature";
    public static final String START_FLAG = "type";

    private enum GeometryKind {
        POINT, LINE, POLYGON, UNKNOWN
    }

    private static GeometryKind geometryKind(int geoType) {
        switch (geoType) {
            case GTPoint:
            case GTMultiPoint:
                return GeometryKind.POINT;
            case GTLineString:
            case GTMultiLineString:
                return GeometryKind.LINE;
            case GTPolygon:
            case GTMultiPolygon:
                return GeometryKind.POLYGON;
            default:
                return GeometryKind.UNKNOWN;
        }
    }

    /** Writes geometry-specific style props onto a MapLibre feature. */
    public static void apply(Style style, Feature feature, int geoType) {
        if (style == null || feature == null) {
            return;
        }
        switch (geometryKind(geoType)) {
            case POINT:
                feature.addStringProperty(COLOR_FILL, MPLFeaturesUtils.getColorName(style.getColor()));
                feature.addStringProperty(COLOR_STROKE, MPLFeaturesUtils.getColorName(style.getOutColor()));
                feature.addNumberProperty(SIZE, MPLFeaturesUtils.getMPLThinkness(((SimpleMarkerStyle) style).getSize()));
                feature.addNumberProperty(THICKNESS, MPLFeaturesUtils.getMPLThinkness(style.getWidth()));
                feature.addBooleanProperty(SCALE_SIZE_WITH_ZOOM, style.isScaleSizeWithZoom());
                if (style instanceof SimpleMarkerStyle) {
                    SimpleMarkerStyle markerStyle = (SimpleMarkerStyle) style;
                    feature.addNumberProperty(FILL_TYPE, markerStyle.getType());
                    feature.addNumberProperty(CIRCLE_BLUR, markerStyle.getCircleBlur());
                    feature.addStringProperty(
                            ICON_IMAGE,
                            markerStyle.getIconImage() != null ? markerStyle.getIconImage() : "");
                    float iconSize = markerStyle.getIconSize();
                    feature.addNumberProperty(
                            ICON_SIZE,
                            iconSize > 0f ? MPLFeaturesUtils.getMPLThinkness(iconSize) : 0f);
                    feature.addNumberProperty(ICON_ROTATE, markerStyle.getIconRotate());
                    addFloatArrayProperty(feature, ICON_OFFSET, new Float[]{
                            markerStyle.getIconOffsetX(),
                            markerStyle.getIconOffsetY()});
                    feature.addStringProperty(
                            ICON_ANCHOR,
                            MarkerIconRegistry.iconAnchorValue(markerStyle.getIconAnchor()));
                    feature.addBooleanProperty(ICON_ALLOW_OVERLAP, markerStyle.isIconAllowOverlap());
                    feature.addBooleanProperty(ICON_IGNORE_PLACEMENT, markerStyle.isIconIgnorePlacement());
                }
                applyOpacity(feature, style, geoType);
                applyText(feature, style, geoType);
                break;
            case LINE:
                feature.addStringProperty(COLOR_FILL, MPLFeaturesUtils.getColorName(style.getColor()));
                feature.addStringProperty(COLOR_STROKE, MPLFeaturesUtils.getColorName(style.getOutColor()));
                feature.addNumberProperty(THICKNESS, MPLFeaturesUtils.getMPLThinkness(style.getWidth()));
                feature.addBooleanProperty(SCALE_SIZE_WITH_ZOOM, style.isScaleSizeWithZoom());
                if (style instanceof SimpleLineStyle) {
                    SimpleLineStyle lineStyle = (SimpleLineStyle) style;
                    feature.addNumberProperty(FILL_TYPE, lineStyle.getType());
                    feature.addNumberProperty(DASH_PRESET, lineStyle.getDashPreset());
                    feature.addStringProperty(LINE_CAP, MplStyleMapper.lineCapValue(lineStyle.getLineCap()));
                    feature.addStringProperty(LINE_JOIN, MplStyleMapper.lineJoinValue(lineStyle.getLineJoin()));
                    feature.addNumberProperty(
                            LINE_MITER_LIMIT,
                            MplStyleMapper.lineMiterLimit(
                                    lineStyle.getLineJoin(),
                                    lineStyle.getLineMiterLimit()));
                    feature.addNumberProperty(
                            LINE_BLUR,
                            MplStyleMapper.lineBlurPaintValue(lineStyle.getLineBlur()));
                    feature.addNumberProperty(LINE_OFFSET, lineStyle.getLineOffset());
                    feature.addNumberProperty(LINE_GAP_WIDTH, lineStyle.getLineGapWidth());
                    feature.addNumberProperty(
                            LINE_OUTLINE_MULTIPLIER,
                            lineStyle.getLineOutlineMultiplier());
                    if (MplStyleMapper.isLineDashType(lineStyle.getType())
                            || lineStyle.getDashArray() != null) {
                        addFloatArrayProperty(
                                feature,
                                LINE_DASH_ARRAY,
                                MplStyleMapper.dashArray(
                                        lineStyle.getDashArray(),
                                        lineStyle.getDashPreset()));
                    }
                }
                applyOpacity(feature, style, geoType);
                applyText(feature, style, geoType);
                break;
            case POLYGON:
                feature.addStringProperty(COLOR_FILL_RULE, MPLFeaturesUtils.getColorName(style.getColor()));
                feature.addStringProperty(COLOR_STROKE, MPLFeaturesUtils.getColorName(style.getOutColor()));
                feature.addNumberProperty(THICKNESS, MPLFeaturesUtils.getMPLThinkness(style.getWidth()));
                feature.addBooleanProperty(SCALE_SIZE_WITH_ZOOM, style.isScaleSizeWithZoom());
                if (style instanceof SimplePolygonStyle) {
                    SimplePolygonStyle polygonStyle = (SimplePolygonStyle) style;
                    int fillPattern = polygonStyle.getFillPattern();
                    if (polygonStyle.getFillPatternImage() != null
                            && fillPattern <= PolygonPatternRegistry.FILL_PATTERN_NONE) {
                        fillPattern = PolygonPatternRegistry.FILL_PATTERN_HATCH;
                    }
                    feature.addNumberProperty(
                            FILL_PATTERN,
                            fillPattern);
                    if (polygonStyle.getFillPatternImage() != null) {
                        feature.addStringProperty(FILL_PATTERN_IMAGE, polygonStyle.getFillPatternImage());
                    }
                    addFloatArrayProperty(feature, FILL_TRANSLATE, new Float[]{
                            polygonStyle.getFillTranslateX(),
                            polygonStyle.getFillTranslateY()});
                }
                applyOpacity(feature, style, geoType);
                applyText(feature, style, geoType);
                break;
            default:
                break;
        }
    }

    /** Removes geometry-specific style props from a MapLibre feature. */
    public static void clear(Feature feature, int geoType) {
        if (feature == null) {
            return;
        }
        switch (geometryKind(geoType)) {
            case POINT:
                clearText(feature);
                feature.removeProperty(COLOR_FILL);
                feature.removeProperty(COLOR_STROKE);
                feature.removeProperty(SIZE);
                feature.removeProperty(THICKNESS);
                feature.removeProperty(SCALE_SIZE_WITH_ZOOM);
                feature.removeProperty(FILL_TYPE);
                feature.removeProperty(CIRCLE_BLUR);
                feature.removeProperty(ICON_IMAGE);
                feature.removeProperty(ICON_SIZE);
                feature.removeProperty(ICON_ROTATE);
                feature.removeProperty(ICON_OFFSET);
                feature.removeProperty(ICON_ANCHOR);
                feature.removeProperty(ICON_ALLOW_OVERLAP);
                feature.removeProperty(ICON_IGNORE_PLACEMENT);
                feature.removeProperty(OPACITY);
                feature.removeProperty(STROKE_OPACITY);
                break;
            case LINE:
                feature.removeProperty(COLOR_FILL);
                feature.removeProperty(COLOR_STROKE);
                clearText(feature);
                feature.removeProperty(THICKNESS);
                feature.removeProperty(SCALE_SIZE_WITH_ZOOM);
                feature.removeProperty(FILL_TYPE);
                feature.removeProperty(DASH_PRESET);
                feature.removeProperty(LINE_CAP);
                feature.removeProperty(LINE_JOIN);
                feature.removeProperty(LINE_MITER_LIMIT);
                feature.removeProperty(LINE_BLUR);
                feature.removeProperty(LINE_OFFSET);
                feature.removeProperty(LINE_GAP_WIDTH);
                feature.removeProperty(LINE_OUTLINE_MULTIPLIER);
                feature.removeProperty(LINE_DASH_ARRAY);
                feature.removeProperty(OPACITY);
                feature.removeProperty(STROKE_OPACITY);
                break;
            case POLYGON:
                feature.removeProperty(COLOR_FILL_RULE);
                feature.removeProperty(COLOR_STROKE);
                feature.removeProperty(THICKNESS);
                feature.removeProperty(SCALE_SIZE_WITH_ZOOM);
                feature.removeProperty(FILL_PATTERN);
                feature.removeProperty(FILL_PATTERN_IMAGE);
                feature.removeProperty(FILL_TRANSLATE);
                feature.removeProperty(OPACITY);
                feature.removeProperty(STROKE_OPACITY);
                clearText(feature);
                break;
            default:
                break;
        }
    }

    private static void applyOpacity(Feature feature, Style style, int geoType) {
        float fillOpacity = MplStyleMapper.alphaToOpacity(style.getAlpha());
        if (geoType == GTPolygon || geoType == GTMultiPolygon) {
            fillOpacity = MplStyleMapper.polygonFillOpacity(style);
        }
        feature.addNumberProperty(OPACITY, fillOpacity);
        feature.addNumberProperty(STROKE_OPACITY, MplStyleMapper.alphaToOpacity(style.getOutAlpha()));
    }

    private static void applyText(Feature feature, Style style, int geoType) {
        int textColor;
        float textSizeNg;
        int align;
        LabelAttributes labelAttributes = LabelAttributes.fromStyle(style);

        if (style instanceof SimpleMarkerStyle) {
            SimpleMarkerStyle ms = (SimpleMarkerStyle) style;
            textColor = ms.getTextColor();
            textSizeNg = ms.getTextSize();
            align = ms.getTextAlignment();
        } else if (style instanceof SimplePolygonStyle) {
            SimplePolygonStyle ps = (SimplePolygonStyle) style;
            textColor = ps.getTextColor();
            Float polygonTextSize = ps.getTextSize();
            textSizeNg = polygonTextSize != null ? polygonTextSize : 12f;
            align = ALIGN_TOP;
        } else if (style instanceof SimpleLineStyle) {
            SimpleLineStyle ls = (SimpleLineStyle) style;
            textColor = ls.getTextColor();
            textSizeNg = ls.getTextSize();
            align = ALIGN_TOP;
        } else {
            return;
        }

        float textSize = (textSizeNg + 3) * 3;
        feature.addStringProperty(TEXT_COLOR, MPLFeaturesUtils.getColorName(textColor));
        feature.addNumberProperty(TEXT_SIZE, textSize);
        feature.addBooleanProperty(TEXT_SCALE_WITH_ZOOM, labelAttributes.isTextScaleWithZoom());
        feature.addStringProperty(TEXT_ANCHOR, MPLFeaturesUtils.getTextAnchor(align));

        Float[] offsets = geoType == GTPolygon || geoType == GTMultiPolygon
                ? new Float[]{0.0f, 0f}
                : MPLFeaturesUtils.getTextAnchorOffsets(align, textSizeNg);
        addFloatArrayProperty(feature, TEXT_OFFSETS, offsets);
        applyLabelAttributes(feature, labelAttributes, geoType);
    }

    private static void applyLabelAttributes(
            Feature feature,
            LabelAttributes labelAttributes,
            int geoType) {
        if (labelAttributes == null) {
            return;
        }
        feature.addStringProperty(
                TEXT_HALO_COLOR,
                MPLFeaturesUtils.getColorName(labelAttributes.getTextHaloColor()));
        feature.addNumberProperty(TEXT_HALO_WIDTH, labelAttributes.getTextHaloWidth());
        feature.addNumberProperty(TEXT_HALO_BLUR, labelAttributes.getTextHaloBlur());
        feature.addNumberProperty(TEXT_OPACITY, labelAttributes.textOpacityFloat());
        addStringArrayProperty(feature, TEXT_FONT, labelAttributes.getTextFontStack());
        feature.addStringProperty(TEXT_JUSTIFY, labelAttributes.getTextJustify());
        feature.addStringProperty(TEXT_TRANSFORM, labelAttributes.getTextTransform());
        feature.addNumberProperty(TEXT_LETTER_SPACING, labelAttributes.getTextLetterSpacing());
        feature.addNumberProperty(TEXT_LINE_HEIGHT, labelAttributes.getTextLineHeight());
        feature.addNumberProperty(TEXT_PADDING, labelAttributes.getTextPadding());
        feature.addBooleanProperty(
                TEXT_KEEP_UPRIGHT,
                labelAttributes.getTextKeepUpright() == null
                        || labelAttributes.getTextKeepUpright());
        feature.addNumberProperty(TEXT_MAX_ANGLE, labelAttributes.getTextMaxAngle());
        feature.addNumberProperty(
                TEXT_MAX_WIDTH,
                labelAttributes.getTextMaxWidth() > 0f ? labelAttributes.getTextMaxWidth() : 0f);
        feature.addBooleanProperty(
                TEXT_ALLOW_OVERLAP,
                labelAttributes.getTextAllowOverlap() != null
                        ? labelAttributes.getTextAllowOverlap()
                        : false);
        feature.addBooleanProperty(TEXT_OPTIONAL, labelAttributes.isTextOptional());
        feature.addNumberProperty(SYMBOL_SPACING, labelAttributes.getSymbolSpacing());
        feature.addStringProperty(SYMBOL_PLACEMENT, symbolPlacementValue(geoType, labelAttributes));
        feature.addStringProperty(
                TEXT_ROTATION_ALIGNMENT,
                textRotationAlignmentValue(geoType, labelAttributes));
    }

    /** Removes label-related GeoJSON properties only (not geometry paint props). */
    public static void clearTextProps(Feature feature) {
        clearText(feature);
    }

    private static void clearText(Feature feature) {
        feature.removeProperty(TEXT_COLOR);
        feature.removeProperty(TEXT_SIZE);
        feature.removeProperty(TEXT_SCALE_WITH_ZOOM);
        feature.removeProperty(TEXT_ANCHOR);
        feature.removeProperty(TEXT_OFFSETS);
        feature.removeProperty(TEXT_HALO_COLOR);
        feature.removeProperty(TEXT_HALO_WIDTH);
        feature.removeProperty(TEXT_HALO_BLUR);
        feature.removeProperty(TEXT_OPACITY);
        feature.removeProperty(TEXT_FONT);
        feature.removeProperty(TEXT_JUSTIFY);
        feature.removeProperty(TEXT_TRANSFORM);
        feature.removeProperty(TEXT_LETTER_SPACING);
        feature.removeProperty(TEXT_LINE_HEIGHT);
        feature.removeProperty(TEXT_PADDING);
        feature.removeProperty(TEXT_KEEP_UPRIGHT);
        feature.removeProperty(TEXT_MAX_ANGLE);
        feature.removeProperty(TEXT_MAX_WIDTH);
        feature.removeProperty(TEXT_ALLOW_OVERLAP);
        feature.removeProperty(TEXT_OPTIONAL);
        feature.removeProperty(TEXT_ROTATION_ALIGNMENT);
        feature.removeProperty(SYMBOL_SPACING);
        feature.removeProperty(SYMBOL_PLACEMENT);
    }

    private static String symbolPlacementValue(int geoType, LabelAttributes labelAttributes) {
        if (geoType == GTPoint || geoType == GTMultiPoint
                || geoType == GTPolygon || geoType == GTMultiPolygon) {
            return Property.SYMBOL_PLACEMENT_POINT;
        }
        if (geoType == GTLineString || geoType == GTMultiLineString) {
            return labelAttributes.isLineLabelRepeat()
                    ? Property.SYMBOL_PLACEMENT_LINE
                    : Property.SYMBOL_PLACEMENT_LINE_CENTER;
        }
        return Property.SYMBOL_PLACEMENT_POINT;
    }

    private static String textRotationAlignmentValue(
            int geoType,
            LabelAttributes labelAttributes) {
        if (geoType == GTLineString || geoType == GTMultiLineString) {
            return labelAttributes.isLineLabelHorizontal()
                    ? Property.TEXT_ROTATION_ALIGNMENT_VIEWPORT
                    : Property.TEXT_ROTATION_ALIGNMENT_MAP;
        }
        return Property.TEXT_ROTATION_ALIGNMENT_AUTO;
    }

    private static void addFloatArrayProperty(Feature feature, String name, Float[] values) {
        JsonArray arr = new JsonArray();
        for (Float value : values) {
            arr.add(value);
        }
        feature.addProperty(name, arr);
    }

    private static void addStringArrayProperty(Feature feature, String name, String[] values) {
        JsonArray arr = new JsonArray();
        for (String value : values) {
            arr.add(value);
        }
        feature.addProperty(name, arr);
    }
}

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
import com.nextgis.maplib.display.MplStyleMapper;
import com.nextgis.maplib.display.PolygonPatternRegistry;
import com.nextgis.maplib.display.SimpleLineStyle;
import com.nextgis.maplib.display.SimpleMarkerStyle;
import com.nextgis.maplib.display.SimplePolygonStyle;
import com.nextgis.maplib.display.Style;

import org.maplibre.geojson.Feature;

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
    public static final String TEXT_ANCHOR = "textanchor";
    public static final String TEXT_OFFSETS = "textoffset";
    public static final String TEXT_HALO_COLOR = "texthalo_color";
    public static final String TEXT_HALO_WIDTH = "texthalo_width";
    public static final String TEXT_HALO_BLUR = "texthalo_blur";
    public static final String TEXT_OPACITY = MplStyleMapper.PROP_TEXT_OPACITY;

    public static final String COLOR_FILL = "colorfill";
    public static final String COLOR_STROKE = "colorstroke";
    public static final String SIZE = "size";
    public static final String THICKNESS = "thinkness";
    public static final String OPACITY = "opacity";
    public static final String STROKE_OPACITY = MplStyleMapper.PROP_STROKE_OPACITY;

    public static final String FILL_TYPE = "filltype";
    public static final String FILL_TYPE2 = "filltype2";
    public static final String DASH_PRESET = "dashpreset";
    public static final String FILL_PATTERN = PolygonPatternRegistry.PROP_FILL_PATTERN;

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
                if (style instanceof SimpleMarkerStyle) {
                    feature.addNumberProperty(FILL_TYPE, ((SimpleMarkerStyle) style).getType());
                }
                applyOpacity(feature, style, geoType);
                applyText(feature, style, geoType);
                break;
            case LINE:
                feature.addStringProperty(COLOR_FILL, MPLFeaturesUtils.getColorName(style.getColor()));
                feature.addNumberProperty(THICKNESS, MPLFeaturesUtils.getMPLThinkness(style.getWidth()));
                if (style instanceof SimpleLineStyle) {
                    SimpleLineStyle lineStyle = (SimpleLineStyle) style;
                    feature.addNumberProperty(FILL_TYPE, lineStyle.getType());
                    feature.addNumberProperty(DASH_PRESET, lineStyle.getDashPreset());
                }
                applyOpacity(feature, style, geoType);
                applyText(feature, style, geoType);
                break;
            case POLYGON:
                feature.addStringProperty(COLOR_FILL_RULE, MPLFeaturesUtils.getColorName(style.getColor()));
                feature.addStringProperty(COLOR_STROKE, MPLFeaturesUtils.getColorName(style.getOutColor()));
                feature.addNumberProperty(THICKNESS, MPLFeaturesUtils.getMPLThinkness(style.getWidth()));
                if (style instanceof SimplePolygonStyle) {
                    feature.addNumberProperty(
                            FILL_PATTERN,
                            ((SimplePolygonStyle) style).getFillPattern());
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
                feature.removeProperty(OPACITY);
                feature.removeProperty(STROKE_OPACITY);
                break;
            case LINE:
                feature.removeProperty(COLOR_FILL);
                clearText(feature);
                feature.removeProperty(THICKNESS);
                feature.removeProperty(FILL_TYPE);
                feature.removeProperty(DASH_PRESET);
                feature.removeProperty(OPACITY);
                feature.removeProperty(STROKE_OPACITY);
                break;
            case POLYGON:
                feature.removeProperty(COLOR_FILL_RULE);
                feature.removeProperty(COLOR_STROKE);
                feature.removeProperty(THICKNESS);
                feature.removeProperty(FILL_PATTERN);
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
        feature.addStringProperty(TEXT_ANCHOR, MPLFeaturesUtils.getTextAnchor(align));

        Float[] offsets = geoType == GTPolygon || geoType == GTMultiPolygon
                ? new Float[]{0.0f, 0f}
                : MPLFeaturesUtils.getTextAnchorOffsets(align, textSizeNg);
        JsonArray arr = new JsonArray();
        arr.add(offsets[0]);
        arr.add(offsets[1]);
        feature.addProperty(TEXT_OFFSETS, arr);
        applyLabelHalo(feature, labelAttributes);
    }

    private static void applyLabelHalo(Feature feature, LabelAttributes labelAttributes) {
        if (labelAttributes == null) {
            return;
        }
        if (labelAttributes.getTextHaloWidth() > 0f) {
            feature.addStringProperty(
                    TEXT_HALO_COLOR,
                    MPLFeaturesUtils.getColorName(labelAttributes.getTextHaloColor()));
            feature.addNumberProperty(TEXT_HALO_WIDTH, labelAttributes.getTextHaloWidth());
            if (labelAttributes.getTextHaloBlur() > 0f) {
                feature.addNumberProperty(TEXT_HALO_BLUR, labelAttributes.getTextHaloBlur());
            }
            if (labelAttributes.getTextOpacity() < LabelAttributes.DEFAULT_TEXT_OPACITY) {
                feature.addNumberProperty(TEXT_OPACITY, labelAttributes.textOpacityFloat());
            }
        }
    }

    /** Removes label-related GeoJSON properties only (not geometry paint props). */
    public static void clearTextProps(Feature feature) {
        clearText(feature);
    }

    private static void clearText(Feature feature) {
        feature.removeProperty(TEXT_COLOR);
        feature.removeProperty(TEXT_SIZE);
        feature.removeProperty(TEXT_ANCHOR);
        feature.removeProperty(TEXT_OFFSETS);
        feature.removeProperty(TEXT_HALO_COLOR);
        feature.removeProperty(TEXT_HALO_WIDTH);
        feature.removeProperty(TEXT_HALO_BLUR);
        feature.removeProperty(TEXT_OPACITY);
    }
}

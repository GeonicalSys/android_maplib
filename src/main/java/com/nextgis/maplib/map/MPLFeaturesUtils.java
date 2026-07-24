package com.nextgis.maplib.map;



import static com.nextgis.maplib.display.SimpleMarkerStyle.ALIGN_BOTTOM;
import static com.nextgis.maplib.display.SimpleMarkerStyle.ALIGN_BOTTOM_LEFT;
import static com.nextgis.maplib.display.SimpleMarkerStyle.ALIGN_BOTTOM_RIGHT;
import static com.nextgis.maplib.display.SimpleMarkerStyle.ALIGN_LEFT;
import static com.nextgis.maplib.display.SimpleMarkerStyle.ALIGN_RIGHT;
import static com.nextgis.maplib.display.SimpleMarkerStyle.ALIGN_TOP;
import static com.nextgis.maplib.display.SimpleMarkerStyle.ALIGN_TOP_LEFT;
import static com.nextgis.maplib.display.SimpleMarkerStyle.ALIGN_TOP_RIGHT;
import static com.nextgis.maplib.util.GeoConstants.GTLineString;
import static com.nextgis.maplib.util.GeoConstants.GTMultiLineString;
import static com.nextgis.maplib.util.GeoConstants.GTMultiPoint;
import static com.nextgis.maplib.util.GeoConstants.GTMultiPolygon;
import static com.nextgis.maplib.util.GeoConstants.GTPoint;
import static com.nextgis.maplib.util.GeoConstants.GTPolygon;
import static com.nextgis.maplib.util.GeoConstants.GT_RASTER_WA;
import static com.nextgis.maplib.util.GeoConstants.GT_TRACK_WA;
import static com.nextgis.maplib.util.GeoConstants.TMSTYPE_NORMAL;
import static com.nextgis.maplib.util.GeoConstants.TMSTYPE_OSM;

import static org.maplibre.android.style.layers.PropertyFactory.rasterBrightnessMax;
import static org.maplibre.android.style.layers.PropertyFactory.rasterBrightnessMin;
import static org.maplibre.android.style.layers.PropertyFactory.rasterContrast;
import static org.maplibre.android.style.layers.PropertyFactory.rasterOpacity;
import static org.maplibre.android.style.layers.PropertyFactory.rasterResampling;

import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.nextgis.maplib.api.ILayer;
import com.nextgis.maplib.api.ITextStyle;
import com.nextgis.maplib.datasource.GeoGeometry;
import com.nextgis.maplib.datasource.GeoGeometryCollection;
import com.nextgis.maplib.datasource.GeoLineString;
import com.nextgis.maplib.datasource.GeoLinearRing;
import com.nextgis.maplib.datasource.GeoMultiLineString;
import com.nextgis.maplib.datasource.GeoMultiPoint;
import com.nextgis.maplib.datasource.GeoMultiPolygon;
import com.nextgis.maplib.datasource.GeoPoint;
import com.nextgis.maplib.datasource.GeoPolygon;
import com.nextgis.maplib.display.FieldStyleRule;
import com.nextgis.maplib.display.LabelAttributes;
import com.nextgis.maplib.display.LabelTemplate;
import com.nextgis.maplib.display.MarkerIconRegistry;
import com.nextgis.maplib.display.MplStyleMapper;
import com.nextgis.maplib.display.PolygonPatternRegistry;
import com.nextgis.maplib.map.mpl.LineLayerFactory;
import com.nextgis.maplib.map.mpl.MplLayerBuildContext;
import com.nextgis.maplib.map.mpl.MplLayerBuildResult;
import com.nextgis.maplib.map.mpl.MplLayerStyleVars;
import com.nextgis.maplib.map.mpl.PointLayerFactory;
import com.nextgis.maplib.map.mpl.PolygonLayerFactory;
import com.nextgis.maplib.display.RuleFeatureRenderer;
import com.nextgis.maplib.display.SimpleLineStyle;
import com.nextgis.maplib.display.SimpleMarkerStyle;
import com.nextgis.maplib.display.SimplePolygonStyle;
import com.nextgis.maplib.display.TMSRenderer;
import com.nextgis.maplib.display.TextStyleUtil;
import com.nextgis.maplib.map.MLP.LineEditClass;
import com.nextgis.maplib.map.MLP.MLGeometryEditClass;
import com.nextgis.maplib.map.MLP.MeasurmentLine;
import com.nextgis.maplib.map.MLP.MultiLineEditClass;
import com.nextgis.maplib.map.MLP.MultiPointEditClass;
import com.nextgis.maplib.map.MLP.MultiPolygonEditClass;
import com.nextgis.maplib.map.MLP.PointEditClass;
import com.nextgis.maplib.map.MLP.PolygonEditClass;
import com.nextgis.maplib.util.GeoConstants;


import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.FillLayer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.Property;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.layers.PropertyValue;
import org.maplibre.android.style.layers.RasterLayer;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.android.style.sources.RasterSource;
import org.maplibre.android.style.sources.Source;
import org.maplibre.android.style.sources.TileSet;
import org.maplibre.android.style.sources.VectorSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.Geometry;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.MultiLineString;
import org.maplibre.geojson.MultiPoint;
import org.maplibre.geojson.MultiPolygon;
import org.maplibre.geojson.Point;
import org.maplibre.geojson.Polygon;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

// Loader: NG SQLite features → MapLibre GeoJSON with per-feature style properties.
//
// GeoJSON property contract (used in MapLibre data-driven paint via Expression.get):
//   signature      — label text (SymbolLayer text-field)
//   colorfill      — point fill / line color
//   fillcolor      — polygon fill (rule-style alias: prop_color_fill_rule)
//   colorstroke    — stroke / outline color
//   textcolor      — label color; line edging color for rules
//   size           — point radius (CircleLayer)
//   thinkness      — stroke width (points, lines, polygon outline)
//   opacity        — fill / line body opacity
//   strokeopacity  — stroke / outline opacity
//   fillpattern    — polygon fill pattern id (0 solid, 1 hatch, 2 cross, 3 dots, 4 brick, 5 forest, 6 marsh)
//   filltype       — line style: 1 solid, 2 dash, 3 solid+outline, 4 dash+outline; point marker type
//   textsize       — label size
//   textanchor     — MapLibre text-anchor
//   textoffset     — [x, y] label offset
//   texthalo_color, texthalo_width, texthalo_blur — label halo (rule-style)
//   textopacity    — label opacity 0–1 (rule-style)
//   featureid, layerid, order — selection / ordering
public class MPLFeaturesUtils {

    static public Number pointRaduis = 8;
    static public Number middleRaduis = 4;
    static public String colorLightBlue = "#03a9f4";
    static public String colorVeryLightBlue = "#A2BCF8";
    static public String colorBlue = "#0000FF";
    static public String colorRED = "#FF0000";

    static public String prop_color_fill_rule = MplFeatureStyleProps.COLOR_FILL_RULE;
    static public String prop_text_color = MplFeatureStyleProps.TEXT_COLOR;
    static public String prop_text_textsize = MplFeatureStyleProps.TEXT_SIZE;
    static public String prop_text_scale_with_zoom = MplFeatureStyleProps.TEXT_SCALE_WITH_ZOOM;
    static public String prop_text_textanchor = MplFeatureStyleProps.TEXT_ANCHOR;
    static public String prop_text_textoffsets = MplFeatureStyleProps.TEXT_OFFSETS;
    static public String prop_texthalo_color = MplFeatureStyleProps.TEXT_HALO_COLOR;
    static public String prop_texthalo_width = MplFeatureStyleProps.TEXT_HALO_WIDTH;
    static public String prop_texthalo_blur = MplFeatureStyleProps.TEXT_HALO_BLUR;
    static public String prop_text_opacity = MplFeatureStyleProps.TEXT_OPACITY;
    static public String prop_text_font = MplFeatureStyleProps.TEXT_FONT;
    static public String prop_text_justify = MplFeatureStyleProps.TEXT_JUSTIFY;
    static public String prop_text_transform = MplFeatureStyleProps.TEXT_TRANSFORM;
    static public String prop_text_letter_spacing = MplFeatureStyleProps.TEXT_LETTER_SPACING;
    static public String prop_text_line_height = MplFeatureStyleProps.TEXT_LINE_HEIGHT;
    static public String prop_text_padding = MplFeatureStyleProps.TEXT_PADDING;
    static public String prop_text_keep_upright = MplFeatureStyleProps.TEXT_KEEP_UPRIGHT;
    static public String prop_text_max_angle = MplFeatureStyleProps.TEXT_MAX_ANGLE;
    static public String prop_text_max_width_prop = MplFeatureStyleProps.TEXT_MAX_WIDTH;
    static public String prop_text_allow_overlap = MplFeatureStyleProps.TEXT_ALLOW_OVERLAP;
    static public String prop_text_optional = MplFeatureStyleProps.TEXT_OPTIONAL;
    static public String prop_text_rotation_alignment = MplFeatureStyleProps.TEXT_ROTATION_ALIGNMENT;
    static public String prop_symbol_spacing = MplFeatureStyleProps.SYMBOL_SPACING;
    static public String prop_symbol_placement = MplFeatureStyleProps.SYMBOL_PLACEMENT;
    static public String prop_label_min_zoom = MplFeatureStyleProps.LABEL_MIN_ZOOM;
    static public String prop_label_max_zoom = MplFeatureStyleProps.LABEL_MAX_ZOOM;

    // common properties — aliases of {@link MplFeatureStyleProps}
    static public String prop_color_fill = MplFeatureStyleProps.COLOR_FILL;
    static public String prop_color_stroke = MplFeatureStyleProps.COLOR_STROKE;
    static public String prop_size = MplFeatureStyleProps.SIZE;
    static public String prop_thinkness = MplFeatureStyleProps.THICKNESS;
    static public String prop_opacity = MplFeatureStyleProps.OPACITY;
    static public String prop_stroke_opacity = MplFeatureStyleProps.STROKE_OPACITY;

    static public String prop_type = MplFeatureStyleProps.FILL_TYPE;
    static public String prop_type2 = MplFeatureStyleProps.FILL_TYPE2;
    static public String prop_dash_preset = MplFeatureStyleProps.DASH_PRESET;
    static public String prop_fill_pattern = MplFeatureStyleProps.FILL_PATTERN;

    static public String prop_featureid = MplFeatureStyleProps.FEATURE_ID;
    static public String prop_layerid = MplFeatureStyleProps.LAYER_ID;
    static public String prop_order = MplFeatureStyleProps.ORDER;
    static public String namePrefix = "nglayer-";
    static public String prop_color = MplFeatureStyleProps.COLOR;
    static public String prop_signature_text = MplFeatureStyleProps.SIGNATURE;
    static public String prop_start_flag = MplFeatureStyleProps.START_FLAG;

    static final public String layer_namepart = "layer-";
    static final public String source_namepart = "source-";
    static final public String outline_namepart = "_outline";
    static final public String dash_namepart = "_dash";
    static final public String pattern_namepart = "_pattern";
    static final public String track_namepart = "track-";
    static final public String track_flags_namepart = "track-flags-";

    static final public String source_polygon_text = "-text"; // source for text part of polygon[s]
    static final public String id_name = "_id";


    public static MLGeometryEditClass createEditObject(
            int geoType,
            GeoJsonSource selectedEditedSource,
            Feature editingFeature,
            List<Feature> polygonFeatures,
            GeoJsonSource choosedSource, // source of point/ line / polygon
            GeoJsonSource vertexSource,
            GeoJsonSource markerSource,
            final String  layerPath) {

        if (geoType == GTPolygon) {
            return new PolygonEditClass(geoType, selectedEditedSource, editingFeature, polygonFeatures,
                    choosedSource, vertexSource, markerSource, layerPath);
        } else if (geoType == GeoConstants.GTPoint) {
            return new PointEditClass(geoType, selectedEditedSource, editingFeature, polygonFeatures,
                    choosedSource, vertexSource, markerSource, layerPath);
        } else if (geoType == GeoConstants.GTMultiPoint) {
            return new MultiPointEditClass(geoType, selectedEditedSource, editingFeature, polygonFeatures,
                    choosedSource, vertexSource, markerSource, layerPath);
        } else if (geoType == GeoConstants.GTLineString) {
            return new LineEditClass(geoType, selectedEditedSource, editingFeature, polygonFeatures,
                    choosedSource, vertexSource, markerSource, layerPath);
        } else if (geoType == GeoConstants.GTMultiLineString) {
            return new MultiLineEditClass(geoType, selectedEditedSource, editingFeature, polygonFeatures,
                    choosedSource, vertexSource, markerSource, layerPath);
        } else if (geoType == GeoConstants.GTMultiPolygon) {
            return new MultiPolygonEditClass(geoType, selectedEditedSource, editingFeature, polygonFeatures,
                    choosedSource, vertexSource, markerSource, layerPath);
        }
        else if (geoType == GeoConstants.GT_MEASURMENT) {
            return new MeasurmentLine(geoType, selectedEditedSource, editingFeature, polygonFeatures,
                    choosedSource, vertexSource, markerSource, layerPath);
        }
        else
            return null;
    }


    static public String getLayerSignatureField(final VectorLayer layer){
        com.nextgis.maplib.display.Style style = layer.getDefaultStyleNoExcept();
        if (style!=null){
            String styleField = ((ITextStyle)style).getField();
            return TextUtils.isEmpty(styleField)? null : styleField;
        }
        return null;
    }

    static public List<org.maplibre.geojson.Feature> createFeatureListFromTrackLayer(final TrackLayer layer) {
        Map<Integer, GeoLineString> tracks = layer.getTracks();
        List<org.maplibre.geojson.Feature> lineFeatures = new ArrayList<>();

        for (Map.Entry<Integer, GeoLineString> entry : tracks.entrySet()) {
            Integer id = entry.getKey();
            LineString lineString = getLineString(entry.getValue());
            Feature lineFeature = org.maplibre.geojson.Feature.fromGeometry(lineString);
            lineFeature.addStringProperty(prop_layerid, String.valueOf(layer.getId()));
            lineFeatures.add(lineFeature);
        }
        return lineFeatures;
    }

    /** Start/end flag markers disabled — track line only. Kept for API compatibility. */
    @SuppressWarnings("unused")
    static public List<org.maplibre.geojson.Feature> createFeatureListFlagsFromTrackLayer(final TrackLayer layer) {
        return new ArrayList<>();
    }

    static public List<org.maplibre.geojson.Feature> createFeatureListFromLayer(final VectorLayer layer) {
        List<org.maplibre.geojson.Feature> vectorFeatures = new ArrayList<>();

        String signatureField =  getLayerSignatureField(layer);
        com.nextgis.maplib.display.Style layerStyle = layer.getDefaultStyleNoExcept();
        String styleField = ((ITextStyle) layerStyle).getField();
        String commonText = ((ITextStyle) layerStyle).getText();
        LabelAttributes labelAttributes = LabelAttributes.fromStyle(layerStyle);

        boolean needSignatures = false;
        if (layer.getRenderer() instanceof RuleFeatureRenderer ||
                !TextUtils.isEmpty(styleField) || !TextUtils.isEmpty(commonText)
                || LabelTemplate.hasTemplate(labelAttributes.getLabelTemplate())) {
            needSignatures = true;
        }

        if (layer.getGeometryType() == GeoConstants.GTPoint) {
            return getPointFeatures(layer,signatureField, needSignatures, commonText);
        }

        if (layer.getGeometryType() == GeoConstants.GTMultiPoint) {
            return getMultiPointFeatures(layer,signatureField, needSignatures, commonText);
        }

        if (layer.getGeometryType() == GeoConstants.GTLineString) {
            return getLineFeatures(layer,signatureField, needSignatures, commonText);
        }

        if (layer.getGeometryType() == GeoConstants.GTMultiLineString) {
            return getMultiLineFeatures(layer,signatureField, needSignatures, commonText);
        }

        if (layer.getGeometryType() == GTPolygon) {
            return getPolygonFeatures(layer,signatureField, needSignatures, commonText);
        }

        if (layer.getGeometryType() == GeoConstants.GTMultiPolygon) {
            return getMultiPolygonFeatures(layer,signatureField, needSignatures, commonText);
        }
        return vectorFeatures;
    }

    private static List<Feature> getLineFeatures(VectorLayer layer, String signatureField,
                                                 boolean needSignatures, String commonText){
        boolean ruleStyle =  layer.getRenderer() instanceof RuleFeatureRenderer;

        List<org.maplibre.geojson.Feature> lineFeatures = new ArrayList<>();
        Map<Long, com.nextgis.maplib.datasource.Feature> features = layer.getFeatures();
        int i = 0;
        Iterator<Map.Entry<Long, com.nextgis.maplib.datasource.Feature>> iterator = features.entrySet().iterator();

        while (iterator.hasNext()){
            Map.Entry<Long, com.nextgis.maplib.datasource.Feature> entry = iterator.next();
            i++;
            Long id = entry.getKey();
            com.nextgis.maplib.datasource.Feature feature = entry.getValue();
            GeoLineString geoLineGeometry = (GeoLineString) feature.getGeometry();
            LineString lineString = getLineString(geoLineGeometry);
            Feature lineFeature = org.maplibre.geojson.Feature.fromGeometry(lineString);
            lineFeature.addStringProperty(prop_layerid, String.valueOf(layer.getId()));
            lineFeature.addStringProperty(prop_order, String.valueOf(i));
            lineFeature.addStringProperty(prop_featureid, String.valueOf(id));
            lineFeature.addStringProperty(prop_color, colorBlue);

            applyTextAndStyle(
                    layer,
                    feature,
                    lineFeature,
                    GTLineString,
                    ruleStyle,
                    needSignatures,
                    signatureField,
                    commonText  );
            lineFeatures.add(lineFeature);
            iterator.remove();
        }
        return lineFeatures;
    }

    private static List<Feature> getMultiLineFeatures(VectorLayer layer, String signatureField,
                                                      boolean needSignatures, String commonText){

        boolean ruleStyle = false;
        if (layer.getRenderer() instanceof RuleFeatureRenderer) { // feature render
            ruleStyle = true;
        }

        List<org.maplibre.geojson.Feature> lineFeatures = new ArrayList<>();
        Map<Long, com.nextgis.maplib.datasource.Feature> features = layer.getFeatures();
        int i = 0;
        Iterator<Map.Entry<Long, com.nextgis.maplib.datasource.Feature>> iterator = features.entrySet().iterator();

        while (iterator.hasNext()){
            Map.Entry<Long, com.nextgis.maplib.datasource.Feature> entry = iterator.next();
            List<LineString> linesArray = new ArrayList<>();
            i++;
            Long id = entry.getKey();
            com.nextgis.maplib.datasource.Feature feature = entry.getValue();
            GeoMultiLineString geoMultiLineString = (GeoMultiLineString) feature.getGeometry();
            for (int j = 0; j < geoMultiLineString.size(); j++) {
                GeoLineString geoLineString = geoMultiLineString.get(j);
                LineString lineString = getLineString(geoLineString);
                linesArray.add(lineString);
            }
            MultiLineString multiLineString = MultiLineString.fromLineStrings(linesArray);
            Feature lineFeature = Feature.fromGeometry(multiLineString);
            lineFeature.addStringProperty(prop_layerid, String.valueOf(layer.getId()));
            lineFeature.addStringProperty(prop_order, String.valueOf(i));
            lineFeature.addStringProperty(prop_featureid, String.valueOf(id));
            lineFeature.addStringProperty(prop_color, colorBlue);

            applyTextAndStyle(
                    layer,
                    feature,
                    lineFeature,
                    GTMultiLineString,
                    ruleStyle,
                    needSignatures,
                    signatureField,
                    commonText );
            lineFeatures.add(lineFeature);
            iterator.remove();
        }
        return lineFeatures;
    }


    private static List<Feature> getPointFeatures(VectorLayer layer, String signatureField,
                                                  boolean needSignatures, String commonText){
        boolean ruleStyle = layer.getRenderer() instanceof RuleFeatureRenderer;

        List<org.maplibre.geojson.Feature> pointFeatures = new ArrayList<>();
        Map<Long, com.nextgis.maplib.datasource.Feature> features = layer.getFeatures();
        int i = 0;
        Iterator<Map.Entry<Long, com.nextgis.maplib.datasource.Feature>> iterator = features.entrySet().iterator();

        while (iterator.hasNext()){
            Map.Entry<Long, com.nextgis.maplib.datasource.Feature> entry = iterator.next();

            i++;
            Long id = entry.getKey();
            com.nextgis.maplib.datasource.Feature feature = entry.getValue();
            GeoPoint geoPointGeometry = (GeoPoint) feature.getGeometry();
            if (geoPointGeometry == null)
                continue;
            double[] lonLat = convert3857To4326(geoPointGeometry.getX(), geoPointGeometry.getY());
            Point point = Point.fromLngLat(lonLat[0], lonLat[1]);
            Feature pointFeature = org.maplibre.geojson.Feature.fromGeometry(point);
            pointFeature.addStringProperty(prop_layerid, String.valueOf(layer.getId()));
            pointFeature.addStringProperty(prop_order, String.valueOf(i));
            pointFeature.addStringProperty(prop_featureid, String.valueOf(id));

            applyTextAndStyle(
                    layer,
                    feature,
                    pointFeature,
                    GTPoint,
                    ruleStyle,
                    needSignatures,
                    signatureField,
                    commonText );

            pointFeatures.add(pointFeature);
            iterator.remove();
        }
        return pointFeatures;
    }

    private static List<Feature> getMultiPointFeatures(VectorLayer layer, String signatureField,
                                                       boolean needSignatures, String commonText) {
        boolean ruleStyle = layer.getRenderer() instanceof RuleFeatureRenderer;

        List<org.maplibre.geojson.Feature> mpointFeatures = new ArrayList<>();
        Map<Long, com.nextgis.maplib.datasource.Feature> features = layer.getFeatures();
        int i = 0;
        Iterator<Map.Entry<Long, com.nextgis.maplib.datasource.Feature>> iterator = features.entrySet().iterator();
        while (iterator.hasNext()){
            Map.Entry<Long, com.nextgis.maplib.datasource.Feature> entry = iterator.next();
            i++;
            Long id = entry.getKey();
            com.nextgis.maplib.datasource.Feature feature = entry.getValue();
            GeoMultiPoint geoMultiPointtGeometry = (GeoMultiPoint) feature.getGeometry();
            List<Point> pointList = new ArrayList<>();
            for (int j = 0; j < geoMultiPointtGeometry.size(); j++) {
                GeoPoint geoPointGeometry = (GeoPoint) geoMultiPointtGeometry.get(j);
                double[] lonLat = convert3857To4326(geoPointGeometry.getX(), geoPointGeometry.getY());
                Point point = Point.fromLngLat(lonLat[0], lonLat[1]);
                pointList.add(point);
            }
            MultiPoint multiPoint = MultiPoint.fromLngLats(pointList);
            Feature mpointFeature = org.maplibre.geojson.Feature.fromGeometry(multiPoint);
            mpointFeature.addStringProperty(prop_layerid, String.valueOf(layer.getId()));
            mpointFeature.addStringProperty(prop_order, String.valueOf(i));
            mpointFeature.addStringProperty(prop_featureid, String.valueOf(id));

            applyTextAndStyle(
                    layer,
                    feature,
                    mpointFeature,
                    GTMultiPoint,
                    ruleStyle,
                    needSignatures,
                    signatureField,
                    commonText
            );

            mpointFeatures.add(mpointFeature);
            iterator.remove();
        }
        return mpointFeatures;
    }


    static public List<Feature> getPolygonFeatures(final VectorLayer layer, String signatureField,
                                                   boolean needSignatures, String commonText){
        boolean ruleStyle = layer.getRenderer() instanceof RuleFeatureRenderer;

        List<org.maplibre.geojson.Feature> vectorFeatures = new ArrayList<>();
        Map<Long, com.nextgis.maplib.datasource.Feature> features = layer.getFeatures();
        int i = 0;
        Iterator<Map.Entry<Long, com.nextgis.maplib.datasource.Feature>> iterator = features.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, com.nextgis.maplib.datasource.Feature> entry = iterator.next();
            i++;
            Long id = entry.getKey();
            com.nextgis.maplib.datasource.Feature feature = entry.getValue();

            GeoPolygon geoPolygonGeometry = (GeoPolygon) feature.getGeometry();
            org.maplibre.geojson.Feature polyFeature = getFeatureFromNGFeaturePolygon(geoPolygonGeometry);
            polyFeature.addStringProperty(prop_layerid, String.valueOf(layer.getId()));
            polyFeature.addStringProperty(prop_order, String.valueOf(i));
            polyFeature.addStringProperty(prop_featureid, String.valueOf(id));

            applyTextAndStyle(
                    layer,
                    feature,
                    polyFeature,
                    GTPolygon,
                    ruleStyle,
                    needSignatures,
                    signatureField,
                    commonText );

            vectorFeatures.add(polyFeature);
            iterator.remove();  // free immediately
        }
        return vectorFeatures;
    }

    static public List<Feature> getMultiPolygonFeatures(final VectorLayer layer, String signatureField,
                                                        boolean needSignatures, String commonText){

        boolean ruleStyle = layer.getRenderer() instanceof RuleFeatureRenderer;

        List<org.maplibre.geojson.Feature> vectorFeatures = new ArrayList<>();
        Map<Long, com.nextgis.maplib.datasource.Feature> features = layer.getFeatures();
        int i = 0;
        Iterator<Map.Entry<Long, com.nextgis.maplib.datasource.Feature>> iterator = features.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<Long, com.nextgis.maplib.datasource.Feature> entry = iterator.next();
            i++;
            Long id = entry.getKey();
            com.nextgis.maplib.datasource.Feature feature = entry.getValue();
            GeoGeometryCollection geoGeometryCollection = (GeoGeometryCollection) feature.getGeometry();
            ArrayList<Polygon> polygons = new ArrayList<>();
            for (int j = 0; j < geoGeometryCollection.size(); j++) {
                GeoPolygon polygonNG = (GeoPolygon) geoGeometryCollection.getGeometry(j);
                Polygon polygonML = getPolygonSeparFromNGFeaturePolygon(polygonNG);
                polygons.add(polygonML);
            }
            MultiPolygon multiPolygon = MultiPolygon.fromPolygons(polygons);
            org.maplibre.geojson.Feature mpolyFeature = Feature.fromGeometry(multiPolygon);
            mpolyFeature.addStringProperty(prop_layerid, String.valueOf(layer.getId()));
            mpolyFeature.addStringProperty(prop_order, String.valueOf(i));
            mpolyFeature.addStringProperty(prop_featureid, String.valueOf(id));
            if (signatureField != null) {
                mpolyFeature.addStringProperty(prop_signature_text, getSpaceCorrectedText(entry.getValue().getFieldValueAsString(signatureField)));
            }

            applyTextAndStyle(
                    layer,
                    feature,
                    mpolyFeature,
                    GTMultiPolygon,
                    ruleStyle,
                    needSignatures,
                    signatureField,
                    commonText);

            vectorFeatures.add(mpolyFeature);
            iterator.remove();
        }
        return vectorFeatures;
    }

    public static void applyTextAndStyle(
            VectorLayer layer,
            com.nextgis.maplib.datasource.Feature ngFeature,
            Feature feature,
            int geoType,
            boolean ruleStyle,
            boolean needSignatures,
            String signatureField,
            String commonText) {
        if (ruleStyle) {
            applyRuleStyleInternal(
                            layer,
                            ngFeature,
                            feature,
                            geoType,
                            commonText );
            return;
        }

        if (geoType == GTPolygon || geoType == GTMultiPolygon) {
            com.nextgis.maplib.display.Style defaultStyle = layer.getDefaultStyleNoExcept();
            if (defaultStyle != null) {
                MplFeatureStyleProps.apply(defaultStyle, feature, geoType);
            }
        }

        if (needSignatures) {
            // Layer SymbolLayer owns text size/color; drop stale per-feature props from cache.
            MplFeatureStyleProps.clearTextProps(feature);
            String signatureText = resolveSignatureText(
                    ngFeature,
                    signatureField,
                    commonText,
                    LabelAttributes.fromStyle(layer.getDefaultStyleNoExcept()));
            if (!TextUtils.isEmpty(signatureText)) {
                feature.addStringProperty(prop_signature_text, signatureText);
            }
        }
    }

    /**
     * Whether a style-only reload must refresh GeoJSON feature properties (not just MapLibre layers).
     */
    public static boolean needsSourceStyleRefresh(VectorLayer layer) {
        if (layer.getRenderer() instanceof RuleFeatureRenderer) {
            return true;
        }
        com.nextgis.maplib.display.Style layerStyle = layer.getDefaultStyleNoExcept();
        if (layerStyle == null) {
            return false;
        }
        String styleField = ((ITextStyle) layerStyle).getField();
        String commonText = ((ITextStyle) layerStyle).getText();
        LabelAttributes labelAttributes = LabelAttributes.fromStyle(layerStyle);
        return !TextUtils.isEmpty(styleField)
                || !TextUtils.isEmpty(commonText)
                || LabelTemplate.hasTemplate(labelAttributes.getLabelTemplate());
    }

    /**
     * Strip MapLibre style/signature props, keeping geometry and stable ids for geometry cache.
     */
    public static List<Feature> toGeometryShells(List<Feature> features, int geoType) {
        List<Feature> shells = new ArrayList<>(features.size());
        for (Feature feature : features) {
            if (feature == null || feature.geometry() == null) {
                continue;
            }
            Feature shell = Feature.fromGeometry(feature.geometry());
            copyStableFeatureIds(feature, shell);
            shells.add(shell);
        }
        return shells;
    }

    private static void copyStableFeatureIds(Feature from, Feature to) {
        String layerId = from.getStringProperty(prop_layerid);
        if (layerId != null) {
            to.addStringProperty(prop_layerid, layerId);
        }
        String order = from.getStringProperty(prop_order);
        if (order != null) {
            to.addStringProperty(prop_order, order);
        }
        String featureId = from.getStringProperty(prop_featureid);
        if (featureId != null) {
            to.addStringProperty(prop_featureid, featureId);
        }
    }

    /**
     * Re-apply rule/signature props onto features that already carry geometry (no SQLite geom scan).
     */
    public static void refreshMaplibreStyleOnFeatures(VectorLayer layer, List<Feature> features) {
        if (layer == null || features == null || features.isEmpty()) {
            return;
        }
        int geoType = layer.getGeometryType();
        String signatureField = getLayerSignatureField(layer);
        com.nextgis.maplib.display.Style layerStyle = layer.getDefaultStyleNoExcept();
        String styleField = layerStyle != null ? ((ITextStyle) layerStyle).getField() : null;
        String commonText = layerStyle != null ? ((ITextStyle) layerStyle).getText() : null;
        LabelAttributes labelAttributes = LabelAttributes.fromStyle(layerStyle);
        boolean ruleStyle = layer.getRenderer() instanceof RuleFeatureRenderer;
        boolean needSignatures = ruleStyle
                || !TextUtils.isEmpty(styleField)
                || !TextUtils.isEmpty(commonText)
                || LabelTemplate.hasTemplate(labelAttributes.getLabelTemplate());
        String effectiveSignatureField = !TextUtils.isEmpty(styleField) ? styleField : signatureField;

        for (Feature feature : features) {
            if (feature == null) {
                continue;
            }
            String featureId = feature.getStringProperty(prop_featureid);
            if (TextUtils.isEmpty(featureId)) {
                continue;
            }
            com.nextgis.maplib.datasource.Feature ngFeature;
            try {
                ngFeature = layer.getFeature(Long.parseLong(featureId));
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (ngFeature == null) {
                continue;
            }
            feature.removeProperty(prop_signature_text);
            MplFeatureStyleProps.clear(feature, geoType);
            applyTextAndStyle(
                    layer,
                    ngFeature,
                    feature,
                    geoType,
                    ruleStyle,
                    needSignatures,
                    effectiveSignatureField,
                    commonText);
        }
    }

    @Nullable
    private static String resolveSignatureText(
            com.nextgis.maplib.datasource.Feature ngFeature,
            @Nullable String field,
            @Nullable String commonText,
            LabelAttributes labelAttributes) {
        if (labelAttributes != null && LabelTemplate.hasTemplate(labelAttributes.getLabelTemplate())) {
            return getSpaceCorrectedText(
                    LabelTemplate.resolve(labelAttributes.getLabelTemplate(), ngFeature));
        }
        if (!TextUtils.isEmpty(field)) {
            String text = "_id".equals(field)
                    ? String.valueOf(ngFeature.getId())
                    : getNullableValue(ngFeature, field);
            return getSpaceCorrectedText(text);
        }
        if (!TextUtils.isEmpty(commonText)) {
            return getSpaceCorrectedText(commonText);
        }
        return null;
    }


    /**
     * MapLibre text-size for the layer symbol. For simple renderer the size comes only from layer
     * style; for rule renderer per-feature {@link #prop_text_textsize} may override the default.
     * Zoom scaling must wrap the size expression (not sit inside coalesce) so ["zoom"] is evaluated.
     */
    private static Expression buildTextSizeExpression(
            float textSizeNg,
            LabelAttributes labelAttributes,
            boolean ruleStyle) {
        float baseSize = (textSizeNg + 3) * 3;
        Expression sizeExpr = ruleStyle
                ? Expression.coalesce(
                Expression.get(prop_text_textsize),
                Expression.literal(baseSize))
                : Expression.literal(baseSize);

        if (ruleStyle) {
            boolean defaultScaleWithZoom = labelAttributes != null
                    && labelAttributes.isTextScaleWithZoom();
            return MplStyleMapper.zoomScaleExpression(
                    sizeExpr,
                    Expression.get(prop_text_scale_with_zoom),
                    defaultScaleWithZoom,
                    labelAttributes != null ? labelAttributes.getTextZoomScaleStops() : null);
        }

        if (labelAttributes != null && labelAttributes.isTextScaleWithZoom()) {
            return MplStyleMapper.zoomScaleExpression(
                    sizeExpr,
                    true,
                    labelAttributes.getTextZoomScaleStops());
        }
        return sizeExpr;
    }

    private static void applyRuleStyleInternal(
            VectorLayer layer,
            com.nextgis.maplib.datasource.Feature ngFeature,
            Feature feature,
            int geoType,
            String commonText  ) {
        RuleFeatureRenderer rfr =  (RuleFeatureRenderer) layer.getRenderer();

        FieldStyleRule fsr =  (FieldStyleRule) rfr.getStyleRule();

        String key = fsr.getKey();
        boolean isIdKey = "_id".equals(key);

        String keyValue = isIdKey   ? String.valueOf(ngFeature.getId()): getNullableValue(ngFeature, key);
        com.nextgis.maplib.display.Style style = fsr.resolveEffectiveStyle(keyValue, rfr.getStyle());

        if (style != null) {
            String ruleCommonText = style.getText();
            applyText(style, ngFeature, feature, ruleCommonText != null? ruleCommonText : commonText);
            MplFeatureStyleProps.apply(style, feature, geoType);
        }
    }

    private static void applyText(
            com.nextgis.maplib.display.Style style,
            com.nextgis.maplib.datasource.Feature ngFeature,
            Feature feature,
            String commonText){
        String field = ((ITextStyle) style).getField();
        String signatureText = resolveSignatureText(
                ngFeature,
                field,
                commonText,
                LabelAttributes.fromStyle(style));
        if (!TextUtils.isEmpty(signatureText)) {
            feature.addStringProperty(prop_signature_text, signatureText);
        }
    }


    static public LineString getLineString(GeoLineString geoLineGeometry) {
        List<Point> pointList = new ArrayList<>();
        for (int j = 0; j < geoLineGeometry.getPointCount(); j++) {
            GeoPoint geoPointGeometry = (GeoPoint) geoLineGeometry.getPoint(j);
            double[] lonLat = convert3857To4326(geoPointGeometry.getX(), geoPointGeometry.getY());
            Point point = Point.fromLngLat(lonLat[0], lonLat[1]);
            pointList.add(point);
        }
        return LineString.fromLngLats(pointList);
    }


    public static org.maplibre.geojson.Feature getFeatureFromNGFeatureMultiLine(GeoMultiLineString geoLineGeometry) {
        List<List<Point>> mline = new ArrayList<>();
        for (int j = 0; j < geoLineGeometry.size(); j++) {
            GeoLineString lineitem = geoLineGeometry.get(j);
            List<Point> points = new ArrayList<>();
            for (GeoPoint item : lineitem.getPoints()) {
                double[] lonLat = convert3857To4326(item.getX(), item.getY());
                points.add(Point.fromLngLat(lonLat[0], lonLat[1]));
            }
            mline.add(points);
        }
        return org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.MultiLineString.fromLngLats(mline));
    }

    public static org.maplibre.geojson.Feature getFeatureFromNGFeatureLine(GeoLineString geoLineGeometry) {
        List<Point> points = new ArrayList<>();
        for (GeoPoint item : geoLineGeometry.getPoints()) {
            double[] lonLat = convert3857To4326(item.getX(), item.getY());
            points.add(Point.fromLngLat(lonLat[0], lonLat[1]));
        }
        return org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.LineString.fromLngLats(points));
    }

    public static org.maplibre.geojson.Feature getFeatureFromNGFeaturePolygon(GeoPolygon geoPolygonGeometry) {
        List<List<Point>> points = new ArrayList<>();
        List<Point> outerRing = new ArrayList<>();
        for (GeoPoint item : geoPolygonGeometry.getOuterRing().getPoints()) {
            double[] lonLat = convert3857To4326(item.getX(), item.getY());
            outerRing.add(Point.fromLngLat(lonLat[0], lonLat[1]));
        }
        points.add(outerRing);
        for (GeoLinearRing innerRing : geoPolygonGeometry.getInnerRings()) {
            List<Point> newInnerRing = new ArrayList<>();
            for (GeoPoint itemPoint : innerRing.getPoints()) {
                double[] lonLat = convert3857To4326(itemPoint.getX(), itemPoint.getY());
                newInnerRing.add(Point.fromLngLat(lonLat[0], lonLat[1]));
            }
            points.add(newInnerRing);
        }
        return org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.Polygon.fromLngLats(points));
    }

    public static org.maplibre.geojson.Feature getFeatureFromNGFeatureMultiPolygon(GeoMultiPolygon geoPolygonGeometry) {
        ArrayList<Polygon> polygons = new ArrayList<>();
        for (int j = 0; j < geoPolygonGeometry.size(); j++) {
            GeoPolygon polygonNG = (GeoPolygon) geoPolygonGeometry.getGeometry(j);
            Polygon polygonML = getPolygonSeparFromNGFeaturePolygon(polygonNG);
            polygons.add(polygonML);
        }
        MultiPolygon multiPolygon = MultiPolygon.fromPolygons(polygons);
        return org.maplibre.geojson.Feature.fromGeometry(multiPolygon);
    }

    public static org.maplibre.geojson.Feature getFeatureFromNGFeaturePoint(GeoPoint geoPointGeometry) {
        double[] lonLat = convert3857To4326(geoPointGeometry.getX(), geoPointGeometry.getY());
        Point point = Point.fromLngLat(lonLat[0], lonLat[1]);
        return org.maplibre.geojson.Feature.fromGeometry(point);
    }

    public static org.maplibre.geojson.Feature getFeatureFromNGFeatureMultiPoint(GeoMultiPoint geoGeometry) {
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < geoGeometry.size(); i++) {
            GeoPoint geoPoint = geoGeometry.get(i);
            double[] lonLat = convert3857To4326(geoPoint.getX(), geoPoint.getY());
            Point point = Point.fromLngLat(lonLat[0], lonLat[1]);
            points.add(point);
        }
        MultiPoint multiPoint = MultiPoint.fromLngLats(points);
        return org.maplibre.geojson.Feature.fromGeometry(multiPoint);
    }

    public static org.maplibre.geojson.Feature getPolygonFromNGFeaturePolygon(GeoPolygon geoPolygonGeometry) {
        List<List<Point>> points = new ArrayList<>();
        List<Point> outerRing = new ArrayList<>();
        for (GeoPoint item : geoPolygonGeometry.getOuterRing().getPoints()) {
            double[] lonLat = convert3857To4326(item.getX(), item.getY());
            outerRing.add(Point.fromLngLat(lonLat[0], lonLat[1]));
        }
        points.add(outerRing);
        for (GeoLinearRing innerRing : geoPolygonGeometry.getInnerRings()) {
            List<Point> newInnerRing = new ArrayList<>();
            for (GeoPoint itemPoint : innerRing.getPoints()) {
                double[] lonLat = convert3857To4326(itemPoint.getX(), itemPoint.getY());
                newInnerRing.add(Point.fromLngLat(lonLat[0], lonLat[1]));
            }
            points.add(newInnerRing);
        }
        return org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.Polygon.fromLngLats(points));
    }

    public static org.maplibre.geojson.Polygon getPolygonSeparFromNGFeaturePolygon(GeoPolygon geoPolygonGeometry) {
        List<List<Point>> points = new ArrayList<>();
        List<Point> outerRing = new ArrayList<>();
        for (GeoPoint item : geoPolygonGeometry.getOuterRing().getPoints()) {
            double[] lonLat = convert3857To4326(item.getX(), item.getY());
            outerRing.add(Point.fromLngLat(lonLat[0], lonLat[1]));
        }
        points.add(outerRing);
        for (GeoLinearRing innerRing : geoPolygonGeometry.getInnerRings()) {
            List<Point> newInnerRing = new ArrayList<>();
            for (GeoPoint itemPoint : innerRing.getPoints()) {
                double[] lonLat = convert3857To4326(itemPoint.getX(), itemPoint.getY());
                newInnerRing.add(Point.fromLngLat(lonLat[0], lonLat[1]));
            }
            points.add(newInnerRing);
        }
        return org.maplibre.geojson.Polygon.fromLngLats(points);
    }

    public static org.maplibre.geojson.Feature getFeatureFromNGFeature(GeoGeometry ngGeometry) {
        switch (ngGeometry.getType()){
            case GTPoint: return getFeatureFromNGFeaturePoint((GeoPoint) ngGeometry);
            case GTMultiPoint: return getFeatureFromNGFeatureMultiPoint((GeoMultiPoint) ngGeometry);
            case GTLineString: return getFeatureFromNGFeatureLine((GeoLineString) ngGeometry);
            case GTMultiLineString: return getFeatureFromNGFeatureMultiLine((GeoMultiLineString) ngGeometry);
            case GTPolygon: return getPolygonFromNGFeaturePolygon((GeoPolygon) ngGeometry);
            case GTMultiPolygon: return getFeatureFromNGFeatureMultiPolygon((GeoMultiPolygon) ngGeometry);
            default: return null;

        }
    }


    static public double[] convert3857To4326(double x, double y) {
        double lon = x * 180 / 20037508.34;
        double lat = Math.toDegrees(Math.atan(Math.sinh(y * Math.PI / 20037508.34)));
        return new double[]{lon, lat};
    }

    static public double[] convert4326To3857(double lon, double lat) {
        double x = lon * 20037508.34 / 180;
        double y = Math.log(Math.tan(Math.PI / 4 + Math.toRadians(lat) / 2)) * 20037508.34 / Math.PI;
        return new double[]{x, y};
    }

    static GeoPoint geoPointFromMaplibrePoint(Point point){
        double[] centerPoints = convert4326To3857(point.longitude(), point.latitude());
        return new GeoPoint(centerPoints[0], centerPoints[1]);
    }

    static public GeoPoint geoPointFromLatLng(LatLng latLng){
        double[] centerPoints = convert4326To3857(latLng.getLongitude(), latLng.getLatitude());
        return new GeoPoint(centerPoints[0], centerPoints[1]);
    }

    static public  LatLng latLngPointFromGeoPoint(GeoPoint gePoint){
        double[] lonLat = convert3857To4326(gePoint.getX(), gePoint.getY());
        return new LatLng(lonLat[1], lonLat[0]);
    }


    static public void createSourceForLayer(int layerId,
                                            int layerType,
                                            final List<org.maplibre.geojson.Feature> layerFeatures,
                                            final Style style,
                                            Map<String, GeoJsonSource> sourceHashMap,
                                            Map<Integer, String> rasterLayersURL,
                                            Map<Integer, Integer> rasterLayersTmsTypeMap,
                                            String layerPath,
                                            boolean forceCreate,
                                            java.net.URI fileUri) {
        if (layerType == GT_TRACK_WA){
            return;
        }

        if (layerType == GT_RASTER_WA){
                Source rasterExisting = style.getSource(layerPath);
                RasterSource rasterSource = (rasterExisting instanceof RasterSource)
                        ? (RasterSource) rasterExisting : null;
                if (rasterExisting != null && rasterSource == null) {
                    // Wrong-kind source left from a previous layer type; drop so the cast is safe.
                    style.removeSource(layerPath);
                }
                if (rasterSource != null && rasterSource.getUrl()!= null &&  !rasterSource.getUrl().equals(rasterLayersURL.get(layerId))){
                    style.removeSource(layerPath);
                    rasterSource = null;
                }
                if (rasterSource == null || forceCreate) {

                    String url = rasterLayersURL.get(layerId);
                    if (url == null) {
                        Log.w("Mbgl", "createSourceForLayer: missing raster URL layer id=" + layerId
                                + " path=" + layerPath);
                        return;
                    }
                    if (url.contains("{q}")){
                        // replace for zxy scheme
                        url = url.replace("{q}", "quadtiles{z}/{x}/{y}");
                    }

                    TileSet tileSet = new TileSet(
                            "tileset",
                            url);

                    Integer tileTmsType =rasterLayersTmsTypeMap.get(layerId);

                    if ( tileTmsType != null && tileTmsType != -1){
                        if (tileTmsType == TMSTYPE_NORMAL) {
                            tileSet.setScheme( "tms");
                        }
                        if (tileTmsType == TMSTYPE_OSM) {
                            tileSet.setScheme("xyz");
                        }
                    }

                    rasterSource = new RasterSource(layerPath,tileSet, 256 );
                    style.addSource(rasterSource);
                }
            return;
        }

        boolean addPolyTextSource = false;
        if (layerType == GTPolygon || layerType == GTMultiPolygon){
            addPolyTextSource = true;
        }

        Source vectorExisting = style.getSource(layerPath);
        if (vectorExisting != null && !(vectorExisting instanceof GeoJsonSource)) {
            // Wrong-kind source left from a previous layer type; drop so the cast is safe.
            style.removeSource(layerPath);
            vectorExisting = null;
        }
        GeoJsonSource vectorSource = (GeoJsonSource) vectorExisting;
        boolean useNativeUri = fileUri != null
                && layerFeatures != null
                && !layerFeatures.isEmpty();
        if (vectorSource == null) {
            if (useNativeUri) {
                try {
                    vectorSource = new GeoJsonSource(layerPath, fileUri);
                } catch (Exception uriEx) {
                    Log.w("Mbgl", "createSourceForLayer: native URI failed, fallback to GeoJson "
                            + layerPath + " " + uriEx.getMessage());
                    vectorSource = new GeoJsonSource(layerPath,
                            FeatureCollection.fromFeatures(layerFeatures));
                }
            } else {
                vectorSource = new GeoJsonSource(layerPath, FeatureCollection.fromFeatures(layerFeatures));
            }
            style.addSource(vectorSource);
        } else {
            if (useNativeUri) {
                try {
                    vectorSource.setUri(fileUri);
                } catch (Exception uriEx) {
                    Log.w("Mbgl", "createSourceForLayer: setUri failed, fallback to GeoJson "
                            + layerPath + " " + uriEx.getMessage());
                    vectorSource.setGeoJson(FeatureCollection.fromFeatures(layerFeatures));
                }
            } else {
                vectorSource.setGeoJson(FeatureCollection.fromFeatures(layerFeatures));
            }
        }

        sourceHashMap.put(layerPath, vectorSource);

        if (addPolyTextSource){

            List<Feature> points =  convertToPointFeatures(layerFeatures);

            Source vectorTextExisting = style.getSource(layerPath + source_polygon_text);
            if (vectorTextExisting != null && !(vectorTextExisting instanceof GeoJsonSource)) {
                style.removeSource(layerPath + source_polygon_text);
                vectorTextExisting = null;
            }
            GeoJsonSource vectorTextSource = (GeoJsonSource) vectorTextExisting;
            if (vectorTextSource == null) {
                vectorTextSource = new GeoJsonSource(layerPath + source_polygon_text, FeatureCollection.fromFeatures(points));
                Log.d("Mbgl", "create source for: " + layerPath + source_polygon_text);
                style.addSource(vectorTextSource);

            }
            else
                vectorTextSource.setGeoJson(FeatureCollection.fromFeatures(points));
            sourceHashMap.put(layerPath + source_polygon_text, vectorTextSource);
        }
    }

    static public boolean createLocalVectorTileSourceForLayer(
            int layerId,
            final Style style,
            String layerPath,
            String tileUrl,
            float minZoom,
            float maxZoom) {
        if (style == null || TextUtils.isEmpty(layerPath) || TextUtils.isEmpty(tileUrl)) {
            return false;
        }
        Source existing = style.getSource(layerPath);
        if (existing != null && !(existing instanceof VectorSource)) {
            style.removeSource(layerPath);
            existing = null;
        }
        if (existing == null) {
            TileSet tileSet = new TileSet("2.2.0", tileUrl);
            tileSet.setScheme("xyz");
            if (minZoom >= 0f) {
                tileSet.setMinZoom(minZoom);
            }
            if (maxZoom >= 0f) {
                tileSet.setMaxZoom(maxZoom);
            }
            style.addSource(new VectorSource(layerPath, tileSet));
            Log.d("Mbgl", "create local vector tile source: " + layerPath + " url=" + tileUrl);
        }
        return true;
    }

    static public void createFillLayerForLocalVectorTileLayer(
            int layerId,
            int layerType,
            final Style style,
            Map<Integer, org.maplibre.android.style.layers.Layer> layersHashMap,
            Map<Integer, org.maplibre.android.style.layers.Layer> layersHashMap2,
            Map<Integer, org.maplibre.android.style.layers.Layer> symbolsLayerHashMap,
            @Nullable com.nextgis.maplib.display.Style layerStyle,
            ILayer iLayer,
            String layerPath,
            org.maplibre.android.style.layers.Layer signaturesRootLayer) {
        if (style == null || TextUtils.isEmpty(layerPath)) {
            return;
        }
        if (layerType != GTPolygon && layerType != GTMultiPolygon) {
            return;
        }

        float minZoom = -1;
        float maxZoom = -1;
        if (iLayer != null) {
            minZoom = ((com.nextgis.maplib.map.Layer) iLayer).getMinZoom();
            maxZoom = ((com.nextgis.maplib.map.Layer) iLayer).getMaxZoom();
        }
        float layerOpacityFactor = 1f;
        if (iLayer instanceof com.nextgis.maplib.map.Layer) {
            layerOpacityFactor = MplStyleMapper.alphaToOpacity(
                    ((com.nextgis.maplib.map.Layer) iLayer).getLayerOpacity());
        }

        String fillId = namePrefix + layer_namepart + layerId;
        String outlineId = fillId + outline_namepart;
        String symbolId = "symbol-" + namePrefix + layer_namepart + layerId;

        MplLayerStyleVars vars = MplLayerStyleVars.from(layerStyle, layerType);
        LabelAttributes labelAttributes = LabelAttributes.fromStyle(layerStyle);

        FillLayer fillLayer = style.getLayer(fillId) instanceof FillLayer
                ? (FillLayer) style.getLayer(fillId) : null;
        if (style.getLayer(fillId) != null && fillLayer == null) {
            style.removeLayer(style.getLayer(fillId));
        }
        if (fillLayer == null) {
            fillLayer = new FillLayer(fillId, layerPath)
                    .withSourceLayer(LocalVectorTileEncoder.SOURCE_LAYER);
        } else {
            fillLayer.setSourceLayer(LocalVectorTileEncoder.SOURCE_LAYER);
        }

        LineLayer outlineLayer = style.getLayer(outlineId) instanceof LineLayer
                ? (LineLayer) style.getLayer(outlineId) : null;
        if (style.getLayer(outlineId) != null && outlineLayer == null) {
            style.removeLayer(style.getLayer(outlineId));
        }
        if (outlineLayer == null) {
            outlineLayer = new LineLayer(outlineId, layerPath)
                    .withSourceLayer(LocalVectorTileEncoder.SOURCE_LAYER);
        } else {
            outlineLayer.setSourceLayer(LocalVectorTileEncoder.SOURCE_LAYER);
        }

        Expression sortKey = Expression.toNumber(Expression.get(prop_order));
        fillLayer.setProperties(
                PropertyFactory.fillColor(getColorName(vars.fillColor)),
                PropertyFactory.fillOpacity(MplStyleMapper.opacityWithLayerMultiplier(
                        Expression.literal(vars.fillOpacity), layerOpacityFactor)),
                PropertyFactory.fillAntialias(true),
                PropertyFactory.fillSortKey(sortKey));

        outlineLayer.setProperties(
                PropertyFactory.lineColor(getColorName(vars.outlineColor)),
                PropertyFactory.lineWidth(MplStyleMapper.zoomScaleExpression(
                        Expression.literal(getMPLThinkness(vars.thickness)),
                        vars.scaleSizeWithZoom,
                        vars.sizeZoomScaleStops)),
                PropertyFactory.lineOpacity(MplStyleMapper.opacityWithLayerMultiplier(
                        Expression.literal(vars.strokeOpacity), layerOpacityFactor)),
                PropertyFactory.lineSortKey(sortKey));

        if (style.getLayer(fillLayer.getId()) == null) {
            if (signaturesRootLayer != null && style.getLayer(signaturesRootLayer.getId()) != null) {
                style.addLayerBelow(fillLayer, signaturesRootLayer.getId());
            } else {
                style.addLayer(fillLayer);
            }
        }
        if (style.getLayer(outlineLayer.getId()) == null) {
            if (style.getLayer(fillLayer.getId()) != null) {
                style.addLayerAbove(outlineLayer, fillLayer.getId());
            } else if (signaturesRootLayer != null && style.getLayer(signaturesRootLayer.getId()) != null) {
                style.addLayerBelow(outlineLayer, signaturesRootLayer.getId());
            } else {
                style.addLayer(outlineLayer);
            }
        }
        layersHashMap.put(layerId, fillLayer);
        layersHashMap2.put(layerId, outlineLayer);

        boolean needLabels = false;
        if (layerStyle instanceof ITextStyle) {
            ITextStyle textStyle = (ITextStyle) layerStyle;
            needLabels = !TextUtils.isEmpty(textStyle.getField())
                    || !TextUtils.isEmpty(textStyle.getText())
                    || LabelTemplate.hasTemplate(labelAttributes.getLabelTemplate());
        }
        org.maplibre.android.style.layers.Layer existingSymbol = style.getLayer(symbolId);
        if (!needLabels) {
            if (existingSymbol != null) {
                style.removeLayer(existingSymbol);
            }
            symbolsLayerHashMap.remove(layerId);
        } else {
            SymbolLayer symbolLayer = existingSymbol instanceof SymbolLayer
                    ? (SymbolLayer) existingSymbol : null;
            if (existingSymbol != null && symbolLayer == null) {
                style.removeLayer(existingSymbol);
            }
            if (symbolLayer == null) {
                symbolLayer = new SymbolLayer(symbolId, layerPath)
                        .withSourceLayer(LocalVectorTileEncoder.SOURCE_LAYER);
            } else {
                symbolLayer.setSourceLayer(LocalVectorTileEncoder.SOURCE_LAYER);
            }
            String[] font = labelAttributes.getTextFontStack();
            float defaultTextOpacity = labelAttributes.textOpacityFloat();
            symbolLayer.setProperties(
                    PropertyFactory.textField("{" + prop_signature_text + "}"),
                    PropertyFactory.textSize(buildTextSizeExpression(vars.textSize, labelAttributes, false)),
                    PropertyFactory.textOpacity(MplStyleMapper.textOpacityExpression(
                            prop_text_opacity, defaultTextOpacity, layerOpacityFactor)),
                    PropertyFactory.textColor(getColorName(vars.textColor)),
                    PropertyFactory.textHaloColor(getColorName(labelAttributes.getTextHaloColor())),
                    PropertyFactory.textHaloWidth(labelAttributes.getTextHaloWidth()),
                    PropertyFactory.textHaloBlur(labelAttributes.getTextHaloBlur()),
                    PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_POINT),
                    PropertyFactory.textAllowOverlap(labelAttributes.getTextAllowOverlap() != null
                            && labelAttributes.getTextAllowOverlap()),
                    PropertyFactory.textOptional(labelAttributes.isTextOptional()),
                    PropertyFactory.symbolSortKey(sortKey),
                    PropertyFactory.textFont(font),
                    PropertyFactory.textMaxWidth(Math.max(0f, labelAttributes.getTextMaxWidth())));
            if (style.getLayer(symbolLayer.getId()) == null) {
                if (signaturesRootLayer != null && style.getLayer(signaturesRootLayer.getId()) != null) {
                    style.addLayerAbove(symbolLayer, signaturesRootLayer.getId());
                } else if (style.getLayer(outlineLayer.getId()) != null) {
                    style.addLayerAbove(symbolLayer, outlineLayer.getId());
                } else {
                    style.addLayer(symbolLayer);
                }
            }
            symbolsLayerHashMap.put(layerId, symbolLayer);
            float labelMinZoom = labelAttributes.getLabelMinZoom();
            float labelMaxZoom = labelAttributes.getLabelMaxZoom();
            if (labelMinZoom >= 0f) {
                symbolLayer.setMinZoom(labelMinZoom);
            } else if (minZoom != -1) {
                symbolLayer.setMinZoom(minZoom);
            }
            if (labelMaxZoom >= 0f) {
                symbolLayer.setMaxZoom(labelMaxZoom);
            } else if (maxZoom != -1) {
                symbolLayer.setMaxZoom(maxZoom);
            }
        }

        if (minZoom != -1) {
            fillLayer.setMinZoom(minZoom);
            outlineLayer.setMinZoom(minZoom);
        }
        if (maxZoom != -1) {
            fillLayer.setMaxZoom(maxZoom);
            outlineLayer.setMaxZoom(maxZoom);
        }
    }

    static  public List<Feature> convertToPointFeatures(List<Feature> layerFeatures) {
        List<Feature> centroidFeatures = new ArrayList<>();

        for (Feature feature : layerFeatures) {
            Geometry geometry = feature.geometry();
            Point centroid = null;

            if (geometry instanceof Polygon) {
                centroid = calculatePolygonCentroid((Polygon) geometry);
            } else if (geometry instanceof MultiPolygon) {
                centroid = calculateMultiPolygonCentroid((MultiPolygon) geometry);
            }

            if (centroid != null) {
                Feature centroidFeature = Feature.fromGeometry(
                        centroid,
                        feature.properties()
                );
                centroidFeatures.add(centroidFeature);
            }
        }

        return centroidFeatures;
    }

    static private Point calculatePolygonCentroid(Polygon polygon) {
        List<Point> points = polygon.coordinates().get(0); // external ring
        return getAveragePoint(points);
    }

    static private Point calculateMultiPolygonCentroid(MultiPolygon multiPolygon) {
        List<Point> allPoints = new ArrayList<>();

        for (List<List<Point>> polygonRings : multiPolygon.coordinates()) {
            allPoints.addAll(polygonRings.get(0)); //external ring of each poly
        }

        return getAveragePoint(allPoints);
    }

    static  private Point getAveragePoint(List<Point> points) {
        double sumLon = 0;
        double sumLat = 0;
        int count = points.size();

        for (Point point : points) {
            sumLon += point.longitude();
            sumLat += point.latitude();
        }

        return Point.fromLngLat(sumLon / count, sumLat / count);
    }


    static public org.maplibre.android.style.layers.Layer getRasterLayer(int layerId, final Style style){
        String currentNamePrefix = namePrefix;
        org.maplibre.android.style.layers.Layer rasterLayer = style.getLayer(currentNamePrefix + layer_namepart + layerId);
        return rasterLayer;
    }

    /**
     * MapLibre id of an existing style layer for {@code logical}, used as anchor when inserting a
     * new raster so it sits under the first drawable sibling above in {@link LayerGroup} order
     * (same as after a full {@code loadLayersToMaplibreMap} pass). Returns null if nothing exists yet.
     */
    @Nullable
    private static String primaryExistingMbglLayerIdForUserLayer(ILayer logical, Style style) {
        if (logical instanceof TrackLayer) {
            String id = "track-line-layer";
            return style.getLayer(id) != null ? id : null;
        }
        if (logical instanceof VectorLayer) {
            String base = namePrefix + layer_namepart + logical.getId();
            if (style.getLayer(base) != null) {
                return base;
            }
            String sym = "symbol-" + namePrefix + layer_namepart + logical.getId();
            if (style.getLayer(sym) != null) {
                return sym;
            }
            return null;
        }
        if (logical instanceof TMSLayer) {
            String rid = namePrefix + layer_namepart + logical.getId();
            return style.getLayer(rid) != null ? rid : null;
        }
        return null;
    }

    /**
     * Hot-add placement for a new TMS raster: either insert below an anchor (draw under sibling above)
     * or above an anchor (draw on top of sibling below). Bulk {@code loadLayersToMaplibreMap} passes
     * {@code null} and uses the signatures anchor path.
     */
    public static final class RasterSiblingAnchor {
        /** {@code true} → {@link Style#addLayerBelow}; {@code false} → {@link Style#addLayerAbove}. */
        public final boolean insertRasterBelowAnchor;
        public final String anchorLayerId;

        public RasterSiblingAnchor(boolean insertRasterBelowAnchor, String anchorLayerId) {
            this.insertRasterBelowAnchor = insertRasterBelowAnchor;
            this.anchorLayerId = anchorLayerId;
        }
    }

    @Nullable
    private static String topmostExistingMbglLayerIdForUserLayer(ILayer logical, Style style) {
        if (logical instanceof TrackLayer) {
            String id = "track-line-layer";
            return style.getLayer(id) != null ? id : null;
        }
        if (logical instanceof VectorLayer) {
            int lid = logical.getId();
            String base = namePrefix + layer_namepart + lid;
            String sym = "symbol-" + namePrefix + layer_namepart + lid;
            String outline = base + outline_namepart;
            String markerIcon = base + PointLayerFactory.MARKER_ICON_LAYER_SUFFIX;
            String last = null;
            for (org.maplibre.android.style.layers.Layer l : style.getLayers()) {
                String id = l.getId();
                if (id.equals(sym) || id.equals(base) || id.equals(outline) || id.equals(markerIcon)
                        || id.startsWith(base + dash_namepart)
                        || id.equals(base + pattern_namepart)) {
                    last = id;
                }
            }
            return last;
        }
        if (logical instanceof TMSLayer) {
            String rid = namePrefix + layer_namepart + logical.getId();
            return style.getLayer(rid) != null ? rid : null;
        }
        return null;
    }

    @Nullable
    private static String bottommostMbglInSubtree(LayerGroup root, Style style) {
        for (int i = 0; i < root.getLayerCount(); i++) {
            ILayer ch = root.getLayer(i);
            if (ch instanceof LayerGroup) {
                String id = bottommostMbglInSubtree((LayerGroup) ch, style);
                if (id != null) {
                    return id;
                }
            } else {
                String id = primaryExistingMbglLayerIdForUserLayer(ch, style);
                if (id != null) {
                    return id;
                }
            }
        }
        return null;
    }

    @Nullable
    private static String topmostMbglInSubtree(LayerGroup root, Style style) {
        for (int i = root.getLayerCount() - 1; i >= 0; i--) {
            ILayer ch = root.getLayer(i);
            if (ch instanceof LayerGroup) {
                String id = topmostMbglInSubtree((LayerGroup) ch, style);
                if (id != null) {
                    return id;
                }
            } else {
                String id = topmostExistingMbglLayerIdForUserLayer(ch, style);
                if (id != null) {
                    return id;
                }
            }
        }
        return null;
    }

    @Nullable
    private static String mbglBottomAnchorForSiblingAbove(ILayer sibling, Style style) {
        if (sibling instanceof LayerGroup) {
            return bottommostMbglInSubtree((LayerGroup) sibling, style);
        }
        return primaryExistingMbglLayerIdForUserLayer(sibling, style);
    }

    @Nullable
    private static String mbglTopAnchorForSiblingBelow(ILayer sibling, Style style) {
        if (sibling instanceof LayerGroup) {
            return topmostMbglInSubtree((LayerGroup) sibling, style);
        }
        return topmostExistingMbglLayerIdForUserLayer(sibling, style);
    }

    private static boolean isOsmBasemapTmsLayer(ILayer layer) {
        if (!(layer instanceof TMSLayer)) {
            return false;
        }
        TMSLayer t = (TMSLayer) layer;
        if (t.getTMSType() == TMSTYPE_OSM) {
            return true;
        }
        try {
            if (t.getPath() != null && "osm".equalsIgnoreCase(t.getPath().getName())) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    @Nullable
    private static ILayer findFirstOsmTmsInTree(LayerGroup root) {
        for (int i = 0; i < root.getLayerCount(); i++) {
            ILayer ch = root.getLayer(i);
            if (ch instanceof LayerGroup) {
                ILayer found = findFirstOsmTmsInTree((LayerGroup) ch);
                if (found != null) {
                    return found;
                }
            } else if (isOsmBasemapTmsLayer(ch)) {
                return ch;
            }
        }
        return null;
    }

    private static LayerGroup rootLayerGroupContaining(LayerGroup group) {
        LayerGroup root = group;
        ILayer p = root.getParent();
        while (p instanceof LayerGroup) {
            root = (LayerGroup) p;
            p = root.getParent();
        }
        return root;
    }

    /**
     * For hot-add only: MapLibre placement relative to siblings in {@link LayerGroup} order (index 0 =
     * bottom). Prefers a sibling <em>above</em> (higher index): raster is inserted <em>below</em> that
     * sibling's bottom MapLibre layer so the whole sibling draws on top. If none, prefers the nearest
     * OSM basemap <em>below</em> (same intent as {@code LayerFillService} NGRc insert above OSM), then
     * any other sibling below. If still unknown, uses OSM anywhere in the map tree (covers wrong list
     * index after {@code addLayer} or missing parent index). Avoids {@code signaturesRootLayer} when OSM
     * exists in the style.
     */
    @Nullable
    public static RasterSiblingAnchor resolveRasterSiblingAnchorOrNull(ILayer tmsLayer, Style style) {
        if (tmsLayer == null || style == null) {
            return null;
        }
        ILayer parent = tmsLayer.getParent();
        if (!(parent instanceof LayerGroup)) {
            return null;
        }
        LayerGroup group = (LayerGroup) parent;
        int idx = group.getChildLayerIndex(tmsLayer);
        if (idx < 0) {
            return null;
        }
        for (int j = idx + 1; j < group.getLayerCount(); j++) {
            ILayer above = group.getLayer(j);
            String mbglId = mbglBottomAnchorForSiblingAbove(above, style);
            if (mbglId != null) {
                return new RasterSiblingAnchor(true, mbglId);
            }
        }
        for (int j = idx - 1; j >= 0; j--) {
            ILayer below = group.getLayer(j);
            if (!isOsmBasemapTmsLayer(below)) {
                continue;
            }
            String mbglId = mbglTopAnchorForSiblingBelow(below, style);
            if (mbglId != null) {
                return new RasterSiblingAnchor(false, mbglId);
            }
        }
        for (int j = idx - 1; j >= 0; j--) {
            ILayer below = group.getLayer(j);
            if (isOsmBasemapTmsLayer(below)) {
                continue;
            }
            String mbglId = mbglTopAnchorForSiblingBelow(below, style);
            if (mbglId != null) {
                return new RasterSiblingAnchor(false, mbglId);
            }
        }
        ILayer osmFromTree = findFirstOsmTmsInTree(rootLayerGroupContaining(group));
        if (osmFromTree != null && osmFromTree != tmsLayer) {
            String mbglId = mbglTopAnchorForSiblingBelow(osmFromTree, style);
            if (mbglId != null) {
                return new RasterSiblingAnchor(false, mbglId);
            }
        }
        return null;
    }

    static public void createFillLayerForLayer(int layerId, int layerType,
                                               final Style style,
                                               Map<Integer,org.maplibre.android.style.layers.Layer> layersHashMap,
                                               Map<Integer,org.maplibre.android.style.layers.Layer> layersHashMap2,
                                               @Nullable Map<Integer, List<org.maplibre.android.style.layers.Layer>> layersHashMapLineDash,
                                               Map<Integer,org.maplibre.android.style.layers.Layer> symbolsLayerHashMap,
                                               @Nullable com.nextgis.maplib.display.Style layerStyle,
                                               boolean changeLayer,
                                               ILayer iLayer,
                                               String layerPath,
                                               org.maplibre.android.style.layers.Layer firstToolLayer,
                                               org.maplibre.android.style.layers.Layer signaturesRootLayer){ // layers below signaturesRootLayer
        createFillLayerForLayer(layerId, layerType, style, layersHashMap, layersHashMap2,
                layersHashMapLineDash, symbolsLayerHashMap, layerStyle, changeLayer, iLayer, layerPath,
                firstToolLayer, signaturesRootLayer, null);
    }

    static public void createFillLayerForLayer(int layerId, int layerType,
                                               final Style style,
                                               Map<Integer,org.maplibre.android.style.layers.Layer> layersHashMap,
                                               Map<Integer,org.maplibre.android.style.layers.Layer> layersHashMap2,
                                               @Nullable Map<Integer, List<org.maplibre.android.style.layers.Layer>> layersHashMapLineDash,
                                               Map<Integer,org.maplibre.android.style.layers.Layer> symbolsLayerHashMap,
                                               @Nullable com.nextgis.maplib.display.Style layerStyle,
                                               boolean changeLayer,
                                               ILayer iLayer,
                                               String layerPath,
                                               org.maplibre.android.style.layers.Layer firstToolLayer,
                                               org.maplibre.android.style.layers.Layer signaturesRootLayer,
                                               @Nullable RasterSiblingAnchor rasterSiblingAnchor) {
        if (style == null) {
            return;
        }
        // signatures between firstToolLayer and signaturesRootLayer
        float minZoom = -1;
        float maxZoom = -1;
        if (iLayer!= null){
            minZoom =((com.nextgis.maplib.map.Layer)iLayer).getMinZoom();
            maxZoom =((com.nextgis.maplib.map.Layer)iLayer).getMaxZoom();
        }

        float layerOpacityFactor = 1f;
        if (iLayer instanceof com.nextgis.maplib.map.Layer) {
            layerOpacityFactor = MplStyleMapper.alphaToOpacity(
                    ((com.nextgis.maplib.map.Layer) iLayer).getLayerOpacity());
        }

        String currentNamePrefix = namePrefix;

        if (layerType == GT_TRACK_WA){
            return;
        }

        if (layerType == GT_RASTER_WA){
            org.maplibre.android.style.layers.Layer rasterLayer = style.getLayer(currentNamePrefix + layer_namepart + layerId);

            if (rasterLayer == null){
                rasterLayer = new RasterLayer(currentNamePrefix + layer_namepart + layerId, layerPath);

                if (rasterSiblingAnchor != null
                        && rasterSiblingAnchor.anchorLayerId != null
                        && style.getLayer(rasterSiblingAnchor.anchorLayerId) != null) {
                    if (rasterSiblingAnchor.insertRasterBelowAnchor) {
                        style.addLayerBelow(rasterLayer, rasterSiblingAnchor.anchorLayerId);
                    } else {
                        style.addLayerAbove(rasterLayer, rasterSiblingAnchor.anchorLayerId);
                    }
                } else if (signaturesRootLayer != null && style.getLayer(signaturesRootLayer.getId()) != null) {
                    style.addLayerBelow(rasterLayer, signaturesRootLayer.getId());
                } else {
                    style.addLayer(rasterLayer);
                }
            }
                if (minZoom!= -1)
                    rasterLayer.setMinZoom(minZoom);
                if (maxZoom!= -1)
                    rasterLayer.setMaxZoom(maxZoom + 1);

                if (layerOpacityFactor < 0.999f) {
                    rasterLayer.setProperties(PropertyFactory.rasterOpacity(layerOpacityFactor));
                }

                if (iLayer != null && iLayer instanceof  TMSLayer) {
//                    TMSRenderer tmsRenderer = (TMSRenderer) ((TMSLayer) iLayer).getRenderer();
//                    float alpha = tmsRenderer.getAlpha() / 255.0f; // stored value 0 - 255 // need for maplibre 0 - 1
//                    float contrast = (tmsRenderer.getContrast() - 1) ; //stored value 0 - 100 ,  needed -1  +1
//                    float brightness = ((tmsRenderer.getBrightness()) / 255.0f) +1 ; // stored value 0  510 , need value 0  >1   1 norm

//                    float brightnessMin = tmsRenderer.getBrightnessMin();
//                    float brightnessMax = tmsRenderer.getBrightnessMax();
                    //boolean isGray = tmsRenderer.isForceToGrayScale();


//                    rasterLayer.setProperties(
//                            rasterOpacity(alpha),
//                            rasterContrast(contrast)
//
////                            rasterBrightnessMin(brightnessMin),
////                            rasterBrightnessMax(brightnessMax)
//                    );
                }
            return;
        }

        org.maplibre.android.style.layers.Layer simbolLayer = null;
        org.maplibre.android.style.layers.Layer newLayer = null;
        org.maplibre.android.style.layers.Layer newLayer2 = null;
        List<LineLayer> dashLayers = new ArrayList<>();
        FillLayer patternFillLayer = null;
        SymbolLayer markerIconLayer = null;

        String currentNamePrefixSymbol = "symbol-" + namePrefix;

        MplLayerStyleVars styleVars;
        LabelAttributes layerLabelAttributes;

        boolean ruleStyling = iLayer instanceof VectorLayer
                && ((VectorLayer) iLayer).getRenderer() instanceof RuleFeatureRenderer;

        // Rule mode: MapLibre layer defaults come from "other (default)" style, not renderer base.
        com.nextgis.maplib.display.Style layerDefaultsStyle = layerStyle;
        if (ruleStyling) {
            RuleFeatureRenderer rfr = (RuleFeatureRenderer) ((VectorLayer) iLayer).getRenderer();
            if (rfr.getStyleRule() instanceof FieldStyleRule) {
                FieldStyleRule fsr = (FieldStyleRule) rfr.getStyleRule();
                com.nextgis.maplib.display.Style otherStyle = fsr.resolveOtherStyle(layerStyle);
                if (otherStyle != null) {
                    layerDefaultsStyle = otherStyle;
                }
            }
        }
        styleVars = MplLayerStyleVars.from(layerDefaultsStyle, layerType);
        layerLabelAttributes = LabelAttributes.fromStyle(layerDefaultsStyle);

        // polygon makes signature other way: - create points for polygones
        // - differ source for points () (layerPath + source_text)
        // points as center of polygons
        // SymbolLayer for (layerPath + source_text) source
        boolean isPolygon = layerType == GTPolygon || layerType == GTMultiPolygon;

        int textAlignment = styleVars.textAlignment;
        float textSize = styleVars.textSize;
        int textColor = styleVars.textColor;

        MplLayerBuildContext buildContext = new MplLayerBuildContext(
                layerId,
                layerType,
                style,
                changeLayer,
                layerPath,
                currentNamePrefix,
                layerOpacityFactor,
                styleVars,
                iLayer != null && iLayer.getContext() != null
                        ? iLayer.getContext().getAssets()
                        : null,
                ruleStyling,
                iLayer,
                layersHashMap,
                layersHashMap2,
                layersHashMapLineDash);
        MplLayerBuildResult buildResult = new MplLayerBuildResult();

        if (layerType == GeoConstants.GTPoint || layerType == GeoConstants.GTMultiPoint) {
            PointLayerFactory.build(buildContext, buildResult);
        } else if (layerType == GeoConstants.GTLineString || layerType == GeoConstants.GTMultiLineString) {
            LineLayerFactory.build(buildContext, buildResult);
        } else if (layerType == GTPolygon || layerType == GeoConstants.GTMultiPolygon) {
            PolygonLayerFactory.build(buildContext, buildResult);
        }

        newLayer = buildResult.mainLayer;
        newLayer2 = buildResult.outlineLayer;
        dashLayers = buildResult.dashLayers;
        patternFillLayer = buildResult.patternFillLayer;
        markerIconLayer = buildResult.markerIconLayer;

        // signatures turn on
        if (layerStyle!= null) {
            String styleField = ((ITextStyle) layerStyle).getField();
            String styleText = ((ITextStyle) layerStyle).getText();

//            boolean needSignatures = !TextUtils.isEmpty(styleField) || !TextUtils.isEmpty(styleText);
            // old - if signature always turn on for all layer (vector)

            boolean needSignatures = false;

            if (iLayer instanceof  VectorLayer){
                final VectorLayer vectorLayer = (VectorLayer)(iLayer);
                LabelAttributes labelAttributes = LabelAttributes.fromStyle(layerStyle);
                if (ruleStyling ||
                        !TextUtils.isEmpty(styleField) || !TextUtils.isEmpty(styleText)
                        || LabelTemplate.hasTemplate(labelAttributes.getLabelTemplate())) {
                needSignatures = true;
                }
            }

            simbolLayer = style.getLayer(currentNamePrefixSymbol + layer_namepart + layerId);

            if (!needSignatures && simbolLayer != null) {
                // need remove
                style.removeLayer(simbolLayer);
                symbolsLayerHashMap.remove(layerId);

            } else {
                if (needSignatures) {
                    if (simbolLayer == null) {
                        String symbolLayerId = currentNamePrefixSymbol + layer_namepart + layerId;
                        simbolLayer = style.getLayer(symbolLayerId);
                        if (simbolLayer == null) {
                            Log.d("Mbgl", "create layer name : " + symbolLayerId);
                            Log.d("Mbgl", "create layer source : " + (layerPath + (isPolygon ? source_polygon_text : "")));
                            simbolLayer = new SymbolLayer(symbolLayerId,
                                    layerPath + (isPolygon ? source_polygon_text : ""));

                            if (signaturesRootLayer != null && style.getLayer(signaturesRootLayer.getId()) != null ){
                                style.addLayerAbove(simbolLayer, signaturesRootLayer.getId());
                            }
                            else {
                                style.addLayer(simbolLayer);
                            }
                        }
                        symbolsLayerHashMap.put(layerId, simbolLayer);
                    }

                    PropertyValue<String> signatureProperty = null;
                    signatureProperty = PropertyFactory.textField("{" + prop_signature_text + "}");

                    String[] font = layerLabelAttributes.getTextFontStack();

                    String defaultPlacement;
                    String defaultTextRotationAlignment = Property.TEXT_ROTATION_ALIGNMENT_AUTO;
                    boolean isLineLayer = layerType == GeoConstants.GTLineString
                            || layerType == GeoConstants.GTMultiLineString;
                    if (layerType == GeoConstants.GTPoint || layerType == GeoConstants.GTMultiPoint || isPolygon) {
                        defaultPlacement = Property.SYMBOL_PLACEMENT_POINT;
                    } else if (isLineLayer) {
                        if (layerLabelAttributes.isLineLabelRepeat()) {
                            defaultPlacement = Property.SYMBOL_PLACEMENT_LINE;
                        } else {
                            defaultPlacement = Property.SYMBOL_PLACEMENT_LINE_CENTER;
                        }
                        defaultTextRotationAlignment = layerLabelAttributes.isLineLabelHorizontal()
                                ? Property.TEXT_ROTATION_ALIGNMENT_VIEWPORT
                                : Property.TEXT_ROTATION_ALIGNMENT_MAP;
                    } else {
                        defaultPlacement = Property.SYMBOL_PLACEMENT_LINE;
                    }

                    String anchor = getTextAnchor(textAlignment); // def - Property.TEXT_ANCHOR_TOP
                    Float[] offsets =  isPolygon? new Float[]{0.0f, 0f} :  getTextAnchorOffsets(textAlignment, textSize); // {0f, 0f};

                    boolean allowOverlap = layerLabelAttributes.getTextAllowOverlap() != null
                            ? layerLabelAttributes.getTextAllowOverlap()
                            : false;
                    boolean textOptional = layerLabelAttributes.isTextOptional();
                    float symbolSpacing = layerLabelAttributes.getSymbolSpacing();
                    float textMaxWidth = layerLabelAttributes.getTextMaxWidth();
                    String haloColor = getColorName(layerLabelAttributes.getTextHaloColor());
                    float haloWidth = layerLabelAttributes.getTextHaloWidth();
                    float haloBlur = layerLabelAttributes.getTextHaloBlur();

                    Expression textSizeExpression = buildTextSizeExpression(
                            textSize, layerLabelAttributes, ruleStyling);
                    float defaultTextOpacity = layerLabelAttributes != null
                            ? layerLabelAttributes.textOpacityFloat()
                            : 1f;
                    Expression textOpacityExpression = ruleStyling
                            ? MplStyleMapper.textOpacityWithLabelZoomExpression(
                                    prop_text_opacity,
                                    prop_label_min_zoom,
                                    prop_label_max_zoom,
                                    defaultTextOpacity,
                                    layerOpacityFactor)
                            : MplStyleMapper.textOpacityExpression(
                                    prop_text_opacity, defaultTextOpacity, layerOpacityFactor);
                    Expression textAllowOverlapExpression = Expression.coalesce(
                            Expression.get(prop_text_allow_overlap),
                            Expression.literal(allowOverlap));
                    Expression textOptionalExpression = Expression.coalesce(
                            Expression.get(prop_text_optional),
                            Expression.literal(textOptional));
                    Expression textIgnorePlacementExpression = Expression.all(
                            textAllowOverlapExpression,
                            Expression.not(textOptionalExpression));

                    simbolLayer.setProperties(
                            signatureProperty,

                            PropertyFactory.textSize(textSizeExpression),

                            PropertyFactory.textOpacity(textOpacityExpression),

                            PropertyFactory.symbolSpacing(Expression.coalesce(
                                    Expression.get(prop_symbol_spacing),
                                    Expression.literal((double) symbolSpacing))),

                            PropertyFactory.textColor(Expression.coalesce(
                                    Expression.get(prop_text_color), // rule
                                    Expression.literal(getColorName(textColor))  // def value
                            )),

                            PropertyFactory.textHaloColor(Expression.coalesce(
                                    Expression.get(prop_texthalo_color),
                                    Expression.literal(haloColor))),

                            PropertyFactory.textHaloWidth(Expression.coalesce(
                                    Expression.get(prop_texthalo_width),
                                    Expression.literal(haloWidth > 0f ? haloWidth : 0f))),

                            PropertyFactory.textHaloBlur(Expression.coalesce(
                                    Expression.get(prop_texthalo_blur),
                                    Expression.literal(haloBlur))),

                            PropertyFactory.textAnchor(Expression.coalesce(
                                    Expression.get(prop_text_textanchor), // rule
                                    Expression.literal(anchor)  // def value
                            )),
                            PropertyFactory.symbolPlacement(Expression.coalesce(
                                    Expression.get(prop_symbol_placement),
                                    Expression.literal(defaultPlacement))),

                            PropertyFactory.textRotationAlignment(Expression.coalesce(
                                    Expression.get(prop_text_rotation_alignment),
                                    Expression.literal(defaultTextRotationAlignment))),

                            PropertyFactory.textOffset(Expression.coalesce(
                                    Expression.get(prop_text_textoffsets), // rule
                                    Expression.literal(offsets)  // def value
                            )),

                            PropertyFactory.textAllowOverlap(textAllowOverlapExpression),
                            PropertyFactory.textOptional(textOptionalExpression),
                            PropertyFactory.textIgnorePlacement(textIgnorePlacementExpression),
                            PropertyFactory.symbolSortKey(Expression.toNumber(Expression.get(prop_order))),
                            PropertyFactory.textFont(Expression.coalesce(
                                    Expression.get(prop_text_font),
                                    Expression.literal(font))),
                            PropertyFactory.textLineHeight(Expression.coalesce(
                                    Expression.get(prop_text_line_height),
                                    Expression.literal((double) layerLabelAttributes.getTextLineHeight()))),
                            PropertyFactory.textLetterSpacing(Expression.coalesce(
                                    Expression.get(prop_text_letter_spacing),
                                    Expression.literal((double) layerLabelAttributes.getTextLetterSpacing()))),
                            PropertyFactory.textJustify(Expression.coalesce(
                                    Expression.get(prop_text_justify),
                                    Expression.literal(layerLabelAttributes.getTextJustify()))),
                            PropertyFactory.textTransform(Expression.coalesce(
                                    Expression.get(prop_text_transform),
                                    Expression.literal(layerLabelAttributes.getTextTransform()))),
                            PropertyFactory.textPadding(Expression.coalesce(
                                    Expression.get(prop_text_padding),
                                    Expression.literal((double) layerLabelAttributes.getTextPadding()))),
                            PropertyFactory.textKeepUpright(Expression.coalesce(
                                    Expression.get(prop_text_keep_upright),
                                    Expression.literal(layerLabelAttributes.getTextKeepUpright() == null
                                            || layerLabelAttributes.getTextKeepUpright()))),
                            PropertyFactory.textMaxAngle(Expression.coalesce(
                                    Expression.get(prop_text_max_angle),
                                    Expression.literal((double) layerLabelAttributes.getTextMaxAngle()))),
                            PropertyFactory.textMaxWidth(Expression.coalesce(
                                    Expression.get(prop_text_max_width_prop),
                                    Expression.literal((double) (textMaxWidth > 0f ? textMaxWidth : 0f)))));
                }
            }
        }

        if (newLayer != null) {
            if (style.getLayer(newLayer.getId()) == null) {
                if (signaturesRootLayer != null && style.getLayer(signaturesRootLayer.getId()) != null ) {
                    style.addLayerBelow(newLayer, signaturesRootLayer.getId());
                }
                else {
                    style.addLayer(newLayer);
                }
            }
            layersHashMap.put(layerId, newLayer);
        }

        if (patternFillLayer != null) {
            if (style.getLayer(patternFillLayer.getId()) == null) {
                if (newLayer != null) {
                    style.addLayerAbove(patternFillLayer, newLayer.getId());
                } else if (signaturesRootLayer != null
                        && style.getLayer(signaturesRootLayer.getId()) != null) {
                    style.addLayerBelow(patternFillLayer, signaturesRootLayer.getId());
                } else {
                    style.addLayer(patternFillLayer);
                }
            }
        }

        if (newLayer2 != null) {
            if (style.getLayer(newLayer2.getId()) == null) {
                if (newLayer != null
                        && (layerType == GeoConstants.GTPoint || layerType == GeoConstants.GTMultiPoint)) {
                    style.addLayerBelow(newLayer2, newLayer.getId());
                } else if (signaturesRootLayer != null && style.getLayer(signaturesRootLayer.getId()) != null
                        && newLayer != null) {
                    if (layerType == GeoConstants.GTLineString || layerType == GeoConstants.GTMultiLineString) {
                        style.addLayerBelow(newLayer2, newLayer.getId());
                    } else {
                        style.addLayerBelow(newLayer2, signaturesRootLayer.getId());
                    }
                } else {
                    style.addLayer(newLayer2);
                }
            }
            layersHashMap2.put(layerId, newLayer2);
        }

        if (markerIconLayer != null) {
            if (style.getLayer(markerIconLayer.getId()) == null) {
                if (simbolLayer != null && style.getLayer(simbolLayer.getId()) != null) {
                    style.addLayerBelow(markerIconLayer, simbolLayer.getId());
                } else if (signaturesRootLayer != null
                        && style.getLayer(signaturesRootLayer.getId()) != null) {
                    style.addLayerAbove(markerIconLayer, signaturesRootLayer.getId());
                } else if (newLayer != null && style.getLayer(newLayer.getId()) != null) {
                    style.addLayerAbove(markerIconLayer, newLayer.getId());
                } else {
                    style.addLayer(markerIconLayer);
                }
            }
        }

        if (!dashLayers.isEmpty() && layersHashMapLineDash != null) {
            for (LineLayer dashLayer : dashLayers) {
                if (style.getLayer(dashLayer.getId()) == null) {
                    if (newLayer != null) {
                        style.addLayerAbove(dashLayer, newLayer.getId());
                    } else if (signaturesRootLayer != null
                            && style.getLayer(signaturesRootLayer.getId()) != null) {
                        style.addLayerBelow(dashLayer, signaturesRootLayer.getId());
                    } else {
                        style.addLayer(dashLayer);
                    }
                }
            }
            layersHashMapLineDash.put(layerId, new ArrayList<>(dashLayers));
        }

        // Layer geometry zoom (fill/line/circle)
        if (minZoom!= -1){
            if (newLayer != null)
                newLayer.setMinZoom(minZoom);
            if (patternFillLayer != null)
                patternFillLayer.setMinZoom(minZoom);
            for (LineLayer dashLayer : dashLayers) {
                dashLayer.setMinZoom(minZoom);
            }
            if (newLayer2 != null)
                newLayer2.setMinZoom(minZoom);
            if (markerIconLayer != null)
                markerIconLayer.setMinZoom(minZoom);
        }

        if (maxZoom!= -1){
            if (newLayer != null)
                newLayer.setMaxZoom(maxZoom);
            if (patternFillLayer != null)
                patternFillLayer.setMaxZoom(maxZoom);
            for (LineLayer dashLayer : dashLayers) {
                dashLayer.setMaxZoom(maxZoom);
            }
            if (newLayer2 != null)
                newLayer2.setMaxZoom(maxZoom);
            if (markerIconLayer != null)
                markerIconLayer.setMaxZoom(maxZoom);
        }

        // Label zoom can differ from layer geometry zoom. Always reset so a prior clamp cannot stick.
        if (simbolLayer != null) {
            float labelMinZoom = layerLabelAttributes != null
                    ? layerLabelAttributes.getLabelMinZoom() : -1f;
            float labelMaxZoom = layerLabelAttributes != null
                    ? layerLabelAttributes.getLabelMaxZoom() : -1f;
            if (labelMinZoom >= 0f) {
                simbolLayer.setMinZoom(labelMinZoom);
            } else if (minZoom != -1) {
                simbolLayer.setMinZoom(minZoom);
            } else {
                simbolLayer.setMinZoom(0f);
            }
            if (labelMaxZoom >= 0f) {
                simbolLayer.setMaxZoom(labelMaxZoom);
            } else if (maxZoom != -1) {
                simbolLayer.setMaxZoom(maxZoom);
            } else {
                simbolLayer.setMaxZoom(24f);
            }
        }
    }

    public static String getColorName(int color) {
        return String.format("#%06X", (0xFFFFFF & color));
    }

    public static float getMPLThinkness(float x) {
        float xMin = 1f;
        float xMax = 100f;
        float yMin = 1f;
        float yMax = 40f;
        return yMin + (x - xMin) * (yMax - yMin) / (xMax - xMin);
    }

    public static String getTextAnchor(int ngAlignment){
        switch (ngAlignment){
            case ALIGN_TOP: return Property.TEXT_ANCHOR_TOP;
            case ALIGN_TOP_RIGHT: return Property.TEXT_ANCHOR_TOP_RIGHT;
            case ALIGN_RIGHT: return Property.TEXT_ANCHOR_RIGHT;
            case ALIGN_BOTTOM_RIGHT: return Property.TEXT_ANCHOR_BOTTOM_RIGHT;
            case ALIGN_BOTTOM: return Property.TEXT_ANCHOR_BOTTOM;
            case ALIGN_BOTTOM_LEFT: return Property.TEXT_ANCHOR_BOTTOM_LEFT;
            case ALIGN_LEFT: return Property.TEXT_ANCHOR_LEFT;
            case ALIGN_TOP_LEFT: return Property.TEXT_ANCHOR_TOP_LEFT;
        }
        return Property.TEXT_ANCHOR_TOP;
    }


    public static Float[] getTextAnchorOffsets (int ngAlignment, float textSize){
//        public final static float SIZE_SMALL = 3;
//        public final static float SIZE_MEDIUM = 6;
//        public final static float SIZE_BIG = 10;
        final float coef = textSize < 6f ? 2.0f : 1.5f;
        switch (ngAlignment) {
            case ALIGN_TOP :   return new Float[]{0f, -coef};
            case ALIGN_BOTTOM: return   new Float[]{0f, coef};
            case ALIGN_LEFT: return   new Float[]{-0.8f, 0f};
            case ALIGN_RIGHT:return   new Float[]{0.8f, 0f};
            case ALIGN_TOP_LEFT:  return   new Float[]{-0.8f, -coef};
            case ALIGN_TOP_RIGHT: return   new Float[]{0.8f, -coef};
            case ALIGN_BOTTOM_LEFT: return   new Float[]{-0.8f, coef};
            case ALIGN_BOTTOM_RIGHT: return   new Float[]{0.8f, coef};
            default: return new Float[]{0f, -coef};
        }
    }

    static public String getSpaceCorrectedText(String originalText){
        return originalText
                .replace("\u00A0", " ")      //
                .replace("\u2009", " ")      // thin space
                .replaceAll("\\s+", " ")     //
                .trim();
    }


    static public String getNullableValue(com.nextgis.maplib.datasource.Feature feature, String fieldStr ){
        return feature.getFieldValueAsString(feature.getFieldValueIndex(fieldStr));
    }

}



//                        PropertyFactory.lineDasharray(
//
//                        Expression.switchCase(
//                                Expression.eq(Expression.get(prop_type), Expression.literal(1)),
//                                // TRUE -> dashed
//                                Expression.literal(new Float[]{2f, 2f}),
//                                // DEFAULT -> "almost solid" (workaround instead of  null)
//                                Expression.literal(new Float[]{1f, 0f})
//                        ))
//                          try to use coalesce - not work - commented
//                        PropertyFactory.lineDasharray(Expression.coalesce(
//                                Expression.get(prop_type), // rule
//                                Expression.literal(type == 2 ?  new Float[]{2f, 2f} : null)))
//                        PropertyFactory.circleStrokeWidth(Expression.coalesce(
//                                Expression.get(prop_type), // rule
//                                Expression.literal(
//                                        Expression.step(
//                                                Expression.get(prop_type2),  // your prop type
//                                                Expression.literal(new Float[]{0f, 0f}),
//                                                Expression.stop(1, null),  // if type == 2
//                                                Expression.stop(2,  new Float[]{2f, 2f}),  // if type == 2
//                                                Expression.stop (3, null)  // if type == 2
//                                        )
//                                )))
/*
 */

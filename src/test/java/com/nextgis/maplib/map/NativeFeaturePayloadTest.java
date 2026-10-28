package com.nextgis.maplib.map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;
import org.maplibre.geojson.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class NativeFeaturePayloadTest {
    private static final String[] DEAD = {"textfont", "textlineheight", "textpadding", "textkeepupright",
            "textmaxangle", "textallowoverlap", "textoptional", "symbolspacing", "symbolplacement",
            "textrotationalignment", "iconallowoverlap", "iconignoreplacement", "linecap", "linejoin",
            "linemiterlimit", "linedasharray", "dashpreset", "filltranslate"};
    private static final String[] LABEL_REQUIRED = {"signature", "textcolor", "textsize", "textscalewithzoom",
            "textanchor", "textoffset", "texthalo_color", "texthalo_width", "texthalo_blur", "textopacity",
            "textjustify", "texttransform", "textletterspacing", "textmaxwidth", "labelminzoom",
            "labelmaxzoom", "textzoomstops", "label_layout_key"};

    private static Feature rich(int type, String signature) {
        JsonObject props = new JsonObject();
        props.addProperty("featureid", "42");props.addProperty("layerid", "7");props.addProperty("order", "3");
        props.addProperty("colorfill", "#ff0000");props.addProperty("fillcolor", "#ff0000");
        props.addProperty("colorstroke", "#0000ff");props.addProperty("thinkness", 4);
        props.addProperty("opacity", .5);props.addProperty("strokeopacity", .75);
        props.addProperty("fillpattern", 2);props.addProperty("fillpatternimage", "pattern");
        props.addProperty("polygon_pattern_opacity", .4);props.addProperty("polygon_outline_key", "outline");
        props.addProperty("geometry_layout_key", "geometry");props.addProperty("sizezoomstops", "10:1,20:3");
        props.addProperty("scalesizewithzoom", false);props.addProperty("line_dash_key", "dash");
        props.addProperty("lineoffset", 2);props.addProperty("linegapwidth", 3);
        props.addProperty("lineoutlinemultiplier", 4);props.addProperty("circleblur", .2);
        props.addProperty("iconimage", "icon");props.addProperty("iconsize", 5);props.addProperty("iconrotate", 30);
        props.addProperty("iconanchor", "bottom");props.addProperty("filltype", 9);
        props.addProperty("firefly_image", "firefly");props.addProperty("firefly_scale", .5);
        JsonArray pair = new JsonArray();pair.add(1);pair.add(2);props.add("iconoffset", pair);
        for (String key : LABEL_REQUIRED) props.addProperty(key, "label-value-" + key);
        props.addProperty("signature", signature);
        props.add("textoffset", pair.deepCopy());props.addProperty("textsize", 27);
        props.addProperty("textopacity", .8);props.addProperty("labelminzoom", 7);props.addProperty("labelmaxzoom", 19);
        props.addProperty("textscalewithzoom", false);
        for (String key : DEAD) props.add(key, pair.deepCopy());
        JsonObject unknown = new JsonObject();unknown.addProperty("explicit_off", false);unknown.add("nested", pair.deepCopy());
        props.add("future_unknown", unknown);
        return Feature.fromGeometry(geometry(type), props, "geojson-id", BoundingBox.fromLngLats(-3, -3, 3, 3));
    }

    private static Geometry geometry(int type) {
        Point p = Point.fromLngLat(0, 0);
        List<Point> line = Arrays.asList(p, Point.fromLngLat(1, 1));
        List<Point> ring = Arrays.asList(Point.fromLngLat(-2, -2),Point.fromLngLat(2, -2),
                Point.fromLngLat(2, 2),Point.fromLngLat(-2, 2),Point.fromLngLat(-2, -2));
        List<Point> hole = Arrays.asList(Point.fromLngLat(-.5, -.5),Point.fromLngLat(-.5, .5),
                Point.fromLngLat(.5, .5),Point.fromLngLat(.5, -.5),Point.fromLngLat(-.5, -.5));
        switch (type) {
            case 1:return p;
            case 2:return LineString.fromLngLats(line);
            case 3:return Polygon.fromLngLats(Arrays.asList(ring,hole));
            case 4:return MultiPoint.fromLngLats(line);
            case 5:return MultiLineString.fromLngLats(Arrays.asList(line,line));
            case 6:return MultiPolygon.fromLngLats(Arrays.asList(Arrays.asList(ring,hole),Collections.singletonList(ring)));
            default:throw new AssertionError(type);
        }
    }

    @Test public void allGeometryKindsKeepIdsUnknownValuesCompleteGeometryAndOriginals() {
        for (int type = 1; type <= 6; type++) {
            Feature original = rich(type, "Подпись");String before = original.toJson();
            Feature compact = NativeFeaturePayload.geometry(Collections.singletonList(original), type).get(0);
            assertSame(original.geometry(), compact.geometry());assertEquals(original.bbox(), compact.bbox());
            assertEquals("geojson-id", compact.id());
            for (String key : new String[]{"featureid", "layerid", "order", "future_unknown"})
                assertEquals(original.getProperty(key), compact.getProperty(key));
            for (String key : DEAD) assertFalse("Unused native field " + key, compact.hasProperty(key));
            compact.getProperty("future_unknown").getAsJsonObject().addProperty("explicit_off", true);
            assertEquals(before, original.toJson());
        }
    }

    @Test public void pointSourcesKeepActualPaintIconsLabelsAndTheirZoomGates() {
        for (int type : new int[]{1,4}) {
            Feature original = rich(type, "Подпись");
            Feature compact = NativeFeaturePayload.geometry(Collections.singletonList(original), type).get(0);
            for (String key : LABEL_REQUIRED) assertEquals(key, original.getProperty(key), compact.getProperty(key));
            for (String key : new String[]{"colorfill", "colorstroke", "thinkness", "opacity", "strokeopacity",
                    "filltype", "iconimage", "iconsize", "iconrotate", "iconoffset", "iconanchor",
                    "firefly_image", "firefly_scale", "geometry_layout_key", "sizezoomstops"})
                assertEquals(key, original.getProperty(key), compact.getProperty(key));
        }
    }

    @Test public void noSignatureMeansNoUnusedLabelPropertiesInAnyGeometrySource() {
        for (int type = 1; type <= 6; type++) for (String signature : new String[]{null,"","  "}) {
            Feature compact = NativeFeaturePayload.geometry(Collections.singletonList(rich(type, signature)), type).get(0);
            for (String key : LABEL_REQUIRED) assertFalse(key, compact.hasProperty(key));
            assertTrue(compact.hasProperty("future_unknown"));assertTrue(compact.hasProperty("geometry_layout_key"));
        }
    }

    @Test public void polygonGeometryNeverCarriesLabelSettingsOrNestedFontArrays() {
        for (int type : new int[]{3,6}) {
            Feature original = rich(type,"Подпись");
            Feature compact = NativeFeaturePayload.geometry(Collections.singletonList(original),type).get(0);
            for (String key : LABEL_REQUIRED) assertFalse(key,compact.hasProperty(key));
            for (String key : new String[]{"fillcolor","colorstroke","thinkness","fillpatternimage",
                    "polygon_pattern_opacity","polygon_outline_key","opacity","strokeopacity",
                    "geometry_layout_key","sizezoomstops","scalesizewithzoom"})
                assertEquals(original.getProperty(key),compact.getProperty(key));
        }
    }

    @Test public void polygonLabelPointsSkipEmptySignaturesAndCarryNoGeometryPaint() {
        Feature labelled=rich(3,"Подпись"),empty=rich(3,""),blank=rich(6,"  ");
        String before=labelled.toJson();
        List<Feature> labels=MPLFeaturesUtils.nativePolygonLabels(Arrays.asList(labelled,empty,blank));
        assertEquals(1,labels.size());Feature label=labels.get(0);assertTrue(label.geometry() instanceof Point);
        for (String key : new String[]{"featureid","layerid","order","future_unknown"})
            assertEquals(labelled.getProperty(key),label.getProperty(key));
        for (String key : LABEL_REQUIRED) assertEquals(key,labelled.getProperty(key),label.getProperty(key));
        for (String key : new String[]{"fillcolor","colorfill","colorstroke","thinkness","opacity",
                "strokeopacity","fillpattern","fillpatternimage","polygon_outline_key",
                "polygon_pattern_opacity","geometry_layout_key","sizezoomstops","iconoffset"})
            assertFalse(key,label.hasProperty(key));
        assertEquals(before,labelled.toJson());
    }

    @Test public void nativeLineQueryFallbackStillProducesLabelPointsWithAnglesAndPaint() {
        Feature original=rich(2,"Подпись");
        Feature compact=NativeFeaturePayload.geometry(Collections.singletonList(original),2).get(0);
        for (String key : LABEL_REQUIRED) assertEquals(key,original.getProperty(key),compact.getProperty(key));
        List<Feature> points=VisibleLineLabelLayout.create(Collections.singletonList(compact),
                new double[][]{{-2,2},{2,2},{2,-2},{-2,-2}},0);
        List<Feature> labels=NativeFeaturePayload.labels(points);assertEquals(1,labels.size());
        Feature label=labels.get(0);assertEquals("Подпись",label.getStringProperty("signature"));
        assertTrue(label.hasProperty("line_label_angle"));assertTrue(label.hasProperty("line_label_upright_angle"));
        assertEquals(original.getProperty("labelminzoom"),label.getProperty("labelminzoom"));
        assertEquals(original.getProperty("textzoomstops"),label.getProperty("textzoomstops"));
        assertFalse(label.hasProperty("line_dash_key"));assertFalse(label.hasProperty("colorfill"));
    }

    @Test public void sparseUniformPolygonDropsOnlyMatchingKnownFactoryDefaults() {
        Feature original=rich(3,"Подпись");original.removeProperty("colorfill");
        JsonObject defaults=original.properties().deepCopy();
        original.addNumberProperty("thinkness",9);
        Feature compact=NativeFeaturePayload.geometry(Collections.singletonList(original),3,defaults).get(0);
        assertEquals(9,compact.getNumberProperty("thinkness").intValue());
        for (String key : new String[]{"fillcolor","colorstroke","opacity","strokeopacity","fillpattern",
                "fillpatternimage","polygon_pattern_opacity","polygon_outline_key","geometry_layout_key",
                "sizezoomstops","scalesizewithzoom"}) assertFalse(key,compact.hasProperty(key));
        assertEquals(original.getProperty("future_unknown"),compact.getProperty("future_unknown"));
        original.addStringProperty("colorfill","#00ff00");
        compact=NativeFeaturePayload.geometry(Collections.singletonList(original),3,defaults).get(0);
        assertEquals("#ff0000",compact.getStringProperty("fillcolor"));
    }

    @Test public void countsDescribeTransportWithoutReadingOrChangingBusinessValues() {
        Feature first=rich(1,"Подпись"),second=rich(3,"");
        List<Feature> original=Arrays.asList(first,second);
        assertEquals(first.properties().size()+second.properties().size(),NativeFeaturePayload.propertyCount(original));
        assertEquals(1,NativeFeaturePayload.labelCount(original));
        assertEquals(1,NativeFeaturePayload.labels(original).size());
        assertTrue(NativeFeaturePayload.propertyCount(NativeFeaturePayload.geometry(original,3))
                <NativeFeaturePayload.propertyCount(original));
    }
}

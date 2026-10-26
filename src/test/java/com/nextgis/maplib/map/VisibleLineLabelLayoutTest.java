package com.nextgis.maplib.map;

import com.google.gson.JsonObject;
import org.junit.Test;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.Geometry;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.MultiLineString;
import org.maplibre.geojson.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import static org.junit.Assert.*;

public class VisibleLineLabelLayoutTest {
    private static double[][] box(double left,double bottom,double right,double top) {
        return new double[][]{{left,top},{right,top},{right,bottom},{left,bottom}};
    }
    private static LineString line(double... values) {
        List<Point> points=new ArrayList<>();
        for(int i=0;i<values.length;i+=2)points.add(Point.fromLngLat(values[i],values[i+1]));
        return LineString.fromLngLats(points);
    }
    private static Feature feature(String id,Geometry geometry) {
        JsonObject properties=new JsonObject();
        properties.addProperty(MPLFeaturesUtils.prop_featureid,id);
        properties.addProperty(MPLFeaturesUtils.prop_signature_text,"проезд существующий");
        properties.addProperty("custom_style",17);
        return Feature.fromGeometry(geometry,properties);
    }
    private static double angle(Feature feature){return feature.getNumberProperty(VisibleLineLabelLayout.ANGLE).doubleValue();}

    @Test public void aWordLongerThanEveryStraightRunStillHasOneWholeLabelOnTheVisibleCurve() {
        List<Point> points=new ArrayList<>();
        for(int i=-20;i<=20;i++)points.add(Point.fromLngLat(i*.001,(i%2==0?1:-1)*.0001));
        Feature source=feature("7",LineString.fromLngLats(points));
        List<Feature> result=VisibleLineLabelLayout.create(Collections.singletonList(source),box(-.01,-.01,.01,.01),0);
        assertEquals(1,result.size());Feature label=result.get(0);assertTrue(label.geometry() instanceof Point);
        assertEquals("проезд существующий",label.getStringProperty(MPLFeaturesUtils.prop_signature_text));
        assertEquals(17,label.getNumberProperty("custom_style").intValue());assertEquals(0,angle(label),.001);
        Point anchor=(Point)label.geometry();assertEquals(0,anchor.longitude(),.001);
        assertTrue(Math.abs(anchor.latitude())<=.000100001);
        assertFalse(source.hasProperty(VisibleLineLabelLayout.ANGLE));assertEquals(41,((LineString)source.geometry()).coordinates().size());
    }
    @Test public void offscreenDominantDirectionCannotOverrideTheVisiblePart() {
        Feature source=feature("1",line(-20,10, 10,10, 10,-10));
        Feature vertical=VisibleLineLabelLayout.create(Collections.singletonList(source),box(9,-1,11,1),0).get(0);
        assertEquals(90,Math.abs(angle(vertical)),.001);assertEquals(10,((Point)vertical.geometry()).longitude(),.00001);
        Feature horizontal=VisibleLineLabelLayout.create(Collections.singletonList(source),box(0,9,2,11),0).get(0);
        assertEquals(0,angle(horizontal),.001);assertEquals(10,((Point)horizontal.geometry()).latitude(),.00001);
    }
    @Test public void nativeTileDuplicatesAndMultipartFragmentsStillProduceOneLabelPerId() {
        Feature a=feature("a",line(-2,0,2,0)),b=feature("a",line(0,-2,0,2));
        Feature distinct=feature("b",line(-2,.2,2,.2));
        List<Feature> result=VisibleLineLabelLayout.create(Arrays.asList(a,a,b,distinct),box(-1,-1,1,1),0);
        assertEquals(2,result.size());
        Feature multi=feature("a",MultiLineString.fromLngLats(Arrays.asList(
                ((LineString)a.geometry()).coordinates(),((LineString)b.geometry()).coordinates())));
        assertEquals(1,VisibleLineLabelLayout.create(Collections.singletonList(multi),box(-1,-1,1,1),0).size());
    }
    @Test public void rotatedViewportClipsItsCornersRatherThanUsingTheGeographicBoundingBox() {
        double[][] diamond={{-1,0},{0,1},{1,0},{0,-1}};
        assertTrue(VisibleLineLabelLayout.create(Collections.singletonList(feature("1",line(.8,.9,.9,.8))),diamond,0).isEmpty());
        List<Feature> crossing=VisibleLineLabelLayout.create(Collections.singletonList(feature("2",line(-2,0,2,0))),diamond,0);
        assertEquals(1,crossing.size());assertEquals(0,((Point)crossing.get(0).geometry()).longitude(),.00001);
    }
    @Test public void aCrossingWithBothEndpointsOutsideIsKeptAndAnOffscreenObjectIsExcluded() {
        assertEquals(1,VisibleLineLabelLayout.create(Collections.singletonList(feature("1",line(-2,0,2,0))),box(-.1,-.1,.1,.1),0).size());
        assertTrue(VisibleLineLabelLayout.create(Collections.singletonList(feature("1",line(4,4,5,5))),box(-.1,-.1,.1,.1),0).isEmpty());
    }
    @Test public void digitizingDirectionAndTheCameraBearingDoNotTurnTheSettledWordUpsideDown() {
        Feature a=feature("1",line(-2,-1,2,1)),b=feature("1",line(2,1,-2,-1));
        for(double bearing:new double[]{0,85,95,180,270,359}) {
            Feature first=VisibleLineLabelLayout.create(Collections.singletonList(a),box(-1,-1,1,1),bearing).get(0);
            Feature reverse=VisibleLineLabelLayout.create(Collections.singletonList(b),box(-1,-1,1,1),bearing).get(0);
            assertEquals(angle(first),angle(reverse),.00001);
            double screen=first.getNumberProperty(VisibleLineLabelLayout.UPRIGHT_ANGLE).doubleValue()-bearing;
            assertTrue(screen>=-90.00001 && screen<=90.00001);
        }
    }
    @Test public void aDatelineViewportOwnsTheNearbyWrappedLine() {
        List<Feature> result=VisibleLineLabelLayout.create(Collections.singletonList(feature("1",line(179.5,0,-179.5,0))),box(179,-1,-179,1),0);
        assertEquals(1,result.size());assertEquals(180,Math.abs(((Point)result.get(0).geometry()).longitude()),.0001);
    }
    @Test(expected=CancellationException.class) public void aNewCameraCancelsObsoleteGeometryWork() {
        VisibleLineLabelLayout.create(Collections.singletonList(feature("1",line(-1,0,1,0))),box(-1,-1,1,1),0,() -> false);
    }
}

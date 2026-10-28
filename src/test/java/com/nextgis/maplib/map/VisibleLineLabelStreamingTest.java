package com.nextgis.maplib.map;

import com.google.gson.JsonObject;
import org.junit.Test;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.Geometry;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.MultiLineString;
import org.maplibre.geojson.Point;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class VisibleLineLabelStreamingTest {
    private static double[][] box(double w,double s,double e,double n){return new double[][]{{w,n},{e,n},{e,s},{w,s}};}
    private static Feature line(String id,double...xy){List<Point> points=new ArrayList<>();for(int i=0;i<xy.length;i+=2)points.add(Point.fromLngLat(xy[i],xy[i+1]));
        JsonObject props=new JsonObject();props.addProperty(MPLFeaturesUtils.prop_featureid,id);props.addProperty(MPLFeaturesUtils.prop_signature_text,"Цельная подпись");props.addProperty("custom_style",17);
        return Feature.fromGeometry(LineString.fromLngLats(points),props,id);}

    @Test public void preparedStreamingLayoutKeepsTheExactVisibleMultipartDirectionAndRichProperties() throws Exception {
        Feature vertical=line("7",-20,10,10,10,10,-10);
        Feature horizontal=line("8",-20,.5,20,.5);
        Feature multi=Feature.fromGeometry(MultiLineString.fromLngLats(Arrays.asList(
                ((LineString)horizontal.geometry()).coordinates(),((LineString)line("8",30,30,40,40).geometry()).coordinates())),horizontal.properties(),"8");
        List<Feature> full=Arrays.asList(vertical,multi,vertical);
        List<VisibleLineLabelLayout.Label> streamed=VisibleLineLabelQuery.planStreaming(box(9,-1,11,1),10,95,
                (bounds,current,visitor)->{assertEquals(1,bounds.size());for(Feature original:full)visitor.accept(original);},()->true);
        List<VisibleLineLabelLayout.Label> classic=VisibleLineLabelLayout.plan(full,box(9,-1,11,1),95,()->true);
        assertEquals(2,streamed.size());assertEquals(classic.size(),streamed.size());
        for(int i=0;i<streamed.size();i++)assertEquals(classic.get(i).toFeature().toJson(),streamed.get(i).toFeature().toJson());
        Feature label=streamed.get(0).toFeature();
        assertEquals("7",label.getStringProperty(MPLFeaturesUtils.prop_featureid));assertEquals(90,Math.abs(label.getNumberProperty(VisibleLineLabelLayout.ANGLE).doubleValue()),1e-6);
        assertEquals(10,((Point)label.geometry()).longitude(),1e-9);assertEquals(0,((Point)label.geometry()).latitude(),1e-9);
        assertEquals("Цельная подпись",label.getStringProperty(MPLFeaturesUtils.prop_signature_text));assertEquals(17,label.getNumberProperty("custom_style").intValue());
        assertEquals(3,((LineString)vertical.geometry()).coordinates().size());assertEquals(2,((MultiLineString)multi.geometry()).coordinates().size());
    }
    @Test public void generatedLargeOriginalsLeaveOnlyPointAndPropertyCandidatesReachable() throws Exception {
        AtomicInteger delivered=new AtomicInteger();
        List<VisibleLineLabelLayout.Label> labels=VisibleLineLabelQuery.planStreaming(box(-1,-1,1,1),0,0,(bounds,current,visitor)->{
            for(int id=0;id<128;id++){
                List<Point> points=new ArrayList<>();for(int vertex=0;vertex<4097;vertex++)points.add(Point.fromLngLat(-.9+1.8*vertex/4096,id*.001));
                Feature template=line(Integer.toString(id),0,0,1,0);
                visitor.accept(Feature.fromGeometry(LineString.fromLngLats(points),template.properties(),template.id()));delivered.incrementAndGet();
            }
        },()->true);
        assertEquals(128,delivered.get());assertEquals(128,labels.size());
        for(VisibleLineLabelLayout.Label label:labels){assertOnlyPointGeometryReachable(label,new IdentityHashMap<>());assertTrue(label.toFeature().geometry() instanceof Point);}
    }
    @Test public void candidateRetainsLateUiStyleCopyButNoSourceGeometry() throws Exception {
        Feature source=line("late",-2,0,2,0);
        VisibleLineLabelLayout.OriginalPlanner planner=new VisibleLineLabelLayout.OriginalPlanner(box(-1,-1,1,1),0,()->true);
        planner.accept(source);source.addNumberProperty("custom_style",42);
        VisibleLineLabelLayout.Label label=planner.finish().get(0);
        assertEquals(42,label.toFeature().getNumberProperty("custom_style").intValue());assertEquals("late",label.toFeature().id());
        assertFalse(source.hasProperty(VisibleLineLabelLayout.ANGLE));assertOnlyPointGeometryReachable(label,new IdentityHashMap<>());
    }
    @Test public void obsoleteOwnerOrDataCancelsBeforeReadDuringScanAndBeforePublication() throws Exception {
        AtomicInteger reads=new AtomicInteger();
        assertThrows(CancellationException.class,()->VisibleLineLabelQuery.planStreaming(box(-1,-1,1,1),0,0,
                (bounds,current,visitor)->reads.incrementAndGet(),()->false));assertEquals(0,reads.get());
        AtomicBoolean current=new AtomicBoolean(true);
        assertThrows(CancellationException.class,()->VisibleLineLabelQuery.planStreaming(box(-1,-1,1,1),0,0,
                (bounds,stillCurrent,visitor)->{visitor.accept(line("1",-2,0,2,0));current.set(false);},current::get));
        AtomicInteger checks=new AtomicInteger();
        assertThrows(CancellationException.class,()->VisibleLineLabelQuery.planStreaming(box(-1,-1,1,1),0,0,
                (bounds,stillCurrent,visitor)->{List<Point> points=new ArrayList<>();for(int i=0;i<5000;i++)points.add(Point.fromLngLat(i*.0001,0));
                    Feature template=line("long",0,0,1,0);visitor.accept(Feature.fromGeometry(LineString.fromLngLats(points),template.properties(),template.id()));},
                ()->checks.incrementAndGet()<4));
    }
    @Test public void datelineWindowsStillProduceOneRigidLabelFromTheUnclippedOriginal() throws Exception {
        Feature original=line("date",179.5,0,-179.5,0);
        List<VisibleLineLabelLayout.Label> labels=VisibleLineLabelQuery.planStreaming(box(179,-1,-179,1),180,0,
                (bounds,current,visitor)->{assertEquals(2,bounds.size());visitor.accept(original);visitor.accept(original);},()->true);
        assertEquals(1,labels.size());assertEquals(180,Math.abs(((Point)labels.get(0).toFeature().geometry()).longitude()),1e-7);
        assertEquals(-179.5,((LineString)original.geometry()).coordinates().get(1).longitude(),0);
    }

    /** Ownership assertion, not a GC timing assumption: candidates must never retain any line Feature/Geometry graph. */
    private static void assertOnlyPointGeometryReachable(Object value,IdentityHashMap<Object,Boolean> seen)throws Exception{
        if(value==null || seen.put(value,true)!=null)return;
        if(value instanceof Geometry){assertTrue("A label candidate retained full line geometry",value instanceof Point);return;}
        if(value instanceof Feature){Feature feature=(Feature)value;assertTrue(feature.geometry()==null || feature.geometry() instanceof Point);assertOnlyPointGeometryReachable(feature.properties(),seen);return;}
        if(value instanceof JsonObject){for(Map.Entry<String,com.google.gson.JsonElement> entry:((JsonObject)value).entrySet())assertOnlyPointGeometryReachable(entry.getValue(),seen);return;}
        if(value instanceof Iterable){for(Object item:(Iterable<?>)value)assertOnlyPointGeometryReachable(item,seen);return;}
        if(value instanceof Map){for(Object item:((Map<?,?>)value).values())assertOnlyPointGeometryReachable(item,seen);return;}
        if(value.getClass().getName().startsWith(VisibleLineLabelLayout.class.getName()+"$"))for(Field field:value.getClass().getDeclaredFields())
            if(!Modifier.isStatic(field.getModifiers())){field.setAccessible(true);assertOnlyPointGeometryReachable(field.get(value),seen);}
    }
}

package com.nextgis.maplib.map;

import com.google.gson.JsonObject;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.Geometry;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.MultiLineString;
import org.maplibre.geojson.Point;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Pure visible-geometry layout: one rigid label per SQL feature, independent of straight-run length. */
public final class VisibleLineLabelLayout {
    public static final String ANGLE = "line_label_angle";
    public static final String UPRIGHT_ANGLE = "line_label_upright_angle";
    private static final double MAX_LATITUDE = 85.0511287798066;
    private VisibleLineLabelLayout() { }

    public static List<Feature> create(List<Feature> features, double[][] corners, double bearing) {
        return create(features,corners,bearing,() -> true);
    }

    public static List<Feature> create(List<Feature> features, double[][] corners, double bearing,
                                       BooleanSupplier current) {
        List<Feature> result=new ArrayList<>();
        for(Label label:plan(features,corners,bearing,current))result.add(label.toFeature());
        return result;
    }

    /** Geometry work stays in the worker; cloning current mutable style properties stays on the UI thread. */
    public static final class Label {
        private final JsonObject properties;
        private final String id;
        private final Point anchor;
        private final double angle,upright;
        private Label(Feature original,Point anchor,double angle,double upright) {
            this.properties=original.properties();this.id=original.id();
            this.anchor=anchor;this.angle=angle;this.upright=upright;
        }
        public Feature toFeature() {
            JsonObject copy=properties.deepCopy();
            copy.addProperty(ANGLE,angle);copy.addProperty(UPRIGHT_ANGLE,upright);
            return Feature.fromGeometry(anchor,copy,id);
        }
    }

    public static List<Label> plan(List<Feature> features,double[][] corners,double bearing,BooleanSupplier current) {
        Viewport viewport=new Viewport(corners,bearing);
        if(viewport.empty)return new ArrayList<>();
        Map<String,Accumulator> objects=new LinkedHashMap<>();
        for(Feature feature:features) {
            if(!current.getAsBoolean())throw new CancellationException();
            String id=identity(feature);
            if(id==null)continue;
            Accumulator object=objects.get(id);
            if(object==null) { object=new Accumulator(feature);objects.put(id,object); }
            // Native source queries may return the same original feature from several loaded tiles.
            if(object.geometries.add(feature.geometry()))accumulate(feature,object,viewport,current);
        }
        List<Label> labels=new ArrayList<>();
        for(Accumulator object:objects.values()) {
            if(!current.getAsBoolean())throw new CancellationException();
            Label label=label(object,viewport.bearing);if(label!=null)labels.add(label);
        }
        return labels;
    }

    /** Complete DB originals only. Tile fragments must use plan(List), which merges their geometry. */
    static final class OriginalPlanner {
        private final Viewport viewport;
        private final BooleanSupplier current;
        private final Map<String,Label> labels=new LinkedHashMap<>();
        OriginalPlanner(double[][] corners,double bearing,BooleanSupplier current) {
            viewport=new Viewport(corners,bearing);this.current=current;
        }
        void accept(Feature original) {
            if(!current.getAsBoolean())throw new CancellationException();
            if(viewport.empty)return;
            String id=identity(original);
            if(id==null || labels.containsKey(id))return;
            Accumulator object=new Accumulator(original,false);
            accumulate(original,object,viewport,current);
            Label candidate=label(object,viewport.bearing);
            if(candidate!=null)labels.put(id,candidate);
            // Neither candidate nor this planner keeps the full geometry/Feature after accept returns.
        }
        List<Label> finish() {
            if(!current.getAsBoolean())throw new CancellationException();
            return new ArrayList<>(labels.values());
        }
    }
    private static String identity(Feature feature) {
        Geometry geometry=feature.geometry();
        if(!(geometry instanceof LineString) && !(geometry instanceof MultiLineString))return null;
        if(!feature.hasProperty(MPLFeaturesUtils.prop_signature_text)
                || feature.getProperty(MPLFeaturesUtils.prop_signature_text).isJsonNull()
                || feature.getStringProperty(MPLFeaturesUtils.prop_signature_text).trim().isEmpty())return null;
        return feature.hasProperty(MPLFeaturesUtils.prop_featureid)
                ?feature.getProperty(MPLFeaturesUtils.prop_featureid).getAsString():feature.id();
    }
    private static void accumulate(Feature feature,Accumulator object,Viewport viewport,BooleanSupplier current) {
        Geometry geometry=feature.geometry();
        List<List<Point>> parts=geometry instanceof LineString
                ?java.util.Collections.singletonList(((LineString)geometry).coordinates())
                :((MultiLineString)geometry).coordinates();
        int checked=0;
        for(List<Point> part:parts) {
            double[] previous=null;
            for(Point point:part) {
                if((checked++ & 1023)==0 && !current.getAsBoolean())throw new CancellationException();
                if(!Double.isFinite(point.longitude()) || !Double.isFinite(point.latitude())){previous=null;continue;}
                double[] next=project(point.longitude(),point.latitude(),viewport.reference);
                if(previous!=null) {
                    double[] segment=clip(previous,next,viewport.clip,viewport.sign);
                    if(segment!=null)object.add(segment,viewport.centerX,viewport.centerY);
                }
                previous=next;
            }
        }
    }
    private static Label label(Accumulator object,double bearing) {
        if(!Double.isFinite(object.nearest))return null;
        double angle=Math.hypot(object.cos,object.sin)>object.length*1e-6
                ?-Math.toDegrees(Math.atan2(object.sin,object.cos)/2)
                :object.maxX-object.minX>=object.maxY-object.minY?0:-90;
        double relative=((angle-bearing+90)%180+180)%180-90;
        double longitude=Math.toDegrees(object.anchorX);
        longitude=((longitude+180)%360+360)%360-180;
        double latitude=Math.toDegrees(2*Math.atan(Math.exp(object.anchorY))-Math.PI/2);
        return new Label(object.feature,Point.fromLngLat(longitude,latitude),angle,relative+bearing);
    }
    private static final class Viewport {
        final double reference,bearing,sign;
        final double[][] clip=new double[4][];
        final boolean empty;
        double centerX,centerY;
        Viewport(double[][] corners,double bearing) {
            if(corners.length!=4)throw new IllegalArgumentException("Four viewport corners required");
            this.bearing=bearing;reference=Math.toRadians(corners[0][0]);double area=0;
            for(int i=0;i<4;i++){
                clip[i]=project(corners[i][0],corners[i][1],reference);
                centerX+=clip[i][0]/4;centerY+=clip[i][1]/4;
            }
            for(int i=0;i<4;i++)area+=clip[i][0]*clip[(i+1)%4][1]-clip[(i+1)%4][0]*clip[i][1];
            empty=Math.abs(area)<1e-20;sign=area>0?1:-1;
        }
    }

    private static double[] project(double longitude,double latitude,double reference) {
        double x=Math.toRadians(longitude);
        x=reference+((x-reference+Math.PI)%(2*Math.PI)+2*Math.PI)%(2*Math.PI)-Math.PI;
        double lat=Math.toRadians(Math.max(-MAX_LATITUDE,Math.min(MAX_LATITUDE,latitude)));
        return new double[]{x,Math.log(Math.tan(Math.PI/4+lat/2))};
    }

    /** Convex clipping in Mercator also handles a rotated/pitched map's true screen quadrilateral. */
    private static double[] clip(double[] a,double[] b,double[][] polygon,double sign) {
        double enter=0,exit=1;
        for(int i=0;i<4;i++) {
            double[] p=polygon[i],q=polygon[(i+1)%4];
            double dx=q[0]-p[0],dy=q[1]-p[1];
            double da=sign*(dx*(a[1]-p[1])-dy*(a[0]-p[0]));
            double db=sign*(dx*(b[1]-p[1])-dy*(b[0]-p[0]));
            if(da<0 && db<0)return null;
            if(da<0)enter=Math.max(enter,da/(da-db));
            else if(db<0)exit=Math.min(exit,da/(da-db));
            if(enter>exit)return null;
        }
        double dx=b[0]-a[0],dy=b[1]-a[1];
        return new double[]{a[0]+enter*dx,a[1]+enter*dy,a[0]+exit*dx,a[1]+exit*dy};
    }

    private static final class Accumulator {
        final Feature feature;
        final Set<Geometry> geometries;
        double cos,sin,length,anchorX,anchorY,nearest=Double.POSITIVE_INFINITY;
        double minX=Double.POSITIVE_INFINITY,minY=Double.POSITIVE_INFINITY;
        double maxX=Double.NEGATIVE_INFINITY,maxY=Double.NEGATIVE_INFINITY;
        Accumulator(Feature feature){this(feature,true);}
        Accumulator(Feature feature,boolean mergeFragments){this.feature=feature;geometries=mergeFragments?new HashSet<>():null;}
        void add(double[] s,double cx,double cy) {
            double dx=s[2]-s[0],dy=s[3]-s[1],size=Math.hypot(dx,dy);
            if(size<=1e-14)return;
            // An axial mean treats opposite digitizing directions as the same road direction.
            cos+=(dx*dx-dy*dy)/size;sin+=2*dx*dy/size;length+=size;
            minX=Math.min(minX,Math.min(s[0],s[2]));maxX=Math.max(maxX,Math.max(s[0],s[2]));
            minY=Math.min(minY,Math.min(s[1],s[3]));maxY=Math.max(maxY,Math.max(s[1],s[3]));
            double t=Math.max(0,Math.min(1,((cx-s[0])*dx+(cy-s[1])*dy)/(size*size)));
            double x=s[0]+t*dx,y=s[1]+t*dy,distance=(x-cx)*(x-cx)+(y-cy)*(y-cy);
            if(distance<nearest){nearest=distance;anchorX=x;anchorY=y;}
        }
    }
}

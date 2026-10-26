package com.nextgis.maplib.map;

import android.graphics.PointF;
import android.util.Log;
import com.nextgis.maplib.api.ILayer;
import com.nextgis.maplib.api.IGISApplication;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static com.nextgis.maplib.util.GeoConstants.*;

/** Repositions only line-label points after the camera/native sources settle; never reopens SQLite. */
final class VisibleLineLabelController implements MapLibreMap.OnCameraMoveListener,
        MapLibreMap.OnCameraIdleListener,MapView.OnDidBecomeIdleListener,MapView.OnSourceChangedListener {
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor(r -> {
        Thread thread=new Thread(r,"VisibleLineLabels");thread.setDaemon(true);return thread;
    });
    private final WeakReference<MapDrawable> owner;
    private final WeakReference<MapLibreMap> renderer;
    private final WeakReference<MapView> view;
    private volatile long generation;
    private boolean dirty=true,busy,moving,awaitingIdle=true,closed,refreshPosted;

    VisibleLineLabelController(MapDrawable owner,MapLibreMap renderer,MapView view) {
        this.owner=new WeakReference<>(owner);this.renderer=new WeakReference<>(renderer);this.view=new WeakReference<>(view);
        renderer.addOnCameraMoveListener(this);renderer.addOnCameraIdleListener(this);
        view.addOnDidBecomeIdleListener(this);view.addOnSourceChangedListener(this);
        postRefresh();
    }
    void close() {
        closed=true;generation++;
        MapLibreMap map=renderer.get();MapView host=view.get();
        if(map!=null){map.removeOnCameraMoveListener(this);map.removeOnCameraIdleListener(this);}
        if(host!=null){host.removeOnDidBecomeIdleListener(this);host.removeOnSourceChangedListener(this);}
    }
    private void invalidate(){dirty=true;generation++;}
    void requestRefresh(){invalidate();awaitingIdle=true;postRefresh();}
    private void postRefresh(){
        MapView host=view.get();if(host==null || refreshPosted)return;
        refreshPosted=true;host.postDelayed(() -> {refreshPosted=false;refresh();},100);
    }
    @Override public void onCameraMove(){moving=true;invalidate();}
    @Override public void onCameraIdle(){
        moving=false;awaitingIdle=true;invalidate();
        postRefresh();
    }
    @Override public void onDidBecomeIdle(){
        if(awaitingIdle){awaitingIdle=false;invalidate();}
        refresh();
    }
    @Override public void onSourceChangedListener(String id){
        MapDrawable map=owner.get();
        if(map==null || id==null || id.endsWith(MPLFeaturesUtils.source_polygon_text))return;
        if(!id.startsWith(map.getPath().toString()))return;
        for(VectorLayer layer:lines(map))if(layer.getPath().toString().equals(id)){invalidate();return;}
    }
    private static List<VectorLayer> lines(LayerGroup group){
        List<VectorLayer> result=new ArrayList<>();
        for(ILayer child:group.getLayers()) {
            if(child instanceof VectorLayer) {
                VectorLayer layer=(VectorLayer)child;
                if(layer.getGeometryType()==GTLineString || layer.getGeometryType()==GTMultiLineString)result.add(layer);
            } else if(child instanceof LayerGroup)result.addAll(lines((LayerGroup)child));
        }
        return result;
    }
    private void refresh(){
        MapDrawable map=owner.get();MapLibreMap nativeMap=renderer.get();MapView host=view.get();
        if(closed || busy || moving || !dirty || map==null || nativeMap==null || host==null
                || host.getWidth()==0 || host.getHeight()==0)return;
        IGISApplication app=(IGISApplication)map.getContext().getApplicationContext();
        if(app.getMap()!=map || map.getMaplibreMap()!=nativeMap || map.getMaplibreMapView()!=host)return;
        Style style=nativeMap.getStyle();if(style==null || !style.isFullyLoaded())return;
        List<VectorLayer> layers=lines(map);
        dirty=false;
        if(layers.isEmpty())return;
        Map<String,List<Feature>> snapshots=new LinkedHashMap<>();
        try {
            double zoom=nativeMap.getCameraPosition().zoom;
            java.util.Set<String> visibleSources=new java.util.HashSet<>();
            for(Layer nativeLayer:style.getLayers())if(nativeLayer instanceof SymbolLayer
                    && !"none".equals(nativeLayer.getVisibility().getValue())
                    && zoom>=nativeLayer.getMinZoom() && zoom<nativeLayer.getMaxZoom())
                visibleSources.add(((SymbolLayer)nativeLayer).getSourceId());
            for(VectorLayer layer:layers){
                String sourceId=layer.getPath().toString();
                GeoJsonSource labels=style.getSourceAs(sourceId+MPLFeaturesUtils.source_polygon_text);
                if(labels==null)continue;
                if(!layer.isVisible() || !visibleSources.contains(labels.getId())){
                    snapshots.put(labels.getId(),new ArrayList<>());continue;
                }
                org.maplibre.android.style.sources.Source source=style.getSource(sourceId);
                if(source instanceof GeoJsonSource) {
                    List<Feature> cached=map.sourceFeaturesHashMap.get(layer.getId());
                    snapshots.put(labels.getId(),cached!=null && !cached.isEmpty()?new ArrayList<>(cached)
                            :((GeoJsonSource)source).querySourceFeatures(null));
                }
            }
        }catch(RuntimeException error){Log.w("VisibleLineLabels","Native line snapshot deferred",error);return;}
        if(snapshots.isEmpty())return;
        double[][] corners=new double[4][2];
        PointF[] pixels={new PointF(0,0),new PointF(host.getWidth(),0),
                new PointF(host.getWidth(),host.getHeight()),new PointF(0,host.getHeight())};
        for(int i=0;i<4;i++){
            LatLng position=nativeMap.getProjection().fromScreenLocation(pixels[i]);
            corners[i][0]=position.getLongitude();corners[i][1]=position.getLatitude();
        }
        double bearing=nativeMap.getCameraPosition().bearing;
        long token=generation;busy=true;
        WORKER.execute(() -> {
            Map<String,List<VisibleLineLabelLayout.Label>> result=new LinkedHashMap<>();
            try {
                for(Map.Entry<String,List<Feature>> entry:snapshots.entrySet())result.put(entry.getKey(),
                        VisibleLineLabelLayout.plan(entry.getValue(),corners,bearing,() -> !closed && generation==token));
            }catch(CancellationException ignored){ }
            catch(RuntimeException error){Log.w("VisibleLineLabels","Line label layout failed",error);result.clear();}
            host.post(() -> {
                try {
                    if(!closed && generation==token && app.getMap()==map
                            && map.getMaplibreMap()==nativeMap && map.getMaplibreMapView()==host
                            && nativeMap.getStyle()==style)
                        for(Map.Entry<String,List<VisibleLineLabelLayout.Label>> entry:result.entrySet()){
                            GeoJsonSource target=style.getSourceAs(entry.getKey());
                            if(target!=null){
                                List<Feature> points=new ArrayList<>();
                                for(VisibleLineLabelLayout.Label label:entry.getValue())points.add(label.toFeature());
                                target.setGeoJson(FeatureCollection.fromFeatures(points));
                            }
                        }
                }catch(RuntimeException error){Log.w("VisibleLineLabels","Line label apply deferred",error);}
                finally{busy=false;if(dirty && !moving)refresh();}
            });
        });
    }
}

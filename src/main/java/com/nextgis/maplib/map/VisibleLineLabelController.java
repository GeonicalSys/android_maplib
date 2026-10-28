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
import java.util.function.BooleanSupplier;
import static com.nextgis.maplib.util.GeoConstants.*;

/** Repositions line-label points; streamed originals are read by viewport on the IO worker only. */
final class VisibleLineLabelController implements MapLibreMap.OnCameraMoveListener,
        MapLibreMap.OnCameraIdleListener,MapView.OnDidBecomeIdleListener,MapView.OnSourceChangedListener {
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor(r -> {
        Thread thread=new Thread(r,"VisibleLineLabels");thread.setDaemon(true);return thread;
    });
    private final WeakReference<MapDrawable> owner;
    private final WeakReference<MapLibreMap> renderer;
    private final WeakReference<MapView> view;
    private volatile long generation;
    private volatile boolean closed;
    private boolean dirty=true,busy,moving,awaitingIdle=true,refreshPosted;

    private static final class Input {
        final VectorLayer layer;
        final String labelId;
        final GeoJsonSource labels;
        final org.maplibre.android.style.sources.Source geometrySource;
        final String geometrySourceId;
        final SqliteVectorTileProvider provider;
        final List<Feature> classic;
        final long contentVersion;
        final boolean visible;
        Input(VectorLayer layer, GeoJsonSource labels, org.maplibre.android.style.sources.Source geometrySource,
              SqliteVectorTileProvider provider, List<Feature> classic) {
            this.layer=layer;this.labels=labels;labelId=labels.getId();this.geometrySource=geometrySource;
            geometrySourceId=geometrySource.getId();
            this.provider=provider;this.classic=classic;contentVersion=layer.renderContentVersion();visible=layer.isVisible();
        }
        boolean currentData() { return layer.renderContentVersion()==contentVersion && (provider==null || provider.isCurrent()); }
        boolean currentOnUi(MapDrawable map,Style style) {
            return currentData() && map.getLayerById(layer.getId())==layer && layer.isVisible()==visible
                    && style.getSource(labelId)==labels && style.getSource(geometrySourceId)==geometrySource
                    && (provider==null || map.isSqliteVectorLayer(layer.getId())
                        && map.getSqliteVectorProvider(layer.getId())==provider);
        }
    }

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
        for(VectorLayer layer:lines(map))if(id.equals(map.getVectorRenderSourceId(layer.getId()))){invalidate();return;}
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
        Map<String,Input> inputs=new LinkedHashMap<>();
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
                org.maplibre.android.style.sources.Source source=style.getSource(map.getVectorRenderSourceId(layer.getId()));
                if(source==null)continue;
                if(!layer.isVisible() || !visibleSources.contains(labels.getId())){
                    inputs.put(labels.getId(),new Input(layer,labels,source,null,new ArrayList<>()));continue;
                }
                if(map.isSqliteVectorLayer(layer.getId())) {
                    SqliteVectorTileProvider provider=map.getSqliteVectorProvider(layer.getId());
                    if(provider!=null && provider.isCurrent())inputs.put(labels.getId(),new Input(layer,labels,source,provider,null));
                } else if(source instanceof GeoJsonSource) {
                    List<Feature> cached=map.sourceFeaturesHashMap.get(layer.getId());
                    inputs.put(labels.getId(),new Input(layer,labels,source,null,
                            cached!=null && !cached.isEmpty()?new ArrayList<>(cached)
                            :((GeoJsonSource)source).querySourceFeatures(null)));
                }
            }
        }catch(RuntimeException error){Log.w("VisibleLineLabels","Native line snapshot deferred",error);return;}
        if(inputs.isEmpty())return;
        double[][] corners=new double[4][2];
        PointF[] pixels={new PointF(0,0),new PointF(host.getWidth(),0),
                new PointF(host.getWidth(),host.getHeight()),new PointF(0,host.getHeight())};
        for(int i=0;i<4;i++){
            LatLng position=nativeMap.getProjection().fromScreenLocation(pixels[i]);
            corners[i][0]=position.getLongitude();corners[i][1]=position.getLatitude();
        }
        double bearing=nativeMap.getCameraPosition().bearing;
        double centerLongitude=nativeMap.getCameraPosition().target.getLongitude();
        long token=generation;busy=true;
        WORKER.execute(() -> {
            Map<String,List<VisibleLineLabelLayout.Label>> result=new LinkedHashMap<>();
            try {
                for(Input input:inputs.values()) {
                    BooleanSupplier current=() -> !closed && generation==token && input.currentData();
                    result.put(input.labelId,input.provider==null
                            ?VisibleLineLabelLayout.plan(input.classic,corners,bearing,current)
                            :VisibleLineLabelQuery.planStreaming(corners,centerLongitude,bearing,input.provider::visitVisibleLines,current));
                }
            }catch(CancellationException ignored){result.clear();}
            catch(Exception error){Log.w("VisibleLineLabels","Line label layout failed",error);result.clear();}
            host.post(() -> {
                try {
                    if(!closed && generation==token && app.getMap()==map
                            && map.getMaplibreMap()==nativeMap && map.getMaplibreMapView()==host
                            && nativeMap.getStyle()==style)
                        for(Map.Entry<String,List<VisibleLineLabelLayout.Label>> entry:result.entrySet()){
                            Input input=inputs.get(entry.getKey());
                            if(input.currentOnUi(map,style)){
                                List<Feature> points=new ArrayList<>();
                                for(VisibleLineLabelLayout.Label label:entry.getValue())points.add(label.toFeature());
                                MPLFeaturesUtils.setNativeLabelGeoJson(input.labels,"line_labels",points);
                            }
                        }
                }catch(RuntimeException error){Log.w("VisibleLineLabels","Line label apply deferred",error);}
                finally{busy=false;if(dirty && !moving)refresh();}
            });
        });
    }
}

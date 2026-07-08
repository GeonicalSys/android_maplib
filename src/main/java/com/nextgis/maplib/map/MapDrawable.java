/*
 * Project:  NextGIS Mobile
 * Purpose:  Mobile GIS for Android.
 * Author:   Dmitry Baryshnikov (aka Bishop), bishop.dev@gmail.com
 * Author:   NikitaFeodonit, nfeodonit@yandex.com
 * Author:   Stanislav Petriakov, becomeglory@gmail.com
 * *****************************************************************************
 * Copyright (c) 2012-2017 NextGIS, info@nextgis.com
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.nextgis.maplib.map;
import android.accounts.AccountManager;
import android.app.AlertDialog;
import android.app.Fragment;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hypertrack.hyperlog.HyperLog;
import com.nextgis.maplib.R;
import com.nextgis.maplib.api.IGISApplication;
import com.nextgis.maplib.api.ILayer;
import com.nextgis.maplib.api.IMapView;
import com.nextgis.maplib.api.ITextStyle;
import com.nextgis.maplib.datasource.Feature;
import com.nextgis.maplib.datasource.GeoEnvelope;
import com.nextgis.maplib.datasource.GeoGeometry;
import com.nextgis.maplib.datasource.GeoLineString;
import com.nextgis.maplib.datasource.GeoMultiLineString;
import com.nextgis.maplib.datasource.GeoMultiPoint;
import com.nextgis.maplib.datasource.GeoMultiPolygon;
import com.nextgis.maplib.datasource.GeoPoint;
import com.nextgis.maplib.datasource.GeoPolygon;
import com.nextgis.maplib.datasource.ngw.Connection;
import com.nextgis.maplib.datasource.ngw.Connections;
import com.nextgis.maplib.display.GISDisplay;
import com.nextgis.maplib.display.RuleFeatureRenderer;
import com.nextgis.maplib.map.MLP.MLGeometryEditClass;
import com.nextgis.maplib.map.MLP.MeasurmentLine;
import com.nextgis.maplib.map.MLP.MultiLineEditClass;
import com.nextgis.maplib.map.MLP.MultiPointEditClass;
import com.nextgis.maplib.map.MLP.MultiPolygonEditClass;
import com.nextgis.maplib.map.MLP.PointEditClass;
import com.nextgis.maplib.map.MLP.PolygonEditClass;
import com.nextgis.maplib.util.Constants;
import com.nextgis.maplib.util.GeoConstants;
import com.nextgis.maplib.util.MapUtil;
import com.nextgis.maplib.util.ProdLogUtil;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.nextgis.maplib.map.MLP.MultiLineEditClass.getNewLinePoints;
import static com.nextgis.maplib.map.MLP.PolygonEditClass.createPointsForRing;
import static com.nextgis.maplib.map.MPLFeaturesUtils.applyTextAndStyle;
import static com.nextgis.maplib.map.MPLFeaturesUtils.colorBlue;
import static com.nextgis.maplib.map.MPLFeaturesUtils.colorLightBlue;
import static com.nextgis.maplib.map.MPLFeaturesUtils.colorRED;
import static com.nextgis.maplib.map.MPLFeaturesUtils.convert3857To4326;
import static com.nextgis.maplib.map.MPLFeaturesUtils.convert4326To3857;
import static com.nextgis.maplib.map.MPLFeaturesUtils.convertToPointFeatures;
import static com.nextgis.maplib.map.MPLFeaturesUtils.createFeatureListFromLayer;
import static com.nextgis.maplib.map.MPLFeaturesUtils.needsSourceStyleRefresh;
import static com.nextgis.maplib.map.MPLFeaturesUtils.refreshMaplibreStyleOnFeatures;
import static com.nextgis.maplib.map.MPLFeaturesUtils.createFeatureListFromTrackLayer;
import static com.nextgis.maplib.map.MPLFeaturesUtils.createFillLayerForLayer;
import static com.nextgis.maplib.map.MPLFeaturesUtils.createFillLayerForLocalVectorTileLayer;
import static com.nextgis.maplib.map.MPLFeaturesUtils.createLocalVectorTileSourceForLayer;
import static com.nextgis.maplib.map.MPLFeaturesUtils.createSourceForLayer;
import static com.nextgis.maplib.map.MPLFeaturesUtils.geoPointFromLatLng;
import static com.nextgis.maplib.map.MPLFeaturesUtils.getFeatureFromNGFeatureLine;
import static com.nextgis.maplib.map.MPLFeaturesUtils.getFeatureFromNGFeatureMultiLine;
import static com.nextgis.maplib.map.MPLFeaturesUtils.getFeatureFromNGFeatureMultiPoint;
import static com.nextgis.maplib.map.MPLFeaturesUtils.getFeatureFromNGFeatureMultiPolygon;
import static com.nextgis.maplib.map.MPLFeaturesUtils.getFeatureFromNGFeaturePoint;
import static com.nextgis.maplib.map.MPLFeaturesUtils.getFeatureFromNGFeaturePolygon;
import static com.nextgis.maplib.map.MPLFeaturesUtils.getLayerSignatureField;
import static com.nextgis.maplib.map.MPLFeaturesUtils.getMPLThinkness;
import static com.nextgis.maplib.map.MPLFeaturesUtils.getRasterLayer;
import static com.nextgis.maplib.map.MPLFeaturesUtils.id_name;
import static com.nextgis.maplib.map.MPLFeaturesUtils.latLngPointFromGeoPoint;
import static com.nextgis.maplib.map.MPLFeaturesUtils.layer_namepart;
import static com.nextgis.maplib.map.MPLFeaturesUtils.namePrefix;
import static com.nextgis.maplib.map.MPLFeaturesUtils.outline_namepart;
import static com.nextgis.maplib.map.MPLFeaturesUtils.pattern_namepart;
import static com.nextgis.maplib.map.MPLFeaturesUtils.prop_featureid;
import static com.nextgis.maplib.map.MPLFeaturesUtils.prop_layerid;
import static com.nextgis.maplib.map.MPLFeaturesUtils.prop_order;
import static com.nextgis.maplib.map.MPLFeaturesUtils.prop_signature_text;
import static com.nextgis.maplib.map.MPLFeaturesUtils.source_namepart;
import static com.nextgis.maplib.map.MPLFeaturesUtils.source_polygon_text;
import static com.nextgis.maplib.map.mpl.PointLayerFactory.MARKER_ICON_LAYER_SUFFIX;
import static com.nextgis.maplib.util.Constants.DRAW_FINISH_ID;
import static com.nextgis.maplib.util.Constants.MAP_LIMITS_Y;
import static com.nextgis.maplib.util.Constants.MESSAGE_INTENT_STYLING;
import static com.nextgis.maplib.util.Constants.TAG;
import static com.nextgis.maplib.util.GeoConstants.GTLineString;
import static com.nextgis.maplib.util.GeoConstants.GTMultiLineString;
import static com.nextgis.maplib.util.GeoConstants.GTMultiPoint;
import static com.nextgis.maplib.util.GeoConstants.GTMultiPolygon;
import static com.nextgis.maplib.util.GeoConstants.GTNone;
import static com.nextgis.maplib.util.GeoConstants.GTPoint;
import static com.nextgis.maplib.util.GeoConstants.GTPolygon;
import static com.nextgis.maplib.util.GeoConstants.GT_MEASURMENT;
import static com.nextgis.maplib.util.GeoConstants.GT_RASTER_WA;
import static com.nextgis.maplib.util.GeoConstants.GT_TRACK_WA;
import static com.nextgis.maplib.util.NetworkUtil.extractResourceValue;
import static com.nextgis.maplib.util.NetworkUtil.fillConnections;
import static com.nextgis.maplib.util.NetworkUtil.getBaseUrlpart;
import static com.nextgis.maplib.util.NetworkUtil.getHTTPBaseAuth;
import static com.nextgis.maplib.util.SettingsConstants.KEY_PREF_DARK;
import static com.nextgis.maplib.util.SettingsConstants.KEY_PREF_LIGHT;
import static com.nextgis.maplib.util.SettingsConstants.KEY_PREF_NEUTRAL;
import static org.maplibre.android.style.layers.Property.NONE;
import static org.maplibre.android.style.layers.Property.VISIBLE;
import static org.maplibre.android.style.layers.PropertyFactory.rasterBrightnessMax;
import static org.maplibre.android.style.layers.PropertyFactory.rasterBrightnessMin;
import static org.maplibre.android.style.layers.PropertyFactory.rasterContrast;
import static org.maplibre.android.style.layers.PropertyFactory.rasterHueRotate;
import static org.maplibre.android.style.layers.PropertyFactory.rasterOpacity;
import static org.maplibre.android.style.layers.PropertyFactory.rasterSaturation;
import static org.maplibre.android.style.layers.PropertyFactory.visibility;
import static java.util.Collections.emptyList;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.maplibre.android.annotations.Icon;
import org.maplibre.android.annotations.IconFactory;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Projection;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.BackgroundLayer;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.FillLayer;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.Property;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.android.style.sources.Source;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.MultiLineString;
import org.maplibre.geojson.MultiPoint;
import org.maplibre.geojson.MultiPolygon;
import org.maplibre.geojson.Point;
import org.maplibre.geojson.Polygon;


public class MapDrawable
        extends MapEventSource
        implements IMapView,
        View.OnTouchListener,
        MapLibreMap.OnMapLongClickListener,
        MapLibreMap.OnMapClickListener {

    public final static int MODE_NONE = 0;
    public final static int MODE_HIGHLIGHT = 1;
    public final static int MODE_EDIT = 2;
    public final static int MODE_CHANGE = 3;
    public final static int MODE_EDIT_BY_WALK = 4;
    public final static int MODE_EDIT_BY_TOUCH = 5;

    static int testColor = 0;


    // map  layerID : list of added features for layer
    LinkedHashMap<Integer, List<org.maplibre.geojson.Feature>> sourceFeaturesHashMap = new LinkedHashMap<Integer, List<org.maplibre.geojson.Feature>>();

    private final ExecutorService mMaplibreVectorReloadExecutor =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "MaplibreVectorReload"));

    private final java.util.concurrent.ConcurrentHashMap<Integer, Long> mVectorLayerReloadTokens =
            new java.util.concurrent.ConcurrentHashMap<>();

    LinkedHashMap<Integer, List<org.maplibre.geojson.Feature>> sourcesOrder = new LinkedHashMap<Integer, List<org.maplibre.geojson.Feature>>();

    // map sources added to maplibre  from layers
    LinkedHashMap<String, GeoJsonSource>  sourceHashMap = new LinkedHashMap<String, GeoJsonSource>();

    /** Per-layer native GeoJSON file URI when {@link VectorLayerRenderCache#USE_MAPLIBRE_NATIVE_GEOJSON_URI} is on. */
    LinkedHashMap<Integer, java.net.URI> sourceNativeUriMap = new LinkedHashMap<>();

    /** Per-layer local MVT URL for {@code layer_origin.render_mode=local_vector_tiles}. */
    LinkedHashMap<Integer, String> localVectorTileUrlMap = new LinkedHashMap<>();

    // map fill Layer of each added layer
    LinkedHashMap<Integer, org.maplibre.android.style.layers.Layer>  layersHashMap = new LinkedHashMap<Integer, org.maplibre.android.style.layers.Layer>();

    // outline for polygone
    LinkedHashMap<Integer, org.maplibre.android.style.layers.Layer>  layersHashMap2 = new LinkedHashMap<Integer, org.maplibre.android.style.layers.Layer>();

    // dashed line sublayer (same source, filter filltype==2)
    LinkedHashMap<Integer, List<org.maplibre.android.style.layers.Layer>> layersHashMapLineDash =
            new LinkedHashMap<>();

    // Symbols for geometry signature
    LinkedHashMap<Integer, org.maplibre.android.style.layers.Layer>  symbolsLayerHashMap = new LinkedHashMap<Integer, org.maplibre.android.style.layers.Layer>();

    GeoJsonSource selectedEditedSource = null; // choosed  source - from with edit (selectable)
    GeoJsonSource selectedPolySource = null; // choosed source of polygon/line  //
    GeoJsonSource selectedDotSource = null; // choosed source of polygon  //
    GeoJsonSource signaturesRootLayerSource = null; // choosed source of polygon  //

    GeoJsonSource tracksLineSource = null; // constant tracks
    GeoJsonSource trackInProgressSource = null; // flags ( start/stop ) tracks

    CircleLayer selectedDotCircleLayer = null;

    CircleLayer signaturesRootLayer = null;



    GeoJsonSource vertexSource = null;      // edit points  //

    FillLayer fillPolyEditLayer = null; // fill poly on edit layer (while on move points)

    public org.maplibre.geojson.Feature hiddedFeature = null;
    Long hiddedFeatureId = null;
    int hiddedlayerdID = -1;

    FeatureCollection markerFeatureCollection = FeatureCollection.fromFeatures(new ArrayList<>());
    GeoJsonSource markerSource = null; // marker source - select point

    GeoJsonSource locationSource = null;

    List<org.maplibre.geojson.Feature> polygonFeatures = new ArrayList<org.maplibre.geojson.Feature>();  //

    PointF clickPoint = null;

    public Feature  originalSelectedFeature = null;            // original who edit

    public MLGeometryEditClass editingObject = null;    // current edit

    public boolean isMeasurmentChangeVertex = false;
    private org.maplibre.geojson.Feature  editingFeature = null;    // current edit
    private org.maplibre.geojson.Feature  editingFeatureOriginal = null;
    public org.maplibre.geojson.Feature  viewedFeature = null;   // who looking
    private boolean hasEditeometry = false; // was edit

    private boolean isDragging = false;
    private boolean isDraggingByTouchGPS = false;
    private boolean isSwitchVertex = false;
    private MotionEvent startEvent = null;
    private PointF deltaPoint = null;

    public float zoomSaved = 1.0f; // one time used zoom after map start
    public GeoPoint centerSaved = new GeoPoint(0,0); //

    protected int  mLimitsType;
    protected RunnableFuture<Void> mDrawThreadTask;

    public WeakReference<MaplibreMapInteraction> mapContext = new WeakReference(null);

    WeakReference<MapLibreMap> maplibreMap = new WeakReference(null);
    WeakReference<org.maplibre.android.maps.MapView> maplibreMapView = new WeakReference(null);

    /** Monotonic id so only the latest {@link #loadLayersToMaplibreMap} style callback applies UI teardown. */
    private final AtomicInteger mLoadLayersStyleRequestId = new AtomicInteger(0);

    /** Prevents re-entrant layer loading: invalidation during load is deferred. */
    private volatile boolean mLayerLoadInProgress = false;
    private volatile boolean mPendingReload = false;
    /** At most one deferred full reload per started {@link #loadLayersToMaplibreMap} run. */
    private volatile boolean mDeferReloadOnceAllowed = true;

    @Nullable
    private MapView.OnDidFinishLoadingStyleListener mLoadLayersStyleListener;

    /* Upstream: state for restoring an active walk-by-geometry edit after process kill. */
    VectorLayer layerForWalkRestore = null;
    Feature featureToRestore = null;

    public MapDrawable(
            Bitmap backgroundTile,
            Context context,
            File mapPath,
            LayerFactory layerFactory) {
        super(context, mapPath, layerFactory);

        //initialise display
        mDisplay = new GISDisplay(backgroundTile);
        mLimitsType = MAP_LIMITS_Y;
    }

    public void setMapContext(final MaplibreMapInteraction mapContext){
        this.mapContext = new WeakReference<>(mapContext);
    }

    public void setMaplibreMap(final MapLibreMap maplibreMap){
        this.maplibreMap = new WeakReference<>(maplibreMap);
    }

    public void setMaplibreMapView(final org.maplibre.android.maps.MapView maplibreMapView){
        this.maplibreMapView = new WeakReference<>(maplibreMapView);

    }


    public void loadMarkersTopPart(){

        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.post(() -> {
            loadLayersToMaplibreMapLite(new ArrayList<>(), true);
        });
    }

    /**
     * Logs an error to both logcat (with throwable) and HyperLog (full stack embedded in the message
     * so it reaches the exported log file; HyperLog itself persists only the message text).
     */
    static void logErr(String where, Throwable t) {
        Log.e(TAG, where, t);
        try {
            HyperLog.e(TAG, ProdLogUtil.withStack(where, t));
        } catch (Throwable ignored) {
        }
    }

    /**
     * Posts a MapLibre style/source mutation to the main thread wrapped in a Throwable guard.
     * A failure while building one layer must not crash the app or abort unrelated layers; the
     * failure is logged (with layer/operation context) and the rest of the work continues.
     */
    private void postMainGuarded(final String where, final Runnable block) {
        Handler h = new Handler(Looper.getMainLooper());
        h.post(() -> runGuarded(where, block));
    }

    /** Runs a block on the current thread under a Throwable guard, logging context on failure. */
    private void runGuarded(final String where, final Runnable block) {
        try {
            block.run();
        } catch (Throwable t) {
            logErr("MapLibre op failed: " + where, t);
        }
    }

    public void clearMaplLibreMap(){

        MapLibreMap map = maplibreMap.get();
        if (map == null) return;
        final Style style = map.getStyle();
        if (style == null) return;
        for (final Layer layer : style.getLayers()){
//            Log.e("ZXZY", "delete layer" + layer.getId());
            if (!layer.getId().equals("background"))
                style.removeLayer(layer);
        }
        // clear all layers - so - first tool layer also clear
        signaturesRootLayer = null;
        selectedDotCircleLayer = null;

//        for (final Source source : maplibreMap.get().getStyle().getSources()) {
//            Log.e("ZXZY", "delete source" + source.getId());
//            boolean result = style.removeSource(source);
//        }



//
//        this.maplibreMap.get().clear();
//
//        this.maplibreMap.clear();
//        this.maplibreMapView.get().onPause();
//        this.maplibreMapView.get().onStop();
//        this.maplibreMapView.get().onDestroy();
//
//
//        this.maplibreMapView.clear();
//
//
//        this.maplibreMap = new WeakReference<>(null);
//        this.maplibreMapView = new WeakReference<>(null);
    }

    public MapLibreMap getMaplibreMap(){
        return this.maplibreMap.get();
    }

    public org.maplibre.android.maps.MapView getMaplibreMapView(){
        return this.maplibreMapView.get();
    }

    public void reloadLayerByID(int id){

        List<ILayer> ret = new ArrayList<>();
        LayerGroup.getVectorLayersByType(this, GeoConstants.GTAnyCheck, ret);
        for (ILayer iLayer : ret){
            if (iLayer.getId() == id){
                reloadVectorLayerDataToMaplibre(iLayer);
                return;
            }
        }
    }


    // change feature id at map objects - features // objects
    public void changeFeatureId(Long oldFeatureId,Long newFeatureId, int layerId){

        String oldFeatureIdString = String.valueOf(oldFeatureId);
        List<org.maplibre.geojson.Feature> layerFeatures = sourceFeaturesHashMap.get(layerId);
        if (layerFeatures == null) return;
        for (org.maplibre.geojson.Feature feature : layerFeatures){
            if (feature.getStringProperty(prop_featureid).equals(oldFeatureIdString)) {
                feature.addStringProperty(prop_featureid, String.valueOf(newFeatureId));
                break;// only one feature with same id
            }
        }

        for (org.maplibre.geojson.Feature feature : polygonFeatures){
            if (feature.getStringProperty(prop_featureid).equals(oldFeatureIdString)) {
                feature.addStringProperty(prop_featureid, String.valueOf(newFeatureId));
                break;// only one feature with same id
            }
        }
    }

    public void recreateNGWWebMapSourceById(final String path, int id){

        MapLibreMap map = maplibreMap.get();
        if (map == null) {
            return;
        }
        Style style = map.getStyle();
        if (style == null) {
            return;
        }

        if (style.getSource(path)!= null)
            style.removeSource(path);

        ILayer iLayer = getLayerById(id);
        if (!(iLayer instanceof NGWRasterLayer)) {
            logErr("recreateNGWWebMapSourceById: layer id=" + id + " missing or not NGWRasterLayer",
                    new IllegalStateException("layer=" + iLayer));
            return;
        }

        Map<Integer, String> rasterLayersURL = new HashMap<>();
        Map<Integer, Integer> rasterLayersTmsTypeMap = new HashMap<>();
        rasterLayersTmsTypeMap.put(iLayer.getId(), -1);
        rasterLayersURL.put(iLayer.getId(), ((NGWRasterLayer) iLayer).getURL());

        postMainGuarded("recreateNGWWebMapSourceById id=" + id, () -> {
            createSourceForLayer(iLayer.getId(), GT_RASTER_WA,
                    new ArrayList<>(),
                    style, sourceHashMap,
                    rasterLayersURL, rasterLayersTmsTypeMap,
                    iLayer.getPath().toString(), true, null);
        });
    }

    public void deleteLayerByID(int id){
        localVectorTileUrlMap.remove(id);
        LocalVectorTileServer.getInstance().unregisterLayer(id);
        MapLibreMap map = maplibreMap.get();
        if (map == null) {
            return;
        }
        Style style = map.getStyle();
        if (style == null) {
            return;
        }
        String sourceId = namePrefix + source_namepart + id;

        String vectorLayerId = namePrefix + layer_namepart + id;

        String vectorLayerId2 = namePrefix + layer_namepart + id + outline_namepart;

        String vectorLayerIdPattern = namePrefix + layer_namepart + id + pattern_namepart;

        String vectorLayerIdMarkerIcon = namePrefix + layer_namepart + id + MARKER_ICON_LAYER_SUFFIX;

        String currentNamePrefixSymbol = "symbol-" +  namePrefix;
        String vectorLayerIdSymbols =currentNamePrefixSymbol + layer_namepart + id;

        if (style.getLayer(vectorLayerId)!= null)
            style.removeLayer(vectorLayerId);

        if (style.getLayer(vectorLayerId2)!= null)
            style.removeLayer(vectorLayerId2);

        if (style.getLayer(vectorLayerIdPattern)!= null)
            style.removeLayer(vectorLayerIdPattern);

        if (style.getLayer(vectorLayerIdMarkerIcon)!= null)
            style.removeLayer(vectorLayerIdMarkerIcon);

        if (style.getLayer(vectorLayerIdSymbols)!= null)
            style.removeLayer(vectorLayerIdSymbols);


        if (style.getSource(sourceId)!= null)
            style.removeSource(sourceId);
    }

    public void addLayerByID(int id){

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler mainHandler = new Handler(Looper.getMainLooper());
        executor.execute(() -> {
            ILayer crashLayer = null;
            if (maplibreMap.get() != null)
                crashLayer = LayerGroup.getVectorLayersById(this, id);
            try {
                if (maplibreMap.get() != null) {
                    ILayer iLayer = LayerGroup.getVectorLayersById(this, id);
                    if (iLayer == null)
                        return;

                    final AccountManager accountManager = AccountManager.get(getContext());
                    final Connections connections = fillConnections(getContext(), accountManager);

                    if (iLayer instanceof NGWRasterLayer) {
                        // need add auth
                        Connection found = null;
                        if (iLayer instanceof NGWRasterLayer) {
                            for (int i = 0; i < connections.getChildrenCount(); i++) {
                                if (connections.getChild(i).getName().equals((((NGWRasterLayer) iLayer).getAccountName()))) {
                                    found = (Connection) connections.getChild(i);
                                    String basicAuth = getHTTPBaseAuth(found.getLogin(), found.getPassword());
                                    if (null != basicAuth) {
                                        final String url = ((NGWRasterLayer) iLayer).getURL();
                                        final String getBaseUrl = getBaseUrlpart(url);
                                        final String resPart = "resource=" + extractResourceValue(url);

                                        final String[] authPart = new String[4];
                                        authPart[0] = getBaseUrl;
                                        authPart[1] = resPart;
                                        authPart[2] = basicAuth;
                                        authPart[3] = iLayer.getPath().toString();
                                        ((IGISApplication) getContext().getApplicationContext()).updateAuthPair(authPart);
                                        break;
                                    }
                                }
                            }
                        }
                    } else if (iLayer instanceof RemoteTMSLayer) {
                        final String url = ((RemoteTMSLayer) iLayer).getURL();
                        final String getBaseUrl = getBaseUrlpart(url);
                        final String resPart = "resource=" + extractResourceValue(url);
                        final String[] authPart = new String[4];
                        authPart[0] = getBaseUrl;
                        authPart[1] = resPart;
                        authPart[2] = "no";//no auth RemoteTMSLayer - geoservice map
                        authPart[3] = iLayer.getPath().toString();
                        ((IGISApplication) getContext().getApplicationContext()).updateAuthPair(authPart);
                    }

                    int geoType = GTNone;
                    final List<org.maplibre.geojson.Feature> vectorPolygonFeatures = new ArrayList<>();
                    Map<Integer, String> rasterLayersURLMap = new HashMap<>();
                    Map<Integer, Integer> rasterLayersTmsTypeMap = new HashMap<>();
                    com.nextgis.maplib.display.Style ngStyle = null;

                    if (iLayer instanceof VectorLayer) {
                        VectorLayer layer = (VectorLayer) iLayer;
                        geoType = layer.getGeometryType();
                        String tileUrl = null;
                        if (LocalVectorTileRenderMode.shouldUseLocalVectorTiles(layer)) {
                            tileUrl = LocalVectorTileServer.getInstance().registerLayer(layer);
                        }
                        if (tileUrl != null) {
                            sourceFeaturesHashMap.put(layer.getId(), vectorPolygonFeatures);
                            sourcesOrder.put(layer.getId(), new ArrayList<>());
                            sourceNativeUriMap.remove(layer.getId());
                            localVectorTileUrlMap.put(layer.getId(), tileUrl);
                            ngStyle = ((VectorLayer) iLayer).getDefaultStyleNoExcept();
                            HyperLog.d(Constants.TAG, "Local vector tiles enabled hot-add layer=\""
                                    + layer.getName() + "\" url=" + tileUrl);
                        } else {
                        logLocalVectorTilesFallback(layer);
                        // this layer

                        ((IGISApplication) getContext().getApplicationContext()).setGetingStyleInProgress(true);

                        mainHandler.post(()-> {
                                    if (mapContext.get()!= null)
                                        mapContext.get().changeProgress(true);
                        });
                        try {
                            List<org.maplibre.geojson.Feature> fromCache =
                                    VectorLayerRenderCache.tryLoadFeatures(layer);
                            if (fromCache != null) {
                                vectorPolygonFeatures.addAll(fromCache);
                            } else {
                                Intent msg = new Intent(MESSAGE_INTENT_STYLING);
                                String loadHint = getContext().getString(R.string.process_layer_hint);
                                msg.putExtra("msg", loadHint + ((VectorLayer) iLayer).getName());
                                msg.setPackage(getContext().getPackageName());
                                getContext().sendBroadcast(msg);
                                vectorPolygonFeatures.addAll(createFeatureListFromLayer(layer));
                                VectorLayerRenderCache.save(layer, vectorPolygonFeatures);
                            }
                            sourceFeaturesHashMap.put(layer.getId(), vectorPolygonFeatures);
                            sourcesOrder.put(layer.getId(), new ArrayList<>());
                            localVectorTileUrlMap.remove(layer.getId());
                            ngStyle = ((VectorLayer) iLayer).getDefaultStyleNoExcept();

                        } catch (Exception ex) {
                            logErr("addLayerByID prep layer=" + layer.getName(), ex);

                        } finally {
                            Intent msg1 = new Intent(MESSAGE_INTENT_STYLING);
                            msg1.putExtra("msg", "");
                            msg1.setPackage(getContext().getPackageName());

                            getContext().sendBroadcast(msg1);

                            ((IGISApplication) getContext().getApplicationContext()).setGetingStyleInProgress(false);
                            mainHandler.post(()-> {
                                mapContext.get().changeProgress(false);
                            });
                        }
                        }
                    } else if (iLayer instanceof NGWRasterLayer) {
                        geoType = GT_RASTER_WA;
                        NGWRasterLayer layer = (NGWRasterLayer) iLayer;
                        rasterLayersURLMap.put(layer.getId(), ((NGWRasterLayer) layer).getURL());
                        rasterLayersTmsTypeMap.put(layer.getId(), -1);
                    } else if (iLayer instanceof RemoteTMSLayer) {
                        if (((RemoteTMSLayer) iLayer).mIsOfflineLayer) {
                            geoType = GT_RASTER_WA;
                            RemoteTMSLayer layer = (RemoteTMSLayer) iLayer;
                            rasterLayersURLMap.put(layer.getId(), "file://" + (layer).getPath().toString() + "/{z}/{x}/{y}.tile");
                            rasterLayersTmsTypeMap.put(layer.getId(), layer.getTMSType());
                        } else {
                            geoType = GT_RASTER_WA;
                            RemoteTMSLayer layer = (RemoteTMSLayer) iLayer;
                            rasterLayersURLMap.put(layer.getId(), (layer).getURLSubdomain());
                            rasterLayersTmsTypeMap.put(layer.getId(), layer.getTMSType());
                        }
                    } else if (iLayer instanceof LocalTMSLayer) {
                        geoType = GT_RASTER_WA;
                        LocalTMSLayer layer = (LocalTMSLayer) iLayer;
                        rasterLayersURLMap.put(layer.getId(), "file://" + (layer).getPath().toString() + "/{z}/{x}/{y}.tile");
                        rasterLayersTmsTypeMap.put(layer.getId(), layer.getTMSType());
                    }

                    final int finalGeoType = geoType;
                    final com.nextgis.maplib.display.Style finalStyle = ngStyle;

                    postMainGuarded("addLayerByID id=" + iLayer.getId() + " name=" + iLayer.getName(), () -> {
                        if (maplibreMap.get() == null) {
                            return;
                        }
                        Style mainStyle = maplibreMap.get().getStyle();
                        if (mainStyle == null) {
                            return;
                        }
                        String localVectorTileUrl = localVectorTileUrlMap.get(iLayer.getId());
                        if (localVectorTileUrl != null) {
                            boolean sourceOk = createLocalVectorTileSourceForLayer(
                                    iLayer.getId(),
                                    mainStyle,
                                    iLayer.getPath().toString(),
                                    localVectorTileUrl,
                                    iLayer instanceof com.nextgis.maplib.map.Layer
                                            ? ((com.nextgis.maplib.map.Layer) iLayer).getMinZoom()
                                            : -1,
                                    iLayer instanceof com.nextgis.maplib.map.Layer
                                            ? ((com.nextgis.maplib.map.Layer) iLayer).getMaxZoom()
                                            : -1);
                            if (!sourceOk) {
                                Log.w(TAG, "addLayerByID: local vector tile source failed id="
                                        + iLayer.getId());
                                return;
                            }
                        } else {
                            createSourceForLayer(iLayer.getId(), finalGeoType, vectorPolygonFeatures, mainStyle, sourceHashMap,
                                    rasterLayersURLMap, rasterLayersTmsTypeMap,
                                    iLayer.getPath().toString(), false, null);
                        }
                        MPLFeaturesUtils.RasterSiblingAnchor rasterSiblingAnchor = null;
                        if (finalGeoType == GT_RASTER_WA && iLayer instanceof TMSLayer) {
                            rasterSiblingAnchor = MPLFeaturesUtils.resolveRasterSiblingAnchorOrNull(
                                    iLayer, mainStyle);
                        }
                        if (localVectorTileUrl != null) {
                            createFillLayerForLocalVectorTileLayer(
                                    iLayer.getId(),
                                    finalGeoType,
                                    mainStyle,
                                    layersHashMap,
                                    layersHashMap2,
                                    symbolsLayerHashMap,
                                    finalStyle,
                                    iLayer,
                                    iLayer.getPath().toString(),
                                    signaturesRootLayer);
                        } else {
                            createFillLayerForLayer(iLayer.getId(), finalGeoType, mainStyle, layersHashMap, layersHashMap2,
                                layersHashMapLineDash,
                                symbolsLayerHashMap,
                                finalStyle, false, iLayer,
                                iLayer.getPath().toString(),
                                selectedDotCircleLayer,
                                signaturesRootLayer,
                                rasterSiblingAnchor);
                        }

                        checkLayerVisibility(iLayer.getId());
                        /* Same as tapping the layer list (ReorderedLayerView ACTION_UP → loadLayersLite):
                         * hot-added raster was under signaturesRootLayer until full reorder from sourcesOrder. */
                        if (finalGeoType == GT_RASTER_WA) {
                            MaplibreMapInteraction host = mapContext.get();
                            if (host != null) {
                                host.loadLayersLite();
                            }
                        }
                    });
                }
            } catch (OutOfMemoryError outOfMemoryError) {
                if (mapContext.get() != null) {
                    String layerName = (crashLayer == null ? "null" : crashLayer.getName());
                    AlertDialog builder = new AlertDialog.Builder(((Fragment) mapContext.get()).getActivity())
                            .setTitle("MemoryError")
                            .setMessage(((Fragment) mapContext.get()).getActivity().getString(R.string.outofmemory) + layerName)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }
            }
        });

    }

    /**
     * Refresh MapLibre paint/layout for one vector (or TMS) layer after style or data change.
     * Rebuilds per-feature GeoJSON props from SQLite and updates MapLibre layer definitions.
     */
    public void reloadVectorLayerStyleToMaplibre(final int id) {
        // Main-thread entry from layer-settings save (MapViewOverlays.onLayerChanged) and map UI.
        // Guard so a single bad layer/style cannot crash the app while leaving settings.
        runGuarded("reloadVectorLayerStyleToMaplibre id=" + id,
                () -> reloadFillLayerStyleToMaplibre(id));
    }

    public void reloadFillLayerStyleToMaplibre(final  int  id) {
        if (!canReloadVectorLayerStyleOnMap()) {
            return;
        }

        List<ILayer> vectorss = new ArrayList<>();
        List<ILayer> tmss = new ArrayList<>();

        getTMSLayersByType(this,  GeoConstants.GTAnyCheck, tmss);

        LayerGroup.getVectorLayersByType(this, GeoConstants.GTAnyCheck, vectorss);
        for (ILayer iLayer : vectorss){
            if (iLayer.getId() == id){
                com.nextgis.maplib.display.Style newStyle = ((VectorLayer)iLayer).getDefaultStyleNoExcept();
                if (maplibreMap.get() == null) {
                    return;
                }
                Style maplbrStyle = maplibreMap.get().getStyle();
                if (maplbrStyle == null) {
                    return;
                }
                if (! ((VectorLayer) iLayer).isVisible())
                    return;
                if (LocalVectorTileRenderMode.shouldUseLocalVectorTiles((VectorLayer) iLayer)
                        && localVectorTileUrlMap.containsKey(id)) {
                    createFillLayerForLocalVectorTileLayer(
                            id,
                            ((VectorLayer) iLayer).getGeometryType(),
                            maplbrStyle,
                            layersHashMap,
                            layersHashMap2,
                            symbolsLayerHashMap,
                            newStyle,
                            iLayer,
                            iLayer.getPath().toString(),
                            signaturesRootLayer);
                } else {
                    createFillLayerForLayer(id, ((VectorLayer) iLayer).getGeometryType(),maplbrStyle ,layersHashMap,layersHashMap2,
                        layersHashMapLineDash,
                        symbolsLayerHashMap,
                        newStyle, true, iLayer,
                        iLayer.getPath().toString(),
                        selectedDotCircleLayer,
                        signaturesRootLayer);
                }
                checkLayerVisibility(id);
                if (!localVectorTileUrlMap.containsKey(id)) {
                    reloadVectorLayerStylePropsToMaplibre(iLayer);
                }
                return;
            }
        }

        for (ILayer iLayer : tmss){
            if (iLayer.getId() == id){
                if (maplibreMap.get() == null) {
                    return;
                }
                Style maplbrStyle = maplibreMap.get().getStyle();
                if (maplbrStyle == null) {
                    return;
                }
                createFillLayerForLayer(id,  GT_RASTER_WA, maplbrStyle ,layersHashMap, layersHashMap2,
                        layersHashMapLineDash,
                        symbolsLayerHashMap,
                        null, true, iLayer,
                        iLayer.getPath().toString(),
                        selectedDotCircleLayer,
                        signaturesRootLayer);
                checkLayerVisibility(id);
                reloadVectorLayerDataToMaplibre(iLayer);
                return;
            }
        }
    }

    private void applyMaplibreStyleLayersForVector(VectorLayer layer) {
        if (layer == null || maplibreMap.get() == null) {
            return;
        }
        Style maplbrStyle = maplibreMap.get().getStyle();
        if (maplbrStyle == null || !layer.isVisible()) {
            return;
        }
        com.nextgis.maplib.display.Style ngStyle = layer.getDefaultStyleNoExcept();
        if (LocalVectorTileRenderMode.shouldUseLocalVectorTiles(layer)
                && localVectorTileUrlMap.containsKey(layer.getId())) {
            createFillLayerForLocalVectorTileLayer(layer.getId(), layer.getGeometryType(), maplbrStyle,
                    layersHashMap, layersHashMap2, symbolsLayerHashMap,
                    ngStyle, layer, layer.getPath().toString(), signaturesRootLayer);
        } else {
            createFillLayerForLayer(layer.getId(), layer.getGeometryType(), maplbrStyle,
                    layersHashMap, layersHashMap2, layersHashMapLineDash, symbolsLayerHashMap,
                    ngStyle, true, layer, layer.getPath().toString(),
                    selectedDotCircleLayer, signaturesRootLayer);
        }
        checkLayerVisibility(layer.getId());
    }

    /**
     * Layer style/data reload is allowed in view/select modes but blocked during active geometry edit
     * so walk/touch editing is not disrupted.
     */
    private boolean canReloadVectorLayerStyleOnMap() {
        MaplibreMapInteraction frag = mapContext.get();
        if (frag == null) {
            return true;
        }
        int mode = frag.getMode();
        return mode != MODE_EDIT && mode != MODE_EDIT_BY_WALK && mode != MODE_EDIT_BY_TOUCH;
    }

    /**
     * Hot-reload after style change: update MapLibre layer paint (caller) + refresh feature props
     * when needed (rule-style / signatures). Skips full SQLite geometry scan when features are
     * in memory or geometry disk cache is valid.
     */
    public void reloadVectorLayerStylePropsToMaplibre(final ILayer ilayer) {
        if (!canReloadVectorLayerStyleOnMap()) {
            return;
        }
        if (!(ilayer instanceof VectorLayer)) {
            return;
        }
        VectorLayer layer = (VectorLayer) ilayer;
        List<org.maplibre.geojson.Feature> memFeatures = sourceFeaturesHashMap.get(layer.getId());
        boolean hasMemFeatures = memFeatures != null && !memFeatures.isEmpty();
        if (!MPLFeaturesUtils.needsSourceStyleRefresh(layer) && !hasMemFeatures) {
            if (Constants.DEBUG_MODE) {
                Log.d(TAG, "reloadVectorLayerStyleProps: layer paint only " + layer.getName());
            }
            return;
        }
        if (maplibreMap.get() == null || maplibreMapView.get() == null) {
            return;
        }

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler mainHandler = new Handler(Looper.getMainLooper());
        executor.execute(() -> {
            try {
                List<org.maplibre.geojson.Feature> features = sourceFeaturesHashMap.get(layer.getId());
                boolean fromMemory = features != null && !features.isEmpty();
                if (fromMemory) {
                    long t0 = Constants.DEBUG_MODE ? System.nanoTime() : 0L;
                    refreshMaplibreStyleOnFeatures(layer, features);
                    if (Constants.DEBUG_MODE) {
                        Log.d(TAG, "reloadVectorLayerStyleProps memory layer=" + layer.getName()
                                + " n=" + features.size()
                                + " ms=" + ((System.nanoTime() - t0) / 1_000_000));
                    }
                } else {
                    features = VectorLayerRenderCache.tryLoadFeatures(layer);
                    if (features == null) {
                        Log.d(TAG, "reloadVectorLayerStyleProps: no cache, full data reload "
                                + layer.getName());
                        mainHandler.post(() -> reloadVectorLayerDataToMaplibre(ilayer));
                        return;
                    }
                }
                sourceFeaturesHashMap.put(layer.getId(), features);
                final List<org.maplibre.geojson.Feature> result = features;
                postMainGuarded("reloadVectorLayerStyleProps update layer=" + layer.getName(),
                        () -> updateVectorLayerGeoJsonSources(layer, result));
            } catch (Exception ex) {
                logErr("reloadVectorLayerStyleProps: " + layer.getName(), ex);
                mainHandler.post(() -> reloadVectorLayerDataToMaplibre(ilayer));
            } finally {
                executor.shutdown();
            }
        });
    }

    private void updateVectorLayerGeoJsonSources(
            VectorLayer layer,
            List<org.maplibre.geojson.Feature> features) {
        if (layer == null || features == null || maplibreMap.get() == null) {
            return;
        }
        Style style = maplibreMap.get().getStyle();
        if (style == null) {
            return;
        }

        String layerPath = layer.getPath().toString();
        GeoJsonSource layerSource = resolveLiveVectorGeoJsonSource(
                style, layerPath, layer, features);
        if (layerSource != null) {
            sourceNativeUriMap.remove(layer.getId());
            layerSource.setGeoJson(FeatureCollection.fromFeatures(features));
        }
        if (layer.mGeometryType == GTPolygon || layer.mGeometryType == GTMultiPolygon) {
            String textSourceId = layerPath + source_polygon_text;
            GeoJsonSource layerSourceText = resolveLiveGeoJsonSource(style, textSourceId, layer);
            if (layerSourceText == null) {
                createSourceForLayer(layer.getId(), layer.getGeometryType(), features, style,
                        sourceHashMap, new HashMap<>(), new HashMap<>(), layerPath, false, null);
                layerSourceText = resolveLiveGeoJsonSource(style, textSourceId, layer);
            }
            if (layerSourceText != null) {
                List<org.maplibre.geojson.Feature> points = convertToPointFeatures(features);
                layerSourceText.setGeoJson(FeatureCollection.fromFeatures(points));
            }
        }
    }

    private void scheduleVectorLayerGeoJsonReapply(
            @NonNull final VectorLayer layer,
            @NonNull final List<org.maplibre.geojson.Feature> features,
            final long reloadToken) {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> runGuarded("reloadVectorLayerDataToMaplibre delayed reapply layer="
                + layer.getName(), () -> {
            Long currentToken = mVectorLayerReloadTokens.get(layer.getId());
            if (currentToken == null || currentToken.longValue() != reloadToken) {
                return;
            }
            applyMaplibreStyleLayersForVector(layer);
            updateVectorLayerGeoJsonSources(layer, features);
            logVectorLayerMaplibreState("delayed reapply", layer, features.size());
        }), 4500);
    }

    private void logVectorLayerMaplibreState(
            @NonNull String phase,
            @NonNull VectorLayer layer,
            int featureCount) {
        if (maplibreMap.get() == null || maplibreMap.get().getStyle() == null) {
            return;
        }
        Style style = maplibreMap.get().getStyle();
        String layerPath = layer.getPath().toString();
        boolean hasSource = style.getSource(layerPath) instanceof GeoJsonSource;
        boolean hasTextSource = !(layer.getGeometryType() == GTPolygon
                || layer.getGeometryType() == GTMultiPolygon)
                || style.getSource(layerPath + source_polygon_text) instanceof GeoJsonSource;
        boolean hasFillLayer = style.getLayer(namePrefix + layer_namepart + layer.getId()) != null;
        boolean hasOutlineLayer = style.getLayer(
                namePrefix + layer_namepart + layer.getId() + outline_namepart) != null;
        HyperLog.d(TAG, "MapLibre vector reload " + phase
                + " layer=\"" + ProdLogUtil.truncateForLog(layer.getName(), 100)
                + "\" id=" + layer.getId()
                + " features=" + featureCount
                + " source=" + hasSource
                + " textSource=" + hasTextSource
                + " fillLayer=" + hasFillLayer
                + " outlineLayer=" + hasOutlineLayer
                + " visible=" + layer.isVisible());
    }

    @Nullable
    private GeoJsonSource resolveLiveVectorGeoJsonSource(
            @NonNull Style style,
            @NonNull String sourceId,
            @NonNull VectorLayer layer,
            @NonNull List<org.maplibre.geojson.Feature> features) {
        GeoJsonSource liveSource = resolveLiveGeoJsonSource(style, sourceId, layer);
        if (liveSource != null) {
            return liveSource;
        }

        HyperLog.w(TAG, "MapLibre source missing; recreate layer=\""
                + ProdLogUtil.truncateForLog(layer.getName(), 100)
                + "\" id=" + layer.getId()
                + " features=" + features.size()
                + " source=" + ProdLogUtil.truncateForLog(sourceId, 120));
        createSourceForLayer(layer.getId(), layer.getGeometryType(), features, style,
                sourceHashMap, new HashMap<>(), new HashMap<>(), sourceId, false, null);
        return resolveLiveGeoJsonSource(style, sourceId, layer);
    }

    @Nullable
    private GeoJsonSource resolveLiveGeoJsonSource(
            @NonNull Style style,
            @NonNull String sourceId,
            @NonNull VectorLayer layer) {
        Source live = style.getSource(sourceId);
        if (live instanceof GeoJsonSource) {
            GeoJsonSource liveGeoJson = (GeoJsonSource) live;
            GeoJsonSource cached = sourceHashMap.get(sourceId);
            if (cached != liveGeoJson) {
                sourceHashMap.put(sourceId, liveGeoJson);
                HyperLog.d(TAG, "MapLibre source cache refreshed layer=\""
                        + ProdLogUtil.truncateForLog(layer.getName(), 100)
                        + "\" id=" + layer.getId()
                        + " source=" + ProdLogUtil.truncateForLog(sourceId, 120));
            }
            return liveGeoJson;
        }

        sourceHashMap.remove(sourceId);
        if (live != null) {
            HyperLog.w(TAG, "MapLibre source has wrong type; remove layer=\""
                    + ProdLogUtil.truncateForLog(layer.getName(), 100)
                    + "\" id=" + layer.getId()
                    + " source=" + ProdLogUtil.truncateForLog(sourceId, 120));
            style.removeSource(sourceId);
        }
        return null;
    }

    public void reloadVectorLayerDataToMaplibre(final  ILayer ilayer) {
        Handler mainHandler = new Handler(Looper.getMainLooper());

        Runnable r = () -> {
            mMaplibreVectorReloadExecutor.execute(() -> {
                if (maplibreMap.get() == null || maplibreMapView.get() == null)
                    return;
                if (ilayer instanceof  TMSLayer){

                    return;
                }


                if (!(ilayer instanceof VectorLayer))
                    return;
                VectorLayer layer = (VectorLayer) ilayer;

                if (LocalVectorTileRenderMode.shouldUseLocalVectorTiles(layer)) {
                    String tileUrl = localVectorTileUrlMap.get(layer.getId());
                    if (tileUrl == null) {
                        tileUrl = LocalVectorTileServer.getInstance().registerLayer(layer);
                    }
                    if (tileUrl != null) {
                        final String finalTileUrl = tileUrl;
                        localVectorTileUrlMap.put(layer.getId(), finalTileUrl);
                        sourceFeaturesHashMap.put(layer.getId(), new ArrayList<>());
                        sourcesOrder.put(layer.getId(), new ArrayList<>());
                        postMainGuarded("reloadVectorLayerDataToMaplibre local tiles layer="
                                + layer.getName(), () -> {
                            if (maplibreMap.get() == null || maplibreMap.get().getStyle() == null) {
                                return;
                            }
                            Style style = maplibreMap.get().getStyle();
                            createLocalVectorTileSourceForLayer(
                                    layer.getId(),
                                    style,
                                    layer.getPath().toString(),
                                    finalTileUrl,
                                    layer.getMinZoom(),
                                    layer.getMaxZoom());
                            applyMaplibreStyleLayersForVector(layer);
                            checkLayerVisibility(layer.getId());
                        });
                        return;
                    }
                    logLocalVectorTilesFallback(layer);
                }

                ((IGISApplication)getContext().getApplicationContext()).setGetingStyleInProgress(true);
                mainHandler.post(() -> {
                    mapContext.get().changeProgress(true);
                });

                String loadHint = getContext().getString(R.string.process_layer_hint);

                Intent msg = new Intent(MESSAGE_INTENT_STYLING);
                msg.putExtra("msg", loadHint + layer.getName());
                msg.setPackage(getContext().getPackageName());
                getContext().sendBroadcast(msg);

                try {
                    long tDbStart = Constants.DEBUG_MODE ? System.nanoTime() : 0L;
                    List<org.maplibre.geojson.Feature> vectorPolygonFeatures = createFeatureListFromLayer(layer);
                    if (Constants.DEBUG_MODE) {
                        Log.d(TAG, "reloadVectorLayerDataToMaplibre DB layer=" + layer.getName()
                                + " features=" + vectorPolygonFeatures.size()
                                + " ns=" + (System.nanoTime() - tDbStart));
                    }
                    HyperLog.d(TAG, "reloadVectorLayerDataToMaplibre layer=\""
                            + ProdLogUtil.truncateForLog(layer.getName(), 100)
                            + "\" id=" + layer.getId()
                            + " features=" + vectorPolygonFeatures.size());
                    VectorLayerRenderCache.save(layer, vectorPolygonFeatures);
                    sourceFeaturesHashMap.put(layer.getId(), vectorPolygonFeatures);
                    sourcesOrder.put(layer.getId(), new ArrayList<>());
                    long reloadToken = System.nanoTime();
                    mVectorLayerReloadTokens.put(layer.getId(), reloadToken);

                    postMainGuarded("reloadVectorLayerDataToMaplibre update layer=" + layer.getName(), () -> {
                        updateVectorLayerGeoJsonSources(layer, vectorPolygonFeatures);
                        applyMaplibreStyleLayersForVector(layer);
                        logVectorLayerMaplibreState("apply", layer, vectorPolygonFeatures.size());
                        scheduleVectorLayerGeoJsonReapply(layer, vectorPolygonFeatures, reloadToken);
                    });
                } catch (OutOfMemoryError e) {
                    mainHandler.post(() -> Toast.makeText(mContext,
                            mContext.getString(R.string.outofmemory) + layer.getName(),
                            Toast.LENGTH_LONG).show());
                } catch (Exception ex) {
                    logErr("reloadVectorLayerDataToMaplibre: " + layer.getName(), ex);
                } finally {
                    ((IGISApplication) getContext().getApplicationContext()).setGetingStyleInProgress(false);
                    mainHandler.post(() -> {
                        if (mapContext.get() != null)
                            mapContext.get().changeProgress(false);
                    });
                    Intent clear = new Intent(MESSAGE_INTENT_STYLING);
                    clear.putExtra("msg", "");
                    clear.setPackage(getContext().getPackageName());
                    getContext().sendBroadcast(clear);
                }
            });
        };
        mainHandler.postDelayed(r, 500);

    }

    /**
     * Hides styling progress, clears status text, resets app flag. Safe if map/fragment is gone.
     */
    private void dismissStylingProgress() {
        try {
            ((IGISApplication) getContext().getApplicationContext()).setGetingStyleInProgress(false);
            MaplibreMapInteraction frag = mapContext.get();
            if (frag != null) {
                frag.changeProgress(false);
            }
            Intent clear = new Intent(MESSAGE_INTENT_STYLING);
            clear.putExtra("msg", "");
            clear.setPackage(getContext().getPackageName());
            getContext().sendBroadcast(clear);
        } catch (Exception ignored) {
        }
    }

    /** Used only from {@link #loadLayersToMaplibreMap} parallel path when {@link Constants#MAP_STARTUP_PARALLEL_VECTOR_PREP}. */
    private void sendStylingBroadcast(Handler mainHandler, String message) {
        mainHandler.post(() -> {
            try {
                Intent msg = new Intent(MESSAGE_INTENT_STYLING);
                msg.putExtra("msg", message);
                msg.setPackage(getContext().getPackageName());
                getContext().sendBroadcast(msg);
            } catch (Exception ignored) {
            }
        });
    }

    private void logLocalVectorTilesFallback(VectorLayer layer) {
        if (!LocalVectorTileRenderMode.isRequested(layer)) {
            return;
        }
        String reason = !LocalVectorTileRenderMode.isEnabled()
                ? "feature flag disabled"
                : (!LocalVectorTileRenderMode.isProviderAvailable()
                ? "provider unavailable" : "provider registration failed or unsupported geometry");
        HyperLog.d(Constants.TAG, "Local vector tiles requested; classic fallback layer=\""
                + layer.getName() + "\" remoteId="
                + (layer instanceof NGWVectorLayer ? ((NGWVectorLayer) layer).getRemoteId() : -1)
                + " reason=" + reason);
    }

    private static final class VectorLayerPrepResult {
        final VectorLayer layer;
        final List<org.maplibre.geojson.Feature> features;
        final int geometryType;
        final com.nextgis.maplib.display.Style ngStyle;
        final boolean fromCache;
        final java.net.URI nativeGeoJsonUri;
        final String localVectorTileUrl;

        VectorLayerPrepResult(VectorLayer layer,
                List<org.maplibre.geojson.Feature> features,
                int geometryType,
                com.nextgis.maplib.display.Style ngStyle,
                boolean fromCache,
                java.net.URI nativeGeoJsonUri,
                String localVectorTileUrl) {
            this.layer = layer;
            this.features = features;
            this.geometryType = geometryType;
            this.ngStyle = ngStyle;
            this.fromCache = fromCache;
            this.nativeGeoJsonUri = nativeGeoJsonUri;
            this.localVectorTileUrl = localVectorTileUrl;
        }
    }

    /**
     * Parallel vector prep for {@link #loadLayersToMaplibreMap}; inactive unless
     * {@link Constants#MAP_STARTUP_PARALLEL_VECTOR_PREP}.
     */
    private VectorLayerPrepResult prepareVectorLayerForMaplibre(VectorLayer layer, Handler mainHandler) {
        try {
            if (LocalVectorTileRenderMode.shouldUseLocalVectorTiles(layer)) {
                String tileUrl = LocalVectorTileServer.getInstance().registerLayer(layer);
                if (tileUrl != null) {
                    com.nextgis.maplib.display.Style ngStyle = layer.getDefaultStyleNoExcept();
                    HyperLog.d(Constants.TAG, "Local vector tiles enabled layer=\""
                            + layer.getName() + "\" url=" + tileUrl);
                    return new VectorLayerPrepResult(layer, new ArrayList<>(), layer.getGeometryType(),
                            ngStyle, false, null, tileUrl);
                }
            }
            logLocalVectorTilesFallback(layer);
            List<org.maplibre.geojson.Feature> vectorFeatures = VectorLayerRenderCache.tryLoadFeatures(layer);
            boolean fromCache = vectorFeatures != null;
            if (!fromCache) {
                sendStylingBroadcast(mainHandler,
                        getContext().getString(R.string.process_layer_hint) + layer.getName());
                long tDbStart = Constants.DEBUG_MODE ? System.nanoTime() : 0L;
                vectorFeatures = createFeatureListFromLayer(layer);
                if (Constants.DEBUG_MODE) {
                    Log.d(TAG, "loadLayersToMaplibreMap DB build layer=" + layer.getName()
                            + " features=" + vectorFeatures.size()
                            + " ms=" + ((System.nanoTime() - tDbStart) / 1_000_000));
                }
                VectorLayerRenderCache.save(layer, vectorFeatures);
            }
            com.nextgis.maplib.display.Style ngStyle = layer.getDefaultStyleNoExcept();
            java.net.URI nativeUri = fromCache ? VectorLayerRenderCache.tryLoadAsUri(layer) : null;
            return new VectorLayerPrepResult(layer, vectorFeatures, layer.getGeometryType(), ngStyle,
                    fromCache, nativeUri, null);
        } catch (OutOfMemoryError outOfMemoryError) {
            mainHandler.post(() -> Toast.makeText(mContext,
                    mContext.getString(R.string.outofmemory) + layer.getName(),
                    Toast.LENGTH_LONG).show());
            return null;
        } catch (Throwable t) {
            Log.e(TAG, "prepareVectorLayerForMaplibre: " + layer.getName(), t);
            return null;
        }
    }

    public void loadLayersToMaplibreMap(final String styleJson,
                                        final  List<ILayer> allLayers,
                                        final boolean createSource,
                                        final boolean skipInvisibleLayers) {

        MaplibreMapInteraction mapFrag = mapContext.get();
        MapView mapViewRef = maplibreMapView.get();
        MapLibreMap mapRef = maplibreMap.get();
        if (mapFrag == null || mapViewRef == null || mapRef == null) {
            return;
        }

        if (mLayerLoadInProgress) {
            if (mDeferReloadOnceAllowed) {
                mPendingReload = true;
                mDeferReloadOnceAllowed = false;
                Log.d(TAG, "loadLayersToMaplibreMap: already in progress, deferring once");
            } else {
                Log.d(TAG, "loadLayersToMaplibreMap: already in progress, extra request ignored");
            }
            return;
        }
        mLayerLoadInProgress = true;
        mPendingReload = false;
        mDeferReloadOnceAllowed = true;

        mapFrag.changeProgress(true);

        mapViewRef.setOnTouchListener(this);
        mapRef.addOnMapClickListener(this);
        mapRef.addOnMapLongClickListener(this);

        final int poolSize = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        Handler mainHandler = new Handler(Looper.getMainLooper());

        final Map<Integer, Integer> layersType = new HashMap<>();
        final Map<Integer, String> layersPath = new HashMap<>();
        final Map<Integer, com.nextgis.maplib.display.Style> layersStyle = new HashMap<>();
        final Map<Integer, String> rasterLayersURLMap = new HashMap<>();
        final Map<Integer, Integer> rasterLayersTmsTypeMap = new HashMap<>();

        final List<org.maplibre.geojson.Feature> tracksFeatures = new ArrayList<>();

        executor.execute(() -> {
            TrackLayer trackLayerNGW = null;
            if (maplibreMap.get() == null || maplibreMapView.get() == null) {
                mainHandler.post(() -> {
                    mLayerLoadInProgress = false;
                    dismissStylingProgress();
                });
                return;
            }
            // Always notify app for MapViewOverlays (skip heavy per-layer MapLibre reload while we rebuild style).
            // Needed for collector batch + loadLayersToMaplibreMap; not tied to disk cache / parallel prep flags.
            ((IGISApplication) getContext().getApplicationContext()).setGetingStyleInProgress(true);
            ProdLogUtil.setPhase("loadLayers prep");
            try {
            // Load layers

            final AccountManager accountManager = AccountManager.get(getContext());
            final Connections connections = fillConnections(getContext(), accountManager);

            //sourceFeaturesHashMap.clear();
            //sourceHashMap.clear();
            sourcesOrder.clear();
            localVectorTileUrlMap.clear();
            LocalVectorTileServer.getInstance().clearLayers();

            if (Constants.MAP_STARTUP_PARALLEL_VECTOR_PREP) {
            final long tWorkerWallStart = Constants.DEBUG_MODE ? System.nanoTime() : 0L;

            List<VectorLayer> vectorLoadOrder = new ArrayList<>();
            for (ILayer iLayer : allLayers) {
                if (iLayer instanceof VectorLayer) {
                    VectorLayer vl = (VectorLayer) iLayer;
                    if (skipInvisibleLayers && !vl.isVisible()) {
                        continue;
                    }
                    vectorLoadOrder.add(vl);
                }
            }

            ExecutorService parallelPool = Executors.newFixedThreadPool(poolSize);
            try {
                final long tVecPhaseStart = Constants.DEBUG_MODE ? System.nanoTime() : 0L;
                List<Future<VectorLayerPrepResult>> vecFutures = new ArrayList<>(vectorLoadOrder.size());
                for (VectorLayer vl : vectorLoadOrder) {
                    vecFutures.add(parallelPool.submit(() -> prepareVectorLayerForMaplibre(vl, mainHandler)));
                }
                for (Future<VectorLayerPrepResult> vf : vecFutures) {
                    VectorLayerPrepResult r;
                    try {
                        r = vf.get();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        Log.e(TAG, "loadLayersToMaplibreMap: vector prep interrupted", e);
                        break;
                    } catch (ExecutionException e) {
                        Log.e(TAG, "loadLayersToMaplibreMap: vector prep failed", e.getCause());
                        continue;
                    }
                    if (r == null || r.features == null) {
                        continue;
                    }
                    if (r.fromCache) {
                        sendStylingBroadcast(mainHandler,
                                getContext().getString(R.string.map_loading_cache_hit) + r.layer.getName());
                    }

                    layersType.put(r.layer.getId(), r.geometryType);
                    layersPath.put(r.layer.getId(), r.layer.getPath().toString());
                    layersStyle.put(r.layer.getId(), r.ngStyle);
                    sourceFeaturesHashMap.put(r.layer.getId(), r.features);
                    if (r.nativeGeoJsonUri != null) {
                        sourceNativeUriMap.put(r.layer.getId(), r.nativeGeoJsonUri);
                    } else {
                        sourceNativeUriMap.remove(r.layer.getId());
                    }
                    if (r.localVectorTileUrl != null) {
                        localVectorTileUrlMap.put(r.layer.getId(), r.localVectorTileUrl);
                    } else {
                        localVectorTileUrlMap.remove(r.layer.getId());
                    }
                    sourcesOrder.put(r.layer.getId(), new ArrayList<>());
                }
                if (Constants.DEBUG_MODE) {
                    Log.d(Constants.TAG, "MapDrawable loadLayers vector phase wall ms="
                            + ((System.nanoTime() - tVecPhaseStart) / 1_000_000));
                }
            } finally {
                parallelPool.shutdown();
                try {
                    if (!parallelPool.awaitTermination(30, TimeUnit.MINUTES)) {
                        parallelPool.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    parallelPool.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }

            for (ILayer iLayer : allLayers) {
//                Log.e("MPLREM",  "iterate layer " + iLayer.getName());
                try {
                    if (iLayer instanceof VectorLayer) {
                        continue;
                    } else if (iLayer instanceof TrackLayer) {
                        trackLayerNGW = (TrackLayer) iLayer;
                        layersType.put(trackLayerNGW.getId(), GT_TRACK_WA);
                        layersPath.put(trackLayerNGW.getId(), trackLayerNGW.getPath().toString());

                        tracksFeatures.clear();
                        tracksFeatures.addAll(createFeatureListFromTrackLayer(trackLayerNGW));

                        //List<org.maplibre.geojson.Feature> tracksFeatures = createFeatureListFromTrackLayer(layer);
                        //sourceFeaturesHashMap.put(layer.getId(), tracksFeatures);
                    } else if (iLayer instanceof NGWRasterLayer) {
                        // need add auth
                        Connection found = null;
                        if (iLayer instanceof NGWRasterLayer) {
                            for (int i = 0; i < connections.getChildrenCount(); i++) {
                                if (connections.getChild(i).getName().equals((((NGWRasterLayer) iLayer).getAccountName()))) {
                                    found = (Connection) connections.getChild(i);
                                    String basicAuth = getHTTPBaseAuth(found.getLogin(), found.getPassword());
                                    if (null != basicAuth) {
                                        final String url = ((NGWRasterLayer) iLayer).getURL();
                                        final String getBaseUrl = getBaseUrlpart(url);
                                        final String resPart = "resource=" + extractResourceValue(url);
                                        final String[] authPart = new String[4];
                                        authPart[0] = getBaseUrl;
                                        authPart[1] = resPart;
                                        authPart[2] = basicAuth;
                                        authPart[3] = iLayer.getPath().toString();
                                        ((IGISApplication) getContext().getApplicationContext()).updateAuthPair(authPart);
                                        break;
                                    }
                                }
                            }

                            TMSLayer layer = (TMSLayer) iLayer;
                            layersType.put(layer.getId(), GT_RASTER_WA);
                            layersPath.put(layer.getId(), layer.getPath().toString());

                            rasterLayersURLMap.put(layer.getId(), ((NGWRasterLayer) layer).getURL());
                            sourceFeaturesHashMap.put(layer.getId(), new ArrayList<>());
                            sourcesOrder.put(layer.getId(), new ArrayList<>());
                        }
                    } else if (iLayer instanceof RemoteTMSLayer) {
                        final String url = ((RemoteTMSLayer) iLayer).getURL();
                        final String getBaseUrl = getBaseUrlpart(url);
                        final String resPart = "resource=" + extractResourceValue(url);
                        final String[] authPart = new String[4];
                        authPart[0] = getBaseUrl;
                        authPart[1] = resPart;
                        authPart[2] = "no";//no auth RemoteTMSLayer - geoservice map
                        authPart[3] = iLayer.getPath().toString();
                        ((IGISApplication) getContext().getApplicationContext()).updateAuthPair(authPart);

                        TMSLayer layer = (TMSLayer) iLayer;
                        layersType.put(layer.getId(), GT_RASTER_WA);
                        layersPath.put(layer.getId(), layer.getPath().toString());

                        if (((RemoteTMSLayer) layer).mIsOfflineLayer) {
                            rasterLayersURLMap.put(layer.getId(), "file://" + (layer).getPath().toString() + "/{z}/{x}/{y}.tile");
                            rasterLayersTmsTypeMap.put(layer.getId(), layer.getTMSType());
                        } else {
                            rasterLayersURLMap.put(layer.getId(), ((RemoteTMSLayer) layer).getURLSubdomain());
                            rasterLayersTmsTypeMap.put(layer.getId(), layer.getTMSType());
                        }
                        sourceFeaturesHashMap.put(layer.getId(), new ArrayList<>());
                        sourcesOrder.put(layer.getId(), new ArrayList<>());
                    } else if (iLayer instanceof LocalTMSLayer) {
                        TMSLayer layer = (TMSLayer) iLayer;
                        layersType.put(layer.getId(), GT_RASTER_WA);
                        layersPath.put(layer.getId(), layer.getPath().toString());

                        rasterLayersURLMap.put(layer.getId(), "file://" + (layer).getPath().toString() + "/{z}/{x}/{y}.tile");
                        rasterLayersTmsTypeMap.put(layer.getId(), layer.getTMSType());
                        sourceFeaturesHashMap.put(layer.getId(), new ArrayList<>());
                        sourcesOrder.put(layer.getId(), new ArrayList<>());
                    }
                } catch (OutOfMemoryError outOfMemoryError){
                    mainHandler.post(()-> {
                        Toast.makeText(mContext, mContext.getString(R.string.outofmemory) + iLayer.getName(), Toast.LENGTH_LONG).show();
                        Intent clear = new Intent(MESSAGE_INTENT_STYLING);
                        clear.putExtra("msg", "");
                        clear.setPackage(mContext.getPackageName());
                        mContext.sendBroadcast(clear);
                    });
                    if (iLayer instanceof VectorLayer) {
                        int vid = ((VectorLayer) iLayer).getId();
                        layersType.remove(vid);
                        layersPath.remove(vid);
                        layersStyle.remove(vid);
                        sourceFeaturesHashMap.remove(vid);
                        sourcesOrder.remove(vid);
                    }
                } catch (Exception ex) {
                    Log.e(TAG, "loadLayersToMaplibreMap: layer " + iLayer.getName(), ex);
                    mainHandler.post(() -> {
                        Intent clear = new Intent(MESSAGE_INTENT_STYLING);
                        clear.putExtra("msg", "");
                        clear.setPackage(getContext().getPackageName());
                        getContext().sendBroadcast(clear);
                    });
                    if (iLayer instanceof VectorLayer) {
                        int vid = ((VectorLayer) iLayer).getId();
                        layersType.remove(vid);
                        layersPath.remove(vid);
                        layersStyle.remove(vid);
                        sourceFeaturesHashMap.remove(vid);
                        sourcesOrder.remove(vid);
                    }
                }
            }

                if (Constants.DEBUG_MODE) {
                    Log.d(Constants.TAG, "MapDrawable loadLayers worker pre-setStyle ms="
                            + ((System.nanoTime() - tWorkerWallStart) / 1_000_000));
                }

            } else {
                // Sequential vector + track/raster (disk cache via VECTOR_RENDER_DISK_CACHE_ENABLED)
                for (ILayer iLayer : allLayers) {
                    try {
                        if (iLayer instanceof VectorLayer) {
                            VectorLayer layer = (VectorLayer) iLayer;
                            if (skipInvisibleLayers && !layer.isVisible()) {
                                continue;
                            }
                            String tileUrl = null;
                            if (LocalVectorTileRenderMode.shouldUseLocalVectorTiles(layer)) {
                                tileUrl = LocalVectorTileServer.getInstance().registerLayer(layer);
                            }
                            if (tileUrl != null) {
                                com.nextgis.maplib.display.Style ngStyle = layer.getDefaultStyleNoExcept();
                                layersType.put(layer.getId(), layer.getGeometryType());
                                layersPath.put(layer.getId(), layer.getPath().toString());
                                layersStyle.put(layer.getId(), ngStyle);
                                sourceFeaturesHashMap.put(layer.getId(), new ArrayList<>());
                                sourceNativeUriMap.remove(layer.getId());
                                localVectorTileUrlMap.put(layer.getId(), tileUrl);
                                sourcesOrder.put(layer.getId(), new ArrayList<>());
                                HyperLog.d(Constants.TAG, "Local vector tiles enabled layer=\""
                                        + layer.getName() + "\" url=" + tileUrl);
                                continue;
                            }
                            logLocalVectorTilesFallback(layer);
                            List<org.maplibre.geojson.Feature> vectorFeatures =
                                    VectorLayerRenderCache.tryLoadFeatures(layer);
                            boolean fromCache = vectorFeatures != null;
                            if (!fromCache) {
                                Intent msg = new Intent(MESSAGE_INTENT_STYLING);
                                String loadHint = getContext().getString(R.string.process_layer_hint);
                                msg.putExtra("msg", loadHint + layer.getName());
                                msg.setPackage(getContext().getPackageName());
                                getContext().sendBroadcast(msg);
                                long tDbStart = Constants.DEBUG_MODE ? System.nanoTime() : 0L;
                                vectorFeatures = createFeatureListFromLayer(layer);
                                if (Constants.DEBUG_MODE) {
                                    Log.d(TAG, "loadLayersToMaplibreMap DB build layer=" + layer.getName()
                                            + " features=" + vectorFeatures.size()
                                            + " ms=" + ((System.nanoTime() - tDbStart) / 1_000_000));
                                }
                                VectorLayerRenderCache.save(layer, vectorFeatures);
                            } else {
                                sendStylingBroadcast(mainHandler,
                                        getContext().getString(R.string.map_loading_cache_hit) + layer.getName());
                            }
                            com.nextgis.maplib.display.Style ngStyle = layer.getDefaultStyleNoExcept();
                            layersType.put(layer.getId(), layer.getGeometryType());
                            layersPath.put(layer.getId(), layer.getPath().toString());
                            layersStyle.put(layer.getId(), ngStyle);
                            sourceFeaturesHashMap.put(layer.getId(), vectorFeatures);
                            java.net.URI nativeUri = fromCache
                                    ? VectorLayerRenderCache.tryLoadAsUri(layer) : null;
                            if (nativeUri != null) {
                                sourceNativeUriMap.put(layer.getId(), nativeUri);
                            } else {
                                sourceNativeUriMap.remove(layer.getId());
                            }
                            localVectorTileUrlMap.remove(layer.getId());
                            sourcesOrder.put(layer.getId(), new ArrayList<>());
                        } else if (iLayer instanceof TrackLayer) {
                            TrackLayer layer = (TrackLayer) iLayer;
                            layersType.put(layer.getId(), GT_TRACK_WA);
                            layersPath.put(layer.getId(), layer.getPath().toString());

                            tracksFeatures.clear();
                            tracksFeatures.addAll(createFeatureListFromTrackLayer(layer));
                        } else if (iLayer instanceof NGWRasterLayer) {
                            Connection found = null;
                            for (int i = 0; i < connections.getChildrenCount(); i++) {
                                if (connections.getChild(i).getName().equals((((NGWRasterLayer) iLayer).getAccountName()))) {
                                    found = (Connection) connections.getChild(i);
                                    String basicAuth = getHTTPBaseAuth(found.getLogin(), found.getPassword());
                                    if (null != basicAuth) {
                                        final String url = ((NGWRasterLayer) iLayer).getURL();
                                        final String getBaseUrl = getBaseUrlpart(url);
                                        final String resPart = "resource=" + extractResourceValue(url);
                                        final String[] authPart = new String[4];
                                        authPart[0] = getBaseUrl;
                                        authPart[1] = resPart;
                                        authPart[2] = basicAuth;
                                        authPart[3] = iLayer.getPath().toString();
                                        ((IGISApplication) getContext().getApplicationContext()).updateAuthPair(authPart);
                                        break;
                                    }
                                }
                            }

                            TMSLayer layer = (TMSLayer) iLayer;
                            layersType.put(layer.getId(), GT_RASTER_WA);
                            layersPath.put(layer.getId(), layer.getPath().toString());

                            rasterLayersURLMap.put(layer.getId(), ((NGWRasterLayer) layer).getURL());
                            sourceFeaturesHashMap.put(layer.getId(), new ArrayList<>());
                            sourcesOrder.put(layer.getId(), new ArrayList<>());
                        } else if (iLayer instanceof RemoteTMSLayer) {
                            final String url = ((RemoteTMSLayer) iLayer).getURL();
                            final String getBaseUrl = getBaseUrlpart(url);
                            final String resPart = "resource=" + extractResourceValue(url);
                            final String[] authPart = new String[4];
                            authPart[0] = getBaseUrl;
                            authPart[1] = resPart;
                            authPart[2] = "no";
                            authPart[3] = iLayer.getPath().toString();
                            ((IGISApplication) getContext().getApplicationContext()).updateAuthPair(authPart);

                            TMSLayer layer = (TMSLayer) iLayer;
                            layersType.put(layer.getId(), GT_RASTER_WA);
                            layersPath.put(layer.getId(), layer.getPath().toString());

                            if (((RemoteTMSLayer) layer).mIsOfflineLayer) {
                                rasterLayersURLMap.put(layer.getId(), "file://" + (layer).getPath().toString() + "/{z}/{x}/{y}.tile");
                                rasterLayersTmsTypeMap.put(layer.getId(), layer.getTMSType());
                            } else {
                                rasterLayersURLMap.put(layer.getId(), ((RemoteTMSLayer) layer).getURLSubdomain());
                                rasterLayersTmsTypeMap.put(layer.getId(), layer.getTMSType());
                            }
                            sourceFeaturesHashMap.put(layer.getId(), new ArrayList<>());
                            sourcesOrder.put(layer.getId(), new ArrayList<>());
                        } else if (iLayer instanceof LocalTMSLayer) {
                            TMSLayer layer = (TMSLayer) iLayer;
                            layersType.put(layer.getId(), GT_RASTER_WA);
                            layersPath.put(layer.getId(), layer.getPath().toString());

                            rasterLayersURLMap.put(layer.getId(), "file://" + (layer).getPath().toString() + "/{z}/{x}/{y}.tile");
                            rasterLayersTmsTypeMap.put(layer.getId(), layer.getTMSType());
                            sourceFeaturesHashMap.put(layer.getId(), new ArrayList<>());
                            sourcesOrder.put(layer.getId(), new ArrayList<>());
                        }
                    } catch (OutOfMemoryError outOfMemoryError) {
                        mainHandler.post(() -> {
                            Toast.makeText(mContext, mContext.getString(R.string.outofmemory) + iLayer.getName(), Toast.LENGTH_LONG).show();
                            Intent clear = new Intent(MESSAGE_INTENT_STYLING);
                            clear.putExtra("msg", "");
                            clear.setPackage(mContext.getPackageName());
                            mContext.sendBroadcast(clear);
                        });
                        if (iLayer instanceof VectorLayer) {
                            int vid = ((VectorLayer) iLayer).getId();
                            layersType.remove(vid);
                            layersPath.remove(vid);
                            layersStyle.remove(vid);
                            sourceFeaturesHashMap.remove(vid);
                            sourcesOrder.remove(vid);
                        }
                    } catch (Exception ex) {
                        Log.e(TAG, "loadLayersToMaplibreMap: layer " + iLayer.getName(), ex);
                        mainHandler.post(() -> {
                            Intent clear = new Intent(MESSAGE_INTENT_STYLING);
                            clear.putExtra("msg", "");
                            clear.setPackage(getContext().getPackageName());
                            getContext().sendBroadcast(clear);
                        });
                        if (iLayer instanceof VectorLayer) {
                            int vid = ((VectorLayer) iLayer).getId();
                            layersType.remove(vid);
                            layersPath.remove(vid);
                            layersStyle.remove(vid);
                            sourceFeaturesHashMap.remove(vid);
                            sourcesOrder.remove(vid);
                        }
                    }
                }
            }

            final long[] tSetStyleCallNs = (Constants.DEBUG_MODE && Constants.MAP_STARTUP_UX_EXTRAS_ENABLED)
                    ? new long[1] : null;
            final TrackLayer trackLayerFinal = trackLayerNGW;
            mainHandler.post(() -> {
                final int styleRequestId = mLoadLayersStyleRequestId.incrementAndGet();
                MapView mapViewForStyle = maplibreMapView.get();
                MapLibreMap mapForStyle = maplibreMap.get();
                if (mapViewForStyle == null || mapForStyle == null) {
                    mLayerLoadInProgress = false;
                    dismissStylingProgress();
                    return;
                }
                if (mLoadLayersStyleListener != null) {
                    mapViewForStyle.removeOnDidFinishLoadingStyleListener(mLoadLayersStyleListener);
                    mLoadLayersStyleListener = null;
                }
                mLoadLayersStyleListener = new MapView.OnDidFinishLoadingStyleListener() {
                    @Override
                    public void onDidFinishLoadingStyle() {
                        MapView mvSelf = maplibreMapView.get();
                        if (mvSelf != null && mLoadLayersStyleListener == this) {
                            mvSelf.removeOnDidFinishLoadingStyleListener(this);
                            mLoadLayersStyleListener = null;
                        }
                        if (styleRequestId != mLoadLayersStyleRequestId.get()) {
                            return;
                        }
                        if (Constants.MAP_STARTUP_UX_EXTRAS_ENABLED && Constants.DEBUG_MODE
                                && tSetStyleCallNs != null && tSetStyleCallNs[0] != 0) {
                            Log.d(Constants.TAG, "MapDrawable loadLayers mbgl style load wait ms="
                                    + ((System.nanoTime() - tSetStyleCallNs[0]) / 1_000_000));
                        }
                        final long tStyleBodyStart = (Constants.MAP_STARTUP_UX_EXTRAS_ENABLED && Constants.DEBUG_MODE)
                                ? System.nanoTime() : 0L;
                        try {
                        Style style = maplibreMap.get().getStyle();
                        updateMapBackground();

                        for (Layer layer :maplibreMap.get().getStyle().getLayers()){
                            if (!layer.getId().equals("background"))
                                style.removeLayer(layer);
                        }

                        if (createSource) {
                            for (Source source : maplibreMap.get().getStyle().getSources()) {
                                boolean result = style.removeSource(source);
                            }
                        }

                        if (createSource) {
                            selectedDotSource = new GeoJsonSource("selected-dot-source", FeatureCollection.fromFeatures(emptyList()));
                            style.addSource(selectedDotSource);

                            signaturesRootLayerSource = new GeoJsonSource("signature-root-source", FeatureCollection.fromFeatures(emptyList()));
                            style.addSource(signaturesRootLayerSource);
                        }

                        selectedDotCircleLayer = new CircleLayer("selected-dot-layer", "selected-dot-source")
                                .withProperties(PropertyFactory.circleStrokeWidth(1f),
                                        PropertyFactory.circleStrokeColor("#000000"));
                        selectedDotCircleLayer.setProperties(
                                PropertyFactory.circleRadius(Expression.get("radius")),
                                PropertyFactory.circleColor(Expression.get("color")),
                                PropertyFactory.circleOpacity(1.0f));
                        style.addLayer(selectedDotCircleLayer);

                        signaturesRootLayer = new CircleLayer("signatures-root-layer", "signature-root-source");
                        style.addLayerBelow(signaturesRootLayer, "selected-dot-layer");

                        if (createSource) {
                            selectedPolySource = new GeoJsonSource("selected-poly-source", FeatureCollection.fromFeatures(emptyList()));
                            style.addSource(selectedPolySource);
                        }

                        LineLayer lineLayer = new LineLayer("selected-polygon-line", "selected-poly-source")
                                .withProperties(
                                        PropertyFactory.lineColor(Expression.get("color")),
                                        PropertyFactory.lineWidth(2.0f) );
                        style.addLayer(lineLayer);

                        fillPolyEditLayer = new FillLayer("selected-polygon-fill" ,"selected-poly-source" )
                                .withProperties(
                                        PropertyFactory.fillColor("#FF00FF"),
                                        PropertyFactory.fillOpacity(0.2f));

                        // edit layer source
                        if (createSource) {
                            vertexSource = new GeoJsonSource("vertex-source", FeatureCollection.fromFeatures(emptyList()));
                            style.addSource(vertexSource);
                        }

                        CircleLayer vertexFillLayer = new CircleLayer("vertex-layer", "vertex-source")
                                .withProperties(PropertyFactory.circleStrokeWidth(1f),
                                        PropertyFactory.circleStrokeColor("#000000"));
                        vertexFillLayer.setProperties(
                                PropertyFactory.circleRadius(Expression.get("radius")),
                                PropertyFactory.circleColor(Expression.get("color")),
                                PropertyFactory.circleOpacity(1.0f));

                        style.addLayer(vertexFillLayer);



                        final Drawable drawableStand = getContext().getResources().getDrawable( R.drawable.ic_location_standing);
                        final Bitmap bitmapStand = drawableToBitmap(drawableStand);
                        String iconStandId = "user-marker-location-stand";
                        style.addImage(iconStandId, bitmapStand);

                        final Drawable drawableGo = getContext().getResources().getDrawable( R.drawable.ic_location_moving);
                        final Bitmap bitmapGo = drawableToBitmap(drawableGo);
                        String iconGoId = "user-marker-location-go";
                        style.addImage(iconGoId, bitmapGo);

                        SymbolLayer locationLayer = new SymbolLayer("user-location-layer", "user-location-source")
                                .withProperties(
                                        PropertyFactory.iconImage(
                                                Expression.switchCase(
                                                        Expression.eq(Expression.get("type"), Expression.literal("stand")), Expression.literal("user-marker-location-stand"),
                                                        Expression.eq(Expression.get("type"), Expression.literal("go")), Expression.literal("user-marker-location-go"),
                                                        Expression.literal("user-marker-location-stand"))),
                                        PropertyFactory.iconRotate(Expression.get("bearing")),
                                        PropertyFactory.iconSize(1.0f),
                                        PropertyFactory.iconAllowOverlap(true),
                                        PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP));
                        style.addLayer(locationLayer);


                        // TRACKING
                        // saved track line
                        if (createSource) {
                            tracksLineSource = new GeoJsonSource("track-line-source", FeatureCollection.fromFeatures(tracksFeatures));
                            style.addSource(tracksLineSource);
                        }

                        Layer trackLayer = new LineLayer( "track-line-layer", "track-line-source").
                                withProperties(
                                        PropertyFactory.lineColor("#0000FF"),
                                        PropertyFactory.lineWidth(getMPLThinkness(5)));
                        style.addLayer(trackLayer);

                        // track in progress
                        if (createSource) {
                            trackInProgressSource = new GeoJsonSource("track-inprogress-source", FeatureCollection.fromFeatures(new ArrayList<>()));
                            style.addSource(trackInProgressSource);
                        }

                        Layer trackInProgressLayer = new LineLayer( "track-inprogress-layer", "track-inprogress-source").
                                withProperties(
                                        PropertyFactory.lineColor("#0000FF"),
                                        PropertyFactory.lineWidth(getMPLThinkness(5)));
                        style.addLayer(trackInProgressLayer);

                        /* Upstream adds track start/end flag icons here; per CUSTOMIZATIONS §14
                           «Tracks: no start/end flag icons» we intentionally skip flag source/layer
                           and the bitmap registration. checkLayerVisibility kept so track layer
                           visibility still applies (track is not in sourcesOrder loop below). */
                        if (trackLayerFinal != null) {
                            checkLayerVisibility(trackLayerFinal.getId());
                        }

                        // marker
                        final Drawable drawable = getContext().getResources().getDrawable( R.drawable.ic_action_anchor_2);
                        final Bitmap bitmap = drawableToBitmap(drawable);

                        final IconFactory iconFactory = IconFactory.getInstance(getContext());
                        final Icon markerIcon = iconFactory.fromBitmap(bitmap);
                        String iconId = "marker-icon-selected";
                        style.addImage(iconId, bitmap);

                        // marker layer
                        markerFeatureCollection = FeatureCollection.fromFeatures(new ArrayList<>());

                        if (createSource) {
                            markerSource = new GeoJsonSource("marker-source", markerFeatureCollection);
                            style.addSource(markerSource);
                        }

                        SymbolLayer symbolLayer = new SymbolLayer("marker-layer", "marker-source")
                                .withProperties(
                                        org.maplibre.android.style.layers.PropertyFactory.iconImage(iconId),
                                        org.maplibre.android.style.layers.PropertyFactory.iconAnchor(org.maplibre.android.style.layers.Property.ICON_ANCHOR_TOP_LEFT));
                        style.addLayer(symbolLayer);

                        if (createSource) {
                            locationSource = new GeoJsonSource("user-location-source", Point.fromLngLat(-100.0, -100.0));
                            style.addSource(locationSource);
                        }

                        List<Map.Entry<Integer, List<org.maplibre.geojson.Feature>>> listOf = new ArrayList<>(sourcesOrder.entrySet());
                        Collections.reverse(listOf);

                        for (Map.Entry<Integer, List<org.maplibre.geojson.Feature>> entry : listOf) {
                            // create source and FillLayer put to style
                            Integer geoType = layersType.get(entry.getKey());
                            List<org.maplibre.geojson.Feature> featuresForLayer = sourceFeaturesHashMap.get(entry.getKey());
                            String pathForLayer = layersPath.get(entry.getKey());
                            if (geoType == null || featuresForLayer == null || pathForLayer == null) {
                                Log.w(TAG, "loadLayersToMaplibreMap: skip incomplete layer id=" + entry.getKey());
                                continue;
                            }
                            String localVectorTileUrl = localVectorTileUrlMap.get(entry.getKey());
                            ILayer phaseLayer = getLayerById(entry.getKey());
                            ProdLogUtil.setPhase("loadLayers apply id=" + entry.getKey()
                                    + (phaseLayer != null ? " name=" + phaseLayer.getName() : ""));

                            if (localVectorTileUrl != null) {
                                if (createSource) {
                                    boolean sourceOk = createLocalVectorTileSourceForLayer(
                                            entry.getKey(),
                                            style,
                                            pathForLayer,
                                            localVectorTileUrl,
                                            phaseLayer instanceof com.nextgis.maplib.map.Layer
                                                    ? ((com.nextgis.maplib.map.Layer) phaseLayer).getMinZoom()
                                                    : -1,
                                            phaseLayer instanceof com.nextgis.maplib.map.Layer
                                                    ? ((com.nextgis.maplib.map.Layer) phaseLayer).getMaxZoom()
                                                    : -1);
                                    if (!sourceOk) {
                                        Log.w(TAG, "loadLayersToMaplibreMap: local vector tile source failed id="
                                                + entry.getKey());
                                        continue;
                                    }
                                }
                                createFillLayerForLocalVectorTileLayer(
                                        entry.getKey(),
                                        geoType,
                                        style,
                                        layersHashMap,
                                        layersHashMap2,
                                        symbolsLayerHashMap,
                                        layersStyle.get(entry.getKey()),
                                        phaseLayer,
                                        pathForLayer,
                                        signaturesRootLayer);
                            } else {
                                if (createSource)
                                    createSourceForLayer(entry.getKey(),
                                        geoType,
                                        featuresForLayer,
                                        style,
                                        sourceHashMap,
                                        rasterLayersURLMap,
                                        rasterLayersTmsTypeMap,
                                        pathForLayer, false,
                                        sourceNativeUriMap.get(entry.getKey()));

                                createFillLayerForLayer(entry.getKey(),
                                    geoType,
                                    style,
                                    layersHashMap,
                                    layersHashMap2,
                                    layersHashMapLineDash,
                                    symbolsLayerHashMap,
                                    layersStyle.get(entry.getKey()), false,
                                    getLayerById(entry.getKey()),
                                    pathForLayer, selectedDotCircleLayer,
                                    signaturesRootLayer);
                            }

                            checkLayerVisibility(entry.getKey());
                        }

                        if (Constants.MAP_STARTUP_UX_EXTRAS_ENABLED && Constants.DEBUG_MODE) {
                            Log.d(Constants.TAG, "MapDrawable loadLayers style apply body ms="
                                    + ((System.nanoTime() - tStyleBodyStart) / 1_000_000));
                        }

                        /* Upstream: restore active walk-by-geometry edit after process kill / map reload. */
                        if (layerForWalkRestore != null && featureToRestore != null) {
                            try {
                                editingObject = null;
                                editingFeature = null;

                                startFeatureSelectionForEdit(layerForWalkRestore,
                                        layerForWalkRestore.getGeometryType(),
                                        featureToRestore,
                                        true,
                                        layerForWalkRestore.getDefaultStyleNoExcept(),
                                        true);

                                editingFeature = MPLFeaturesUtils.getFeatureFromNGFeature(featureToRestore.getGeometry());
                                if (editingObject != null) {
                                    editingObject.editingFeature = editingFeature;
                                    editingObject.extractVertices(editingFeature, false);
                                    editingObject.hideVertext();
                                    editingObject.selectLastPoint();
                                }
                            } catch (Throwable wt) {
                                Log.e(TAG, "loadLayersToMaplibreMap: walk-restore", wt);
                            } finally {
                                layerForWalkRestore = null;
                                featureToRestore = null;
                            }
                        }

                        /* Upstream: collector hooks — must run after layers are applied. */
                        MaplibreMapInteraction loadedFrag = mapContext.get();
                        if (loadedFrag != null) {
                            try {
                                loadedFrag.setMapLayersLoaded();
                                loadedFrag.checkCreateIfNeed();
                            } catch (Throwable th) {
                                Log.e(TAG, "loadLayersToMaplibreMap: post-load hooks", th);
                            }
                        }

                        } catch (Throwable t) {
                            logErr("loadLayersToMaplibreMap: onDidFinishLoadingStyle", t);
                        } finally {
                            mLayerLoadInProgress = false;
                            if (styleRequestId == mLoadLayersStyleRequestId.get()) {
                                dismissStylingProgress();
                            }
                            if (mPendingReload) {
                                mPendingReload = false;
                                Log.d(TAG, "loadLayersToMaplibreMap: executing deferred reload");
                                MaplibreMapInteraction mf = mapContext.get();
                                if (mf != null) {
                                    mf.reloadMapStyleAndLayersAfterLayerFillBatch();
                                }
                            }
                        }
                    }
                };
                mapViewForStyle.addOnDidFinishLoadingStyleListener(mLoadLayersStyleListener);
                if (Constants.MAP_STARTUP_UX_EXTRAS_ENABLED && Constants.DEBUG_MODE && tSetStyleCallNs != null) {
                    tSetStyleCallNs[0] = System.nanoTime();
                }
                mapForStyle.setStyle(new Style.Builder().fromJson(styleJson));
            });
            } catch (Throwable t) {
                logErr("loadLayersToMaplibreMap: worker", t);
                mainHandler.post(() -> {
                    mLayerLoadInProgress = false;
                    dismissStylingProgress();
                });
            } finally {
                ProdLogUtil.setPhase("");
            }
        });
        executor.shutdown();
    }

    public void updateWalkingFeature(Feature featureToUpate){
        editingFeature = MPLFeaturesUtils.getFeatureFromNGFeature(featureToUpate.getGeometry());
        editingObject.editingFeature = editingFeature;
        editingObject.extractVertices(editingFeature,  false);
        editingObject.hideVertext();
        editingObject.selectLastPoint();
    }

    public void loadLayersToMaplibreMapLite(final  List<ILayer> allLayers, boolean skipUserLayers){
        if (maplibreMap.get() == null) {
            return;
        }
        Style style = maplibreMap.get().getStyle();
        if (style == null) {
            if (Constants.DEBUG_MODE) {
                Log.d(Constants.TAG, "loadLayersToMaplibreMapLite: skip, style is null");
            }
            return;
        }
        for (Layer layer : style.getLayers()) {
            if (!layer.getId().equals("background"))
                style.removeLayer(layer);
        }

        selectedDotCircleLayer = new CircleLayer("selected-dot-layer", "selected-dot-source")
                .withProperties(PropertyFactory.circleStrokeWidth(1f),
                        PropertyFactory.circleStrokeColor("#000000"));
        selectedDotCircleLayer.setProperties(
                PropertyFactory.circleRadius(Expression.get("radius")),
                PropertyFactory.circleColor(Expression.get("color")),
                PropertyFactory.circleOpacity(1.0f));
        style.addLayer(selectedDotCircleLayer);

        signaturesRootLayer = new CircleLayer("signatures-root-layer", "signature-root-source");
        style.addLayerBelow(signaturesRootLayer, "selected-dot-layer");

        LineLayer lineLayer = new LineLayer("selected-polygon-line", "selected-poly-source")
                .withProperties(
                        PropertyFactory.lineColor(Expression.get("color")),
                        PropertyFactory.lineWidth(2.0f) );
        style.addLayer(lineLayer);

        fillPolyEditLayer = new FillLayer("selected-polygon-fill" ,"selected-poly-source" )
                .withProperties(
                        PropertyFactory.fillColor("#FF00FF"),
                        PropertyFactory.fillOpacity(0.2f));


        CircleLayer vertexFillLayer = new CircleLayer("vertex-layer", "vertex-source")
                .withProperties(PropertyFactory.circleStrokeWidth(1f),
                        PropertyFactory.circleStrokeColor("#000000"));
        vertexFillLayer.setProperties(
                PropertyFactory.circleRadius(Expression.get("radius")),
                PropertyFactory.circleColor(Expression.get("color")),
                PropertyFactory.circleOpacity(1.0f));

        style.addLayer(vertexFillLayer);

        final Drawable drawableStand = getContext().getResources().getDrawable( R.drawable.ic_location_standing);
        final Bitmap bitmapStand = drawableToBitmap(drawableStand);
        String iconStandId = "user-marker-location-stand";
        style.addImage(iconStandId, bitmapStand);

        final Drawable drawableGo = getContext().getResources().getDrawable( R.drawable.ic_location_moving);
        final Bitmap bitmapGo = drawableToBitmap(drawableGo);
        String iconGoId = "user-marker-location-go";
        style.addImage(iconGoId, bitmapGo);

        SymbolLayer locationLayer = new SymbolLayer("user-location-layer", "user-location-source")
                .withProperties(
                        PropertyFactory.iconImage(
                                Expression.switchCase(
                                        Expression.eq(Expression.get("type"), Expression.literal("stand")), Expression.literal("user-marker-location-stand"),
                                        Expression.eq(Expression.get("type"), Expression.literal("go")), Expression.literal("user-marker-location-go"),
                                        Expression.literal("user-marker-location-stand"))),
                        PropertyFactory.iconRotate(Expression.get("bearing")),
                        PropertyFactory.iconSize(1.0f),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP));
        style.addLayer(locationLayer);

        // TRACKING
        // saved track line
        Layer trackLayer = new LineLayer( "track-line-layer", "track-line-source").
                withProperties(
                        PropertyFactory.lineColor("#0000FF"),
                        PropertyFactory.lineWidth(getMPLThinkness(5)));
        style.addLayer(trackLayer);

        // track in progress
        Layer trackInProgressLayer = new LineLayer( "track-inprogress-layer", "track-inprogress-source").
                withProperties(
                        PropertyFactory.lineColor("#0000FF"),
                        PropertyFactory.lineWidth(getMPLThinkness(5)));
        style.addLayer(trackInProgressLayer);

        // marker
        final Drawable drawable = getContext().getResources().getDrawable( R.drawable.ic_action_anchor_2);
        final Bitmap bitmap = drawableToBitmap(drawable);

        final IconFactory iconFactory = IconFactory.getInstance(getContext());
        final Icon markerIcon = iconFactory.fromBitmap(bitmap);
        String iconId = "marker-icon-selected";
        style.addImage(iconId, bitmap);

        SymbolLayer symbolLayer = new SymbolLayer("marker-layer", "marker-source")
                .withProperties(
                        org.maplibre.android.style.layers.PropertyFactory.iconImage(iconId),
                        org.maplibre.android.style.layers.PropertyFactory.iconAnchor(org.maplibre.android.style.layers.Property.ICON_ANCHOR_TOP_LEFT));
        style.addLayer(symbolLayer);

        if (!skipUserLayers) {
            final Map<Integer, Integer> layersType = new HashMap<>();
            final Map<Integer, com.nextgis.maplib.display.Style> layersStyle = new HashMap<>();
            final Map<Integer, String> rasterLayersURLMap = new HashMap<>();
            final Map<Integer, Integer> rasterLayersTmsTypeMap = new HashMap<>();

            final List<org.maplibre.geojson.Feature> tracksFeatures = new ArrayList<>();

            final AccountManager accountManager = AccountManager.get(getContext());
            final Connections connections = fillConnections(getContext(), accountManager);

            sourcesOrder.clear();

            for (ILayer iLayer : allLayers) {
//            Log.e("MPLREM",  "iterate layer " + iLayer.getName());

                if (iLayer instanceof VectorLayer) {
                    VectorLayer layer = (VectorLayer) iLayer;
                    layersType.put(layer.getId(), layer.getGeometryType());

                    layersStyle.put(layer.getId(), layer.getDefaultStyleNoExcept());

                    List<org.maplibre.geojson.Feature> vectorFeatures = sourceFeaturesHashMap.get(layer.getId());
                    // be more lite - get features from saved hash
                    //sourceFeaturesHashMap.put(layer.getId(), vectorFeatures);
                    //List<org.maplibre.geojson.Feature> vectorFeatures = createFeatureListFromLayer(layer);
                    //sourceFeaturesHashMap.put(layer.getId(), vectorFeatures);
                    sourcesOrder.put(layer.getId(), new ArrayList<>());
                } else if (iLayer instanceof TrackLayer) {
                    TrackLayer layer = (TrackLayer) iLayer;
                    layersType.put(layer.getId(), GT_TRACK_WA);
                    tracksFeatures.clear();
                    tracksFeatures.addAll(createFeatureListFromTrackLayer(layer));
                } else if (iLayer instanceof NGWRasterLayer) {
                    // need add auth
                    Connection found = null;
                    if (iLayer instanceof NGWRasterLayer) {
                        for (int i = 0; i < connections.getChildrenCount(); i++) {
                            if (connections.getChild(i).getName().equals((((NGWRasterLayer) iLayer).getAccountName()))) {
                                found = (Connection) connections.getChild(i);
                                String basicAuth = getHTTPBaseAuth(found.getLogin(), found.getPassword());
                                if (null != basicAuth) {
                                    final String url = ((NGWRasterLayer) iLayer).getURL();
                                    final String getBaseUrl = getBaseUrlpart(url);
                                    final String resPart = "resource=" + extractResourceValue(url);
                                    final String[] authPart = new String[4];
                                    authPart[0] = getBaseUrl;
                                    authPart[1] = resPart;
                                    authPart[2] = basicAuth;
                                    authPart[3] = iLayer.getPath().toString();
                                    ((IGISApplication) getContext().getApplicationContext()).updateAuthPair(authPart);
                                    break;
                                }
                            }
                        }

                        TMSLayer layer = (TMSLayer) iLayer;
                        layersType.put(layer.getId(), GT_RASTER_WA);
                        rasterLayersURLMap.put(layer.getId(), ((NGWRasterLayer) layer).getURL());
                        sourceFeaturesHashMap.put(layer.getId(), new ArrayList<>());
                        sourcesOrder.put(layer.getId(), new ArrayList<>());
                    }
                } else if (iLayer instanceof RemoteTMSLayer) {
                    final String url = ((RemoteTMSLayer) iLayer).getURL();
                    final String getBaseUrl = getBaseUrlpart(url);
                    final String resPart = "resource=" + extractResourceValue(url);
                    final String[] authPart = new String[4];
                    authPart[0] = getBaseUrl;
                    authPart[1] = resPart;
                    authPart[2] = "no";//no auth RemoteTMSLayer - geoservice map
                    authPart[3] = iLayer.getPath().toString();
                    ((IGISApplication) getContext().getApplicationContext()).updateAuthPair(authPart);

                    TMSLayer layer = (TMSLayer) iLayer;
                    layersType.put(layer.getId(), GT_RASTER_WA);
                    if (((RemoteTMSLayer) layer).mIsOfflineLayer) {
                        rasterLayersURLMap.put(layer.getId(), "file://" + (layer).getPath().toString() + "/{z}/{x}/{y}.tile");
                        rasterLayersTmsTypeMap.put(layer.getId(), layer.getTMSType());
                    } else {
                        rasterLayersURLMap.put(layer.getId(), ((RemoteTMSLayer) layer).getURLSubdomain());
                        rasterLayersTmsTypeMap.put(layer.getId(), layer.getTMSType());
                    }
                    sourceFeaturesHashMap.put(layer.getId(), new ArrayList<>());
                    sourcesOrder.put(layer.getId(), new ArrayList<>());
                } else if (iLayer instanceof LocalTMSLayer) {
                    TMSLayer layer = (TMSLayer) iLayer;
                    layersType.put(layer.getId(), GT_RASTER_WA);
                    rasterLayersURLMap.put(layer.getId(), "file://" + (layer).getPath().toString() + "/{z}/{x}/{y}.tile");
                    rasterLayersTmsTypeMap.put(layer.getId(), layer.getTMSType());
                    sourceFeaturesHashMap.put(layer.getId(), new ArrayList<>());
                    sourcesOrder.put(layer.getId(), new ArrayList<>());
                }
            }



            List<Map.Entry<Integer, List<org.maplibre.geojson.Feature>>> listOf = new ArrayList<>(sourcesOrder.entrySet());
            Collections.reverse(listOf);

            for (Map.Entry<Integer, List<org.maplibre.geojson.Feature>> entry : listOf) {

                ILayer entryLayer = getLayerById(entry.getKey());
                Integer entryType = layersType.get(entry.getKey());
                if (entryLayer == null || entryType == null) {
                    Log.w(TAG, "loadLayersToMaplibreMapLite: skip incomplete layer id=" + entry.getKey());
                    continue;
                }
                runGuarded("loadLayersToMaplibreMapLite layer id=" + entry.getKey()
                        + " name=" + entryLayer.getName(), () -> {
                    if (localVectorTileUrlMap.containsKey(entry.getKey())) {
                        createFillLayerForLocalVectorTileLayer(
                                entry.getKey(),
                                entryType,
                                style,
                                layersHashMap,
                                layersHashMap2,
                                symbolsLayerHashMap,
                                layersStyle.get(entry.getKey()),
                                entryLayer,
                                entryLayer.getPath().toString(),
                                signaturesRootLayer);
                    } else {
                        createFillLayerForLayer(entry.getKey(),
                                entryType,
                                style,
                                layersHashMap,
                                layersHashMap2,
                                layersHashMapLineDash,
                                symbolsLayerHashMap,
                                layersStyle.get(entry.getKey()), false,
                                entryLayer,
                                entryLayer.getPath().toString(), selectedDotCircleLayer,
                                signaturesRootLayer);
                    }

                    checkLayerVisibility(entry.getKey());
                });
            }
        }

        syncUserLocationSourceFromStyle(style);
    }

    /**
     * After {@link #loadLayersToMaplibreMapLite} the style keeps {@code user-location-source} but the
     * cached {@link #locationSource} can point at a detached GeoJsonSource; GPS updates then no-op until
     * a full style reload (e.g. app resume). Always resolve from the live {@link Style}.
     */
    private void syncUserLocationSourceFromStyle(@Nullable Style style) {
        if (style == null) {
            return;
        }
        Source src = style.getSource("user-location-source");
        if (src instanceof GeoJsonSource) {
            locationSource = (GeoJsonSource) src;
        }
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        android.graphics.PointF screenPoint = new android.graphics.PointF(event.getX(), event.getY());
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN: {
                if (mapContext.get()!= null)
                    mapContext.get().setLongLongClickProcesses(false);
                clickPoint = new PointF(event.getX(), event.getY());
                android.graphics.RectF rect = new android.graphics.RectF(event.getX() - 20,event.getY() - 20,event.getX() + 20,event.getY() + 20);
                List<org.maplibre.geojson.Feature> featuresMarker = maplibreMap.get().queryRenderedFeatures(rect, "marker-layer");

                if (mapContext.get().getMode() == MODE_EDIT_BY_TOUCH ){
                    isDraggingByTouchGPS = true;
                    startEvent = event;

                    if (editingObject != null)
                        editingObject.hideVertext();
                    return true;
                }

                if (!featuresMarker.isEmpty()){
                    // press marker - lock for future move
                    isDragging = true;
                    startEvent = event;
                    return true;
                }
                // no marker  - check vertex press
                android.graphics.RectF rectVertex = new android.graphics.RectF(event.getX() - 30,event.getY() - 30,event.getX() + 30,event.getY() + 30);
                List<org.maplibre.geojson.Feature> features = maplibreMap.get().queryRenderedFeatures(rectVertex, "vertex-layer");

                if (!features.isEmpty()) {
                    org.maplibre.geojson.Feature clickedFeature = null;
                    // vertes press - change selection
                    int index = -1;

                    // check for middle point click
                    if (features.get(0).hasNonNullValueForProperty("middle")) {
                        isSwitchVertex = true;
                        // need add point
                        //int previndex = features.get(0).getNumberProperty("previndex").intValue();
                        clickedFeature = features.get(0);
                        //index = features.get(0).getNumberProperty("index").intValue();

                        if (editingObject != null) {
                            editingObject.updateSelectionMiddlePoint(features.get(0));
                            //editingObject.updateSelectionVerticeIndex(index);
                            editingObject.updateEditingPolygonAndVertex();
                            mapContext.get().updateGeometryFromMaplibre(editingObject.editingFeature, originalSelectedFeature, editingObject);

                        }
                        Point point = ((Point)clickedFeature.geometry());
                        setMarker(new LatLng(point.latitude(), point.longitude()));
                        isDragging = true;
                        return true;
                    }

                    if (features.get(0).hasNonNullValueForProperty("index")) {
                        clickedFeature = features.get(0);
                        index = features.get(0).getNumberProperty("index").intValue();
                        if (editingObject != null && editingObject instanceof  MeasurmentLine)
                            isMeasurmentChangeVertex = true;
                    }

                    if (index != -1) {
                        if (editingObject != null) {
                            editingObject.updateSelectionVerticeIndex(index);
                            editingObject.updateEditingPolygonAndVertex();
                            editingObject.displayMiddlePoints(false, true);
                            mapContext.get().updateActions(editingObject);
                        }
                        Point point = ((Point)clickedFeature.geometry());
                        setMarker(new LatLng(point.latitude(), point.longitude()));
                        return true;
                    }
                } else { // no select - no touch precess return false
                    return false;
                }
                break;
            }

            case MotionEvent.ACTION_MOVE: {
                int selectedVertexIndex = -1;
                if (editingObject != null )
                    selectedVertexIndex = editingObject.getSelectedVertexIndex();

                if (isDraggingByTouchGPS && editingObject!= null){
                    if (mapContext.get().getMode() == MODE_EDIT_BY_TOUCH){
                        PointF  newPoint = new PointF(screenPoint.x,screenPoint.y);
                        LatLng latLng = maplibreMap.get().getProjection().fromScreenLocation(newPoint);
                        editingObject.addNewFlowPoint(latLng, false);
                        editingObject.updateEditingPolygonAndVertex();
                        return true;
                    }
                }

                if (isDragging && selectedVertexIndex != -1) {
                    if (!hasEditeometry) {
                        hasEditeometry = true;
                    }
                    if(deltaPoint == null && startEvent != null){
                        if (editingObject != null){
                            LatLng latLng = editingObject.getSelectedPoint();
                            if (latLng != null) {

                                PointF vertex = maplibreMap.get().getProjection().toScreenLocation(latLng);
                                float dx = startEvent.getX() - vertex.x;
                                float dy = startEvent.getY() - vertex.y;
                                deltaPoint = new PointF(dx, dy);
                            }
                        }
                    }
                    if(deltaPoint == null){
                        return true;
                    }
                    PointF  newShiftedPoint = new PointF(screenPoint.x -deltaPoint.x,screenPoint.y - deltaPoint.y );
                    LatLng latLng = maplibreMap.get().getProjection().fromScreenLocation(newShiftedPoint);
                    Point newPoint = Point.fromLngLat(latLng.getLongitude(), latLng.getLatitude());

                    if (editingObject != null) {
                        editingObject.updateSelectionVertice(newPoint);
                        editingObject.updateEditingPolygonAndVertex();
                    }
                    if (markerFeatureCollection.features().size() > 0)
                        hideMarker();
                    return true;
                } else {
                    return false;
                }
            }

            case MotionEvent.ACTION_UP: {
                if (mapContext.get()!=null && mapContext.get().getLongLongClickProcesses()){
                    mapContext.get().setLongLongClickProcesses(false);
                    return false;
                }

                float deltaX = clickPoint.x - event.getX();
                float deltaY = clickPoint.y - event.getY();
                float distance = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);

                if (isDraggingByTouchGPS){
                    mapContext.get().updateGeometryFromMaplibre(editingObject.editingFeature, originalSelectedFeature, editingObject);
                    isDraggingByTouchGPS = false;
                    isDragging = false;
                    isDraggingByTouchGPS = false;
                    isSwitchVertex = false;
                    deltaPoint = null;
                    startEvent = null;
                    return false;
                } else {


                    if (!isDragging)
                        if (distance < 5) {
                            if (editingObject != null && editingObject instanceof MeasurmentLine){
                                if (!isMeasurmentChangeVertex) {
                                    android.graphics.PointF touchscreenPoint = new android.graphics.PointF(event.getX(), event.getY());
                                    LatLng latLng = maplibreMap.get().getProjection().fromScreenLocation(touchscreenPoint); // todo add tolerance and rect
                                    editingObject.addNewFlowPoint(latLng, false);
                                    setMarker(latLng);
                                    editingObject.updateEditingPolygonAndVertex();
                                    updateMeasurmentCaptions(editingObject);

                                } else
                                    isMeasurmentChangeVertex = false;
                                return false;

                            } else
                                mapContext.get().processMapClick(screenPoint.x, screenPoint.y);
                        }
                    clickPoint = null;

                    if (isDragging || isSwitchVertex) {
                        if (editingObject != null) {
                            mapContext.get().updateGeometryFromMaplibre(editingObject.editingFeature, originalSelectedFeature, editingObject);
                            editingObject.regenerateVertexFeatures();
                            editingObject.displayMiddlePoints(false, true);
                            LatLng pointReleased = editingObject.getSelectedPoint();

                            if (pointReleased != null)
                                setMarker(pointReleased);

                            if (editingObject  instanceof  MeasurmentLine)
                                updateMeasurmentCaptions(editingObject);
                        } else
                            setMarker(event);
                    }
                }
                isDragging = false;
                isDraggingByTouchGPS = false;
                isSwitchVertex = false;
                deltaPoint = null;
                startEvent = null;
                return false;
            }
        }
        return false;
    }


    public void updateHistoryByWalkEnd() {
        if (editingObject == null || mapContext.get() == null)
            return;
        mapContext.get().updateGeometryFromMaplibre(
                editingObject.editingFeature, originalSelectedFeature, editingObject);
    }

    public void setMarker(MotionEvent motionEvent){
        android.graphics.PointF screenPoint = new android.graphics.PointF(motionEvent.getX(), motionEvent.getY());
        LatLng latLng = maplibreMap.get().getProjection().fromScreenLocation(screenPoint); // todo add tolerance and rect
        setMarker(latLng);
    }

    public void setMarker(LatLng latLng){
        if (latLng == null) {
            Log.e(TAG, "set marker on null point");
            return;
        }
        org.maplibre.geojson.Feature feature = org.maplibre.geojson.Feature.fromGeometry(Point.fromLngLat(latLng.getLongitude(), latLng.getLatitude()));
        markerFeatureCollection = FeatureCollection.fromFeature(feature);
        markerSource.setGeoJson(markerFeatureCollection);
    }

    public void hideMarker(){
        markerFeatureCollection = FeatureCollection.fromFeatures(emptyList());
        if (markerSource != null)
            markerSource.setGeoJson(markerFeatureCollection);
    }

    private static Bitmap drawableToBitmap(Drawable drawable) {
        Bitmap bitmap = Bitmap.createBitmap(
                drawable.getIntrinsicWidth(),
                drawable.getIntrinsicHeight(),
                Bitmap.Config.ARGB_8888
        );
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }


    @Override
    public boolean onMapClick(@NonNull LatLng latLng) {

        return false;
    }

    @Override
    public boolean onMapLongClick(@NonNull LatLng latLng) {
        // ask mapFragment for selection// state
        // map
        PointF clickPoint = maplibreMap.get().getProjection().toScreenLocation(latLng);
        GeoEnvelope clickeEnelope = getClickEnelope(clickPoint);

        boolean result = mapContext.get().processMapLongClick(clickeEnelope, clickPoint);
        if (result && mapContext.get()!= null)
            mapContext.get().setLongLongClickProcesses(true);
        return false;
    }

    public GeoEnvelope getClickEnelope(PointF clickPoint){
        int TOLERANCE_DP       = 20;
        float mTolerancePX = getContext().getResources().getDisplayMetrics().density * TOLERANCE_DP;

        PointF minP = new PointF(clickPoint.x - mTolerancePX,clickPoint.y - mTolerancePX);
        PointF maxP = new PointF(clickPoint.x + mTolerancePX,clickPoint.y + mTolerancePX);

        LatLng minL = maplibreMap.get().getProjection().fromScreenLocation(minP);
        LatLng maxL = maplibreMap.get().getProjection().fromScreenLocation(maxP);

        double[] minPoints = convert4326To3857(minL.getLongitude(), minL.getLatitude());
        double[] maxPoints = convert4326To3857(maxL.getLongitude(), maxL.getLatitude());

        var minx =   minPoints[0];
        var maxx =   maxPoints[0];
        var miny =   minPoints[1];
        var maxy =   maxPoints[1];

        if (minx > maxx){
            minx =   maxPoints[0];
            maxx =   minPoints[0];
        }
        if (miny > maxy){
            miny =   maxPoints[1];
            maxy =   minPoints[1];
        }

        //val exactEnv: GeoEnvelope = GeoEnvelope(pointsMin[0],  pointsMax[0], pointsMin[1], pointsMax[1])
        GeoEnvelope exactEnv  = new  GeoEnvelope(minx, maxx, miny, maxy);
        return exactEnv;
    }

    public void startFeatureSelectionForView(ILayer layerd, Feature originalSelectedFeature){
        if (layerd == null)
            return;
        Long selectedFeatureId = originalSelectedFeature.getId();

        if (editingFeature != null  ){
            Integer lID = Integer.valueOf(editingFeature.getStringProperty(prop_layerid));
            Long fID = Long.valueOf(editingFeature.getStringProperty(prop_featureid));

            if (layerd.getId() == lID && selectedFeatureId.equals(fID))
                return;
            // need unselect feature
            unselectFeatureFromView();
            return;
        }

        List<org.maplibre.geojson.Feature> layerFeatures = sourceFeaturesHashMap.get(layerd.getId());

        if (layerFeatures != null && !layerFeatures.isEmpty()) {
            for (org.maplibre.geojson.Feature item : layerFeatures){
                if (item!= null && item.hasProperty(prop_featureid)) {
                    long id = item.getNumberProperty(prop_featureid).longValue();
                    if (id == selectedFeatureId) {
                        viewedFeature = item;
                        break;
                    }
                }
            }
        } else if (localVectorTileUrlMap.containsKey(layerd.getId())
                && originalSelectedFeature.getGeometry() != null) {
            viewedFeature = MPLFeaturesUtils.getFeatureFromNGFeature(originalSelectedFeature.getGeometry());
            if (viewedFeature != null) {
                viewedFeature.addNumberProperty(prop_featureid, selectedFeatureId);
                viewedFeature.addNumberProperty(prop_layerid, layerd.getId());
                viewedFeature.addNumberProperty(prop_order, selectedFeatureId);
            }
        }

        if (viewedFeature != null) {

            var featureSelected = copyFeature(viewedFeature);
            featureSelected.addStringProperty("color", colorLightBlue);

            int type = ((VectorLayer)layerd).getGeometryType();

            if  (type == GTPoint || type == GTMultiPoint) {
                selectedDotSource.setGeoJson(featureSelected);
            }

            if  (type == GeoConstants.GTPolygon || type == GTMultiPolygon
                    || type == GTLineString ||type == GTMultiLineString ) {
                selectedPolySource.setGeoJson(featureSelected);
            }

            this.originalSelectedFeature = originalSelectedFeature;
        }
    }


    public void startFeatureSelectionForEdit(final ILayer  ilayer, Integer layerGeoType,
                                             Feature originalSelectedFeature, boolean createNew,
                                             com.nextgis.maplib.display.Style ngstyle,
                                             boolean isFillByWalking){

        Long selectedFeatureId = originalSelectedFeature.getId();

        // clear prev edit state
        if (editingObject != null) {
            if (editingFeature != null) {
                Integer lID = null;
                if (editingFeature.hasProperty(prop_layerid))
                    lID = Integer.valueOf(editingFeature.getStringProperty(prop_layerid));
                else
                    lID = ilayer.getId();


                Long fID = null;
                if (editingFeature.hasProperty(prop_featureid))
                    fID = Long.valueOf(editingFeature.getStringProperty(prop_featureid));
                else
                    fID = originalSelectedFeature.getId();

                if (ilayer.getId() != lID || !selectedFeatureId.equals(fID)) {
                    // need clear previous edited obj
                    unselectFeatureFromEdit(false, false);
                } else {
                    // same obj - do nothing
                    return;
                }
            }
        }

        // clear sources
        selectedPolySource.setGeoJson(FeatureCollection.fromFeatures(new ArrayList<>()));
        selectedDotSource.setGeoJson(FeatureCollection.fromFeatures(new ArrayList<>()));
        vertexSource.setGeoJson(FeatureCollection.fromFeatures(new ArrayList<>()));


        List<org.maplibre.geojson.Feature> layerFeatures = sourceFeaturesHashMap.get(ilayer.getId());
        org.maplibre.geojson.Feature  editingFeatureTmp = null;
        if (layerFeatures != null)
            for (org.maplibre.geojson.Feature item:layerFeatures){
                if (item.hasProperty(prop_featureid)) {
                    long id = item.getNumberProperty(prop_featureid).longValue();
                    if (id == selectedFeatureId) {
                        editingFeatureTmp = item;
                        break;
                    }
                }
            }

        if (createNew) {
            org.maplibre.geojson.Feature feature = null;
            int type = ((VectorLayer)ilayer).getGeometryType();

            LatLng center = null;
            if (originalSelectedFeature != null && originalSelectedFeature.getGeometry() != null
                    && originalSelectedFeature.getGeometry() instanceof  GeoPoint){
                center = latLngPointFromGeoPoint((GeoPoint) originalSelectedFeature.getGeometry());
            } else {
                center = maplibreMap.get().getCameraPosition().target;
            }

            Projection projection = maplibreMap.get().getProjection();
            Point point = Point.fromLngLat(center.getLongitude(), center.getLatitude());

            if (!isFillByWalking)
                switch (type){
                    case GTPoint :
                        feature = org.maplibre.geojson.Feature.fromGeometry(point);
                        break;

                    case GTMultiPoint:
                        MultiPoint mpoint = MultiPoint.fromLngLats(Arrays.asList(point));
                        feature = org.maplibre.geojson.Feature.fromGeometry(mpoint);
                        break;

                    case GeoConstants.GTPolygon:
                        List<List<org.maplibre.geojson.Point>> polyList = new ArrayList<>();
                        polyList.add(createPointsForRing(center, maplibreMap.get().getProjection(),  true));
                        Polygon polygon = Polygon.fromLngLats(polyList);
                        feature = org.maplibre.geojson.Feature.fromGeometry(polygon);
                        break;

                    case GTMultiPolygon:
                        List<List<org.maplibre.geojson.Point>> polyListMP = new ArrayList<>();
                        polyListMP.add(createPointsForRing(center, maplibreMap.get().getProjection(),  true));
                        MultiPolygon polygonMP = MultiPolygon.fromLngLats(Arrays.asList(polyListMP));
                        feature = org.maplibre.geojson.Feature.fromGeometry(polygonMP);
                        break;

                    case GTLineString:
                        List<org.maplibre.geojson.Point> lineList = getNewLinePoints(center, projection);
                        LineString line = LineString.fromLngLats(lineList);
                        feature = org.maplibre.geojson.Feature.fromGeometry(line);
                        break;

                    case GTMultiLineString:
                        List<org.maplibre.geojson.Point> lineList2 = getNewLinePoints(center, projection);
                        List<List<org.maplibre.geojson.Point>> multiline = new ArrayList<>();
                        multiline.add(lineList2);
                        MultiLineString multiLineString = MultiLineString.fromLngLats(multiline);
                        feature = org.maplibre.geojson.Feature.fromGeometry(multiLineString);
                        break;
                }else {
                // need create with 0 point - first point will be from gps on walking
                    switch (type){
                        case GTPoint :
                            feature = org.maplibre.geojson.Feature.fromGeometry(point);
                            break;

                        case GTMultiPoint:
                            MultiPoint mpoint = MultiPoint.fromLngLats(emptyList());
                            feature = org.maplibre.geojson.Feature.fromGeometry(mpoint);
                            break;

                        case GeoConstants.GTPolygon:
                            Polygon polygon = Polygon.fromLngLats(emptyList());
                            feature = org.maplibre.geojson.Feature.fromGeometry(polygon);
                            break;

                        case GTMultiPolygon:
                            MultiPolygon polygonMP = MultiPolygon.fromLngLats(emptyList());
                            feature = org.maplibre.geojson.Feature.fromGeometry(polygonMP);
                            break;

                        case GTLineString:
                            //List<org.maplibre.geojson.Point> lineList = getNewLinePoints(center, projection);
                            LineString line = LineString.fromLngLats(emptyList());
                            feature = org.maplibre.geojson.Feature.fromGeometry(line);
                            break;

                        case GTMultiLineString:
                            MultiLineString multiLineString = MultiLineString.fromLngLats(emptyList());
                            feature = org.maplibre.geojson.Feature.fromGeometry(multiLineString);
                            break;
                }
            }

            feature.addStringProperty(prop_layerid, String.valueOf(ilayer.getId()));

            if (ngstyle != null){
                String styleField = ((ITextStyle)ngstyle).getField();
                String styleText = ((ITextStyle) ngstyle).getText();

                if (styleField != null || styleText != null ) {
                    String signature = null;
                    if (styleText != null)
                        signature = styleText;
                    else {
                        if (styleField == id_name)
                            signature = String.valueOf(originalSelectedFeature.getId());
                        else
                            signature = originalSelectedFeature.getFieldValueAsString(styleField);
                    }

                    if (!TextUtils.isEmpty(signature)) {
                        feature.addStringProperty(prop_signature_text,
                                MPLFeaturesUtils.getSpaceCorrectedText(signature));
                    }
                }
            }


            sourceFeaturesHashMap.computeIfAbsent(ilayer.getId(), k -> new ArrayList<>());
            int size = sourceFeaturesHashMap.get(ilayer.getId()).size();
            feature.addStringProperty(prop_order, String.valueOf(size+1));
            feature.addStringProperty(prop_featureid, String.valueOf(originalSelectedFeature.getId()));
            editingFeatureTmp = copyFeature(feature);
        }

        if (editingFeatureTmp != null) {
            selectedEditedSource = sourceHashMap.get(ilayer.getPath().toString());
            editingFeature = editingFeatureTmp;

            int type = ((VectorLayer)ilayer).getGeometryType();
            GeoJsonSource choosed = null;
            if  (type == GTPoint || type == GTMultiPoint) {
                selectedDotSource.setGeoJson(FeatureCollection.fromFeature(editingFeature));
                choosed = selectedDotSource;
                editingFeature.addStringProperty("color", colorRED);
            }

            if  (type == GeoConstants.GTPolygon || type == GTMultiPolygon) {
                selectedPolySource.setGeoJson(FeatureCollection.fromFeature(editingFeature));
                choosed = selectedPolySource;
            }

            if  (type == GTLineString || type == GTMultiLineString) {
                selectedPolySource.setGeoJson(FeatureCollection.fromFeature(editingFeature));
                choosed = selectedPolySource;
            }

            editingFeatureOriginal = copyFeature(editingFeatureTmp);

            polygonFeatures = sourceFeaturesHashMap.get(ilayer.getId());
            this.originalSelectedFeature = originalSelectedFeature;

            // choose layer
            editingObject = MPLFeaturesUtils.createEditObject(layerGeoType,
                    selectedEditedSource,
                    editingFeature,
                    polygonFeatures,
                    choosed,
                    vertexSource,
                    markerSource,
                    ilayer.getPath().toString());

            Layer layer = maplibreMap.get().getStyle().getLayer("selected-polygon-fill");
            if (layerGeoType ==GeoConstants.GTPolygon ||
                    layerGeoType == GTMultiPolygon){
                if (layer == null) {
                    maplibreMap.get().getStyle().addLayer(fillPolyEditLayer);
                }
            } else {
                if (layer != null)
                    maplibreMap.get().getStyle().removeLayer(fillPolyEditLayer);
            }

            editingObject.extractVertices(editingFeature,  true);

            LatLng selectedPoint = editingObject.getSelectedPoint();
            setMarker(selectedPoint);
            editingObject.updateEditingPolygonAndVertex();
        }
    }

    public void deleteFeature(Long selectedFeatureId, int layerdID){
        List<org.maplibre.geojson.Feature> layerFeatures = sourceFeaturesHashMap.get(layerdID);
        org.maplibre.geojson.Feature  editingFeatureTmp = null;
        for (org.maplibre.geojson.Feature item: layerFeatures){
            if (item.hasProperty(prop_featureid)) {
                long id = item.getNumberProperty(prop_featureid).longValue();
                if (id == selectedFeatureId) {
                    editingFeatureTmp = item;
                    break;
                }
            }
        }

        if (editingFeatureTmp != null) {
            selectedEditedSource = sourceHashMap.get(getLayerById(layerdID).getPath().toString());
            editingFeature = editingFeatureTmp;
            editingFeatureOriginal = editingFeatureTmp;
            polygonFeatures = sourceFeaturesHashMap.get(layerdID);

            Iterator<org.maplibre.geojson.Feature> it = polygonFeatures.iterator();
            String targetOrder = editingFeature.getStringProperty(MPLFeaturesUtils.prop_order);

            while (it.hasNext()) {
                org.maplibre.geojson.Feature f = it.next();
                if (Objects.equals(f.getStringProperty(MPLFeaturesUtils.prop_order), targetOrder)) {
                    it.remove();
                }
            }

            //polygonFeatures.removeIf(f -> Objects.equals(f.getStringProperty(prop_order), editingFeature.getStringProperty(prop_order)));
            selectedEditedSource.setGeoJson(FeatureCollection.fromFeatures(polygonFeatures));
            // need check sign
            ILayer iLayer = getLayerById(layerdID);
            VectorLayer vectorLayer = null;
            if (iLayer instanceof  VectorLayer)
                vectorLayer = (VectorLayer)iLayer;

            if (vectorLayer != null){
                if (vectorLayer.mGeometryType == GTPolygon || vectorLayer.mGeometryType == GTMultiPolygon){
                    reAssembleSignPoly(
                            maplibreMap.get().getStyle(),
                            polygonFeatures,
                            vectorLayer.getPath().toString());
                }
            }
        }
        editingFeature = null;
        editingFeatureOriginal = null;
        selectedPolySource.setGeoJson(FeatureCollection.fromFeatures(new ArrayList<>()));
        selectedDotSource.setGeoJson(FeatureCollection.fromFeatures(new ArrayList<>()));
    }

    public void hideFeature(Long selectedFeatureId, int layerdID){
        List<org.maplibre.geojson.Feature> layerFeatures = sourceFeaturesHashMap.get(layerdID);
        org.maplibre.geojson.Feature found = null;
        for (org.maplibre.geojson.Feature feature : layerFeatures){
            if (feature.getStringProperty(prop_featureid).equals(String.valueOf(selectedFeatureId))) {
                found = feature;
                break;
            }
        }
        GeoJsonSource source = sourceHashMap.get(getLayerById(layerdID).getPath().toString());

        if (found != null && source != null){
            String fid = String.valueOf(selectedFeatureId);
            Iterator<org.maplibre.geojson.Feature> it = layerFeatures.iterator();
            while (it.hasNext()) {
                org.maplibre.geojson.Feature f = it.next();
                if (Objects.equals(f.getStringProperty(prop_featureid), fid)) {
                    it.remove();
                    hiddedFeature = f;
                    hiddedFeatureId =selectedFeatureId;
                    hiddedlayerdID = layerdID;

                }
            }

            if (getLayerById(layerdID) instanceof VectorLayer vectorLayer){
                if (vectorLayer.mGeometryType == GTPolygon || vectorLayer.mGeometryType == GTMultiPolygon){
                    reAssembleSignPoly(
                            maplibreMap.get().getStyle(),
                            layerFeatures,
                            getLayerById(layerdID).getPath().toString());
                }
            }

            source.setGeoJson(FeatureCollection.fromFeatures(layerFeatures));
            selectedPolySource.setGeoJson(FeatureCollection.fromFeatures(new ArrayList<>()));
            selectedDotSource.setGeoJson(FeatureCollection.fromFeatures(new ArrayList<>()));

        }
    }

    public void showFeatureFromHide(Long selectedFeatureId, int layerdID, org.maplibre.geojson.Feature hiddedFeature){
        List<org.maplibre.geojson.Feature> layerFeatures = sourceFeaturesHashMap.get(layerdID);
        GeoJsonSource source = sourceHashMap.get(getLayerById(layerdID).getPath().toString());

        if (hiddedFeature != null && source != null && layerFeatures != null){
            layerFeatures.add(hiddedFeature);

            source.setGeoJson(FeatureCollection.fromFeatures(layerFeatures));
            selectedPolySource.setGeoJson(FeatureCollection.fromFeature(hiddedFeature));
            //selectedDotSource.setGeoJson(FeatureCollection.fromFeature(hiddedFeature));

        }
    }

    public void updateMarkerByEditObject(){
        if (editingObject != null && editingObject.getSelectedPoint() != null){

            setMarker(editingObject.getSelectedPoint());
        }
    }

    public void replaceGeometryFromHistoryChanges(GeoGeometry newGeometry){
        int oldIndex = -1;
        if (editingObject != null && editingObject.getSelectedPoint() != null){
            oldIndex = editingObject.getSelectedVertexIndex();
        }

        if  (newGeometry == null)
            return;

        if (newGeometry instanceof GeoLineString){
            org.maplibre.geojson.Feature featureML = getFeatureFromNGFeatureLine((GeoLineString)newGeometry);

            if(editingObject  != null && editingObject.editingFeature != null)
                copyProperties(editingObject.editingFeature, featureML);

            selectedPolySource.setGeoJson(featureML);
            editingObject.extractVertices(featureML,  false);
            editingObject.editingFeature = featureML;
        }

        if (newGeometry instanceof GeoMultiLineString){
            org.maplibre.geojson.Feature featureML = getFeatureFromNGFeatureMultiLine((GeoMultiLineString)newGeometry);

            if(editingObject  != null && editingObject.editingFeature != null)
                copyProperties(editingObject.editingFeature, featureML);

            selectedPolySource.setGeoJson(featureML);

            editingObject.extractVertices(featureML,  false);
            editingObject.editingFeature = featureML;
        }

        if (newGeometry instanceof  GeoPolygon){
            org.maplibre.geojson.Feature featureML = getFeatureFromNGFeaturePolygon((GeoPolygon)newGeometry);

            if(editingObject  != null && editingObject.editingFeature != null)
                copyProperties(editingObject.editingFeature, featureML);

            selectedPolySource.setGeoJson(featureML);

            editingObject.extractVertices(featureML,  false);
            editingObject.editingFeature = featureML;
        } else  if (newGeometry instanceof GeoMultiPolygon){

            org.maplibre.geojson.Feature featureML = getFeatureFromNGFeatureMultiPolygon((GeoMultiPolygon)newGeometry);

            if(editingObject  != null && editingObject.editingFeature != null)
                copyProperties(editingObject.editingFeature, featureML);

            selectedPolySource.setGeoJson(featureML);

            editingObject.extractVertices(featureML,  false);
            editingObject.editingFeature = featureML;

        }  else if (newGeometry instanceof  GeoPoint){

            org.maplibre.geojson.Feature featureML = getFeatureFromNGFeaturePoint((GeoPoint)newGeometry);

            if(editingObject  != null && editingObject.editingFeature != null)
                copyProperties(editingObject.editingFeature, featureML);

            selectedDotSource.setGeoJson(featureML);
            editingObject.extractVertices(featureML,  false);
            editingObject.editingFeature = featureML;

        } else if (newGeometry instanceof GeoMultiPoint){

            org.maplibre.geojson.Feature featureML = getFeatureFromNGFeatureMultiPoint((GeoMultiPoint)newGeometry);

            if(editingObject  != null && editingObject.editingFeature != null)
                copyProperties(editingObject.editingFeature, featureML);

            selectedDotSource.setGeoJson(featureML);
            editingObject.extractVertices(featureML,  false);
            editingObject.editingFeature = featureML;
        }
        if (oldIndex != -1) {
            editingObject.setSelectedVertexIndex(oldIndex);
            editingObject.updateEditingPolygonAndVertex();
        }
    }

    public void  cancelFeatureEdit(boolean backToOriginal){
        unselectFeatureFromEdit(backToOriginal, false);
        hideMarker();

        Layer layer = maplibreMap.get().getStyle().getLayer("selected-polygon-fill");
        if (layer != null)
            maplibreMap.get().getStyle().removeLayer(fillPolyEditLayer);


        if (originalSelectedFeature!= null && originalSelectedFeature.getId() != -1)
            startFeatureSelectionForView(mapContext.get().getSelectedLayer(), originalSelectedFeature );
    }

    public void unselectFeatureFromView(){
        if (viewedFeature != null) {
            //set color back
            viewedFeature.addStringProperty("color", colorBlue);

            viewedFeature = null;
            selectedPolySource.setGeoJson(FeatureCollection.fromFeatures(new ArrayList<>()));
            selectedDotSource.setGeoJson(FeatureCollection.fromFeatures(new ArrayList<>()));
        }
    }

    public void hideVertex(){
        if (editingObject != null)
            editingObject.hideVertext();
    }


    public void hideSelectedDotSource(){
        if (selectedDotSource != null)
            selectedDotSource.setGeoJson(FeatureCollection.fromFeatures(new ArrayList<>()));
    }

    public void showVertex(){
        if (editingObject != null)
            editingObject.showVertext();
    }

    public void showMarker(){
        if (editingObject != null)
            editingObject.showCurrentMarker();
    }

    public void unselectFeatureFromEdit(boolean backToOriginal, boolean keepEditObj) {
        if (editingObject != null && editingObject.editingFeature != null) {
            if (backToOriginal) {
                copyProperties(editingFeatureOriginal, editingObject.editingFeature);
            }

            hasEditeometry = false;
            org.maplibre.geojson.Feature target = backToOriginal ? editingFeatureOriginal : editingObject.editingFeature;

            boolean needChangeFeature = true;
            if (target != null && target.hasNonNullValueForProperty(prop_featureid) &&
                    target.getStringProperty(prop_featureid).equals("-1")) {
                needChangeFeature = false;
            }

            if (needChangeFeature) {
                // remove old - add new
                String targetOrder = target.getStringProperty(prop_order);
                Iterator<org.maplibre.geojson.Feature> it = polygonFeatures.iterator();
                while (it.hasNext()) {
                    org.maplibre.geojson.Feature f = it.next();
                    if (Objects.equals(f.getStringProperty(prop_order), targetOrder)) {
                        it.remove();
                        break;
                    }
                }
                target.addStringProperty("color", colorLightBlue);

                polygonFeatures.add(target);
            }

            if (!keepEditObj) {
                selectedEditedSource.setGeoJson(FeatureCollection.fromFeatures(polygonFeatures));

                // re-assemble signs for poly
                reAssembleSignPoly(maplibreMap.get().getStyle(),
                        polygonFeatures,
                        editingObject.layerPath);

                GeoJsonSource choosed = null;
                if (editingObject instanceof PointEditClass ||
                        editingObject instanceof MultiPointEditClass) {
                    choosed = selectedDotSource;
                } else {
                    choosed = selectedPolySource;
                }

                org.maplibre.geojson.Feature featureToRecolor = backToOriginal ? editingFeatureOriginal : editingObject.editingFeature;
                featureToRecolor.addStringProperty("color", colorLightBlue);
                // color for selection

                if  (keepEditObj)
                    choosed.setGeoJson(FeatureCollection.fromFeature(featureToRecolor));
                else
                    choosed.setGeoJson(FeatureCollection.fromFeatures(new ArrayList<>()));

                // clear vertex
                vertexSource.setGeoJson(FeatureCollection.fromFeatures(new ArrayList<>()));

                // fill layer remove
                Layer layer = maplibreMap.get().getStyle().getLayer("selected-polygon-fill");
                if (layer != null) {
                    maplibreMap.get().getStyle().removeLayer(fillPolyEditLayer);
                }

                // clear edited objects
                hideVertex();
                editingObject = null;
                editingFeatureOriginal = null;
                editingFeature = null;
            }
        }
    }

    public org.maplibre.geojson.Feature copyFeature(org.maplibre.geojson.Feature from ){
        org.maplibre.geojson.Feature newFeature = org.maplibre.geojson.Feature.fromGeometry(from.geometry());
        copyProperties(from, newFeature);
        return newFeature;
    }

    public void copyProperties(org.maplibre.geojson.Feature from, org.maplibre.geojson.Feature targetFeature){
        JsonObject properties = from.properties();
        if (properties != null) {
            for (String key : properties.keySet()) {
                JsonElement value = properties.get(key);
                if (value.isJsonPrimitive()) {
                    if (value.getAsJsonPrimitive().isString()) {
                        targetFeature.addStringProperty(key, value.getAsString());
                    } else if (value.getAsJsonPrimitive().isNumber()) {
                        targetFeature.addNumberProperty(key, value.getAsNumber());
                    } else if (value.getAsJsonPrimitive().isBoolean()) {
                        targetFeature.addBooleanProperty(key, value.getAsBoolean());
                    }
                } else {
                    targetFeature.addProperty(key, value);
                }
            }
        }
    }

    public void clearMapListeners(){
        maplibreMap.get().removeOnMapClickListener(this);
        maplibreMap.get().removeOnMapLongClickListener(this);
    }

    @Override
    public void draw(
            Canvas canvas,
            boolean clearBackground)    {
        if (mDisplay != null) {
            mDisplay.draw(canvas, clearBackground);
        }
    }

    @Override
    public void draw(
            Canvas canvas,
            float x,
            float y,
            boolean clearBackground)
    {
        if (mDisplay != null) {
            mDisplay.draw(canvas, x, y, clearBackground);
        }
    }

    @Override
    public void draw(
            Canvas canvas,
            float x,
            float y,
            float scale)
    {
        if (mDisplay != null) {
            mDisplay.draw(canvas, x, y, scale);
        }
    }


    @Override
    public void buffer(
            float x,
            float y,
            float scale)
    {
        if (mDisplay != null) {
            mDisplay.buffer(x, y, scale);
        }
    }


    @Override
    public void setViewSize(
            int w,
            int h)
    {
        super.setViewSize(w, h);

        if (mDisplay != null) {
            if(mDisplay.setSize(w, h))
                onExtentChanged((int) mDisplay.getZoomLevel(), mDisplay.getCenter());
        }
    }


    @Override
    public float getZoomLevel()
    {
        if (mDisplay != null) {
            return mDisplay.getZoomLevel();
        }
        return 0;
    }


    /**
     * Set new map extent according zoom level and center
     *
     * @param zoom
     *         A zoom level
     * @param center
     *         A map center coordinates
     */
    @Override
    public void setZoomAndCenter(
            float zoom,
            GeoPoint center, boolean startSecondMaplibre,
            int delay)
    {
        if (mDisplay != null) {
            float newZoom = zoom;
            if (zoom < mDisplay.getMinZoomLevel()) {
                newZoom = mDisplay.getMinZoomLevel();
            } else if (zoom > mDisplay.getMaxZoomLevel()) {
                newZoom = mDisplay.getMaxZoomLevel();
            }

            newZoom = Math.round(newZoom);
            mDisplay.setZoomAndCenter(newZoom, center);
            onExtentChanged((int) newZoom, center);
            zoomSaved = zoom;
            centerSaved = center;
        }

        if (!startSecondMaplibre)
            if (maplibreMap.get()!= null)
                maplibreMap.get().moveCamera(CameraUpdateFactory.
                        newLatLngZoom(latLngPointFromGeoPoint(center), zoom)
                );
    }


    public final GeoPoint getMaplibreCenter() {
        if (maplibreMap.get() != null) {

            LatLng center =  maplibreMap.get().getCameraPosition().target;
            double[] centerPoints = convert4326To3857(center.getLongitude(), center.getLatitude());

            return new GeoPoint(centerPoints[0], centerPoints[1]);
        }
        return null;
    }

    @Override
    public void zoomToExtent(GeoEnvelope envelope) {
        zoomToExtent(envelope, getMaxZoom() ,true);
    }


    public void zoomToLatLng(GeoEnvelope envelope) {
        GeoPoint center = envelope.getCenter();

        double[] lonLatCenter = convert3857To4326(center.getX(),center.getY());
        LatLng lngCenter = new LatLng(lonLatCenter[1], lonLatCenter[0]);

        MapLibreMap map = maplibreMap.get();
        if (map == null) return;
        map.moveCamera(CameraUpdateFactory.newLatLng(lngCenter));
    }

    public void zoomToExtent(GeoEnvelope envelope, float maxZoom, boolean startSecondMaplibre) {
        if (envelope.isInit()) {
            double size = GeoConstants.MERCATOR_MAX * 2;
            double scale = Math.min(envelope.width() / size, envelope.height() / size);
            double zoom = MapUtil.lg(1 / scale);
            if (zoom < getMinZoom())
                zoom = getMinZoom();
            if (zoom > maxZoom)
                zoom = maxZoom;

            setZoomAndCenter((float) zoom, envelope.getCenter(), startSecondMaplibre, 800);
            if (!startSecondMaplibre)
                return;

            // maplibre part
            double[] lonLat1 = convert3857To4326(envelope.getMaxX(), envelope.getMinY());
            double[] lonLat2 = convert3857To4326(envelope.getMinX(), envelope.getMaxY());

            LatLng sw = new LatLng(lonLat1[1], lonLat1[0]);
            LatLng ne = new LatLng(lonLat2[1], lonLat2[0]);
            LatLngBounds bounds = new LatLngBounds.Builder()
                    .include(sw)
                    .include(ne)
                    .build();
            if (maplibreMap != null &&  maplibreMap.get() != null)
                maplibreMap.get().easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, 50), 800);
        }
    }

    @Override
    public GeoPoint getMapCenter()
    {
        if (mDisplay != null) {
            return mDisplay.getCenter();
        }
        return new GeoPoint();
    }

    @Override
    public GeoEnvelope getCurrentBounds() {

        if (maplibreMap.get()!= null){
            LatLngBounds bounds = maplibreMap.get().getProjection().getVisibleRegion().latLngBounds;

            LatLng swPoint = bounds.getSouthWest();
            LatLng nePoint = bounds.getNorthEast();


            double[] sw = convert4326To3857(swPoint.getLongitude(), swPoint.getLatitude());
            double[] ne = convert4326To3857(nePoint.getLongitude(), nePoint.getLatitude());

            return new GeoEnvelope(sw[0], ne[0], sw[1], ne[1]);

        }

        return null;
    }


    public GeoEnvelope getFullScreenBounds()
    {



        if (mDisplay != null) {
            return mDisplay.getScreenBounds();
        }
        return null;
    }

    @Override
    public GeoEnvelope getLimits()
    {
        if (mDisplay != null) {
            return mDisplay.getLimits();
        }
        return null;
    }


    @Override
    public void setLimits(
            GeoEnvelope limits,
            int limitsType)
    {
        if (mDisplay != null) {
            mDisplay.setGeoLimits(limits, limitsType);
        }
    }


    @Override
    public GeoPoint screenToMap(GeoPoint pt)
    {

        if (maplibreMap.get() != null) {
            LatLng latLng = maplibreMap.get().getProjection().fromScreenLocation(new PointF((float) pt.getX(), (float) pt.getY()));
            GeoPoint result = geoPointFromLatLng(latLng);
            return result;
        }
        if (mDisplay != null) {
            return mDisplay.screenToMap(pt);
        }
        return null;
    }


    @Override
    public GeoPoint mapToScreen(GeoPoint pt)
    {
        if (mDisplay != null) {
            return mDisplay.mapToScreen(pt);
        }
        return null;
    }


    @Override
    public float[] mapToScreen(GeoPoint[] geoPoints)
    {
        if (mDisplay != null) {
            return mDisplay.mapToScreen(geoPoints);
        }
        return null;
    }


    @Override
    public GeoEnvelope screenToMap(GeoEnvelope env)
    {
        if (mDisplay != null) {
            return mDisplay.screenToMap(env);
        }
        return null;
    }


    @Override
    public GeoPoint[] screenToMap(float[] points)
    {
        if (mDisplay != null && points != null) {
            return mDisplay.screenToMap(points);
        }
        return new GeoPoint[]{};
    }

    @Override
    public void runDraw(final GISDisplay display)
    {
        try {
            cancelDraw();
        }
        catch (Exception e) {

        }
        onLayerDrawStarted();

        if (null != display && mDisplay != display) {
            mDisplay = display;
        }

        mDisplay.clearLayer();

        mDrawThreadTask = new FutureTask<Void>(
                new Runnable()
                {
                    @Override
                    public void run()
                    {
                        android.os.Process.setThreadPriority(
                                Constants.DEFAULT_DRAW_THREAD_PRIORITY);
                        MapDrawable.super.runDraw(mDisplay);
                    }

                }, null)
        {
            @Override
            protected void done()
            {
                super.done();
                if (!isCancelled()) {
                    onDrawFinished(DRAW_FINISH_ID, 1.0f);
                }
                else {
                    onDrawFinished(MapDrawable.this.getId(), 1.0f);
                }
            }
        };

        new Thread(mDrawThreadTask).start();
    }


    @Override
    public void cancelDraw()
    {
        super.cancelDraw();

        FutureTask task = (FutureTask) mDrawThreadTask;
        if (null != task) {
            task.cancel(true);
        }
    }


    @Override
    public float getMaxZoom()
    {
        float mapMax = super.getMaxZoom();
        if (null != mDisplay) {
            float displayMax = mDisplay.getMaxZoomLevel();
            if (displayMax < mapMax) {
                return displayMax;
            }
        }
        return mapMax;
    }


    @Override
    public float getMinZoom()
    {
        float mapMin = super.getMinZoom();
        if (null != mDisplay) {
            float displayMin = mDisplay.getMinZoomLevel();
            if (displayMin > mapMin) {
                return displayMin;
            }
        }
        return mapMin;
    }


    @Override
    public void setMaxZoom(float maxZoom)
    {
        super.setMaxZoom(maxZoom);
        if (mDisplay != null) {
            mDisplay.setMaxZoomLevel(maxZoom);
        }
    }


    @Override
    public void setMinZoom(float minZoom)
    {
        super.setMinZoom(minZoom);
        if (mDisplay != null) {
            mDisplay.setMinZoomLevel(minZoom);
        }
    }


    public void clearBackground(Canvas canvas)
    {
        if (null != mDisplay) {
            mDisplay.clearBackground(canvas);
        }
    }


    public void setBackground(Bitmap background) {
        mDisplay.setBackground(background);
    }

    public boolean deleteCurrentPoint(){
        if (editingObject != null) {
            editingObject.deleteCurrentPoint();
            mapContext.get().updateGeometryFromMaplibre(editingObject.editingFeature, originalSelectedFeature, editingObject);
        }
        return true;
    }

    /**
     * For multipolygon edit, false when internal selection indices are inconsistent (would no-op / crash on delete vertex).
     * Other geometry types rely on existing menu rules.
     */
    public boolean canDeleteCurrentPointSafe() {
        if (editingObject instanceof MultiPolygonEditClass) {
            return ((MultiPolygonEditClass) editingObject).canDeleteCurrentPoint();
        }
        return true;
    }

    public boolean deleteCurrentLine(){
        if (editingObject != null && editingObject instanceof MultiLineEditClass) {
            ((MultiLineEditClass)editingObject).deleteCurrentLine();
            mapContext.get().updateGeometryFromMaplibre(editingObject.editingFeature, originalSelectedFeature, editingObject);
        }
        return true;
    }

    public boolean addNewPoint(LatLng center){
        if (editingObject != null && editingObject instanceof MultiPointEditClass) {
            ((MultiPointEditClass)editingObject).addNewPoint(center);
            mapContext.get().updateGeometryFromMaplibre(editingObject.editingFeature, originalSelectedFeature, editingObject);
        }
        return true;
    }

    public boolean addNewLine(LatLng center, Projection projection){
        if (editingObject != null && editingObject instanceof MultiLineEditClass) {
            ((MultiLineEditClass)editingObject).addNewLine(center, projection);
            mapContext.get().updateGeometryFromMaplibre(editingObject.editingFeature, originalSelectedFeature, editingObject);

        }
        return true;
    }

    public boolean deleteCurrentHole(){
        if (editingObject != null && editingObject instanceof PolygonEditClass) {
            ((PolygonEditClass)editingObject).deleteCurrentHole();
            mapContext.get().updateGeometryFromMaplibre(editingObject.editingFeature, originalSelectedFeature, editingObject);
        }

        if (editingObject != null && editingObject instanceof MultiPolygonEditClass) {
            ((MultiPolygonEditClass)editingObject).deleteSelectedPolygon();
            mapContext.get().updateGeometryFromMaplibre(editingObject.editingFeature, originalSelectedFeature, editingObject);
        }
        return true;
    }

    public boolean addHole(LatLng center, Projection projection){
        if (editingObject != null && editingObject instanceof PolygonEditClass) {
            ((PolygonEditClass)editingObject).addHole(center, projection);
            mapContext.get().updateGeometryFromMaplibre(editingObject.editingFeature, originalSelectedFeature, editingObject);
        }
        if (editingObject != null && editingObject instanceof MultiPolygonEditClass) {
            ((MultiPolygonEditClass)editingObject).addHole(center, projection);
            mapContext.get().updateGeometryFromMaplibre(editingObject.editingFeature, originalSelectedFeature, editingObject);
        }
        return true;
    }

    public boolean deleteCurrentPolygon(){
        if (editingObject != null && editingObject instanceof MultiPolygonEditClass) {
            ((MultiPolygonEditClass)editingObject).deleteSelectedPolygon();
            mapContext.get().updateGeometryFromMaplibre(editingObject.editingFeature, originalSelectedFeature, editingObject);
        }
        return true;
    }

    public boolean addNewPolygon(LatLng center, Projection projection){
        if (editingObject != null && editingObject instanceof MultiPolygonEditClass) {
            ((MultiPolygonEditClass)editingObject).addNewPolygonAt(center,projection);
            mapContext.get().updateGeometryFromMaplibre(editingObject.editingFeature, originalSelectedFeature, editingObject);
        }
        return true;
    }

    public boolean moveToPoint(LatLng point){
        if (editingObject != null) {
            editingObject.movePointTo(point);
            mapContext.get().updateGeometryFromMaplibre(editingObject.editingFeature, originalSelectedFeature, editingObject);
        }
        return true;
    }

    public void finishCreateNewFeature(
            long newFeatureID,
            VectorLayer layer){

        hideMarker();

        if (editingObject != null) {
            editingObject.finishCreateNewFeature(newFeatureID);

            boolean ruleStyle = false;
            if (layer.getRenderer() instanceof RuleFeatureRenderer) { // feature render
                ruleStyle = true;
            }

            String signatureField =  getLayerSignatureField(layer);
            com.nextgis.maplib.display.Style layerStyle = layer.getDefaultStyleNoExcept();
            String styleField = ((ITextStyle) layerStyle).getField();
            String styleText = ((ITextStyle) layerStyle).getText();

            boolean needSignatures = false;
            if (layer.getRenderer() instanceof RuleFeatureRenderer ||
                    !TextUtils.isEmpty(styleField) || !TextUtils.isEmpty(styleText)) {
                needSignatures = true;
            }
            String commonText = ((ITextStyle) layerStyle).getText();


            // get created feature with fields
            Uri uri = ContentUris.withAppendedId(layer.getContentUri(), newFeatureID);
            uri = uri.buildUpon().fragment("no_sync").build();

            // get it's cursor
            try {
                Cursor cursor = layer.query(uri, null, null, null, null, null);
                if (cursor.moveToFirst()) {
                    Feature newFeatureWithFields = layer.cursorToFeature(cursor);

                    // update new feature properties
                    applyTextAndStyle(
                            layer,
                            newFeatureWithFields,
                            editingObject.editingFeature,
                            layer.getGeometryType(),
                            ruleStyle,
                            needSignatures,
                            signatureField,
                            commonText);
                }
                cursor.close();
            } catch (Exception ex){
                logErr("applyTextAndStyle new feature", ex);
            }

        }
        originalSelectedFeature.setId(newFeatureID);

        // WA for sign by id field for new feature
        if (editingObject != null)
            if (editingObject.originalEditingFeature != null && editingObject.originalEditingFeature.getStringProperty(prop_signature_text) != null &&
                editingObject.originalEditingFeature.getStringProperty(prop_signature_text) .equals("-1"))
                editingObject.originalEditingFeature.addStringProperty(prop_signature_text, String.valueOf(newFeatureID));

        cancelFeatureEdit(false);
    }


    // reload geometry and label props for one feature after attribute edit
    public void reloadFeatureToMaplibre(
            long newFeatureID,
            VectorLayer layer){

        org.maplibre.geojson.Feature targetMlFeature = viewedFeature;
        if (targetMlFeature == null) {
            targetMlFeature = findMaplibreFeatureById(layer.getId(), newFeatureID);
        }
        if (targetMlFeature == null) {
            reloadVectorLayerStyleToMaplibre(layer.getId());
            return;
        }

        viewedFeature = targetMlFeature;

        boolean ruleStyle = layer.getRenderer() instanceof RuleFeatureRenderer;

        String signatureField = getLayerSignatureField(layer);
        com.nextgis.maplib.display.Style layerStyle = layer.getDefaultStyleNoExcept();
        String styleField = ((ITextStyle) layerStyle).getField();
        String styleText = ((ITextStyle) layerStyle).getText();

        boolean needSignatures = layer.getRenderer() instanceof RuleFeatureRenderer
                || !TextUtils.isEmpty(styleField) || !TextUtils.isEmpty(styleText);
        String commonText = ((ITextStyle) layerStyle).getText();

        Uri uri = ContentUris.withAppendedId(layer.getContentUri(), newFeatureID);
        uri = uri.buildUpon().fragment("no_sync").build();

        try {
            Cursor cursor = layer.query(uri, null, null, null, null, null);
            if (cursor.moveToFirst()) {
                Feature newFeatureWithFields = layer.cursorToFeature(cursor);

                applyTextAndStyle(
                        layer,
                        newFeatureWithFields,
                        viewedFeature,
                        layer.getGeometryType(),
                        ruleStyle,
                        needSignatures,
                        signatureField,
                        commonText);

                if (newFeatureWithFields.getGeometry() instanceof GeoPoint) {
                    GeoPoint geoPointGeometry = (GeoPoint) newFeatureWithFields.getGeometry();
                    double[] lonLat = convert3857To4326(geoPointGeometry.getX(), geoPointGeometry.getY());
                    Point point = Point.fromLngLat(lonLat[0], lonLat[1]);
                    viewedFeature = org.maplibre.geojson.Feature.fromGeometry(
                            point, viewedFeature.properties());
                }
            }
            cursor.close();
        } catch (Exception ex) {
            Log.e(TAG, "reloadFeatureToMaplibre: " + layer.getName(), ex);
        }

        List<org.maplibre.geojson.Feature> targetFeatures = sourceFeaturesHashMap.get(layer.getId());
        if (targetFeatures == null) {
            return;
        }

        String targetOrder = String.valueOf(newFeatureID);
        Iterator<org.maplibre.geojson.Feature> it = targetFeatures.iterator();
        while (it.hasNext()) {
            org.maplibre.geojson.Feature f = it.next();
            if (Objects.equals(f.getStringProperty(prop_featureid), targetOrder)) {
                it.remove();
                break;
            }
        }

        targetFeatures.add(viewedFeature);

        GeoJsonSource targetSource = sourceHashMap.get(layer.getPath().toString());
        if (targetSource != null) {
            targetSource.setGeoJson(FeatureCollection.fromFeatures(targetFeatures));
        }

        if (layer.getGeometryType() == GTPolygon || layer.getGeometryType() == GTMultiPolygon) {
            reAssembleSignPoly(maplibreMap.get().getStyle(),
                    targetFeatures,
                    layer.getPath().toString());
        }
    }

    @Nullable
    private org.maplibre.geojson.Feature findMaplibreFeatureById(int layerId, long featureId) {
        List<org.maplibre.geojson.Feature> layerFeatures = sourceFeaturesHashMap.get(layerId);
        if (layerFeatures == null) {
            return null;
        }
        for (org.maplibre.geojson.Feature item : layerFeatures) {
            if (item != null && item.hasProperty(prop_featureid)) {
                long id = item.getNumberProperty(prop_featureid).longValue();
                if (id == featureId) {
                    return item;
                }
            }
        }
        return null;
    }

    public boolean getLayerVisible(int id){
        ILayer targetlayer = getVectorLayersById(this,  id);
        if (targetlayer != null)
            return ((com.nextgis.maplib.map.Layer)targetlayer).isVisible();
        else
            return false;
    }

    public void refreshLayerVisibility(int id, boolean visible){
        if (maplibreMap.get() == null || maplibreMap.get().getStyle() == null)
            return;

        ILayer targetlayer = getVectorLayersById(this,  id);

        if (targetlayer instanceof NGWRasterLayer || targetlayer instanceof  RemoteTMSLayer ||
                targetlayer instanceof  LocalTMSLayer){
            Layer layerRaster = getRasterLayer(id,  maplibreMap.get().getStyle());
            if (layerRaster != null){
                layerRaster.setProperties(visibility(visible ? VISIBLE:NONE));
            }
        }
    }



        public void checkLayerVisibility(int id){
        if (maplibreMap.get() == null || maplibreMap.get().getStyle() == null)
            return;

        ILayer targetlayer = getVectorLayersById(this,  id);
        if (targetlayer == null || !(targetlayer instanceof com.nextgis.maplib.map.Layer)) {
            return;
        }
        boolean isVisible = ((com.nextgis.maplib.map.Layer) targetlayer).isVisible();

        Layer layer = layersHashMap.get(id);
        if (layer != null)
            layer.setProperties(visibility(isVisible ? VISIBLE:NONE));

        Layer layer2 = layersHashMap2.get(id);
        if (layer2 != null)
            layer2.setProperties(visibility(isVisible ? VISIBLE:NONE));

        Layer layerPattern = maplibreMap.get().getStyle().getLayer(
                namePrefix + layer_namepart + id + pattern_namepart);
        if (layerPattern != null)
            layerPattern.setProperties(visibility(isVisible ? VISIBLE : NONE));

        Layer layerMarkerIcon = maplibreMap.get().getStyle().getLayer(
                namePrefix + layer_namepart + id + MARKER_ICON_LAYER_SUFFIX);
        if (layerMarkerIcon != null)
            layerMarkerIcon.setProperties(visibility(isVisible ? VISIBLE : NONE));

        List<Layer> layerLineDashList = layersHashMapLineDash.get(id);
        if (layerLineDashList != null) {
            for (Layer layerLineDash : layerLineDashList) {
                layerLineDash.setProperties(visibility(isVisible ? VISIBLE : NONE));
            }
        }

        Layer layerSymbol = symbolsLayerHashMap.get(id);
        if (layerSymbol != null)
            layerSymbol.setProperties(visibility(isVisible ? VISIBLE:NONE));


        if (isVisible && targetlayer instanceof VectorLayer){
            List<org.maplibre.geojson.Feature> features  = sourceFeaturesHashMap.get(targetlayer.getId());
            if (features == null
            //        || features.isEmpty() // if only one feature - stuc on edit
            ){
                // layer was not uploaded by start - skipped
                // start load layer
                if (features == null)
                    addLayerByID(id);
                else //
                    reloadVectorLayerDataToMaplibre(targetlayer);
            }
        }


        if (targetlayer instanceof NGWRasterLayer || targetlayer instanceof  RemoteTMSLayer ||
                targetlayer instanceof  LocalTMSLayer){
            Layer layerRaster = getRasterLayer(id,  maplibreMap.get().getStyle());
            if (layerRaster != null){
                layerRaster.setProperties(visibility(isVisible ? VISIBLE:NONE));
            }
        }
        if (targetlayer instanceof TrackLayer ){

            Layer trackLayer = maplibreMap.get().getStyle().getLayer("track-line-layer");
            if (trackLayer!= null)
                trackLayer.setProperties(visibility(isVisible ? VISIBLE:NONE));

            Layer trackLayerInProgress = maplibreMap.get().getStyle().getLayer("track-inprogress-layer");
            if (trackLayerInProgress!= null)
                trackLayerInProgress.setProperties(visibility(isVisible ? VISIBLE:NONE));
        }
    }

    public void changePointColor(){
        String colorS = "#FFFFFF";
        if (testColor == 1)
            colorS = "#00FFFF";
        if (testColor == 2)
            colorS = "#0000FF";
        if (testColor == 3)
            colorS = "#FF00FF";

        int id = 5; // test point layer
        List<ILayer> ret = new ArrayList<>();
        LayerGroup.getVectorLayersByType(this, GeoConstants.GTAnyCheck, ret);
        for (ILayer iLayer : ret){
            if (iLayer.getId() == id){
                com.nextgis.maplib.display.Style newStyle = ((VectorLayer)iLayer).getDefaultStyleNoExcept();
                Style maplbrStyle = maplibreMap.get().getStyle();

                String currentNamePrefix = namePrefix;
                org.maplibre.android.style.layers.Layer newLayer = maplbrStyle.getLayer(currentNamePrefix + "layer-" + id);
                newLayer.setProperties(PropertyFactory.circleRadius(22f),
                        PropertyFactory.circleColor(colorS),
                        PropertyFactory.circleStrokeColor("#AA0044"),  //
                        PropertyFactory.circleStrokeWidth(5f),         //
                        PropertyFactory.circleStrokeOpacity(1f));
                break;
            }
        }

        testColor++;
        if (testColor > 3 )
            testColor = 0;
    }



    public void updateLocation(Point point, boolean isStanding, float bearing) {
        MapLibreMap map = maplibreMap.get();
        if (map == null) {
            return;
        }
        syncUserLocationSourceFromStyle(map.getStyle());
        if (locationSource == null) {
            return;
        }
        org.maplibre.geojson.Feature pointFeature = org.maplibre.geojson.Feature.fromGeometry(point);
        pointFeature.addStringProperty("type", String.valueOf(isStanding ? "stand" : "go"));
        if (isStanding) {
            bearing = 0.0f;
        }
        pointFeature.addNumberProperty("bearing", bearing);

        locationSource.setGeoJson(pointFeature);
    }

    public void addPointByWalk(LatLng latLng) {
        if (editingObject != null) {
            editingObject.addNewFlowPoint(latLng, true);
            editingObject.updateEditingPolygonAndVertex();
        }
    }

    public void reloadCurrentTrackToMap(){
        reloadCurrentTrackToMap(null);
    }

    public void reloadCurrentTrackToMap(@Nullable Location leadLocation){
        if (maplibreMap.get() == null)
            return;
        Style style = maplibreMap.get().getStyle();
        if (style != null) {

            List<org.maplibre.geojson.Feature> tracksFeatures = createFeatureListFromCurrentTrack(getContext(), leadLocation);

            //if (tracksFeatures .size() > 0){
                GeoJsonSource tracksLineSource = (GeoJsonSource)style.getSource("track-inprogress-source");
                if (tracksLineSource!=null)
                    tracksLineSource.setGeoJson(FeatureCollection.fromFeatures(tracksFeatures));
            //}
        }
    }

    static public List<org.maplibre.geojson.Feature> createFeatureListFromCurrentTrack(Context context) {
        return createFeatureListFromCurrentTrack(context, null);
    }

    static public List<org.maplibre.geojson.Feature> createFeatureListFromCurrentTrack(
            Context context,
            @Nullable Location leadLocation) {

        List<org.maplibre.geojson.Feature> result = new ArrayList<>();

        List<Point> pointsList = new ArrayList<>();

        Cursor mCursor;
        final Uri mContentUriTracks;

        IGISApplication app = (IGISApplication) context.getApplicationContext();
        String authority = app.getAuthority();

        String[] mProjection = new String[] {TrackLayer.FIELD_ID};
        String   mSelection  = TrackLayer.FIELD_VISIBLE + " = 1 AND (" + TrackLayer.FIELD_END +
                " IS NULL OR " + TrackLayer.FIELD_END +
                " = '')";

        mContentUriTracks = Uri.parse("content://" + authority + "/" + TrackLayer.TABLE_TRACKS);
        mCursor = context.getContentResolver()
                .query(mContentUriTracks, mProjection, mSelection, null, null);

        if (mCursor == null) {
            return result;
        }

        try {
            if (mCursor.getCount() == 0 || !mCursor.moveToFirst()) {
                return result;
            }

            String id = mCursor.getString(0);
            String[] proj = new String[] {TrackLayer.FIELD_LON, TrackLayer.FIELD_LAT};

            Cursor track = null;
            try {
                track = context.getContentResolver()
                        .query(Uri.withAppendedPath(mContentUriTracks, id), proj, null, null, null);
            } catch (Exception ex) {
                logErr("createFeatureListFromTrackLayer query", ex);
                return result;
            }

            if (track == null || track.getCount() == 0 || !track.moveToFirst()) {
                if (track != null)
                    track.close();
                return result;
            }

            try {
                int lonInx = track.getColumnIndex(TrackLayer.FIELD_LON);
                int latInx = track.getColumnIndex(TrackLayer.FIELD_LAT);
                int i = 0;
                do {
                    i++;
                    float x1 = track.getFloat(lonInx);
                    float y1 = track.getFloat(latInx);
                    double[] lonLat = convert3857To4326(x1, y1);
                    Point point1 = Point.fromLngLat(lonLat[0], lonLat[1]);
                    pointsList.add(point1);
                } while (track.moveToNext());
            } finally {
                track.close();
            }

            appendCurrentTrackLeadPoint(pointsList, leadLocation);
            LineString lineString = LineString.fromLngLats(pointsList);
            org.maplibre.geojson.Feature lineFeature = org.maplibre.geojson.Feature.fromGeometry(lineString);
            result.add(lineFeature);

            return result;
        } finally {
            mCursor.close();
        }
    }

    private static void appendCurrentTrackLeadPoint(List<Point> pointsList, @Nullable Location leadLocation) {
        if (leadLocation == null || pointsList.isEmpty()) {
            return;
        }
        Point lead = Point.fromLngLat(leadLocation.getLongitude(), leadLocation.getLatitude());
        Point last = pointsList.get(pointsList.size() - 1);
        if (Math.abs(last.longitude() - lead.longitude()) < 1e-9
                && Math.abs(last.latitude() - lead.latitude()) < 1e-9) {
            return;
        }
        pointsList.add(lead);
    }

    public void reloadTrackListToMap(){
        List<ILayer> tracks = new ArrayList<>();
        LayerGroup.getLayersByType(this, Constants.LAYERTYPE_TRACKS, tracks);
        if (tracks.size() > 0){

            Style style = maplibreMap.get().getStyle();
            if (style != null) {

                TrackLayer trackLayer = (TrackLayer) (tracks.get(0));
                List<org.maplibre.geojson.Feature> tracksFeatures = createFeatureListFromTrackLayer(trackLayer);

                GeoJsonSource tracksLineSource = (GeoJsonSource)style.getSource("track-line-source");
                if (tracksLineSource!=null)
                    tracksLineSource.setGeoJson(FeatureCollection.fromFeatures(tracksFeatures));

                /* Upstream updates track-flag-source here; per CUSTOMIZATIONS §14 «Tracks: no start/end
                   flag icons» we skip flag source update (it does not exist in our style). */
                checkLayerVisibility(trackLayer.getId());
            }


        }
    }


    // draw icon in color
    public Bitmap recolorBitmap(Bitmap src, int color) {
        Bitmap result = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(src, 0, 0, paint);

        return result;
    }


    public void addPressedPoint(LatLng point){
        Point newPoint = Point.fromLngLat(point.getLongitude(), point.getLatitude());
        org.maplibre.geojson.Feature feature = org.maplibre.geojson.Feature.fromGeometry(newPoint);
        feature.addStringProperty("color", colorLightBlue);

        selectedDotSource.setGeoJson(FeatureCollection.fromFeature(feature));
    }

    public void clearPressedPoint(){
        selectedDotSource.setGeoJson(FeatureCollection.fromFeatures(emptyList()));

    }

    // check if need sign for polygon
    // make point and add to source
    public void reAssembleSignPoly(@Nullable  final Style style,
                                   final List<org.maplibre.geojson.Feature> polyFeatures,
                                   String layerPath ){

        if (style == null)
            return;

        List<org.maplibre.geojson.Feature> points =  convertToPointFeatures(polyFeatures);
        if (points.size() == 0)
            return;
        GeoJsonSource vectorTextSource = (GeoJsonSource) style.getSource(layerPath + source_polygon_text);
        if (vectorTextSource == null) {
            vectorTextSource = new GeoJsonSource(layerPath + source_polygon_text, FeatureCollection.fromFeatures(points));
            style.addSource(vectorTextSource);
            sourceHashMap.put(layerPath + source_polygon_text, vectorTextSource);
        }
        else
            vectorTextSource.setGeoJson(FeatureCollection.fromFeatures(points));

    }

    public boolean checkMeasurment(int mode){
        if (editingObject != null && editingObject instanceof  MeasurmentLine ) {
            stoptMeasuring();
            return true;
        }
        return false;

    }

    public void stoptMeasuring(){
        hideMarker();
        hideVertex();
        //selectedEditedSource.setGeoJson(FeatureCollection.fromFeatures(new ArrayList<>()));
        selectedPolySource.setGeoJson(FeatureCollection.fromFeatures(new ArrayList<>()));
        editingObject = null;
        editingFeature = null;

    }

    public void startMeasuring(){
        if (editingObject != null)
            editingObject = null;

        if (originalSelectedFeature != null)
            originalSelectedFeature = null;


        org.maplibre.geojson.Feature feature = null;

        LatLng center = null;
        if (originalSelectedFeature != null && originalSelectedFeature.getGeometry() != null
                && originalSelectedFeature.getGeometry() instanceof  GeoPoint){
            center = latLngPointFromGeoPoint((GeoPoint) originalSelectedFeature.getGeometry());
        } else {
            center = maplibreMap.get().getCameraPosition().target;
        }

        Projection projection = maplibreMap.get().getProjection();
        Point point = Point.fromLngLat(center.getLongitude(), center.getLatitude());



        Point point1Geo = Point.fromLngLat(center.getLongitude(),center.getLatitude());
        List<org.maplibre.geojson.Point> lineList = new ArrayList<>(); //  getNewLinePoints(center, projection);
        //lineList.remove(1);
        lineList.add(point1Geo);

        LineString line = LineString.fromLngLats(lineList);
        feature = org.maplibre.geojson.Feature.fromGeometry(line);
        editingFeature = feature;

        GeoJsonSource choosed = selectedPolySource;

        selectedPolySource.setGeoJson(FeatureCollection.fromFeature(editingFeature));


        // choose layer
        editingObject = MPLFeaturesUtils.createEditObject(GT_MEASURMENT,
                selectedEditedSource,
                editingFeature,
                polygonFeatures,
                choosed,
                vertexSource,
                markerSource,
                "");

        Layer layer = maplibreMap.get().getStyle().getLayer("selected-polygon-fill");

        if (layer != null)
            maplibreMap.get().getStyle().removeLayer(fillPolyEditLayer);


        editingObject.setSelectedVertexIndex(0); // firsr point always selected
        editingObject.extractVertices(editingFeature,  true);

        LatLng selectedPoint = editingObject.getSelectedPoint();
        setMarker(selectedPoint);
        editingObject.updateEditingPolygonAndVertex();
    }

    public void updateMeasurmentCaptions(MLGeometryEditClass editingObject) {
        if (mapContext.get() != null) {
            GeoGeometry geometry = mapContext.get().getGeometryFromMaplibreGeometry(editingObject.editingFeature);

            if (geometry != null && geometry instanceof GeoLineString) {
                double length = ((GeoLineString) (geometry)).getLength();
                mapContext.get().onLengthChanged(length);
            }

            Polygon polygon = Polygon.fromLngLats(((MeasurmentLine)editingObject).getPoints());
            org.maplibre.geojson.Feature featurePoly =  org.maplibre.geojson.Feature.fromGeometry(polygon);
            GeoGeometry geometryPoly = mapContext.get().getGeometryFromMaplibreGeometry(featurePoly);

            if (geometryPoly instanceof GeoPolygon){
                double area = ((GeoPolygon) (geometryPoly)).getArea();
                mapContext.get().onAreaChanged(area);
            }
        }
    }

    public void updateMapBackground(){
        MapLibreMap mapBg = maplibreMap.get();
        if (mapBg != null){
            Style bgStyle = mapBg.getStyle();
            if (bgStyle == null) {
                return;
            }
            SharedPreferences mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(getContext());
            int  colorRes = 0; // black
            String KEY_PREF_MAP_BG = "map_bg"; // copy of
            String namepart = "neutral_";
            switch (mSharedPreferences.getString(KEY_PREF_MAP_BG, KEY_PREF_LIGHT)) {
                    case KEY_PREF_LIGHT:
                        colorRes = R.drawable.bk_tile_light;
                        namepart = "light_";
                        break;
                    case KEY_PREF_DARK:
                        colorRes = R.drawable.bk_tile_dark;
                        namepart = "dark_";
                        break;
                    default:
                        colorRes = R.drawable.bk_tile;
                        namepart = "neutral_";
                        break;
                }

            Bitmap bitmap = BitmapFactory.decodeResource(getContext().getResources(), colorRes);
            bgStyle.addImage("bg-pattern" + namepart, bitmap);

            BackgroundLayer bgLayer = (BackgroundLayer) bgStyle.getLayer("background");
            if (bgLayer == null) {
                bgLayer = new BackgroundLayer("background");
                bgStyle.addLayerAt(bgLayer, 0);
            }

            bgLayer.setProperties(PropertyFactory.backgroundPattern("bg-pattern" + namepart));
        }
    }

    public List<org.maplibre.geojson.Feature> getLayerFeatures(final ILayer  ilayerd){

        List<org.maplibre.geojson.Feature> features =  sourceFeaturesHashMap.get(ilayerd.getId());
        return features;
    }

    public void updateSelectedMarker() {
        if (viewedFeature != null) {
            viewedFeature.addStringProperty("color", colorLightBlue);
            selectedDotSource.setGeoJson(FeatureCollection.fromFeature(viewedFeature));
        }
    }

    // call when mapFragment restored from destroy and WalkEditService running
    // - get Feature from service, re-create editFeature, fill with data  and continue record by walking
    public void startEditByWalkFromRestore(
            final VectorLayer  vectorLayer,
                Feature originalSelectedFeature){
        Log.e("WWALK", "MapDrawable startEditByWalkFromRestore featureid = "
                +  (originalSelectedFeature ==null ? "null" : originalSelectedFeature.getId()) );
        featureToRestore = originalSelectedFeature;
        layerForWalkRestore = vectorLayer;
    }

    // use from collector
    public void loadViewFeature(long selectedFeatureId, int layerid){
        List<org.maplibre.geojson.Feature> layerFeatures = sourceFeaturesHashMap.get(layerid);

        for (org.maplibre.geojson.Feature item : layerFeatures){
            if (item!= null && item.hasProperty(prop_featureid)) {
                long id = item.getNumberProperty(prop_featureid).longValue();
                if (id == selectedFeatureId) {
                    viewedFeature = item;
                    break;
                }
            }
        }
    }

    // use from collector
    public void updateEditedId(long newId){
        if (originalSelectedFeature != null && originalSelectedFeature.getId() == -1)
            originalSelectedFeature.setId(newId);

        if (editingObject!= null && editingObject.editingFeature!= null && editingObject.editingFeature.hasProperty(prop_featureid)
        && editingObject.editingFeature.getStringProperty(prop_featureid).equals("-1"))
            editingObject.editingFeature.addStringProperty(prop_featureid, "" + newId);
    }



    // future update raster prop
//    public void updateRasterLayerProperties(Integer layerid, int alpha, float contrast,
//                                            float brightnessMin, float brightnessMax ){
//        if (layerid!= null && layerid != -1){
//
//            Style style = maplibreMap.get().getStyle();
//            if (style == null)
//                return;
//            org.maplibre.android.style.layers.Layer rasterLayer = style.getLayer(namePrefix + layer_namepart + layerid);
//
//
//            if (rasterLayer!= null && rasterLayer instanceof RasterLayer){
////                float alphaF = alpha / 255.0f; // stored value 0 - 255 // need for maplibre 0 - 1
////                float contrast = (tmsRenderer.getContrast() - 1) ; //stored value 0 - 100 ,  needed -1  +1
////                float brightness = ((tmsRenderer.getBrightness()) / 255.0f) +1 ; // stored value 0  510 , need value 0  >1   1 norm
//                //boolean isGray = tmsRenderer.isForceToGrayScale();
//
//
////                Log.e("BRG", "ON MAP SET min=" + brightnessMin + " max:" + brightnessMax + " cont=" + contrast + " apllha=" +alphaF);
//
////                rasterLayer.setProperties(
//////                        rasterOpacity(alphaF),
//////                        rasterContrast(contrast),
//////                        rasterBrightnessMin(brightnessMin),
//////                        rasterBrightnessMax(brightnessMax)
//////                        rasterSaturation(saturation),
//////                        rasterHueRotate(hueRotate)
////                );
//            }
//        }

//    }


//    public void updateMapBackground(){
//        if (maplibreMap.get()!= null){
//            SharedPreferences mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(getContext());
//            String color = "#000000"; // black
//            String KEY_PREF_MAP_BG               = "map_bg";
//            switch (mSharedPreferences.getString(KEY_PREF_MAP_BG, KEY_PREF_NEUTRAL)) {
//                case KEY_PREF_LIGHT:
//                    color = "#FFFFFF";//backgroundResId = com.nextgis.maplibui.R.drawable.bk_tile_light;
//                    break;
//                case KEY_PREF_DARK:
//                    color = "#000000";//backgroundResId = com.nextgis.maplibui.R.drawable.bk_tile_dark;
//                    break;
//                default:
//                    color = "#888888";
//                    //backgroundResId = com.nextgis.maplibui.R.drawable.bk_tile;
//                    break;
//            }
//            maplibreMap.get().getStyle().getLayer("background").setProperties(
//                    PropertyFactory.backgroundColor(color)
//            );
//        }
//    }




//    public void updatelayerOrder(ILayer from, ILayer to){
//
//
//        List<String> fromLayers =  getLayerMLibreNames(from.getId(), from.getType());
//        List<String> toLayers =  getLayerMLibreNames(to.getId(), to.getType());
//
//        Log.e("MPLREM",  "from: " + fromLayers.get(0) + " to " + toLayers.get(0));
//
//
//    }



// PMTILES example
//                        // raster PMTILES
//                        String pmTilesPath = "pmtiles://file:///storage/emulated/0/Android/data/com.nextgis.mobile.debug/files/map/flowers.pmtiles";
//
//                        RasterSource source = new RasterSource("raster-pmtiles-source", pmTilesPath);
//                        style.addSource(source);
//
//                        RasterLayer layer = new RasterLayer("raster-pmtiles-layer", "raster-pmtiles-source");
//                        style.addLayer(layer);
//                        // END   PMTILES
//
//
//                        // PMTILES
//                        String pmTilesPath2 = "pmtiles://file:///storage/emulated/0/Android/data/com.nextgis.mobile.debug/files/map/cb_2018_us_zcta510_500k.pmtiles";
//
//                        VectorSource source2 = new VectorSource("pmtiles-source", pmTilesPath2);
//                        style.addSource(source2);
//
//                        LineLayer layer2 = new LineLayer("pmtiles-layer", "pmtiles-source")
//                                .withSourceLayer("zcta")
//                                .withProperties(
//                                PropertyFactory.lineColor("#0000ff"),
//                                PropertyFactory.lineWidth(2f)
//                        );
//                        style.addLayer(layer2);
//
//                        FillLayer fillLayer = new FillLayer("poly-pmtiles-layer", "pmtiles-source")
//                                .withSourceLayer("zcta")
//                                .withProperties(
//                                        PropertyFactory.fillColor("#FF0000"),
//                                        PropertyFactory.fillOpacity(0.5f));
//                        style.addLayer(fillLayer);
//                        // END   PMTILES
}

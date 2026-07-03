/*
 * Project:  NextGIS Mobile
 * Purpose:  Mobile GIS for Android.
 * Author:   Dmitry Baryshnikov (aka Bishop), bishop.dev@gmail.com
 * Author:   NikitaFeodonit, nfeodonit@yandex.com
 * Author:   Stanislav Petriakov, becomeglory@gmail.com
 * *****************************************************************************
 * Copyright (c) 2012-2015. NextGIS, info@nextgis.com
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

import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;
import com.nextgis.maplib.api.ILayer;
import com.nextgis.maplib.api.ILayerView;
import com.nextgis.maplib.api.IRenderer;
import com.nextgis.maplib.datasource.GeoEnvelope;
import com.nextgis.maplib.datasource.GeoPoint;
import com.nextgis.maplib.display.GISDisplay;
import com.hypertrack.hyperlog.HyperLog;
import com.nextgis.maplib.util.Constants;
import com.nextgis.maplib.util.FileUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.nextgis.maplib.util.Constants.*;
import static com.nextgis.maplib.util.SettingsConstants.KEY_PREF_MAP;
import static com.nextgis.maplib.util.SettingsConstants.KEY_PREF_MAP_PATH;


public class LayerGroup
        extends Layer
{
    protected static final String JSON_COLLECTOR_DISTRICT_KEY = "collector_district";

    protected final LinkedHashMap<Integer, ILayer> mLayers = new LinkedHashMap<>();
    protected LayerFactory mLayerFactory;
    protected int          mLayerDrawIndex;
    protected GISDisplay   mDisplay;
    protected OnAllLayersAddedListener mOnAllLayersAddedListener;
    protected String       mCollectorDistrict;


    public interface OnAllLayersAddedListener
    {
        void onAllLayersAdded(LinkedHashMap<Integer, ILayer> layers);
    }

    /**
     * Collector project district from {@code resmeta.items.district} (stored on import).
     */
    public String getCollectorDistrict() {
        return mCollectorDistrict;
    }

    public void setCollectorDistrict(String collectorDistrict) {
        mCollectorDistrict = TextUtils.isEmpty(collectorDistrict) ? null : collectorDistrict.trim();
        HyperLog.d(Constants.TAG, NGWVectorLayer.LOG_DISTRICT_FILTER + " group=\"" + getName()
                + "\" setCollectorDistrict="
                + (mCollectorDistrict != null ? mCollectorDistrict : "<cleared>"));
    }

    /**
     * Walks parent {@link LayerGroup} chain for the nearest non-empty {@link #getCollectorDistrict()}.
     */
    public static String findCollectorDistrict(ILayer layer) {
        ILayer current = layer;
        while (current != null) {
            if (current instanceof LayerGroup) {
                String district = ((LayerGroup) current).getCollectorDistrict();
                if (!TextUtils.isEmpty(district)) {
                    return district;
                }
            }
            if (current instanceof Table) {
                current = ((Table) current).getParent();
            } else {
                break;
            }
        }
        return null;
    }

    /**
     * Clears collector project district metadata on this group and all nested groups.
     *
     * @return {@code true} if any group metadata changed.
     */
    public boolean clearCollectorDistrictRecursive() {
        boolean changed = false;
        if (!TextUtils.isEmpty(mCollectorDistrict)) {
            setCollectorDistrict(null);
            changed = true;
        }
        synchronized (this) {
            for (ILayer layer : mLayers.values()) {
                if (layer instanceof LayerGroup) {
                    changed |= ((LayerGroup) layer).clearCollectorDistrictRecursive();
                }
            }
        }
        return changed;
    }


    public LayerGroup(
            final Context context,
            final File path,
            final LayerFactory layerFactory)
    {
        super(context, path);

        mLayerFactory = layerFactory;

        mLayerDrawIndex = 0;

        mLayerType = LAYERTYPE_GROUP;
    }


    /**
     * Get layer by identificator
     *
     * @param id
     *         Layer identificator
     *
     * @return Layer or null
     */
    public ILayer getLayerById(int id)
    {
        if (mId == id) {
            return this;
        }

        if (mLayers.get(id)!= null)
            return mLayers.get(id);

        for (ILayer layer : mLayers.values()) {
            if (layer.getId() == id) {
                return layer;
            }
        }
        return null;
    }


    /**
     * search layer by it human readabler name
     * @param name Name to search
     * @return ILayer or null
     */
    public ILayer getLayerByName(String name)
    {
        if (mName.equals(name)) {
            return this;
        }

        for (ILayer layer : mLayers.values()) {
            if (layer.getName().equals(name)) {
                return layer;
            }
        }
        return null;
    }

    /**
     * Search layer by it folder name
     * @param name Name to search
     * @return ILayer or null
     */
    public ILayer getLayerByPathName(String name){
        if (getPath().getName().equals(name)) {
            return this;
        }

        for (ILayer layer : mLayers.values()) {
            if (layer.getPath().getName().equals(name)) {
                return layer;
            }
        }
        return null;
    }


    /**
     * Get a list of specified type layers
     *
     * @param layerGroup
     *         to inspect for layers
     * @param types
     *         A layer type
     * @param layerList
     *         A list to fill with find layers
     */
    public static void getLayersByType(
            LayerGroup layerGroup,
            int types,
            List<ILayer> layerList)
    {
        for (int i = 0; i < layerGroup.getLayerCount(); i++) {
            ILayer layer = layerGroup.getLayer(i);

            if (0 != (types & layer.getType())) {
                layerList.add(layer);
            }

            if (layer instanceof LayerGroup) {
                getLayersByType((LayerGroup) layer, types, layerList);
            }
        }
    }

    /**
     * Depth-first search for a non-group layer whose display name matches (trimmed equals).
     */
    public static ILayer findLayerByDisplayNameRecursive(LayerGroup layerGroup, String name) {
        if (layerGroup == null || name == null) {
            return null;
        }
        final String want = name.trim();
        if (want.isEmpty()) {
            return null;
        }
        for (int i = 0; i < layerGroup.getLayerCount(); i++) {
            ILayer layer = layerGroup.getLayer(i);
            if (layer instanceof LayerGroup) {
                ILayer found = findLayerByDisplayNameRecursive((LayerGroup) layer, name);
                if (found != null) {
                    return found;
                }
            } else {
                String n = layer.getName();
                if (n != null && want.equals(n.trim())) {
                    return layer;
                }
            }
        }
        return null;
    }

    /**
     * Depth-first search for an NGW vector layer with the given server resource id and account name.
     */
    public static NGWVectorLayer findNgwVectorLayerByRemoteIdRecursive(
            LayerGroup layerGroup,
            long remoteId,
            String accountName) {
        if (layerGroup == null || accountName == null) {
            return null;
        }
        for (int i = 0; i < layerGroup.getLayerCount(); i++) {
            ILayer layer = layerGroup.getLayer(i);
            if (layer instanceof LayerGroup) {
                NGWVectorLayer found = findNgwVectorLayerByRemoteIdRecursive(
                        (LayerGroup) layer, remoteId, accountName);
                if (found != null) {
                    return found;
                }
            } else if (layer instanceof NGWVectorLayer) {
                NGWVectorLayer nv = (NGWVectorLayer) layer;
                if (nv.getRemoteId() == remoteId && accountName.equals(nv.getAccountName())) {
                    return nv;
                }
            }
        }
        return null;
    }

    /**
     * Insert index for a collector NGW layer among siblings (same account, remote id listed in
     * {@code collectorRemoteIdsInProjectOrder}). Ranks are inverted so visual list order matches the
     * collector project: internal layer index 0 is the bottom row in the UI drawer, the last internal index
     * is the top row (adapter uses reversed indexing).
     */
    public static int computeCollectorOrderedInsertIndex(
            LayerGroup group,
            String accountName,
            long[] collectorRemoteIdsInProjectOrder,
            int targetIndexInProjectOrder) {
        if (group == null || accountName == null || collectorRemoteIdsInProjectOrder == null
                || targetIndexInProjectOrder < 0) {
            return group != null ? group.getLayerCount() : 0;
        }
        final int L = collectorRemoteIdsInProjectOrder.length;
        Map<Long, Integer> internalRank = new HashMap<>();
        for (int i = 0; i < L; i++) {
            internalRank.put(collectorRemoteIdsInProjectOrder[i], L - 1 - i);
        }
        int targetRank = L - 1 - targetIndexInProjectOrder;
        int count = group.getLayerCount();
        for (int gi = 0; gi < count; gi++) {
            ILayer child = group.getLayer(gi);
            if (!(child instanceof NGWVectorLayer)) {
                continue;
            }
            NGWVectorLayer nv = (NGWVectorLayer) child;
            if (!accountName.equals(nv.getAccountName())) {
                continue;
            }
            Integer rank = internalRank.get(nv.getRemoteId());
            if (rank == null) {
                continue;
            }
            if (rank >= targetRank) {
                return gi;
            }
        }
        return count;
    }

    public static ILayer getVectorLayersById(
            LayerGroup layerGroup,
            int id){
        return layerGroup.getLayerById(id);
    }

    public static void getVectorLayersByType(
            LayerGroup layerGroup,
            int types,
            List<ILayer> layerList)
    {
        for (int i = 0; i < layerGroup.getLayerCount(); i++) {
            ILayer layer = layerGroup.getLayer(i);

            if (layer instanceof VectorLayer ) {
                VectorLayer vectorLayer = (VectorLayer) layer;
                if (0 != (types & 1 << vectorLayer.getGeometryType())) {
                    layerList.add(0, layer);
                }
            }

            if (layer instanceof LayerGroup) {
                getVectorLayersByType((LayerGroup) layer, types, layerList);
            }
        }
    }

    public static void getTMSLayersByType(
            LayerGroup layerGroup,
            int types,
            List<ILayer> layerList)
    {
        for (int i = 0; i < layerGroup.getLayerCount(); i++) {
            ILayer layer = layerGroup.getLayer(i);

            if ( layer instanceof TMSLayer) {
                TMSLayer tmsLayer = (TMSLayer) layer;
                layerList.add(0, layer);
            }

            if (layer instanceof LayerGroup) {
                getTMSLayersByType((LayerGroup) layer, types, layerList);
            }
        }
    }



    public static void getAllLayers(
            LayerGroup layerGroup,
            List<ILayer> layerList)
    {
        for (int i = 0; i < layerGroup.getLayerCount(); i++) {
            ILayer layer = layerGroup.getLayer(i);

            if ( ! (layer instanceof LayerGroup)) {
//                Log.e("MPLREM",  "get layer: " + layer.getId() + " _ "+ layer.getName());
                layerList.add(0, layer);
            }

            if (layer instanceof LayerGroup) {
                getAllLayers((LayerGroup) layer,layerList);
            }
        }
    }

    public List<ILayer> getLayers(){
        return new ArrayList<>(mLayers.values());
        //return mLayers.values().;
    }


    /**
     * Create existed layer from path and add it to the map
     *
     * @param layer
     *         A layer object
     */
    public void addLayer(ILayer layer)
    {
        if (layer != null) {
            mLayers.put(layer.getId(), layer);
            layer.setParent(this);
            onLayerAdded(layer);
        }
    }

    public void insertLayer(
            int index,
            ILayer layer) {
        if (layer != null) {
            putAtIndex(mLayers, index, layer.getId(), layer);
            //mLayers.add(index, layer);
            layer.setParent(this);
            onLayerAdded(layer);
        }
    }

    public void moveLayer(
            int newPosition,
            ILayer layer) {



        if (layer != null) {
            synchronized (this) {

                moveLayer(mLayers, layer, newPosition );



//                mLayers.remove(layer);
//                mLayers.add(newPosition, layer);
            }
            onLayersReordered();
        }
    }


//    public void replaceAllLayers(ArrayList<ILayer> newList)
//    {
//        ILayer trackLayer = null;
//        synchronized (this) {
//            for (ILayer layer : mLayers.values()){
//                if (layer instanceof  TrackLayer){
//                    trackLayer = layer;
//                    break;
//                }
//            }
//
//            mLayers.clear();
//
//            if (trackLayer != null)
//                newList.add(trackLayer);
//
//            for (ILayer iLayer : newList)
//                mLayers.put(iLayer.getId(), iLayer);
//
//        }
//
//
////        //save changes to file
////        SharedPreferences mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(getContext());
////
////        File defaultPath = getContext().getExternalFilesDir(KEY_PREF_MAP);
//////        if (defaultPath == null) {
//////            defaultPath = new File(getFilesDir(), KEY_PREF_MAP);
//////        }
////
////        String KEY_PREF_MAP_NAME             = "map_name";
////        String mapPath = mSharedPreferences.getString(KEY_PREF_MAP_PATH, defaultPath.getPath());
////        String mapName = mSharedPreferences.getString(KEY_PREF_MAP_NAME, "default");
////
////        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(getContext());
////
////
////        JSONObject jsonObject = new JSONObject(FileUtil.readFromFile(getFileName()));
////        final JSONArray jsonArray = jsonObject.getJSONArray(JSON_LAYERS_KEY);
////
////        for (int i = 0; i < jsonArray.length(); i++) {
////            JSONObject jsonLayer = jsonArray.getJSONObject(i);
////            String sPath = jsonLayer.getString(JSON_PATH_KEY);
////            File inFile = new File(getPath(), sPath);
////            if (inFile.exists()) {
////                ILayer layer = mLayerFactory.createLayer(mContext, inFile);
////                if (null != layer && layer.load()) {
////                    addLayer(layer);
////                }
////            }
////        }
////
////        * */
//
//
//    }

    public int removeLayer(ILayer layer)
    {
        synchronized (this) {
            int result = mLayers.size() - 1;

            if (layer != null) {


                result = removeLayerAndGetIndex(mLayers, layer);

                //result = mLayers.indexOf(layer);

                onLayerDeleted(layer.getId());
            }
            return result;
        }
    }


    @Override
    public void runDraw(GISDisplay display)
    {
        if (null != display && mDisplay != display) {
            mDisplay = display;
        }

        List<ILayer> layersCopy;
        synchronized (this) {
            if (mLayers.size() == 0) {
                return;
            }
            layersCopy = new ArrayList<>(mLayers.values());
        }

            for (ILayer layer : layersCopy) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }

                if (layer instanceof LayerGroup) {
                    LayerGroup layerGroup = (LayerGroup) layer;
                    layerGroup.runDraw(mDisplay);

                } else {

                    if (layer.isValid() && layer instanceof ILayerView) {

                        ILayerView layerView = (ILayerView) layer;
                        if (layerView.isVisible() && layer instanceof IRenderer &&
                                mDisplay.getZoomLevel() <= layerView.getMaxZoom() &&
                                mDisplay.getZoomLevel() >= layerView.getMinZoom()) {
                            // Log.d(Constants.TAG, "Layer Draw Index: " + mLayerDrawIndex);

                            IRenderer renderer = (IRenderer) layer;
                            renderer.runDraw(mDisplay);

                        }
                    }
                }
            }
    }


    @Override
    public void cancelDraw()
    {
        for (ILayer layer : mLayers.values()) {
            if (layer instanceof IRenderer) {
                IRenderer renderer = (IRenderer) layer;
                renderer.cancelDraw();
            }
        }
    }


    @Override
    public boolean isVisible()
    {
        for (ILayer layer : mLayers.values()) {
            if (layer instanceof ILayerView) {
                ILayerView layerView = (ILayerView) layer;
                if (layerView.isVisible()) {
                    return true;
                }
            }
        }
        return false;
    }


    @Override
    public void setVisible(boolean visible)
    {
        for (ILayer layer : mLayers.values()) {
            if (layer instanceof ILayerView) {
                ILayerView layerView = (ILayerView) layer;
                layerView.setVisible(visible);
            }
        }
    }


    public int getVisibleTopLayerId()
    {
       for (int i = mLayers.size() - 1; i >= 0; i--) {
            ILayer layer = mLayers.get(i);
            if (layer instanceof LayerGroup) {
                LayerGroup layerGroup = (LayerGroup) layer;
                int visibleTopLayerId = layerGroup.getVisibleTopLayerId();
                if (Constants.NOT_FOUND != visibleTopLayerId) {
                    return visibleTopLayerId;
                }

            } else {
                if (layer.isValid() && layer instanceof ILayerView) {
                    ILayerView layerView = (ILayerView) layer;
                    if (layerView.isVisible()) {
                        return layer.getId();
                    }
                }
            }
        }

        return Constants.NOT_FOUND;
    }


    public int getVisibleLayerCount()
    {
        int visibleLayerCount = 0;

        for (int i = mLayers.size() - 1; i >= 0; i--) {
            ILayer layer = mLayers.get(i);
            if (layer instanceof LayerGroup) {
                LayerGroup layerGroup = (LayerGroup) layer;
                visibleLayerCount += layerGroup.getVisibleLayerCount();

            } else {
                if (layer.isValid() && layer instanceof ILayerView) {
                    ILayerView layerView = (ILayerView) layer;
                    if (layerView.isVisible()) {
                        ++visibleLayerCount;
                    }
                }
            }
        }

        return visibleLayerCount;
    }


    @Override
    public boolean delete(boolean keepTrack)
    {
        for (ILayer layer : mLayers.values()) {
            layer.setParent(null);
            layer.delete(keepTrack);
        }
        return super.delete(keepTrack);
    }


    @Override
    public JSONObject toJSON()
            throws JSONException
    {
        JSONObject rootConfig = super.toJSON();

        JSONArray jsonArray = new JSONArray();
        rootConfig.put(JSON_LAYERS_KEY, jsonArray);
        for (ILayer layer : mLayers.values()) {
            JSONObject layerObject = new JSONObject();
            layerObject.put(JSON_PATH_KEY, layer.getPath().getName());
            jsonArray.put(layerObject);
        }
        if (!TextUtils.isEmpty(mCollectorDistrict)) {
            rootConfig.put(JSON_COLLECTOR_DISTRICT_KEY, mCollectorDistrict);
        }
        return rootConfig;
    }


    public void clearLayers()
    {
        for (ILayer layer : mLayers.values()) {
            if (layer instanceof LayerGroup) {
                ((LayerGroup) layer).clearLayers();
            }
        }
        mLayers.clear();
    }


    @Override
    public void fromJSON(JSONObject jsonObject)
            throws JSONException
    {
        super.fromJSON(jsonObject);

        if (jsonObject.has(JSON_COLLECTOR_DISTRICT_KEY) && !jsonObject.isNull(JSON_COLLECTOR_DISTRICT_KEY)) {
            setCollectorDistrict(jsonObject.optString(JSON_COLLECTOR_DISTRICT_KEY, null));
        } else {
            mCollectorDistrict = null;
        }

        clearLayers();

        final JSONArray jsonArray = jsonObject.getJSONArray(JSON_LAYERS_KEY);
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONObject jsonLayer = jsonArray.getJSONObject(i);
            String sPath = jsonLayer.getString(JSON_PATH_KEY);
            File inFile = new File(getPath(), sPath);
            if (inFile.exists()) {
                ILayer layer = mLayerFactory.createLayer(mContext, inFile);
                if (null != layer && layer.load()) {
                    addLayer(layer);
                }
            }
        }

        if (mOnAllLayersAddedListener != null)
            mOnAllLayersAddedListener.onAllLayersAdded(mLayers);
    }


    @Override
    public GeoEnvelope getExtents()
    {
        return mExtents;
    }


    @Override
    public void onUpgrade(final SQLiteDatabase sqLiteDatabase, final int oldVersion, final int newVersion) {
        setOnAllLayersAddedListener(new OnAllLayersAddedListener() {
            @Override
            public void onAllLayersAdded(LinkedHashMap<Integer, ILayer> layers) {
                for (ILayer layer : mLayers.values()) {
                        layer.onUpgrade(sqLiteDatabase, oldVersion, newVersion);
                        setOnAllLayersAddedListener(null);
                }
            }
        });
    }


    protected void setOnAllLayersAddedListener(OnAllLayersAddedListener listener) {
        mOnAllLayersAddedListener = listener;
    }


    protected void onLayerAdded(ILayer layer)
    {
        if (mParent != null && mParent instanceof LayerGroup) {
            LayerGroup group = (LayerGroup) mParent;
            group.onLayerAdded(layer);
        }
    }


    protected void onLayerChanged(ILayer layer)
    {
        if (mParent != null && mParent instanceof LayerGroup) {
            LayerGroup group = (LayerGroup) mParent;
//            Log.e("MPL_LAYERCHANGED", "layer group onLayerChanged call" + layer.getId());
            group.onLayerChanged(layer);

        }
    }

    protected void onLayerVisibleChanged(ILayer layer)
    {
        if (mParent != null && mParent instanceof LayerGroup) {
            LayerGroup group = (LayerGroup) mParent;
//            Log.e("MPL_LAYERCHANGED", "layer group onLayerChanged call" + layer.getId());
            group.onLayerVisibleChanged(layer);

        }
    }

    protected void onLayerChangedFeatureId(ILayer layer, long oldFeatureId, long newFeatureId, int layerId)
    {
        if (mParent != null && mParent instanceof LayerGroup) {
            LayerGroup group = (LayerGroup) mParent;
//            Log.e("onLayerChangedFeatureId", "layer group onLayerChanged call" + layer.getId());
            group.onLayerChangedFeatureId(layer, oldFeatureId, newFeatureId,  layerId);

        }
    }

    protected void onLayerDeleted(int id)
    {
        if (mLayers.get(id) != null) {
            mLayers.remove(id);
            return;
        }

        for (ILayer layer : mLayers.values()) {

            if (layer.getId() == id) {
                mLayers.remove(layer);
                break;
            }
        }

        if (mParent != null && mParent instanceof LayerGroup) {
            LayerGroup group = (LayerGroup) mParent;
            group.onLayerDeleted(id);
        }
    }


    protected void onExtentChanged(
            float zoom,
            GeoPoint center)
    {
        if (mParent != null && mParent instanceof LayerGroup) {
            LayerGroup group = (LayerGroup) mParent;
            group.onExtentChanged(zoom, center);
        }
    }


    protected void onLayersReordered()
    {
        if (mParent != null && mParent instanceof LayerGroup) {
            LayerGroup group = (LayerGroup) mParent;
            group.onLayersReordered();
        }
    }


    public int getLayerCount()
    {
        return mLayers.size();
    }


    public ILayer getLayer(int index)
    {
        return getLayerByindex(mLayers,  index);

        /// return mLayers.get(index);
    }


    public LayerFactory getLayerFactory()
    {
        return mLayerFactory;
    }


    @Override
    public boolean save()
    {
        synchronized (this) {
            for (ILayer layer : mLayers.values()) {
                layer.save();
            }
        }
        return super.save();
    }

    /**
     * Create the layer folder of specified name
     * @param layerName The name of folder
     * @return Path to the layer folder
     */
    public File createLayerStorage(String layerName)
    {
        if(TextUtils.isEmpty(layerName))
            return createLayerStorage();
        return new File(mPath, layerName);
    }

    /**
     * Create the layer folder of random name
     * @return Path to the layer folder
     */
    public File createLayerStorage()
    {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
        String layerDir = LAYER_PREFIX + sdf.format(new Date()) + getLayerCount();
        final Random r = new Random();
        layerDir += r.nextInt(99);

        Log.d(Constants.TAG, "createLayerStorage: " + layerDir);
        return new File(mPath, layerDir);
    }


    @Override
    public void setViewSize(
            int w,
            int h)
    {
        super.setViewSize(w, h);

        for (ILayer layer : mLayers.values()) {
            if (layer instanceof ILayerView) {
                ILayerView lv = (ILayerView) layer;
                lv.setViewSize(w, h);
            }
        }
    }


    public boolean isChanges()
    {
        for (ILayer layer : mLayers.values()) {
            if (layer instanceof LayerGroup) {
                LayerGroup layerGroup = (LayerGroup) layer;
                if (layerGroup.isChanges()) {
                    return true;
                }
            } else if (layer instanceof VectorLayer) {
                VectorLayer vectorLayer = (VectorLayer) layer;
                if (vectorLayer.isChanges()) {
                    return true;
                }
            }
        }
        return false;
    }


    public boolean haveFeaturesNotSyncFlag()
    {
        for (ILayer layer : mLayers.values()) {
            if (layer instanceof LayerGroup) {
                LayerGroup layerGroup = (LayerGroup) layer;
                if (layerGroup.haveFeaturesNotSyncFlag()) {
                    return true;
                }
            } else if (layer instanceof VectorLayer) {
                VectorLayer vectorLayer = (VectorLayer) layer;
                if (vectorLayer.haveFeaturesNotSyncFlag()) {
                    return true;
                }
            }
        }
        return false;
    }


    public static int indexOfLayer(LinkedHashMap<Integer, ILayer> map, ILayer layer) {
        int index = 0;
        for (ILayer value : new ArrayList<>(map.values())) {
            if (value == layer) { // or equals()
                return index;
            }
            index++;
        }
        return -1;
    }

    /** Iteration index of a direct child in {@link #getLayer(int)} order (0 = first / bottom of stack). */
    public int getChildLayerIndex(ILayer layer) {
        return indexOfLayer(mLayers, layer);
    }

    public static ILayer getLayerByindex(LinkedHashMap<Integer, ILayer> map, int index) {
        if (index < 0) {
            return null;
        }
        ArrayList<ILayer> snapshot = new ArrayList<>(map.values());
        if (index < snapshot.size()) {
            return snapshot.get(index);
        }
        return null;
    }

    public static Integer removeLayer(LinkedHashMap<Integer, ILayer> map, ILayer layer) {
        Iterator<Map.Entry<Integer, ILayer>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, ILayer> e = it.next();
            if (e.getValue() == layer) {
                Integer key = e.getKey();
                it.remove();
                return key;
            }
        }
        return null;
    }

    public static int removeLayerAndGetIndex(
            LinkedHashMap<Integer, ILayer> map,
            ILayer layer
    ) {
        int index = 0;
        Iterator<Map.Entry<Integer, ILayer>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, ILayer> e = it.next();
            if (e.getValue() == layer) {
                it.remove();
                return index;
            }
            index++;
        }
        return -1;
    }

    public static void putAtIndex(
            LinkedHashMap<Integer, ILayer> map,
            int index,
            Integer newKey,
            ILayer newLayer
    ) {
        LinkedHashMap<Integer, ILayer> tmp = new LinkedHashMap<>();

        int i = 0;
        boolean inserted = false;

        for (Map.Entry<Integer, ILayer> e : map.entrySet()) {
            if (i == index) {
                tmp.put(newKey, newLayer);
                inserted = true;
            }
            tmp.put(e.getKey(), e.getValue());
            i++;
        }

        if (!inserted) {
            tmp.put(newKey, newLayer); // в конец
        }

        map.clear();
        map.putAll(tmp);
    }

    public static void moveLayer(
            LinkedHashMap<Integer, ILayer> map,
            ILayer layer,
            int newIndex
    ) {
        Integer key = null;

        for (Map.Entry<Integer, ILayer> e : map.entrySet()) {
            if (e.getValue() == layer) {
                key = e.getKey();
                break;
            }
        }
        if (key == null) return;

        map.remove(key);
        putAtIndex(map, newIndex, key, layer);
    }






}

/*
 * Project:  NextGIS Mobile
 * Purpose:  NextGIS Web collector_project resource (leaf in resource tree).
 * *****************************************************************************
 * Copyright (c) 2016-2025 NextGIS, info@nextgis.com
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

package com.nextgis.maplib.datasource.ngw;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;

import com.hypertrack.hyperlog.HyperLog;
import com.nextgis.maplib.util.Constants;
import com.nextgis.maplib.util.HttpResponse;
import com.nextgis.maplib.util.NGWUtil;
import com.nextgis.maplib.util.NetworkUtil;
import com.nextgis.maplib.util.NgwResmetaUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CollectorResource extends Resource {

    private static final String TAG = "CollectorResource";

    private final List<LayerWithStyles> mLayers = new ArrayList<>();
    private final Set<Long> mResolvedLayerRemoteIds = new HashSet<>();
    private String mProjectDistrict;
    private boolean mSnapshotComplete = true;
    private String mSnapshotError;

    public CollectorResource(JSONObject json, Connection connection) {
        super(json, connection);
        try {
            applyEnvelopeMetadata(json);
            JSONObject collectorProject = extractCollectorProject(json);
            if (collectorProject != null) {
                parseCollectorProject(collectorProject);
            } else {
                fetchCollectorProjectDetails();
            }
        } catch (JSONException e) {
            markSnapshotIncomplete("collector_project JSON: " + e.getMessage());
            Log.e(TAG, "parse collector_project", e);
            HyperLog.exception(Constants.TAG, e);
        }
        HyperLog.d(Constants.TAG, "CollectorResource \"" + getName() + "\" remoteId=" + mRemoteId
                + " vector-like layers=" + mLayers.size());
    }

    protected CollectorResource(Parcel in) {
        super(in);
        mRemoteId = in.readLong();
        int count = in.readInt();
        for (int i = 0; i < count; i++) {
            LayerWithStyles layer = in.readParcelable(LayerWithStyles.class.getClassLoader());
            if (layer != null) {
                mLayers.add(layer);
            }
        }
        mProjectDistrict = in.readString();
        mSnapshotComplete = in.readByte() != 0;
        mSnapshotError = in.readString();
    }

    public static final Parcelable.Creator<CollectorResource> CREATOR =
            new Parcelable.Creator<CollectorResource>() {
                @Override
                public CollectorResource createFromParcel(Parcel in) {
                    return new CollectorResource(in);
                }

                @Override
                public CollectorResource[] newArray(int size) {
                    return new CollectorResource[size];
                }
            };

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        super.writeToParcel(parcel, flags);
        parcel.writeLong(mRemoteId);
        parcel.writeInt(mLayers.size());
        for (LayerWithStyles layer : mLayers) {
            parcel.writeParcelable(layer, flags);
        }
        parcel.writeString(mProjectDistrict);
        parcel.writeByte((byte) (mSnapshotComplete ? 1 : 0));
        parcel.writeString(mSnapshotError);
    }

    /**
     * Vector / PostGIS layers from the collector project (for import).
     */
    public List<LayerWithStyles> getLayers() {
        return Collections.unmodifiableList(mLayers);
    }

    /**
     * Collector project district from NGW {@code resmeta.items.district} (Latin, e.g. {@code vologda}).
     */
    public String getProjectDistrict() {
        return mProjectDistrict;
    }

    /** True only when the complete project tree and every referenced vector resource were resolved. */
    public boolean isSnapshotComplete() {
        return mSnapshotComplete;
    }

    public String getSnapshotError() {
        return mSnapshotError;
    }

    private void markSnapshotIncomplete(String error) {
        mSnapshotComplete = false;
        if (TextUtils.isEmpty(mSnapshotError)) {
            mSnapshotError = error;
        }
        HyperLog.w(Constants.TAG, "CollectorResource: incomplete snapshot remoteId="
                + mRemoteId + " error=" + error);
    }

    private void applyEnvelopeMetadata(JSONObject envelope) {
        String district = NgwResmetaUtil.getResmetaItemString(envelope, "district");
        if (!TextUtils.isEmpty(district)) {
            mProjectDistrict = district;
            HyperLog.d(Constants.TAG, "CollectorResource \"" + getName() + "\" resmeta district="
                    + mProjectDistrict);
        }
    }

    private static JSONObject extractCollectorProject(JSONObject envelope) {
        JSONObject res = envelope.optJSONObject("resource");
        if (res != null && res.has("collector_project")) {
            return res.optJSONObject("collector_project");
        }
        return envelope.optJSONObject("collector_project");
    }

    private void fetchCollectorProjectDetails() {
        try {
            String url = NGWUtil.getResourceUrl(mConnection.getURL(), mRemoteId);
            HttpResponse response =
                    NetworkUtil.get(url, mConnection.getLogin(), mConnection.getPassword(), false);
            if (!response.isOk()) {
                markSnapshotIncomplete("project HTTP " + response.getResponseCode());
                HyperLog.w(Constants.TAG, "CollectorResource: full fetch HTTP " + response.getResponseCode());
                return;
            }
            JSONObject full = new JSONObject(response.getResponseBody());
            applyEnvelopeMetadata(full);
            JSONObject cp = extractCollectorProject(full);
            if (cp != null) {
                parseCollectorProject(cp);
            } else {
                markSnapshotIncomplete("full JSON has no collector_project");
                HyperLog.w(Constants.TAG, "CollectorResource: full JSON has no collector_project");
            }
        } catch (IOException | JSONException e) {
            markSnapshotIncomplete("project fetch: " + e.getMessage());
            Log.e(TAG, "fetch collector_project", e);
            HyperLog.exception(Constants.TAG, e);
        }
    }

    private void parseCollectorProject(JSONObject collectorProject) throws JSONException {
        mLayers.clear();
        mResolvedLayerRemoteIds.clear();
        JSONObject rootItem = collectorProject.optJSONObject("root_item");
        if (rootItem == null) {
            markSnapshotIncomplete("collector_project has no root_item");
            HyperLog.w(Constants.TAG, "CollectorResource: collector_project has no root_item");
            return;
        }
        JSONArray childrenArray = rootItem.optJSONArray("children");
        if (childrenArray == null) {
            markSnapshotIncomplete("root_item has no children array");
            HyperLog.w(Constants.TAG, "CollectorResource: root_item has no children");
            return;
        }
        walkCollectorItems(childrenArray);
    }

    /**
     * Items tab in NGW is a tree: groups nest vector layers. Older payloads may list layers only at
     * the first level — both patterns are handled.
     */
    private void walkCollectorItems(JSONArray childrenArray) throws JSONException {
        for (int i = 0; i < childrenArray.length(); i++) {
            JSONObject item = childrenArray.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String itemType = item.optString("item_type", "");
            if ("group".equalsIgnoreCase(itemType) && item.has("children")) {
                walkCollectorItems(item.getJSONArray("children"));
                continue;
            }
            if (item.has("children") && !item.has("resource")) {
                walkCollectorItems(item.getJSONArray("children"));
                continue;
            }
            tryConsumeCollectorItem(item);
        }
    }

    private void tryConsumeCollectorItem(JSONObject item) {
        try {
            final boolean collectorEditable = parseCollectorItemEditable(item);
            if (item.has("resource")) {
                JSONObject wrapped = wrapResourceIfNeeded(item);
                tryAddLayerFromWrapped(wrapped, collectorEditable);
                return;
            }
            long rid = extractLayerResourceId(item);
            if (rid > 0) {
                tryFetchAndAddLayer(rid, collectorEditable);
            }
        } catch (JSONException e) {
            markSnapshotIncomplete("collector item JSON: " + e.getMessage());
            Log.e(TAG, "tryConsumeCollectorItem", e);
            HyperLog.exception(Constants.TAG, e);
        }
    }

    /**
     * NGW collector item «Редактируемый» — not the vector layer description {@code is_editable}.
     */
    private boolean parseCollectorItemEditable(JSONObject item) {
        final String[] keys = {"editable", "layer_editable", "is_editable"};
        String resolvedKey = null;
        boolean value = true;
        for (String k : keys) {
            if (item.has(k) && !item.isNull(k)) {
                value = item.optBoolean(k, true);
                resolvedKey = k;
                break;
            }
        }
        long layerRid = extractLayerResourceId(item);
        if (item.has("resource")) {
            JSONObject res = item.optJSONObject("resource");
            if (res != null) {
                long v = readIdKey(res, "id");
                if (v > 0) {
                    layerRid = v;
                }
            }
        }
        if (Constants.DEBUG_MODE && resolvedKey == null) {
            java.util.Iterator<String> it = item.keys();
            StringBuilder keyList = new StringBuilder();
            while (it.hasNext()) {
                if (keyList.length() > 0) {
                    keyList.append(',');
                }
                keyList.append(it.next());
            }
            HyperLog.d(Constants.TAG, "CollectorResource item editable parse layerRid=" + layerRid
                    + " no editable key; itemKeys=" + keyList);
        } else {
            HyperLog.d(Constants.TAG, "CollectorResource item editable parse layerRid=" + layerRid
                    + " key=" + resolvedKey + " value=" + value);
        }
        return value;
    }

    private long extractLayerResourceId(JSONObject item) {
        String[] keys = {"resource_id", "layer_id", "vector_layer_id", "feature_layer_id", "layer_resource_id"};
        for (String k : keys) {
            long v = readIdKey(item, k);
            if (v > 0) {
                return v;
            }
        }
        return readIdKey(item, "id");
    }

    private static long readIdKey(JSONObject item, String k) {
        if (!item.has(k) || item.isNull(k)) {
            return -1;
        }
        long v = item.optLong(k, -1);
        if (v > 0) {
            return v;
        }
        String s = item.optString(k, "");
        if (s.isEmpty()) {
            return -1;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void tryFetchAndAddLayer(long resourceId, boolean collectorEditable) {
        try {
            if (mResolvedLayerRemoteIds.contains(resourceId)) {
                return;
            }
            JSONObject envelope = fetchResourceEnvelope(resourceId);
            if (envelope == null) {
                markSnapshotIncomplete("resource " + resourceId + " response has no resource");
                return;
            }
            tryAddLayerFromWrapped(envelope, collectorEditable);
        } catch (JSONException | IOException e) {
            markSnapshotIncomplete("resource " + resourceId + ": " + e.getMessage());
            Log.e(TAG, "tryFetchAndAddLayer", e);
            HyperLog.exception(Constants.TAG, e);
        }
    }

    private JSONObject fetchResourceEnvelope(long resourceId)
            throws JSONException, IOException
    {
        String url = NGWUtil.getResourceUrl(mConnection.getURL(), resourceId);
        HttpResponse response =
                NetworkUtil.get(url, mConnection.getLogin(), mConnection.getPassword(), false);
        if (!response.isOk()) {
            markSnapshotIncomplete("resource " + resourceId + " HTTP "
                    + response.getResponseCode());
            HyperLog.w(Constants.TAG, "CollectorResource: GET resource " + resourceId + " -> HTTP "
                    + response.getResponseCode());
            return null;
        }
        JSONObject root = new JSONObject(response.getResponseBody());
        if (root.has("resource")) {
            return root;
        }
        if (root.has("cls") && root.has("id")) {
            JSONObject out = new JSONObject();
            out.put("resource", root);
            return out;
        }
        markSnapshotIncomplete("resource " + resourceId + " has unexpected JSON envelope");
        return null;
    }

    private void tryAddLayerFromWrapped(JSONObject envelope, boolean collectorEditable) throws JSONException {
        JSONObject wrapped = wrapResourceIfNeeded(envelope);
        LayerWithStyles layer = new LayerWithStyles(wrapped, mConnection);
        int t = layer.getType();
        if (t == Connection.NGWResourceTypePostgisLayer
                && mConnection.getNgwVersionMajor() < Constants.NGW_v3) {
            return;
        }
        if (t != Connection.NGWResourceTypeVectorLayer && t != Connection.NGWResourceTypePostgisLayer) {
            return;
        }
        long rid = layer.getRemoteId();
        if (mResolvedLayerRemoteIds.contains(rid)) {
            return;
        }
        layer.setCollectorEditable(collectorEditable);
        layer.fillExtent();
        layer.fillStyles();
        ensureDescriptionFromServer(layer);
        mLayers.add(layer);
        mResolvedLayerRemoteIds.add(rid);
    }

    /**
     * Listing/collector JSON often omits {@code resource.description}; full GET has the pasted
     * config.json text for mobile import.
     */
    private void ensureDescriptionFromServer(LayerWithStyles layer) {
        if (layer.getDescription() != null && !layer.getDescription().trim().isEmpty()) {
            return;
        }
        try {
            JSONObject env = fetchResourceEnvelope(layer.getRemoteId());
            if (env == null) {
                markSnapshotIncomplete("description resource " + layer.getRemoteId() + " unavailable");
                return;
            }
            JSONObject res = env.getJSONObject("resource");
            if (res.has("description") && !res.isNull("description")) {
                String d = res.optString("description", "");
                if (!d.trim().isEmpty()) {
                    layer.setDescription(d.trim());
                }
            }
        } catch (JSONException | IOException e) {
            markSnapshotIncomplete("description resource " + layer.getRemoteId() + ": "
                    + e.getMessage());
            Log.e(TAG, "ensureDescriptionFromServer", e);
            HyperLog.exception(Constants.TAG, e);
        }
    }

    private static JSONObject wrapResourceIfNeeded(JSONObject o) throws JSONException {
        if (o.has("resource")) {
            return o;
        }
        JSONObject envelope = new JSONObject();
        envelope.put("resource", o);
        return envelope;
    }

    @Override
    public int getChildrenCount() {
        return 0;
    }

    @Override
    public INGWResource getChild(int i) {
        return null;
    }
}

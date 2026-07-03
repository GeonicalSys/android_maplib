/*
 * Project:  NextGIS Mobile
 * Purpose:  Persistent origin metadata for NGW-backed local layers.
 */

package com.nextgis.maplib.map;

import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Collector architecture foundation.
 *
 * Describes who owns a local layer. Some values are intentionally written before the active
 * Collector composition-sync implementation exists. Do not remove them as "unused": future sync
 * will use this metadata to distinguish project-managed layers from manual NGW additions, to sync
 * forms independently, and to switch rendering to local vector tiles for selected heavy layers.
 */
public class LayerOriginMetadata {
    public static final int SCHEMA_VERSION = 1;

    public static final String TYPE_COLLECTOR_PROJECT = "collector_project";
    public static final String TYPE_MANUAL_NGW = "manual_ngw";

    public static final String RENDER_MODE_CLASSIC = "classic";
    public static final String RENDER_MODE_LOCAL_VECTOR_TILES = "local_vector_tiles";

    private static final String JSON_SCHEMA_VERSION = "schema_version";
    private static final String JSON_TYPE = "type";
    private static final String JSON_PROJECT_UID = "project_uid";
    private static final String JSON_MANAGED_BY_PROJECT = "managed_by_project";
    private static final String JSON_COLLECTOR_ORDER = "collector_order";
    private static final String JSON_FORM_ID = "form_id";
    private static final String JSON_RENDER_MODE = "render_mode";

    private String mType;
    private String mProjectUid;
    private boolean mManagedByProject;
    private int mCollectorOrder = -1;
    private long mFormId;
    private String mRenderMode = RENDER_MODE_CLASSIC;

    public static LayerOriginMetadata collectorLayer(
            String projectUid,
            int collectorOrder,
            long formId) {
        LayerOriginMetadata metadata = new LayerOriginMetadata();
        metadata.mType = TYPE_COLLECTOR_PROJECT;
        metadata.mProjectUid = projectUid;
        metadata.mManagedByProject = true;
        metadata.mCollectorOrder = collectorOrder;
        metadata.mFormId = Math.max(0L, formId);
        metadata.mRenderMode = RENDER_MODE_CLASSIC;
        return metadata;
    }

    public static LayerOriginMetadata manualNgwLayer(long formId) {
        LayerOriginMetadata metadata = new LayerOriginMetadata();
        metadata.mType = TYPE_MANUAL_NGW;
        metadata.mManagedByProject = false;
        metadata.mFormId = Math.max(0L, formId);
        metadata.mRenderMode = RENDER_MODE_CLASSIC;
        return metadata;
    }

    public static LayerOriginMetadata fromJSON(JSONObject json) {
        if (json == null) {
            return null;
        }
        LayerOriginMetadata metadata = new LayerOriginMetadata();
        metadata.mType = json.optString(JSON_TYPE, null);
        metadata.mProjectUid = json.optString(JSON_PROJECT_UID, null);
        metadata.mManagedByProject = json.optBoolean(JSON_MANAGED_BY_PROJECT, false);
        metadata.mCollectorOrder = json.optInt(JSON_COLLECTOR_ORDER, -1);
        metadata.mFormId = json.optLong(JSON_FORM_ID, 0L);
        metadata.mRenderMode = json.optString(JSON_RENDER_MODE, RENDER_MODE_CLASSIC);
        if (TextUtils.isEmpty(metadata.mRenderMode)) {
            metadata.mRenderMode = RENDER_MODE_CLASSIC;
        }
        return TextUtils.isEmpty(metadata.mType) ? null : metadata;
    }

    public JSONObject toJSON() throws JSONException {
        JSONObject json = new JSONObject();
        json.put(JSON_SCHEMA_VERSION, SCHEMA_VERSION);
        json.put(JSON_TYPE, mType);
        if (!TextUtils.isEmpty(mProjectUid)) {
            json.put(JSON_PROJECT_UID, mProjectUid);
        }
        json.put(JSON_MANAGED_BY_PROJECT, mManagedByProject);
        if (mCollectorOrder >= 0) {
            json.put(JSON_COLLECTOR_ORDER, mCollectorOrder);
        }
        if (mFormId > 0L) {
            json.put(JSON_FORM_ID, mFormId);
        }
        json.put(JSON_RENDER_MODE, TextUtils.isEmpty(mRenderMode)
                ? RENDER_MODE_CLASSIC : mRenderMode);
        return json;
    }

    public String getType() {
        return mType;
    }

    public String getProjectUid() {
        return mProjectUid;
    }

    public boolean isManagedByProject() {
        return mManagedByProject;
    }

    public int getCollectorOrder() {
        return mCollectorOrder;
    }

    public long getFormId() {
        return mFormId;
    }

    public void setFormId(long formId) {
        mFormId = Math.max(0L, formId);
    }

    public String getRenderMode() {
        return TextUtils.isEmpty(mRenderMode) ? RENDER_MODE_CLASSIC : mRenderMode;
    }

    public void setRenderMode(String renderMode) {
        mRenderMode = TextUtils.isEmpty(renderMode) ? RENDER_MODE_CLASSIC : renderMode;
    }
}

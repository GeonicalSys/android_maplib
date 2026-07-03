/*
 * Project:  NextGIS Mobile
 * Purpose:  Persistent mobile metadata for an imported NGW Collector project.
 */

package com.nextgis.maplib.map;

import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Collector architecture foundation.
 *
 * Metadata stored on the local {@link LayerGroup} that represents one imported Collector project.
 * Some fields are not used by the current sync code yet; keep them because future Collector
 * composition sync, form sync, and multi-project switching need a stable project identity without
 * re-importing heavy local layer data.
 */
public class CollectorProjectMetadata {
    public static final int SCHEMA_VERSION = 1;

    private static final String JSON_SCHEMA_VERSION = "schema_version";
    private static final String JSON_PROJECT_UID = "project_uid";
    private static final String JSON_ACCOUNT = "account";
    private static final String JSON_PROJECT_REMOTE_ID = "project_remote_id";
    private static final String JSON_NAME = "name";
    private static final String JSON_DISTRICT = "district";
    private static final String JSON_COMPOSITION_SYNC = "composition_sync";
    private static final String JSON_IMPORTED_AT = "imported_at";

    private String mProjectUid;
    private String mAccountName;
    private long mProjectRemoteId = -1L;
    private String mName;
    private String mDistrict;
    private boolean mCompositionSync = true;
    private long mImportedAt;

    public static String buildProjectUid(String accountName, long projectRemoteId) {
        if (TextUtils.isEmpty(accountName) || projectRemoteId <= 0L) {
            return null;
        }
        return "collector:" + accountName + ":" + projectRemoteId;
    }

    public static CollectorProjectMetadata create(
            String accountName,
            long projectRemoteId,
            String name,
            String district) {
        CollectorProjectMetadata metadata = new CollectorProjectMetadata();
        metadata.mAccountName = accountName;
        metadata.mProjectRemoteId = projectRemoteId;
        metadata.mProjectUid = buildProjectUid(accountName, projectRemoteId);
        metadata.mName = name;
        metadata.mDistrict = TextUtils.isEmpty(district) ? null : district.trim();
        metadata.mImportedAt = System.currentTimeMillis();
        return metadata;
    }

    public static CollectorProjectMetadata fromJSON(JSONObject json) {
        if (json == null) {
            return null;
        }
        CollectorProjectMetadata metadata = new CollectorProjectMetadata();
        metadata.mProjectUid = json.optString(JSON_PROJECT_UID, null);
        metadata.mAccountName = json.optString(JSON_ACCOUNT, null);
        metadata.mProjectRemoteId = json.optLong(JSON_PROJECT_REMOTE_ID, -1L);
        metadata.mName = json.optString(JSON_NAME, null);
        metadata.mDistrict = json.optString(JSON_DISTRICT, null);
        metadata.mCompositionSync = json.optBoolean(JSON_COMPOSITION_SYNC, true);
        metadata.mImportedAt = json.optLong(JSON_IMPORTED_AT, 0L);
        if (TextUtils.isEmpty(metadata.mProjectUid)) {
            metadata.mProjectUid = buildProjectUid(metadata.mAccountName, metadata.mProjectRemoteId);
        }
        if (TextUtils.isEmpty(metadata.mDistrict)) {
            metadata.mDistrict = null;
        }
        return metadata.isValid() ? metadata : null;
    }

    public JSONObject toJSON() throws JSONException {
        JSONObject json = new JSONObject();
        json.put(JSON_SCHEMA_VERSION, SCHEMA_VERSION);
        json.put(JSON_PROJECT_UID, mProjectUid);
        json.put(JSON_ACCOUNT, mAccountName);
        json.put(JSON_PROJECT_REMOTE_ID, mProjectRemoteId);
        if (!TextUtils.isEmpty(mName)) {
            json.put(JSON_NAME, mName);
        }
        if (!TextUtils.isEmpty(mDistrict)) {
            json.put(JSON_DISTRICT, mDistrict);
        }
        json.put(JSON_COMPOSITION_SYNC, mCompositionSync);
        if (mImportedAt > 0L) {
            json.put(JSON_IMPORTED_AT, mImportedAt);
        }
        return json;
    }

    public boolean isValid() {
        return !TextUtils.isEmpty(mProjectUid)
                && !TextUtils.isEmpty(mAccountName)
                && mProjectRemoteId > 0L;
    }

    public String getProjectUid() {
        return mProjectUid;
    }

    public String getAccountName() {
        return mAccountName;
    }

    public long getProjectRemoteId() {
        return mProjectRemoteId;
    }

    public String getName() {
        return mName;
    }

    public String getDistrict() {
        return mDistrict;
    }

    public boolean isCompositionSyncEnabled() {
        return mCompositionSync;
    }

    public void setCompositionSyncEnabled(boolean compositionSync) {
        mCompositionSync = compositionSync;
    }
}

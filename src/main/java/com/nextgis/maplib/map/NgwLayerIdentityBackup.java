/*
 * Project:  NextGIS Mobile
 * Purpose:  Durable NGW layer identity backup.
 */

package com.nextgis.maplib.map;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Small, Android-independent codec for the last fully initialized NGW vector-layer identity.
 */
final class NgwLayerIdentityBackup {
    private static final int SCHEMA_VERSION = 1;

    private static final String JSON_SCHEMA_VERSION = "schema_version";
    private static final String JSON_ACCOUNT = "account";
    private static final String JSON_REMOTE_ID = "remote_id";
    private static final String JSON_SYNC_TYPE = "sync_type";
    private static final String JSON_NGW_LAYER_TYPE = "ngw_layer_type";
    private static final String JSON_SYNC_DIRECTION = "sync_direction";
    private static final String JSON_TRACKED = "tracked";
    private static final String JSON_LAYER_ORIGIN = "layer_origin";

    private NgwLayerIdentityBackup() {
    }

    static String encode(
            String accountName,
            long remoteId,
            int syncType,
            int ngwLayerType,
            int syncDirection,
            boolean tracked,
            LayerOriginMetadata origin)
            throws JSONException {
        if (isEmpty(accountName) || remoteId <= 0L) {
            return null;
        }
        JSONObject json = new JSONObject();
        json.put(JSON_SCHEMA_VERSION, SCHEMA_VERSION);
        json.put(JSON_ACCOUNT, accountName);
        json.put(JSON_REMOTE_ID, remoteId);
        json.put(JSON_SYNC_TYPE, syncType);
        json.put(JSON_NGW_LAYER_TYPE, ngwLayerType);
        json.put(JSON_SYNC_DIRECTION, syncDirection);
        json.put(JSON_TRACKED, tracked);
        if (origin != null) {
            json.put(JSON_LAYER_ORIGIN, origin.toJSON());
        }
        return json.toString();
    }

    static Snapshot decode(String raw) {
        if (isEmpty(raw)) {
            return null;
        }
        try {
            JSONObject json = new JSONObject(raw);
            if (json.optInt(JSON_SCHEMA_VERSION, -1) != SCHEMA_VERSION) {
                return null;
            }
            String accountName = json.optString(JSON_ACCOUNT, "");
            long remoteId = json.optLong(JSON_REMOTE_ID, 0L);
            if (isEmpty(accountName) || remoteId <= 0L) {
                return null;
            }
            return new Snapshot(
                    accountName,
                    remoteId,
                    json.optInt(JSON_SYNC_TYPE, 0),
                    json.optInt(JSON_NGW_LAYER_TYPE, 0),
                    json.optInt(JSON_SYNC_DIRECTION, 3),
                    json.optBoolean(JSON_TRACKED, false),
                    json.optJSONObject(JSON_LAYER_ORIGIN));
        } catch (JSONException e) {
            return null;
        }
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    static final class Snapshot {
        final String accountName;
        final long remoteId;
        final int syncType;
        final int ngwLayerType;
        final int syncDirection;
        final boolean tracked;
        final JSONObject layerOrigin;

        Snapshot(
                String accountName,
                long remoteId,
                int syncType,
                int ngwLayerType,
                int syncDirection,
                boolean tracked,
                JSONObject layerOrigin) {
            this.accountName = accountName;
            this.remoteId = remoteId;
            this.syncType = syncType;
            this.ngwLayerType = ngwLayerType;
            this.syncDirection = syncDirection;
            this.tracked = tracked;
            this.layerOrigin = layerOrigin;
        }
    }
}

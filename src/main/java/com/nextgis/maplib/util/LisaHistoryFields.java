package com.nextgis.maplib.util;

import com.nextgis.maplib.datasource.Field;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Verified, target-bound ownership of the LISA PostGIS history fields. */
public final class LisaHistoryFields {
    // Exact dynamic table keys enrolled in Desktop history_migration.TABLES. At least
    // one correctly typed history field is also required; old layers may lack newer fields.
    private static final Set<String> TABLES = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList("field_lines", "field_points", "field_polygons", "find_rum",
                    "line_tech", "low_storage", "plancut", "point_tech", "poly_tech",
                    "storage", "tracks", "type_roads", "zarub")));
    private static final Map<String, String> REMOTE_TYPES;
    private static final Map<String, Integer> LOCAL_TYPES;

    static {
        Map<String, String> remote = new LinkedHashMap<>();
        Map<String, Integer> local = new LinkedHashMap<>();
        for (String name : new String[]{"created_at", "updated_at", "deleted_at"}) {
            remote.put(name, "DATETIME");
            local.put(name, GeoConstants.FTDateTime);
        }
        for (String name : new String[]{"created_by", "updated_by", "deleted_by"}) {
            remote.put(name, "STRING");
            local.put(name, GeoConstants.FTString);
        }
        remote.put("deletion_operation_id", "BIGINT");
        local.put("deletion_operation_id", GeoConstants.FTLong);
        REMOTE_TYPES = Collections.unmodifiableMap(remote);
        LOCAL_TYPES = Collections.unmodifiableMap(local);
    }

    private final String accountName;
    private final String serverUrl;
    private final long resourceId;
    private final int resourceSrs;
    private final String fieldTypesJson;

    private LisaHistoryFields(String accountName, String serverUrl, long resourceId,
            int resourceSrs, String fieldTypesJson) {
        this.accountName = accountName;
        this.serverUrl = serverUrl;
        this.resourceId = resourceId;
        this.resourceSrs = resourceSrs;
        this.fieldTypesJson = fieldTypesJson;
    }

    /** Returns no proof for missing, ambiguous, unrelated or malformed metadata. */
    public static LisaHistoryFields fromResourceMetadata(JSONObject meta, String accountName,
            String serverUrl, long resourceId) {
        if (meta == null || accountName == null || accountName.isEmpty()
                || serverUrl == null || serverUrl.isEmpty() || resourceId <= 0) {
            return null;
        }
        JSONObject resource = meta.optJSONObject("resource");
        JSONObject postgis = meta.optJSONObject("postgis_layer");
        JSONObject featureLayer = meta.optJSONObject("feature_layer");
        if (resource == null || postgis == null || featureLayer == null
                || !"postgis_layer".equals(resource.opt("cls"))
                || !"dynamic".equals(postgis.opt("schema"))
                || !(postgis.opt("table") instanceof String)
                || !TABLES.contains(postgis.opt("table"))) {
            return null;
        }
        Object actualId = resource.opt("id");
        if (!(actualId instanceof Integer || actualId instanceof Long)
                || ((Number) actualId).longValue() != resourceId) {
            return null;
        }
        JSONArray fields = featureLayer.optJSONArray("fields");
        if (fields == null) {
            return null;
        }
        Set<String> seen = new HashSet<>();
        Map<String, String> fieldTypes = new LinkedHashMap<>();
        int verified = 0;
        for (int i = 0; i < fields.length(); i++) {
            JSONObject field = fields.optJSONObject(i);
            if (field == null || !(field.opt("keyname") instanceof String)
                    || !(field.opt("datatype") instanceof String)) {
                return null;
            }
            String name = (String) field.opt("keyname");
            if (name.isEmpty() || !seen.add(name)) {
                return null;
            }
            fieldTypes.put(name, (String) field.opt("datatype"));
            String expectedType = REMOTE_TYPES.get(name);
            if (expectedType != null) {
                if (!expectedType.equals(field.opt("datatype"))) {
                    return null;
                }
                verified++;
            }
        }
        return verified > 0
                ? new LisaHistoryFields(accountName, serverUrl, resourceId,
                        NgwFeatureData.resourceSrs(meta), new JSONObject(fieldTypes).toString()) : null;
    }

    public int resourceSrs() { return resourceSrs; }
    public String fieldTypesJson() { return fieldTypesJson; }

    public boolean matchesTarget(String accountName, String serverUrl, long resourceId) {
        return this.accountName.equals(accountName) && this.serverUrl.equals(serverUrl)
                && this.resourceId == resourceId;
    }

    public static boolean isServerManaged(String fieldName) {
        return REMOTE_TYPES.containsKey(fieldName);
    }

    /**
     * Eligibility for fetching authoritative metadata; local fields are never proof.
     * Missing history fields are allowed so old offline edits can be sent before layer reload.
     */
    public static boolean hasCompatibleLocalSchema(Collection<Field> fields) {
        if (fields == null) {
            return false;
        }
        Set<String> seen = new HashSet<>();
        int verified = 0;
        for (Field field : fields) {
            if (field == null || field.getName() == null || field.getName().isEmpty()
                    || !seen.add(field.getName())) {
                return false;
            }
            Integer expectedType = LOCAL_TYPES.get(field.getName());
            if (expectedType != null) {
                if (field.getType() != expectedType) {
                    return false;
                }
                verified++;
            }
        }
        return verified > 0;
    }

    /** Copies a payload so a frozen upload receipt is never modified by comparison. */
    public static JSONObject withoutServerManagedFields(JSONObject payload) throws JSONException {
        if (payload == null) {
            return null;
        }
        JSONObject copy = new JSONObject(payload.toString());
        JSONObject fields = copy.optJSONObject(NGWUtil.NGWKEY_FIELDS);
        if (fields != null) {
            for (String name : REMOTE_TYPES.keySet()) {
                fields.remove(name);
            }
        }
        return copy;
    }
}

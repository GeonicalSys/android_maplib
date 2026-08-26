/*
 * Project:  NextGIS Mobile
 * Purpose:  Compare local vector layer schema with NGW resource metadata (sync safety).
 */

package com.nextgis.maplib.util;

import com.nextgis.maplib.datasource.Field;
import com.nextgis.maplib.datasource.GeoGeometryFactory;
import com.nextgis.maplib.map.VectorLayer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.nextgis.maplib.util.LayerUtil.unwrapQuotation;

/**
 * Fetches NGW resource description and checks whether {@link VectorLayer} table/fields still match
 * the server {@code feature_layer} + geometry block (vector_layer / postgis_layer).
 */
public final class NGWLayerSchemaCompat {

    /** Result of the three-way server metadata / serialized config / SQLite comparison. */
    public static final class SchemaComparison {
        private final boolean mComparable;
        private final boolean mResourceClassMatches;
        private final boolean mGeometryMatches;
        private final boolean mSerializedFieldsMatch;
        private final boolean mSqliteFieldsMatch;
        private final String mActualResourceClass;
        private final List<Field> mRemoteFields;

        private SchemaComparison(
                boolean comparable,
                boolean resourceClassMatches,
                boolean geometryMatches,
                boolean serializedFieldsMatch,
                boolean sqliteFieldsMatch,
                String actualResourceClass,
                List<Field> remoteFields) {
            mComparable = comparable;
            mResourceClassMatches = resourceClassMatches;
            mGeometryMatches = geometryMatches;
            mSerializedFieldsMatch = serializedFieldsMatch;
            mSqliteFieldsMatch = sqliteFieldsMatch;
            mActualResourceClass = actualResourceClass;
            mRemoteFields = remoteFields;
        }

        /** A parse/network ambiguity is fail-open and must not trigger destructive recovery. */
        public boolean isComparable() { return mComparable; }
        public boolean isResourceClassMatch() { return mResourceClassMatches; }
        public boolean isGeometryMatch() { return mGeometryMatches; }
        public boolean isSerializedFieldsMatch() { return mSerializedFieldsMatch; }
        public boolean isSqliteFieldsMatch() { return mSqliteFieldsMatch; }
        public String getActualResourceClass() { return mActualResourceClass; }
        public List<Field> getRemoteFields() { return mRemoteFields; }

        public boolean needsRebuild() {
            // vector_layer and postgis_layer share the same local SQLite representation. A wrong
            // serialized class is metadata drift, not a reason to download the table again.
            return mComparable && (!mGeometryMatches || !mSqliteFieldsMatch);
        }

        public boolean isMetadataOnlyMismatch() {
            return mComparable && mResourceClassMatches && mGeometryMatches
                    && mSqliteFieldsMatch && !mSerializedFieldsMatch;
        }

        public boolean isFullyCompatible() {
            return !mComparable || (mResourceClassMatches && mGeometryMatches
                    && mSerializedFieldsMatch && mSqliteFieldsMatch);
        }
    }

    private NGWLayerSchemaCompat() {
    }

    /**
     * GET resource JSON from NGW. Returns null if HTTP not OK or body is not JSON.
     */
    public static JSONObject fetchResourceMetaJson(
            String resourceUrl,
            String login,
            String password)
    {
        HttpResponse response;
        try {
            response = NetworkUtil.get(resourceUrl, login, password, false);
        } catch (IOException e) {
            return null;
        }
        if (!response.isOk()) {
            return null;
        }
        try {
            return new JSONObject(response.getResponseBody());
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * @return true if local layer matches server meta (no rebuild needed); false if schemas differ
     *         or meta cannot be parsed for comparison; true on partial parse errors (fail open).
     */
    public static boolean localSchemaMatchesServerMeta(
            VectorLayer local,
            JSONObject geoJSONObject,
            int ngwVersionMajor,
            String vectorLayerClsKey)
    {
        return compareLocalWithServerMeta(
                local, geoJSONObject, ngwVersionMajor, vectorLayerClsKey, "")
                .isFullyCompatible();
    }

    /**
     * Compare all three schema representations. Only server resource metadata is authoritative;
     * description/config fields are treated as repairable metadata when SQLite already agrees
     * with the server.
     *
     * @param expectedResourceCls expected NGW resource class persisted by the local layer, or null
     *                            to retain legacy class-agnostic behaviour
     */
    public static SchemaComparison compareLocalWithServerMeta(
            VectorLayer local,
            JSONObject geoJSONObject,
            int ngwVersionMajor,
            String vectorLayerClsKey,
            String expectedResourceCls) {
        if (local == null || geoJSONObject == null) {
            return unparsed();
        }
        try {
            if (!geoJSONObject.has("feature_layer")) {
                return unparsed();
            }
            JSONObject featureLayerJSONObject = geoJSONObject.getJSONObject("feature_layer");
            if (!featureLayerJSONObject.has(NGWUtil.NGWKEY_FIELDS)) {
                return unparsed();
            }
            JSONArray fieldsJSONArray = featureLayerJSONObject.getJSONArray(NGWUtil.NGWKEY_FIELDS);
            List<Field> remoteFields = NGWUtil.getFieldsFromJson(fieldsJSONArray);

            String actualResourceCls = "";
            JSONObject resource = geoJSONObject.optJSONObject("resource");
            if (resource != null) {
                actualResourceCls = resource.optString(NGWUtil.NGWKEY_CLS, "");
            }

            JSONObject vectorLayerJSONObject = null;
            if (!actualResourceCls.isEmpty() && geoJSONObject.has(actualResourceCls)) {
                vectorLayerJSONObject = geoJSONObject.getJSONObject(actualResourceCls);
            } else if (geoJSONObject.has(vectorLayerClsKey)) {
                vectorLayerJSONObject = geoJSONObject.getJSONObject(vectorLayerClsKey);
            } else if (ngwVersionMajor >= Constants.NGW_v3 && geoJSONObject.has("postgis_layer")) {
                vectorLayerJSONObject = geoJSONObject.getJSONObject("postgis_layer");
            }
            if (vectorLayerJSONObject == null) {
                return unparsed();
            }

            String geomTypeString = vectorLayerJSONObject.getString(NGWUtil.NGWKEY_GEOMETRY_TYPE);
            int serverGeomType = GeoGeometryFactory.typeFromString(geomTypeString);
            boolean geometryMatches = serverGeomType == local.getGeometryType();
            // Empty string means a legacy caller intentionally does not compare the class. Null
            // means the serialized layer type is missing and should be repaired from NGW.
            boolean resourceClassMatches = expectedResourceCls != null
                    && (expectedResourceCls.isEmpty()
                    || actualResourceCls.isEmpty()
                    || expectedResourceCls.equals(actualResourceCls));

            Map<String, Integer> localTypeByNorm = new HashMap<>();
            for (Field f : local.getFields()) {
                String key = LayerUtil.normalizeFieldName(unwrapQuotation(f.getName()));
                localTypeByNorm.put(key, f.getType());
            }

            Map<String, Integer> remoteTypeByNorm = new HashMap<>();
            for (Field rf : remoteFields) {
                String key = LayerUtil.normalizeFieldName(unwrapQuotation(rf.getName()));
                remoteTypeByNorm.put(key, rf.getType());
            }

            boolean serializedFieldsMatch = localTypeByNorm.equals(remoteTypeByNorm);
            boolean sqliteFieldsMatch = local
                    .validateSqliteSchemaAgainstFields(remoteFields).isEmpty();
            return new SchemaComparison(
                    true,
                    resourceClassMatches,
                    geometryMatches,
                    serializedFieldsMatch,
                    sqliteFieldsMatch,
                    actualResourceCls,
                    remoteFields);
        } catch (JSONException | RuntimeException e) {
            return unparsed();
        }
    }

    private static SchemaComparison unparsed() {
        return new SchemaComparison(
                false, true, true, true, true, "", java.util.Collections.emptyList());
    }

    /**
     * Stable fingerprint of only the server geometry/field schema. Resource timestamps, display
     * name, description ordering and other unrelated metadata must not reset a rebuild circuit.
     */
    public static String schemaFingerprint(
            JSONObject geoJSONObject,
            int ngwVersionMajor,
            String vectorLayerClsKey) {
        if (geoJSONObject == null) {
            return LayerConfigUtil.md5("schema:null");
        }
        try {
            JSONObject featureLayer = geoJSONObject.getJSONObject("feature_layer");
            List<Field> fields = NGWUtil.getFieldsFromJson(
                    featureLayer.getJSONArray(NGWUtil.NGWKEY_FIELDS));
            JSONObject resource = geoJSONObject.optJSONObject("resource");
            String resourceClass = resource != null
                    ? resource.optString(NGWUtil.NGWKEY_CLS, "") : "";
            JSONObject vectorLayer = null;
            if (!resourceClass.isEmpty() && geoJSONObject.has(resourceClass)) {
                vectorLayer = geoJSONObject.getJSONObject(resourceClass);
            } else if (geoJSONObject.has(vectorLayerClsKey)) {
                vectorLayer = geoJSONObject.getJSONObject(vectorLayerClsKey);
            } else if (ngwVersionMajor >= Constants.NGW_v3
                    && geoJSONObject.has("postgis_layer")) {
                vectorLayer = geoJSONObject.getJSONObject("postgis_layer");
            }
            String geometry = vectorLayer != null
                    ? vectorLayer.optString(NGWUtil.NGWKEY_GEOMETRY_TYPE, "") : "";
            TreeMap<String, Integer> canonicalFields = new TreeMap<>();
            for (Field field : fields) {
                canonicalFields.put(
                        LayerUtil.normalizeFieldName(unwrapQuotation(field.getName())),
                        field.getType());
            }
            StringBuilder canonical = new StringBuilder("cls=").append(resourceClass)
                    .append("|geom=").append(geometry);
            for (Map.Entry<String, Integer> entry : canonicalFields.entrySet()) {
                canonical.append('|').append(entry.getKey()).append(':').append(entry.getValue());
            }
            return LayerConfigUtil.md5(canonical.toString());
        } catch (JSONException | RuntimeException e) {
            return LayerConfigUtil.md5("schema:unparsed");
        }
    }
}

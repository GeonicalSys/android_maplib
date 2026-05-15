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

import static com.nextgis.maplib.util.LayerUtil.unwrapQuotation;

/**
 * Fetches NGW resource description and checks whether {@link VectorLayer} table/fields still match
 * the server {@code feature_layer} + geometry block (vector_layer / postgis_layer).
 */
public final class NGWLayerSchemaCompat {

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
        if (local == null || geoJSONObject == null) {
            return true;
        }
        try {
            if (!geoJSONObject.has("feature_layer")) {
                return true;
            }
            JSONObject featureLayerJSONObject = geoJSONObject.getJSONObject("feature_layer");
            if (!featureLayerJSONObject.has(NGWUtil.NGWKEY_FIELDS)) {
                return true;
            }
            JSONArray fieldsJSONArray = featureLayerJSONObject.getJSONArray(NGWUtil.NGWKEY_FIELDS);
            List<Field> remoteFields = NGWUtil.getFieldsFromJson(fieldsJSONArray);

            JSONObject vectorLayerJSONObject = null;
            if (geoJSONObject.has(vectorLayerClsKey)) {
                vectorLayerJSONObject = geoJSONObject.getJSONObject(vectorLayerClsKey);
            } else if (ngwVersionMajor >= Constants.NGW_v3 && geoJSONObject.has("postgis_layer")) {
                vectorLayerJSONObject = geoJSONObject.getJSONObject("postgis_layer");
            }
            if (vectorLayerJSONObject == null) {
                return true;
            }

            String geomTypeString = vectorLayerJSONObject.getString(NGWUtil.NGWKEY_GEOMETRY_TYPE);
            int serverGeomType = GeoGeometryFactory.typeFromString(geomTypeString);
            if (serverGeomType != local.getGeometryType()) {
                return false;
            }

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

            /* Bi-directional match: server-only field (added on Web GIS) or local-only (removed on server)
             * both require a refill/rebuild — previously only server→local was checked. */
            for (Map.Entry<String, Integer> e : remoteTypeByNorm.entrySet()) {
                Integer lt = localTypeByNorm.get(e.getKey());
                if (lt == null || !lt.equals(e.getValue())) {
                    return false;
                }
            }
            for (Map.Entry<String, Integer> e : localTypeByNorm.entrySet()) {
                Integer rt = remoteTypeByNorm.get(e.getKey());
                if (rt == null || !rt.equals(e.getValue())) {
                    return false;
                }
            }
            return true;
        } catch (JSONException e) {
            return true;
        }
    }
}

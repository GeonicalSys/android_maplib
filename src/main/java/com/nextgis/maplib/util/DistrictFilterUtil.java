/*
 * Project:  NextGIS Mobile
 * Purpose:  Optional district filter for collector project vector layers
 *           (fld_district__like=... for comma-separated district lists).
 * *****************************************************************************
 * Copyright (c) 2016-2026 NextGIS, info@nextgis.com
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.nextgis.maplib.util;

import com.nextgis.maplib.datasource.Field;
import com.nextgis.maplib.datasource.ngw.Connection;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Collection;
import java.util.Map;

/**
 * Builds NGW feature API filter query for collector project district
 * (Latin values, e.g. {@code vologda} or a comma-separated list token).
 */
public final class DistrictFilterUtil {

    public static final String DISTRICT_FIELD_KEY = "district";

    private DistrictFilterUtil() {
    }

    public static final class Decision {
        public final boolean active;
        public final String serverWhere;
        /** Non-empty when {@link #active} is false — why the filter was not applied. */
        public final String inactiveReason;

        Decision(boolean active, String serverWhere, String inactiveReason) {
            this.active = active;
            this.serverWhere = serverWhere;
            this.inactiveReason = inactiveReason == null ? "" : inactiveReason;
        }
    }

    public static boolean hasField(Collection<Field> fields, String keyname) {
        if (fields == null || isEmpty(keyname)) {
            return false;
        }
        for (Field field : fields) {
            if (field != null && keyname.equals(field.getName())) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasField(Map<String, Field> fieldsByName, String keyname) {
        return fieldsByName != null && !isEmpty(keyname) && fieldsByName.containsKey(keyname);
    }

    /**
     * @return query fragment without leading {@code ?}, e.g. {@code fld_district=vologda}
     */
    public static String buildFldEqualsQuery(String fieldKey, String value) {
        if (isEmpty(fieldKey) || isEmpty(value)) {
            return "";
        }
        String encoded;
        try {
            encoded = URLEncoder.encode(value.trim(), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            encoded = value.trim();
        }
        return "fld_" + fieldKey + "=" + encoded;
    }

    /**
     * NGW Feature API {@code LIKE} membership query for a comma-separated field.
     * Publisher writes {@code district} as {@code name1, name2} (comma-space), so exact
     * {@code fld_district=olonec} misses objects that also belong to another district.
     *
     * @return query fragment without leading {@code ?}, e.g.
     * {@code fld_district__like=%25olonec%25} after URL-encoding
     */
    public static String buildFldLikeContainsQuery(String fieldKey, String value) {
        if (isEmpty(fieldKey) || isEmpty(value)) {
            return "";
        }
        String pattern = "%" + escapeLikeLiteral(value.trim()) + "%";
        String encoded;
        try {
            encoded = URLEncoder.encode(pattern, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            encoded = pattern;
        }
        return "fld_" + fieldKey + "__like=" + encoded;
    }

    /**
     * Opt-in filter: vector/PostGIS layer + non-empty collector district + {@code district} field in schema.
     */
    public static Decision resolveDistrictFilter(
            int ngwLayerType,
            Map<String, Field> fields,
            String collectorDistrict)
    {
        if (isEmpty(collectorDistrict)) {
            return new Decision(false, "", "collector district is empty");
        }
        if (!isSupportedLayerType(ngwLayerType)) {
            return new Decision(false, "",
                    "layer type " + ngwLayerType + " does not support district filter"
                            + " (expected " + Connection.NGWResourceTypePostgisLayer
                            + " or " + Connection.NGWResourceTypeVectorLayer + ")");
        }
        if (!hasField(fields, DISTRICT_FIELD_KEY)) {
            String fieldKeys = fields == null ? "null" : fields.keySet().toString();
            return new Decision(false, "",
                    "schema has no field \"" + DISTRICT_FIELD_KEY + "\" (keys=" + fieldKeys + ")");
        }
        String serverWhere = buildFldLikeContainsQuery(DISTRICT_FIELD_KEY, collectorDistrict);
        if (isEmpty(serverWhere)) {
            return new Decision(false, "", "could not build fld_ query for district=" + collectorDistrict);
        }
        return new Decision(true, serverWhere, "");
    }

    /**
     * Escape {@code \}, {@code %} and {@code _} so SQL LIKE treats them as literals.
     * District keys such as {@code karel_west} must not use {@code _} as a wildcard.
     */
    static String escapeLikeLiteral(String value) {
        if (value == null || value.length() == 0) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static boolean isSupportedLayerType(int ngwLayerType) {
        return ngwLayerType == Connection.NGWResourceTypePostgisLayer
                || ngwLayerType == Connection.NGWResourceTypeVectorLayer;
    }

    private static boolean isEmpty(CharSequence value) {
        return value == null || value.length() == 0;
    }
}

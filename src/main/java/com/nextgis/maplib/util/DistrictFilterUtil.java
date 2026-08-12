/*
 * Project:  NextGIS Mobile
 * Purpose:  Optional district filter for collector project vector layers (fld_district=...).
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
 * Builds NGW feature API filter query for collector project district (Latin values, e.g. {@code vologda}).
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
        String serverWhere = buildFldEqualsQuery(DISTRICT_FIELD_KEY, collectorDistrict);
        if (isEmpty(serverWhere)) {
            return new Decision(false, "", "could not build fld_ query for district=" + collectorDistrict);
        }
        return new Decision(true, serverWhere, "");
    }

    private static boolean isSupportedLayerType(int ngwLayerType) {
        return ngwLayerType == Connection.NGWResourceTypePostgisLayer
                || ngwLayerType == Connection.NGWResourceTypeVectorLayer;
    }

    private static boolean isEmpty(CharSequence value) {
        return value == null || value.length() == 0;
    }
}

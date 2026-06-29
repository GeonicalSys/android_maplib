/*
 * Project:  NextGIS Mobile
 * Purpose:  Read NextGIS Web resource metadata (resmeta.items).
 * *****************************************************************************
 * Copyright (c) 2016-2026 NextGIS, info@nextgis.com
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.nextgis.maplib.util;

import android.text.TextUtils;

import org.json.JSONObject;

/**
 * Parses {@code resmeta.items} from NGW resource API envelopes (see NGW search/full resource JSON).
 */
public final class NgwResmetaUtil {

    private static final String KEY_ITEMS = "items";

    private NgwResmetaUtil() {
    }

    /**
     * @param envelope root JSON from GET /api/resource/{id} or search item wrapper
     * @param key      resmeta item key, e.g. {@code district}
     * @return trimmed non-empty value or {@code null}
     */
    public static String getResmetaItemString(JSONObject envelope, String key) {
        if (envelope == null || TextUtils.isEmpty(key)) {
            return null;
        }
        JSONObject resmeta = envelope.optJSONObject(NGWUtil.NGWKEY_RESMETA);
        if (resmeta == null) {
            return null;
        }
        JSONObject items = resmeta.optJSONObject(KEY_ITEMS);
        if (items == null || items.isNull(key)) {
            return null;
        }
        String value = items.optString(key, "");
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        return value.trim();
    }
}

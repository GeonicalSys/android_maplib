/*
 * Project:  NextGIS Mobile
 * Purpose:  Parse NGW /feature_count JSON without treating a missing filter as zero.
 */

package com.nextgis.maplib.util;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Interprets {@code GET /api/resource/{id}/feature_count}.
 *
 * <p>Unfiltered requests use {@code total_count}. When a field/geometry {@code where}
 * is active, only {@code filtered_count} is authoritative: older NGW ignores {@code fld_*}
 * on this endpoint and still returns the unfiltered {@code total_count}. That must not
 * be compared with a district-subset local table (it would reload on every sync).
 * Missing {@code filtered_count} is {@link #NEED_FALLBACK}, never zero.
 */
public final class NgwFeatureCountParser {

    public static final int UNKNOWN = -1;
    public static final int NEED_FALLBACK = -2;

    private NgwFeatureCountParser() {
    }

    public static int parse(String body, boolean hasWhere) {
        if (body == null || body.trim().isEmpty()) {
            return UNKNOWN;
        }
        try {
            JSONObject json = new JSONObject(body);
            if (hasWhere) {
                if (json.has(NGWUtil.NGWKEY_FILTERED_COUNT)
                        && !json.isNull(NGWUtil.NGWKEY_FILTERED_COUNT)) {
                    int filtered = json.getInt(NGWUtil.NGWKEY_FILTERED_COUNT);
                    return filtered < 0 ? UNKNOWN : filtered;
                }
                return NEED_FALLBACK;
            }
            if (json.has(NGWUtil.NGWKEY_FEATURE_COUNT)
                    && !json.isNull(NGWUtil.NGWKEY_FEATURE_COUNT)) {
                int total = json.getInt(NGWUtil.NGWKEY_FEATURE_COUNT);
                return total < 0 ? UNKNOWN : total;
            }
            return UNKNOWN;
        } catch (JSONException e) {
            return UNKNOWN;
        }
    }
}

/*
 * Project:  NextGIS Mobile
 * Purpose:  Normalized supported item from a NextGIS Web Collector project.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.nextgis.maplib.datasource.ngw;

import android.os.Parcel;
import android.os.Parcelable;

import com.nextgis.maplib.util.Constants;
import com.nextgis.maplib.util.GeoConstants;

import org.json.JSONObject;

/**
 * A Collector item which this application can materialize locally.
 *
 * <p>The selected resource id is deliberately distinct from {@code extentRemoteId}. For a
 * rasterized style the former is the style id used by the render/tile endpoint and as the stable
 * Collector identity, while the latter is the parent vector/raster layer used only for extent
 * lookup. This allows a vector layer and one or more of its styles to coexist in one project.</p>
 */
public final class CollectorProjectItem implements Parcelable {
    public static final int KIND_VECTOR = 1;
    public static final int KIND_RASTER_STYLE = 2;

    private static final String[] VISIBLE_KEYS = {"visible", "layer_enabled"};
    private static final String[] MIN_ZOOM_KEYS = {"min_zoom", "zoom_min", "layer_min_zoom"};
    private static final String[] MAX_ZOOM_KEYS = {"max_zoom", "zoom_max", "layer_max_zoom"};
    private static final String[] TILE_CACHE_MINUTES_KEYS = {
            "tile_cache_ttl", "tile_cache_lifetime", "cache_lifetime"
    };

    private final int mKind;
    private final long mRemoteId;
    private final long mExtentRemoteId;
    private final String mName;
    private final String mResourceClass;
    private final boolean mCollectorEditable;
    private final boolean mVisible;
    private final float mMinZoom;
    private final float mMaxZoom;
    private final long mTileMaxAge;
    private final long mFormId;
    private final String mConfigJson;

    private CollectorProjectItem(
            int kind,
            long remoteId,
            long extentRemoteId,
            String name,
            String resourceClass,
            boolean collectorEditable,
            boolean visible,
            float minZoom,
            float maxZoom,
            long tileMaxAge,
            long formId,
            String configJson) {
        mKind = kind;
        mRemoteId = remoteId;
        mExtentRemoteId = extentRemoteId > 0L ? extentRemoteId : remoteId;
        mName = name;
        mResourceClass = resourceClass;
        mCollectorEditable = collectorEditable;
        mVisible = visible;
        mMinZoom = minZoom;
        mMaxZoom = maxZoom;
        mTileMaxAge = tileMaxAge;
        mFormId = Math.max(0L, formId);
        mConfigJson = configJson;
    }

    private CollectorProjectItem(Parcel in) {
        mKind = in.readInt();
        mRemoteId = in.readLong();
        mExtentRemoteId = in.readLong();
        mName = in.readString();
        mResourceClass = in.readString();
        mCollectorEditable = in.readByte() != 0;
        mVisible = in.readByte() != 0;
        mMinZoom = in.readFloat();
        mMaxZoom = in.readFloat();
        mTileMaxAge = in.readLong();
        mFormId = in.readLong();
        mConfigJson = in.readString();
    }

    public static CollectorProjectItem vector(
            JSONObject collectorItem,
            JSONObject resource,
            boolean collectorEditable,
            long formId,
            String configJson) {
        return create(
                KIND_VECTOR,
                collectorItem,
                resource,
                resource != null ? resource.optLong("id", 0L) : 0L,
                collectorEditable,
                formId,
                configJson);
    }

    public static CollectorProjectItem rasterStyle(
            JSONObject collectorItem,
            JSONObject resource) {
        long parentId = 0L;
        JSONObject parent = resource != null ? resource.optJSONObject("parent") : null;
        if (parent != null) {
            parentId = parent.optLong("id", 0L);
        }
        return create(
                KIND_RASTER_STYLE,
                collectorItem,
                resource,
                parentId,
                false,
                0L,
                null);
    }

    private static CollectorProjectItem create(
            int kind,
            JSONObject collectorItem,
            JSONObject resource,
            long extentRemoteId,
            boolean collectorEditable,
            long formId,
            String configJson) {
        long remoteId = resource != null ? resource.optLong("id", 0L) : 0L;
        String resourceName = resource != null
                ? resource.optString("display_name", "resource-" + remoteId)
                : "resource-" + remoteId;
        String itemName = collectorItem != null
                ? collectorItem.optString("display_name", "")
                : "";
        String trimmedItemName = itemName != null ? itemName.trim() : "";
        String name = isEmpty(trimmedItemName) ? resourceName : trimmedItemName;
        String resourceClass = resource != null ? resource.optString("cls", "") : "";

        boolean visible = readBoolean(collectorItem, VISIBLE_KEYS, true);
        float minZoom = readFloat(
                collectorItem,
                MIN_ZOOM_KEYS,
                GeoConstants.DEFAULT_MIN_ZOOM);
        float maxZoom = readFloat(
                collectorItem,
                MAX_ZOOM_KEYS,
                GeoConstants.DEFAULT_MAX_ZOOM);
        if (maxZoom < minZoom) {
            float swap = minZoom;
            minZoom = maxZoom;
            maxZoom = swap;
        }
        long tileMaxAge = readTileMaxAge(collectorItem);

        return new CollectorProjectItem(
                kind,
                remoteId,
                extentRemoteId,
                name,
                resourceClass,
                collectorEditable,
                visible,
                minZoom,
                maxZoom,
                tileMaxAge,
                formId,
                configJson);
    }

    private static boolean readBoolean(JSONObject item, String[] keys, boolean fallback) {
        if (item == null) {
            return fallback;
        }
        for (String key : keys) {
            if (item.has(key) && !item.isNull(key)) {
                return item.optBoolean(key, fallback);
            }
        }
        return fallback;
    }

    private static float readFloat(JSONObject item, String[] keys, float fallback) {
        if (item == null) {
            return fallback;
        }
        for (String key : keys) {
            if (!item.has(key) || item.isNull(key)) {
                continue;
            }
            double value = item.optDouble(key, Double.NaN);
            if (!Double.isNaN(value) && !Double.isInfinite(value)) {
                return (float) value;
            }
            String raw = item.optString(key, "");
            if (!isEmpty(raw)) {
                try {
                    return Float.parseFloat(raw);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return fallback;
    }

    private static long readTileMaxAge(JSONObject item) {
        if (item == null) {
            return Constants.DEFAULT_TILE_MAX_AGE;
        }
        for (String key : TILE_CACHE_MINUTES_KEYS) {
            if (!item.has(key) || item.isNull(key)) {
                continue;
            }
            double minutes = item.optDouble(key, Double.NaN);
            if (Double.isNaN(minutes) || Double.isInfinite(minutes) || minutes < 0d) {
                return Constants.DEFAULT_TILE_MAX_AGE;
            }
            double millis = minutes * 60_000d;
            return millis >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) millis;
        }
        return Constants.DEFAULT_TILE_MAX_AGE;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    public int getKind() {
        return mKind;
    }

    public boolean isVector() {
        return mKind == KIND_VECTOR;
    }

    public boolean isRasterStyle() {
        return mKind == KIND_RASTER_STYLE;
    }

    public long getRemoteId() {
        return mRemoteId;
    }

    public long getExtentRemoteId() {
        return mExtentRemoteId;
    }

    public String getName() {
        return mName;
    }

    public String getResourceClass() {
        return mResourceClass;
    }

    public boolean isCollectorEditable() {
        return mCollectorEditable;
    }

    public boolean isVisible() {
        return mVisible;
    }

    public float getMinZoom() {
        return mMinZoom;
    }

    public float getMaxZoom() {
        return mMaxZoom;
    }

    public long getTileMaxAge() {
        return mTileMaxAge;
    }

    public long getFormId() {
        return mFormId;
    }

    public String getConfigJson() {
        return mConfigJson;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mKind);
        dest.writeLong(mRemoteId);
        dest.writeLong(mExtentRemoteId);
        dest.writeString(mName);
        dest.writeString(mResourceClass);
        dest.writeByte((byte) (mCollectorEditable ? 1 : 0));
        dest.writeByte((byte) (mVisible ? 1 : 0));
        dest.writeFloat(mMinZoom);
        dest.writeFloat(mMaxZoom);
        dest.writeLong(mTileMaxAge);
        dest.writeLong(mFormId);
        dest.writeString(mConfigJson);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<CollectorProjectItem> CREATOR =
            new Creator<CollectorProjectItem>() {
                @Override
                public CollectorProjectItem createFromParcel(Parcel in) {
                    return new CollectorProjectItem(in);
                }

                @Override
                public CollectorProjectItem[] newArray(int size) {
                    return new CollectorProjectItem[size];
                }
            };
}

/*
 * Project:  NextGIS Mobile
 * Purpose:  Mobile GIS for Android.
 * Author:   Dmitry Baryshnikov (aka Bishop), bishop.dev@gmail.com
 * Author:   NikitaFeodonit, nfeodonit@yandex.com
 * Author:   Stanislav Petriakov, becomeglory@gmail.com
 * *****************************************************************************
 * Copyright (c) 2012-2016 NextGIS, info@nextgis.com
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.nextgis.maplib.map;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;

import com.nextgis.maplib.R;
import com.nextgis.maplib.api.IJSONStore;
import com.nextgis.maplib.api.IProgressor;
import com.nextgis.maplib.datasource.TileItem;
import com.nextgis.maplib.display.TMSRenderer;
import com.nextgis.maplib.util.Constants;
import com.nextgis.maplib.util.FileUtil;
import com.nextgis.maplib.util.GeoConstants;
import com.nextgis.maplib.util.NGException;
import com.nextgis.maplib.util.NetworkUtil;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URL;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.nextgis.maplib.util.Constants.JSON_RENDERERPROPS_KEY;


public abstract class TMSLayer
        extends Layer
{
    protected static final String JSON_TMSTYPE_KEY     = "tms_type";
    protected static final String JSON_CACHE_SIZE_MULT = "cache_size_multiply";
    private static final String JSON_NGRC_PROVENANCE = "ngrc_provenance";
    private static final String JSON_NGRC_SOURCE_NAME = "source_name";
    private static final String JSON_NGRC_ARCHIVE_SHA256 = "archive_sha256";
    private static final String JSON_NGRC_IMPORTED_AT = "imported_at";
    private static final String JSON_NGRC_UPDATE_POLICY = "update_policy";
    public static final String NGRC_UPDATE_POLICY_IMMUTABLE_LOCAL = "immutable_local";
    public static final String TILE_EXT = ".tile";

    protected int mTMSType;
    protected static final int HTTP_SEPARATE_THREADS = 2;
    protected Map<String, Bitmap> mBitmapCache;
    protected int                 mCacheSize, mCacheSizeMult;
    protected int mViewWidth, mViewHeight;
    protected final Object lock = new Object();
    private String mNgrcSourceName;
    private String mNgrcArchiveSha256;
    private long mNgrcImportedAt;
    private String mLastArchiveSha256;


    protected TMSLayer(
            Context context,
            File path)
    {
        super(context, path);

        mCacheSizeMult = 2;
        mRenderer = new TMSRenderer(this);
    }


    public int getTMSType()
    {
        return mTMSType;
    }


    public void setTMSType(int type)
    {
        mTMSType = type;
    }

    public abstract Bitmap getBitmap(TileItem tile);


    protected void putBitmapToCache(
            String tileHash,
            Bitmap bitmap)
    {
        if (mCacheSizeMult == 0) {
            return;
        }
        synchronized (lock) {
            if (mBitmapCache != null) {
                mBitmapCache.put(tileHash, bitmap);
            }
        }
    }


    protected Bitmap getBitmapFromCache(String tileHash)
    {
        if (mCacheSizeMult == 0) {
            return null;
        }
        synchronized (lock) {
            if (mBitmapCache != null) {
                return mBitmapCache.get(tileHash);
            }
        }
        return null;
    }

    public void clearCache() {
        if (mBitmapCache != null)
            mBitmapCache.clear();
    }

    @Override
    public JSONObject toJSON()
            throws JSONException
    {
        JSONObject rootConfig = super.toJSON();
        rootConfig.put(JSON_TMSTYPE_KEY, mTMSType);
        if (mRenderer instanceof IJSONStore) {
            IJSONStore jsonStore = (IJSONStore) mRenderer;
            rootConfig.put(JSON_RENDERERPROPS_KEY, jsonStore.toJSON());
        }


        rootConfig.put(JSON_CACHE_SIZE_MULT, mCacheSizeMult);
        if (mNgrcArchiveSha256 != null && !mNgrcArchiveSha256.isEmpty()) {
            JSONObject provenance = new JSONObject();
            provenance.put(JSON_NGRC_SOURCE_NAME, mNgrcSourceName);
            provenance.put(JSON_NGRC_ARCHIVE_SHA256, mNgrcArchiveSha256);
            provenance.put(JSON_NGRC_IMPORTED_AT, mNgrcImportedAt);
            provenance.put(JSON_NGRC_UPDATE_POLICY, NGRC_UPDATE_POLICY_IMMUTABLE_LOCAL);
            rootConfig.put(JSON_NGRC_PROVENANCE, provenance);
        }
        return rootConfig;
    }


    @Override
    public void fromJSON(JSONObject jsonObject)
            throws JSONException
    {
        super.fromJSON(jsonObject);
        mTMSType = jsonObject.getInt(JSON_TMSTYPE_KEY);
        if (jsonObject.has(JSON_RENDERERPROPS_KEY)) {
            if (mRenderer instanceof IJSONStore) {
                IJSONStore jsonStore = (IJSONStore) mRenderer;
                jsonStore.fromJSON(jsonObject.getJSONObject(JSON_RENDERERPROPS_KEY));
            }
        }

        if (jsonObject.has(JSON_CACHE_SIZE_MULT)) {
            mCacheSizeMult = jsonObject.getInt(JSON_CACHE_SIZE_MULT);
        }
        JSONObject provenance = jsonObject.optJSONObject(JSON_NGRC_PROVENANCE);
        if (provenance != null) {
            mNgrcSourceName = provenance.optString(JSON_NGRC_SOURCE_NAME, null);
            mNgrcArchiveSha256 = provenance.optString(JSON_NGRC_ARCHIVE_SHA256, null);
            mNgrcImportedAt = provenance.optLong(JSON_NGRC_IMPORTED_AT, 0L);
        }

        if(Constants.DEBUG_MODE) {
            Log.d(Constants.TAG, "Raster layer " + getName() + " mTMSType " + mTMSType);
            Log.d(Constants.TAG, "Raster layer " + getName() + " mCacheSizeMult " + mCacheSizeMult);
        }
    }


    public int getMaxThreadCount()
    {
        return HTTP_SEPARATE_THREADS;
    }


    @Override
    public void setViewSize(
            int w,
            int h)
    {
        super.setViewSize(w, h);

        mViewWidth = w;
        mViewHeight = h;

        setCacheSizeMultiply(mCacheSizeMult);
    }


    public int getCacheSizeMultiply()
    {
        return mCacheSizeMult;
    }


    public void setCacheSizeMultiply(int cacheSizeMult)
    {
        mCacheSizeMult = cacheSizeMult;
        if (mCacheSizeMult == 0) {
            synchronized (lock) {
                mBitmapCache = null;
            }
            return;
        }

        // calc new hash size
        int nTileCount = (int) (mViewWidth * Constants.OFFSCREEN_EXTRASIZE_RATIO /
                                Constants.DEFAULT_TILE_SIZE) *
                         (int) (mViewHeight * Constants.OFFSCREEN_EXTRASIZE_RATIO /
                                Constants.DEFAULT_TILE_SIZE) * mCacheSizeMult;

        if (null != mBitmapCache && mCacheSize >= nTileCount) {
            return;
        }
        if (nTileCount < 30) {
            nTileCount = 30;
        }

        synchronized (lock) {
            mBitmapCache = lruCache(nTileCount);
        }

        mCacheSize = nTileCount;
    }


    protected static <K, V> Map<K, V> lruCache(final int maxSize)
    {
        return new LinkedHashMap<K, V>(maxSize * 4 / 3, 0.75f, true)
        {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest)
            {
                return size() > maxSize;
            }
        };
    }

    protected void fillFromZipInt(Uri uri, IProgressor progressor) throws IOException, NGException, RuntimeException {
        InputStream inputStream;
        String url = uri.toString();
        if (NetworkUtil.isValidUri(url))
            inputStream = new URL(url).openStream();
        else
            inputStream = mContext.getContentResolver().openInputStream(uri);

        if (inputStream == null)
            throw new NGException(mContext.getString(R.string.error_download_data));

        int streamSize = inputStream.available();
        if (null != progressor)
            progressor.setMax(streamSize);

        int increment = 0;
        byte[] buffer = new byte[Constants.IO_BUFFER_SIZE];

        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
        try (DigestInputStream digestInput = new DigestInputStream(inputStream, digest);
             ZipInputStream zis = new ZipInputStream(digestInput)) {
            ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null) {
                FileUtil.unzipEntry(zis, ze, buffer, mPath);
                long compressedSize = ze.getCompressedSize();
                if (compressedSize > 0L) {
                    increment += (int) Math.min(Integer.MAX_VALUE, compressedSize);
                }
                zis.closeEntry();
                if (null != progressor) {
                    if(progressor.isCanceled()) {
                        throw new InterruptedIOException("Archive import cancelled");
                    }
                    progressor.setValue(increment);
                    progressor.setMessage(getContext().getString(R.string.processed) + " " + increment + " " + getContext().getString(R.string.of) + " " + streamSize);
                }
            }
            // ZipInputStream may stop after recognizing the central directory while unread archive
            // bytes (for example a ZIP comment) still remain in the wrapped stream. Drain them so
            // provenance is the SHA-256 of the complete source archive, not only extracted entries.
            while (digestInput.read(buffer) != -1) {
                // DigestInputStream updates the digest while draining.
            }
        }
        mLastArchiveSha256 = toHex(digest.digest());
    }


    public void fillFromZip(Uri uri, IProgressor progressor) throws IOException, NumberFormatException, SecurityException, NGException {
        fillFromZipInt(uri, progressor);
        if (!save()) {
            throw new IOException("Cannot save imported TMS layer configuration");
        }
    }

    /** Extra zoom levels beyond tile min/max so the layer stays visible when slightly over-zoomed. */
    private static final float NGRC_VISIBILITY_ZOOM_PADDING = 2f;

    public void fillFromNgrc(Uri uri, IProgressor progressor) throws IOException, NumberFormatException, SecurityException, NGException {
        fillFromZipInt(uri, progressor);
        if (!load()) {
            throw new IOException("Cannot load imported NGRC configuration");
        }
        float minZ = getMinZoom();
        float maxZ = getMaxZoom();
        setMinZoom(Math.max(GeoConstants.DEFAULT_MIN_ZOOM, minZ - NGRC_VISIBILITY_ZOOM_PADDING));
        setMaxZoom(Math.min(GeoConstants.DEFAULT_MAX_ZOOM, maxZ + NGRC_VISIBILITY_ZOOM_PADDING));
        if (!save()) {
            throw new IOException("Cannot save imported NGRC configuration");
        }
    }

    public void setNgrcImportProvenance(String sourceName, String archiveSha256) {
        mNgrcSourceName = sourceName;
        mNgrcArchiveSha256 = archiveSha256;
        mNgrcImportedAt = System.currentTimeMillis();
    }

    public String getLastArchiveSha256() {
        return mLastArchiveSha256;
    }

    public String getNgrcArchiveSha256() {
        return mNgrcArchiveSha256;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            value.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
        }
        return value.toString();
    }

}

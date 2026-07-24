/*
 * Project:  NextGIS Mobile
 * Purpose:  Mobile GIS for Android.
 * Author:   Dmitry Baryshnikov (aka Bishop), bishop.dev@gmail.com
 * Author:   NikitaFeodonit, nfeodonit@yandex.com
 * Author:   Stanislav Petriakov, becomeglory@gmail.com
 * *****************************************************************************
 * Copyright (c) 2015-2021 NextGIS, info@nextgis.com
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

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SyncResult;
import android.database.Cursor;
import android.os.SystemClock;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.text.TextUtils;
import android.util.JsonReader;
import android.util.Log;
import android.util.Pair;

import com.hypertrack.hyperlog.HyperLog;
import com.nextgis.maplib.R;
import com.nextgis.maplib.api.IGISApplication;
import com.nextgis.maplib.api.INGWLayer;
import com.nextgis.maplib.api.IProgressor;
import com.nextgis.maplib.datasource.Feature;
import com.nextgis.maplib.datasource.Field;
import com.nextgis.maplib.datasource.GeoGeometry;
import com.nextgis.maplib.datasource.GeoGeometryFactory;
import com.nextgis.maplib.datasource.ngw.Connection;
import com.nextgis.maplib.datasource.ngw.SyncAdapter;
import com.nextgis.maplib.util.AccountUtil;
import com.nextgis.maplib.util.AttachItem;
import com.nextgis.maplib.util.Constants;
import com.nextgis.maplib.util.DatabaseContext;
import com.nextgis.maplib.util.ExistFeatureResult;
import com.nextgis.maplib.util.NgwPullDecision;
import com.nextgis.maplib.util.DistrictFilterUtil;
import com.nextgis.maplib.util.FeatureAttachments;
import com.nextgis.maplib.util.FeatureChanges;
import com.nextgis.maplib.util.GeoConstants;
import com.nextgis.maplib.util.HttpResponse;
import com.nextgis.maplib.util.NGException;
import com.nextgis.maplib.util.NGWUtil;
import com.nextgis.maplib.util.LayerConfigDiff;
import com.nextgis.maplib.util.LayerConfigUtil;
import com.nextgis.maplib.util.NGWLayerSchemaCompat;
import com.nextgis.maplib.util.NetworkUtil;
import com.nextgis.maplib.util.ProdLogUtil;
import com.nextgis.maplib.util.ProgressBufferedInputStream;
import com.nextgis.maplib.util.SettingsConstants;
import com.nextgis.maplib.util.SyncResultUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

import static com.nextgis.maplib.datasource.ngw.Connection.NGWResourceTypePostgisLayer;
import static com.nextgis.maplib.util.Constants.CHANGE_OPERATION_ATTACH;
import static com.nextgis.maplib.util.Constants.CHANGE_OPERATION_TEMP;
import static com.nextgis.maplib.util.Constants.FIELD_ATTACH_ID;
import static com.nextgis.maplib.util.Constants.FIELD_ATTACH_OPERATION;
import static com.nextgis.maplib.util.Constants.FIELD_FEATURE_ID;
import static com.nextgis.maplib.util.Constants.FIELD_ID;
import static com.nextgis.maplib.util.Constants.FIELD_OPERATION;
import static com.nextgis.maplib.util.Constants.MESSAGE_ALERT_INTENT;
import static com.nextgis.maplib.util.Constants.MESSAGE_EXTRA;
import static com.nextgis.maplib.util.Constants.MESSAGE_TITLE_EXTRA;
import static com.nextgis.maplib.util.Constants.MIN_LOCAL_FEATURE_ID;
import static com.nextgis.maplib.util.Constants.TAG;
import static com.nextgis.maplib.util.Constants.URI_ATTACH;
import static com.nextgis.maplib.util.Constants.URI_CHANGES;
import static com.nextgis.maplib.util.MapUtil.convertTime;
import static com.nextgis.maplib.util.NGWUtil.appendix;
import static com.nextgis.maplib.util.NetworkUtil.configureSSLdefault;
import static com.nextgis.maplib.util.NetworkUtil.getUserAgent;


public class NGWVectorLayer
        extends VectorLayer
        implements INGWLayer
{
    protected static final String JSON_ACCOUNT_KEY           = "account";
    protected static final String JSON_NGW_VERSION_MAJOR_KEY = "ngw_version_major";
    protected static final String JSON_NGW_VERSION_MINOR_KEY = "ngw_version_minor";
    protected static final String JSON_SYNC_TYPE_KEY         = "sync_type";
    protected static final String JSON_NGWLAYER_TYPE_KEY     = "ngw_layer_type";
    protected static final String JSON_SERVERWHERE_KEY       = "server_where";
    protected static final String JSON_TRACKED_KEY           = "tracked";
    protected static final String JSON_SYNC_DIRECTION_KEY    = "sync_direction";
    protected static final String JSON_LAYER_ORIGIN_KEY      = "layer_origin";

    protected static final int TYPE_CHANGES_TABLE     = 125;
    protected static final int TYPE_CHANGES_FEATURE   = 126;
    protected static final int TYPE_CHANGES_ATTACH    = 127;
    protected static final int TYPE_CHANGES_ATTACH_ID = 128;

    /** Limits progressor calls during bulk fill (every N features or after min interval). */
    private static final int NGW_FILL_PROGRESS_FEATURE_STEP = 50;
    private static final long NGW_FILL_PROGRESS_MIN_INTERVAL_MS = 250L;
    /**
     * Commit SQLite writes every N features so other threads (map/UI) can read the DB instead of
     * blocking on one multi‑minute transaction while streaming NGW JSON into {@link #createFeatureBatch}.
     */
    private static final int NGW_FILL_SQL_TX_BATCH = 250;
    private static final int NGW_SYNC_PULL_MAX_ATTEMPTS = 3;
    private static final long NGW_SYNC_PULL_RETRY_DELAY_MS = 1200L;

    private static final class FeaturePushResult {
        final boolean success;
        final long remoteFeatureId;

        FeaturePushResult(boolean success, long remoteFeatureId) {
            this.success = success;
            this.remoteFeatureId = remoteFeatureId;
        }

        static FeaturePushResult failed() {
            return new FeaturePushResult(false, Constants.NOT_FOUND);
        }

        static FeaturePushResult handledWithoutRemoteId() {
            return new FeaturePushResult(true, Constants.NOT_FOUND);
        }

        static FeaturePushResult success(long remoteFeatureId) {
            return new FeaturePushResult(true, remoteFeatureId);
        }
    }

    protected static final int DIRECTION_TO = 1;
    protected static final int DIRECTION_FROM = 2;
    protected static final int DIRECTION_BOTH = 3;

    protected static boolean mIsAddedToUriMatcher = false;

    protected NetworkUtil mNet;

    protected int mNgwVersionMajor = Constants.NOT_FOUND;
    protected int mNgwVersionMinor = Constants.NOT_FOUND;

    protected String mAccountName;
    protected long   mRemoteId;
    protected int    mSyncType;
    protected int    mNGWLayerType;
    protected int    mCRS = GeoConstants.CRS_WEB_MERCATOR;
    protected String mServerWhere;
    protected boolean mTracked;
    protected int mSyncDirection = DIRECTION_BOTH; //1 - to server only, 2 - from server only, 3 - both directions
    protected LayerOriginMetadata mLayerOriginMetadata;
    //check where to sync on GSM/WI-FI for data/attachments

    /** Log SQLite error text once per getChangesFromServer when createNewFeature insert fails. */
    private boolean mLoggedCreateInsertSqlError;

    /** True when {@link #mServerWhere} was set from parent {@link LayerGroup} district filter (runtime only). */
    private transient boolean mDistrictFilterActive;

    /**
     * District from import {@link LayerGroup} during fill, before the layer is attached to the group.
     */
    private transient String mCollectorDistrictOverride;

    public static final String LOG_DISTRICT_FILTER = "DistrictFilter";

    public NGWVectorLayer(
            Context context,
            File path)
    {
        super(context, path);

        if (null == mNet) {
            mNet = new NetworkUtil(context);
        }

        mSyncType = Constants.SYNC_NONE;
        mLayerType = Constants.LAYERTYPE_NGW_VECTOR;
        mNGWLayerType = Connection.NGWResourceTypeNone;

        if (!mIsAddedToUriMatcher) {
            // get changes for all rows
            mUriMatcher.addURI(mAuthority, "*/" + URI_CHANGES, TYPE_CHANGES_TABLE);

            // get changes for single row
            mUriMatcher.addURI(mAuthority, "*/" + URI_CHANGES + "/#", TYPE_CHANGES_FEATURE);

            //get changes for all attaches of row
            mUriMatcher.addURI(
                    mAuthority, "*/" + URI_CHANGES + "/#/" + URI_ATTACH, TYPE_CHANGES_ATTACH);

            //get changes for single attach by id
            mUriMatcher.addURI(mAuthority, "*/" + URI_CHANGES + "/#/" + URI_ATTACH + "/#",
                    TYPE_CHANGES_ATTACH_ID);

            mIsAddedToUriMatcher = true;
        }
    }


    @Override
    public String getAccountName()
    {
        return mAccountName;
    }


    @Override
    public void setAccountName(String accountName)
    {
        mAccountName = accountName;
        setAccountCacheData();
    }


    @Override
    public long getRemoteId()
    {
        return mRemoteId;
    }


    public String getRemoteUrl()
    {
        try {
            AccountUtil.AccountData accountData = AccountUtil.getAccountData(mContext, mAccountName);
            return NGWUtil.getResourceUrl(accountData.url, mRemoteId);
        } catch (IllegalStateException e) {
            return null;
        }
    }


    @Override
    public void setRemoteId(long remoteId)
    {
        mRemoteId = remoteId;
    }


    public String getServerWhere()
    {
        return mServerWhere;
    }


    public void setServerWhere(String serverWhere)
    {
        mServerWhere = serverWhere;
    }

    public LayerOriginMetadata getLayerOriginMetadata() {
        return mLayerOriginMetadata;
    }

    /**
     * Collector architecture foundation.
     *
     * Persisted layer ownership is consumed by future Collector composition/form/tile sync. Keep
     * this metadata even when current layer data sync only needs account + remote id.
     */
    public void setLayerOriginMetadata(LayerOriginMetadata layerOriginMetadata) {
        mLayerOriginMetadata = layerOriginMetadata;
    }

    public boolean isDistrictFilterActive() {
        return mDistrictFilterActive;
    }

    /**
     * During collector import fill the layer is not yet in {@link LayerGroup}; pass district explicitly.
     */
    public void setCollectorDistrictOverride(String collectorDistrict) {
        mCollectorDistrictOverride = TextUtils.isEmpty(collectorDistrict)
                ? null
                : collectorDistrict.trim();
    }

    /**
     * Opt-in collector district filter from parent {@link LayerGroup#getCollectorDistrict()}.
     *
     * @return {@code true} if {@code fld_district=} filter is active for this fill/pull
     */
    protected boolean applyDistrictFilterFromProjectGroup() {
        String district = mCollectorDistrictOverride;
        final String districtSource;
        if (!TextUtils.isEmpty(district)) {
            districtSource = "fill override";
        } else {
            district = LayerGroup.findCollectorDistrict(this);
            districtSource = TextUtils.isEmpty(district) ? "none" : "parent group";
        }
        HyperLog.d(Constants.TAG, LOG_DISTRICT_FILTER + " layer=\"" + getName() + "\" remoteId=" + mRemoteId
                + " districtSource=" + districtSource
                + " district=" + (TextUtils.isEmpty(district) ? "<empty>" : district)
                + " ngwLayerType=" + mNGWLayerType
                + " vectorType=" + Connection.NGWResourceTypeVectorLayer
                + " postgisType=" + NGWResourceTypePostgisLayer);

        DistrictFilterUtil.Decision decision = DistrictFilterUtil.resolveDistrictFilter(
                mNGWLayerType, mFields, district);
        mCollectorDistrictOverride = null;
        mDistrictFilterActive = decision.active;
        if (!decision.active) {
            HyperLog.d(Constants.TAG, LOG_DISTRICT_FILTER + " OFF: " + decision.inactiveReason);
            return false;
        }
        mServerWhere = decision.serverWhere;
        mTracked = false;
        HyperLog.d(Constants.TAG, LOG_DISTRICT_FILTER + " ON serverWhere=" + mServerWhere + " mTracked=false");
        return true;
    }


    public String getChangeTableName()    {
        return mPath.getName() + Constants.CHANGES_NAME_POSTFIX;
    }


    public String getAttachmentsTableName()    {
        return mPath.getName() + Constants.ATTACHMENTS_NAME_POSTFIX;
    }

    @Override
    public JSONObject toJSON()
            throws JSONException
    {
        JSONObject rootConfig = super.toJSON();
        rootConfig.put(JSON_NGW_VERSION_MAJOR_KEY, mNgwVersionMajor);
        rootConfig.put(JSON_NGW_VERSION_MINOR_KEY, mNgwVersionMinor);
        rootConfig.put(JSON_ACCOUNT_KEY, mAccountName);
        rootConfig.put(Constants.JSON_ID_KEY, mRemoteId);
        rootConfig.put(JSON_SYNC_TYPE_KEY, mSyncType);
        rootConfig.put(JSON_NGWLAYER_TYPE_KEY, mNGWLayerType);
        if (!mDistrictFilterActive) {
            rootConfig.put(JSON_SERVERWHERE_KEY, mServerWhere);
        }
        rootConfig.put(JSON_TRACKED_KEY, mTracked);
        rootConfig.put(GeoConstants.GEOJSON_CRS, mCRS);
        rootConfig.put(JSON_SYNC_DIRECTION_KEY, mSyncDirection);
        if (mLayerOriginMetadata != null) {
            rootConfig.put(JSON_LAYER_ORIGIN_KEY, mLayerOriginMetadata.toJSON());
        }

        return rootConfig;
    }


    @Override
    public void fromJSON(JSONObject jsonObject)
            throws JSONException, SQLiteException
    {
        super.fromJSON(jsonObject);

        mTracked = jsonObject.optBoolean(JSON_TRACKED_KEY);
        mCRS = jsonObject.optInt(GeoConstants.GEOJSON_CRS, GeoConstants.CRS_WEB_MERCATOR);
        if (jsonObject.has(JSON_NGW_VERSION_MAJOR_KEY)) {
            mNgwVersionMajor = jsonObject.getInt(JSON_NGW_VERSION_MAJOR_KEY);
        }
        if (jsonObject.has(JSON_NGW_VERSION_MINOR_KEY)) {
            mNgwVersionMinor = jsonObject.getInt(JSON_NGW_VERSION_MINOR_KEY);
        }

        setAccountName(jsonObject.optString(JSON_ACCOUNT_KEY));

        mRemoteId = jsonObject.optLong(Constants.JSON_ID_KEY);
        mSyncType = jsonObject.optInt(JSON_SYNC_TYPE_KEY, Constants.SYNC_NONE);
        mNGWLayerType = jsonObject.optInt(JSON_NGWLAYER_TYPE_KEY, Constants.LAYERTYPE_NGW_VECTOR);
        mServerWhere = jsonObject.optString(JSON_SERVERWHERE_KEY);
        mSyncDirection = jsonObject.optInt(JSON_SYNC_DIRECTION_KEY, DIRECTION_BOTH);
        mLayerOriginMetadata = LayerOriginMetadata.fromJSON(
                jsonObject.optJSONObject(JSON_LAYER_ORIGIN_KEY));
    }


    @Override
    public void setAccountCacheData()
    {
        // do nothing
    }


    @Override
    protected long insertInternal(ContentValues contentValues)
    {
        if (!contentValues.containsKey(Constants.FIELD_ID)) {
            long id = getUniqId();
            if (MIN_LOCAL_FEATURE_ID > id) {
                id = MIN_LOCAL_FEATURE_ID;
            }
            contentValues.put(FIELD_ID, id);
        }

        return super.insertInternal(contentValues);
    }


    @Override
    protected boolean checkGeometryType(Feature feature)
    {
        return mNgwVersionMajor < Constants.NGW_v3 || super.checkGeometryType(feature);
    }


    // for overriding in the subclasses
    protected String getFeaturesUrl(AccountUtil.AccountData accountData)
    {
        if (mTracked)
            return NGWUtil.getTrackedFeaturesUrl(accountData.url, mRemoteId, getPreferences().getLong(SettingsConstants.KEY_PREF_LAST_SYNC_TIMESTAMP, 0));
        else
            return NGWUtil.getFeaturesUrl(accountData.url, mRemoteId, mServerWhere);
    }


    // for overriding in the subclasses
    protected String getResourceMetaUrl(AccountUtil.AccountData accountData)
    {
        return NGWUtil.getResourceUrl(accountData.url, mRemoteId);
    }


    // for overriding in the subclasses
    protected String getRequiredCls()
    {
        return "vector_layer";
    }


    /**
     * download and create new NGW layer from GeoJSON data
     */
    public void createFromNGW(IProgressor progressor)
            throws NGException, IOException, JSONException, SQLiteException
    {
        if (!mNet.isNetworkAvailable()) { //return tile from cache
            throw new NGException(getContext().getString(R.string.error_network_unavailable));
        }

        if (Constants.DEBUG_MODE) {
            Log.d(Constants.TAG, "download layer " + getName());
        }

        // get account
        AccountUtil.AccountData accountData;
        try {
            accountData = AccountUtil.getAccountData(mContext, mAccountName);
        } catch (IllegalStateException e) {
            throw new NGException(getContext().getString(R.string.error_auth));
        }

        if (null == accountData.url) {
            throw new NGException(getContext().getString(R.string.error_404));
        }

        // get NGW version
        Pair<Integer, Integer> ver = null;
        try {
            ver = NGWUtil.getNgwVersion(accountData.url, accountData.login, accountData.password);
        } catch (IOException | JSONException | NumberFormatException ignored) {
            HyperLog.w(Constants.TAG, "NGWVectorLayer.createFromNGW: " + ignored.getMessage(), ignored);
        }

        if (null != ver) {
            mNgwVersionMajor = ver.first;
            mNgwVersionMinor = ver.second;
        }

        // get layer description
        JSONObject geoJSONObject;
        HttpResponse response = NetworkUtil.get(getResourceMetaUrl(accountData), accountData.login,
                accountData.password, false);
        if (!response.isOk()) {
            HyperLog.w(Constants.TAG, ProdLogUtil.ngwHttpFailure("resourceMeta", getName(), mRemoteId, -1, -1,
                    response));
            throw new NGException(NetworkUtil.getError(mContext, response.getResponseCode()));
        }
        geoJSONObject = new JSONObject(response.getResponseBody());

        //fill field list
        JSONObject featureLayerJSONObject = geoJSONObject.getJSONObject("feature_layer");
        JSONArray fieldsJSONArray = featureLayerJSONObject.getJSONArray(NGWUtil.NGWKEY_FIELDS);
        final int serverExpectedFeatureCount =
                featureLayerJSONObject.optInt(NGWUtil.NGWKEY_FEATURE_COUNT, -1);
        List<Field> fields = NGWUtil.getFieldsFromJson(fieldsJSONArray);

        //fill SRS
        JSONObject vectorLayerJSONObject = null;
        if (geoJSONObject.has(getRequiredCls())) {
            vectorLayerJSONObject = geoJSONObject.getJSONObject(getRequiredCls());
            mNGWLayerType = Connection.NGWResourceTypeVectorLayer;
        } else if (mNgwVersionMajor >= Constants.NGW_v3 && geoJSONObject.has("postgis_layer")) {
            vectorLayerJSONObject = geoJSONObject.getJSONObject("postgis_layer");
            mNGWLayerType = NGWResourceTypePostgisLayer;
        }
        if (null == vectorLayerJSONObject) {
            throw new NGException(getContext().getString(R.string.error_download_data));
        }

        String geomTypeString = vectorLayerJSONObject.getString(JSON_GEOMETRY_TYPE_KEY);
        int geomType = GeoGeometryFactory.typeFromString(geomTypeString);
        JSONObject srs = vectorLayerJSONObject.getJSONObject(NGWUtil.NGWKEY_SRS);
        mCRS = srs.getInt("id");
        if (mCRS != GeoConstants.CRS_WEB_MERCATOR && mCRS != GeoConstants.CRS_WGS84) {
            throw new NGException(getContext().getString(R.string.error_crs_unsupported));
        }

        create(geomType, fields);

        applyDistrictFilterFromProjectGroup();

        String sURL = getFeaturesUrl(accountData);
        HyperLog.d(Constants.TAG, LOG_DISTRICT_FILTER + " fill featuresUrl="
                + sURL + (mDistrictFilterActive ? " (filtered)" : " (full pull)"));
        if (Constants.DEBUG_MODE) {
            Log.d(Constants.TAG, "download features from: " + sURL);
        }

        HttpURLConnection urlConnection = NetworkUtil.getHttpConnection("GET", sURL, accountData.login, accountData.password);
        if (null == urlConnection) {
            if (Constants.DEBUG_MODE) {
                Log.d(TAG, "Error get connection object: " + sURL);
            }
            if (null != progressor) {
                progressor.setMessage(getContext().getString(R.string.error_connect_failed));
            }
            throw new NGException(getContext().getString(R.string.error_connect_failed));
        }

        try {
            if (urlConnection.getResponseCode() == HttpURLConnection.HTTP_MOVED_PERM && urlConnection.getURL().getProtocol().equals("http")) {
                sURL = sURL.replace("http", "https");
                urlConnection = NetworkUtil.getHttpConnection("GET", sURL, accountData.login, accountData.password);
                if (null == urlConnection) {
                    if (null != progressor) {
                        progressor.setMessage(getContext().getString(R.string.error_connect_failed));
                    }
                    throw new NGException(getContext().getString(R.string.error_connect_failed));
                }
            }

            final int responseCode = urlConnection.getResponseCode();
            final String responseMessage = urlConnection.getResponseMessage();
            final int contentLength = urlConnection.getContentLength();
            HyperLog.d(Constants.TAG, "NGW feature pull response layer=\""
                    + ProdLogUtil.truncateForLog(getName(), 100)
                    + "\" res=" + mRemoteId
                    + " http=" + responseCode
                    + (TextUtils.isEmpty(responseMessage) ? "" : " msg=\""
                    + ProdLogUtil.truncateForLog(responseMessage, 120) + "\"")
                    + " contentLength=" + contentLength
                    + " contentType=" + ProdLogUtil.truncateForLog(urlConnection.getContentType(), 120)
                    + " expectedFeatures=" + serverExpectedFeatureCount
                    + " url=" + ProdLogUtil.scrubUrlForLog(sURL));
            if (responseCode != HttpURLConnection.HTTP_OK) {
                String errorBody = null;
                try {
                    errorBody = NetworkUtil.responseToString(urlConnection.getErrorStream());
                } catch (IOException bodyError) {
                    HyperLog.w(Constants.TAG, "NGW feature pull error body read failed: "
                            + bodyError.getMessage(), bodyError);
                }
                HttpResponse featureResponse =
                        new HttpResponse(responseCode, responseMessage, errorBody);
                String httpFailure = ProdLogUtil.ngwHttpFailure(
                        "featurePull",
                        getName(),
                        mRemoteId,
                        -1,
                        -1,
                        featureResponse)
                        + " expectedFeatures=" + serverExpectedFeatureCount
                        + " url=" + ProdLogUtil.scrubUrlForLog(sURL);
                HyperLog.w(Constants.TAG, httpFailure);
                if (Constants.DEBUG_MODE) {
                    Log.w(Constants.TAG, httpFailure);
                }
                String error = NetworkUtil.getError(mContext, responseCode);
                if (NetworkUtil.isTransientNgwHttpError(responseCode, errorBody, responseMessage)) {
                    throw new IOException("NGW feature pull HTTP " + responseCode
                            + (TextUtils.isEmpty(responseMessage) ? "" : " " + responseMessage)
                            + " " + ProdLogUtil.scrubUrlForLog(sURL));
                }
                throw new NGException(error);
            }

            final long fillStartMs = SystemClock.elapsedRealtime();

            InputStream in = new ProgressBufferedInputStream(urlConnection.getInputStream(), contentLength);
            JsonReader reader = new JsonReader(new InputStreamReader(in, "UTF-8"));
            reader.beginArray();

            MapContentProviderHelper map = (MapContentProviderHelper) MapBase.getInstance();
            if (null == map) {
                reader.close();
                throw new NGException(getContext().getString(R.string.error_download_data));
            }
            DatabaseContext.getDbForLayer(this);
            SQLiteDatabase dbTx = map.getDatabase(false);

            int streamSize = in.available();
            if (null != progressor) {
                progressor.setIndeterminate(false);
                if (streamSize > 0)
                    progressor.setMax(streamSize);
                progressor.setMessage(getContext().getString(R.string.start_fill_layer) + " " + getName());
            }

            int featureCount = 0;
            boolean jsonTruncated = false;
            try {
                beginBulkImport();
                dbTx.beginTransaction();
                try {
                    long lastProgressElapsedMs = 0L;
                    while (reader.hasNext()) {
                        try {
                            final Feature feature = NGWUtil.readNGWFeature(reader, fields, mCRS);
                            if (feature.getGeometry() == null || !feature.getGeometry().isValid())
                                continue;

                            createFeatureBatch(feature, dbTx, false);
                        } catch (OutOfMemoryError | IllegalStateException | IOException | NumberFormatException |
                                 NGException e) {
                            e.printStackTrace();
                            if (e instanceof NGException && ((NGException) e).getMessage() != null)
                                throw new NGException(((NGException) e).getMessage());
                            if (null != progressor)
                                throw new NGException(getContext().getString(R.string.error_download_data));

                            jsonTruncated = true;
                            break;
                        }

                        if (null != progressor) {
                            if (progressor.isCanceled()) {
                                break;
                            }
                            final long elapsed = SystemClock.elapsedRealtime();
                            final boolean reportProgress =
                                    (featureCount % NGW_FILL_PROGRESS_FEATURE_STEP == 0)
                                            || (elapsed - lastProgressElapsedMs >= NGW_FILL_PROGRESS_MIN_INTERVAL_MS);
                            if (reportProgress) {
                                lastProgressElapsedMs = elapsed;
                                progressor.setValue(streamSize - in.available());
                                progressor.setMessage(
                                        getContext().getString(R.string.process_features) + ": " + featureCount);
                            }
                        }

                        ++featureCount;
                        if (featureCount % NGW_FILL_SQL_TX_BATCH == 0) {
                            dbTx.setTransactionSuccessful();
                            dbTx.endTransaction();
                            dbTx.beginTransaction();
                        }
                    }
                    if (!jsonTruncated) {
                        reader.endArray();
                    }
                    dbTx.setTransactionSuccessful();
                } finally {
                    if (dbTx.inTransaction()) {
                        dbTx.endTransaction();
                    }
                    endBulkImport();
                }
            } finally {
                reader.close();
            }

            mTracked = vectorLayerJSONObject.optBoolean(JSON_TRACKED_KEY);
            if (mDistrictFilterActive) {
                mTracked = false;
            }
            save();
            notifyLayerChanged();
            VectorLayerRenderCache.invalidateOnDataChange(this);

            if (Constants.DEBUG_MODE) {
                Log.d(Constants.TAG, "feature count: " + featureCount);
                Log.d(Constants.TAG, "createFromNGW fill wall ms: " + (SystemClock.elapsedRealtime() - fillStartMs));
            }

            List<String> missingCols = validateSqliteSchemaAgainstFields();
            if (!missingCols.isEmpty()) {
                String msg = "createFromNGW: SQLite table missing columns: " + missingCols
                        + " for layer \"" + getName() + "\"";
                HyperLog.w(Constants.TAG, msg);
                throw new NGException(msg);
            }

            boolean fillCanceled = progressor != null && progressor.isCanceled();
            if (!hasLocalDataTable()) {
                throw new NGException(getContext().getString(R.string.error_download_data));
            }
            if (!jsonTruncated && !fillCanceled && serverExpectedFeatureCount >= 0
                    && !mDistrictFilterActive) {
                int rows = getSqliteTableRowCount();
                if (rows >= 0 && rows != serverExpectedFeatureCount) {
                    String msg = getContext().getString(R.string.error_ngw_feature_count_mismatch,
                            rows, serverExpectedFeatureCount);
                    HyperLog.w(Constants.TAG, "createFromNGW: " + msg + " layer=\"" + getName() + "\"");
                    throw new NGException(msg);
                }
            }
        } finally {
            urlConnection.disconnect();
        }
    }

    /** Missing column or missing layer table — local storage must be refilled from the server. */
    private static boolean sqliteMessageNeedsLayerRefillFromServer(String msg) {
        if (msg == null) {
            return false;
        }
        String m = msg.toLowerCase(Locale.ROOT);
        return m.contains("has no column") || m.contains("no such table");
    }


    @Override
    public boolean applySoftConfigUpdate(com.nextgis.maplib.util.LayerConfigDiff diff) {
        boolean changed = super.applySoftConfigUpdate(diff);
        if (diff == null || diff.isMatch() || diff.isHard()) {
            return changed;
        }
        JSONObject cfg = diff.getServerConfig();
        if (diff.isSyncSettingsChanged()) {
            if (cfg.has(JSON_SYNC_TYPE_KEY)) {
                mSyncType = cfg.optInt(JSON_SYNC_TYPE_KEY, mSyncType);
                changed = true;
            }
            if (cfg.has(JSON_SYNC_DIRECTION_KEY)) {
                mSyncDirection = cfg.optInt(JSON_SYNC_DIRECTION_KEY, mSyncDirection);
                changed = true;
            }
            if (cfg.has(JSON_TRACKED_KEY)) {
                mTracked = cfg.optBoolean(JSON_TRACKED_KEY, mTracked);
                changed = true;
            }
            if (cfg.has(JSON_SERVERWHERE_KEY)) {
                mServerWhere = cfg.optString(JSON_SERVERWHERE_KEY, mServerWhere);
                changed = true;
            }
            if (changed) save();
        }
        return changed;
    }

    @Override
    public void create(
            int geometryType,
            List<Field> fields)
            throws SQLiteException
    {
        if (mNgwVersionMajor < Constants.NGW_v3 && geometryType < 4
                && mNGWLayerType == Connection.NGWResourceTypeVectorLayer) {
            // to multi
            geometryType += 3;
        }

        super.create(geometryType, fields);
        FeatureChanges.initialize(getChangeTableName());
        FeatureAttachments.initialize(getAttachmentsTableName());
    }


    @Override
    public void addChange(
            long featureId,
            int operation)
    {
        if (0 == (mSyncType & Constants.SYNC_DATA)) {
            return;
        }

        String changeTableName = getChangeTableName();
        boolean canAddChanges = true;

        // for delete operation
        if (operation == Constants.CHANGE_OPERATION_DELETE) {

            // if featureId == NOT_FOUND remove all changes for all features
            if (featureId == Constants.NOT_FOUND) {
                FeatureChanges.removeAllChanges(changeTableName);

                // if feature has changes then remove them for the feature
            } else if (FeatureChanges.isChanges(changeTableName, featureId)) {
                // if feature was new then just remove its changes
                canAddChanges = !FeatureChanges.isChanges(changeTableName, featureId,
                        Constants.CHANGE_OPERATION_NEW);
                FeatureChanges.removeChanges(changeTableName, featureId);
            }
        }

        // we are trying to re-create feature - warning
        if (operation == Constants.CHANGE_OPERATION_NEW && FeatureChanges.isChanges(
                changeTableName, featureId)) {
            Log.w(Constants.TAG, "Something wrong. Should nether get here");
            canAddChanges = false;
        }

        // if can then add change
        if (canAddChanges) {
            FeatureChanges.add(changeTableName, featureId, operation);
        }
    }


    @Override
    public void addChange(
            long featureId,
            long attachId,
            int attachOperation)
    {
        if (0 == (mSyncType & Constants.SYNC_ATTACH)) {
            return;
        }

        String changeTableName = getChangeTableName();
        boolean canAddChanges = true;

        // for delete operation
        if (attachOperation == Constants.CHANGE_OPERATION_DELETE) {

            // if attachId == NOT_FOUND remove all attach changes for the feature
            if (attachId == Constants.NOT_FOUND) {
                FeatureChanges.removeAllAttachChanges(changeTableName, featureId);

                // if attachment has changes then remove them for the attachment
            } else if (FeatureChanges.isAttachChanges(changeTableName, featureId, attachId)) {
                // if attachment was new then just remove its changes
                canAddChanges =
                        !FeatureChanges.isAttachChanges(changeTableName, featureId, attachId,
                                Constants.CHANGE_OPERATION_NEW);
                FeatureChanges.removeAttachChanges(changeTableName, featureId, attachId);
            }
        }

        // we are trying to re-create the attach - warning
        // TODO: replace to attachOperation == CHANGE_OPERATION_NEW ???
        if (0 != (attachOperation & Constants.CHANGE_OPERATION_NEW)
                && FeatureChanges.isAttachChanges(changeTableName, featureId, attachId)) {
            Log.w(Constants.TAG, "Something wrong. Should nether get here");
            canAddChanges = false;
        }

        if (canAddChanges) {
            FeatureChanges.add(changeTableName, featureId, attachId, attachOperation);
        }
    }


    protected void replaceUuidWithUrl(SyncResult syncResult) {
        AccountUtil.AccountData accountData;
        try {
            accountData = AccountUtil.getAccountData(mContext, mAccountName);
            String uuidPattern = "([a-z0-9]{8}-[a-z0-9]{4}-[a-z0-9]{4}-[a-z0-9]{4}-[a-z0-9]{12})";
            String url = accountData.url;
            Matcher uuidMatcher = Pattern.compile(uuidPattern).matcher(url);
            if (!uuidMatcher.find()) {
                return;
            }
            String uuid = uuidMatcher.group();
            HttpResponse response =
                    NetworkUtil.get(NGWUtil.getNgwVersionUrl(accountData.url), accountData.login, accountData.password, false);
            if (!response.isOk() && response.getResponseCode() == 404) {
                String gcUrl = NGWUtil.getNgwUrlResolverUrl(uuid);
                String newUrl = NGWUtil.getRealNgwUrlFromUuid(gcUrl).replace("\"", "");
                IGISApplication app = (IGISApplication) mContext.getApplicationContext();
                app.setUserData(mAccountName, "url", newUrl);
            }
        } catch (IllegalStateException e) {
            log(e, "replaceUuidWithUrl(): account is null " + e.getMessage());
            syncResult.stats.numAuthExceptions++;
        } catch (IOException e) {
            log(e, "replaceUuidWithUrl(): IOException: " + e.getMessage());
            SyncResultUtil.markConnectFailed(syncResult);
        }
    }


    /**
     * Synchronize changes with NGW. Should be run from non UI thread.
     *
     * @param authority
     *         - a content resolver authority (i.e. com.nextgis.mobile.provider)
     * @param syncResult
     *         - report some errors via this parameter
     */
    @Override
    public void sync(
            String authority,
            Pair<Integer, Integer> ver,
            SyncResult syncResult)
    {

        Log.d("SSYNC", "sync of " + getName());
        if (0 != (mSyncType & Constants.SYNC_NONE) || mFields == null) {
            if (0 != (mSyncType & Constants.SYNC_NONE) && mFields != null) {
                tryRefreshServerResourceMetaAndConfig();
            }
            if (Constants.DEBUG_MODE) {
                Log.d(Constants.TAG,
                        "Layer " + getName() + " is not checked to sync or not inited");
                HyperLog.v(Constants.TAG, "NGWVectorLayer: " + getName() + " sync type is SYNC_NONE");
            }
            HyperLog.v(Constants.TAG, "NGWVectorLayer: sync for " + getName() + "  - sync type is SYNC_NONE - exit");
            return;
        }

        // 1. check for old UUID URL and replace it
        replaceUuidWithUrl(syncResult);

        //ExistFeatureResult result = null;
//        if (isRemoteGetAllowed()) {
//            result = checkFeatureForExists(authority, syncResult, this);
//            if (result.result) {
//                return;
//            }
//        }

        // 2. get remote changes
        HyperLog.v(Constants.TAG, "NGWVectorLayer: " + getName() + " isRemoteGetAllowed is " + isRemoteGetAllowed());
        if (isRemoteGetAllowed())
            if (!getChangesFromServer(authority, syncResult)) {
                if (Constants.DEBUG_MODE) {
                    Log.d(Constants.TAG, "Get remote changes failed");
                }
                HyperLog.w(Constants.TAG, "NGWVectorLayer pull aborted layer=\""
                        + ProdLogUtil.truncateForLog(getName(), 100) + "\" res=" + mRemoteId
                        + " " + ProdLogUtil.formatSyncResultStats(syncResult));

                HyperLog.v(Constants.TAG, "NGWVectorLayer: " + getName() + " getChangesFromServer return null - EXIT" );

                return; // layer not exist - exits
            }

        //HyperLog.v(Constants.TAG, "NGWVectorLayer: " + getName() + " isRemoteReadOnly is " + isRemoteReadOnly());
        if (isRemoteReadOnly()) {
            HyperLog.v(Constants.TAG, "NGWVectorLayer: " + getName() + " isRemoteReadOnly is true - EXIT");
            return;
        }

        HyperLog.v(Constants.TAG, "NGWVectorLayer: " + getName() + " isRemoteSendAllowed is " + isRemoteSendAllowed());
        // 3. send current changes
        if (isRemoteSendAllowed())
            if (!sendLocalChanges(syncResult)) {
                HyperLog.v(Constants.TAG, "NGWVectorLayer: " + getName() + " sendLocalChanges failed - return false" );

                if (Constants.DEBUG_MODE) {
                    Log.d(Constants.TAG, "Set local changes failed");
                }
            }
    }

    private boolean isRemoteGetAllowed() {
        return (mSyncDirection & DIRECTION_FROM) != 0;
    }

    private boolean isRemoteSendAllowed() {
        return (mSyncDirection & DIRECTION_TO) != 0;
    }

    @Override
    public boolean isEditingAllowed() {
        boolean collectorManaged = mLayerOriginMetadata != null
                && mLayerOriginMetadata.isManagedByProject();
        return LayerEditingPolicy.isEditingAllowed(
                mIsEditable,
                mCollectorEditable,
                collectorManaged,
                isRemoteSendAllowed());
    }

    public int getSyncDirection() {
        return mSyncDirection;
    }

    public void setSyncDirection(int direction) {
        mSyncDirection = direction;
    }

    public boolean sendLocalChanges(SyncResult syncResult)
    {
        HyperLog.v(Constants.TAG, "NGWVectorLayer: " + getName() + " sendLocalChanges START" );

        String changeTableName = getChangeTableName();
        long changesCount = FeatureChanges.getChangeCount(changeTableName);
        if (Constants.DEBUG_MODE) {
            Log.d(Constants.TAG, "sendLocalChanges: " + changesCount);
        }

        HyperLog.v(Constants.TAG, "NGWVectorLayer: " + getName() + " sendLocalChanges changesCount " + changesCount);
        if (0 == changesCount) {
            HyperLog.v(Constants.TAG, "NGWVectorLayer: " + getName() + " sendLocalChanges 0 - EXIT" );
            return true;
        }

        boolean isError = false;

        try {
            // get column's IDs, there is at least one entry
            Cursor changeCursor = FeatureChanges.getFirstChangeFromRecordId(changeTableName, 0);
            changeCursor.moveToFirst();

            int recordIdColumn = changeCursor.getColumnIndex(Constants.FIELD_ID);
            int featureIdColumn = changeCursor.getColumnIndex(Constants.FIELD_FEATURE_ID);
            int operationColumn = changeCursor.getColumnIndex(Constants.FIELD_OPERATION);
            int attachIdColumn = changeCursor.getColumnIndex(Constants.FIELD_ATTACH_ID);
            int attachOperationColumn =
                    changeCursor.getColumnIndex(Constants.FIELD_ATTACH_OPERATION);

            long nextChangeRecordId = changeCursor.getLong(recordIdColumn);

            changeCursor.close();

            final AccountUtil.AccountData accountData = AccountUtil.getAccountData(mContext, mAccountName);
            while (true) {

                changeCursor = FeatureChanges.getFirstChangeFromRecordId(changeTableName,
                        nextChangeRecordId);

                if (null == changeCursor) {
                    break;
                }

                if (!changeCursor.moveToFirst()) {
                    // no more change records
                    changeCursor.close();
                    break;
                }

                long changeRecordId = changeCursor.getLong(recordIdColumn);
                nextChangeRecordId = changeRecordId + 1;

                long changeFeatureId = changeCursor.getLong(featureIdColumn);
                int changeOperation = changeCursor.getInt(operationColumn);
                long changeAttachId = changeCursor.getLong(attachIdColumn);
                int changeAttachOperation = changeCursor.getInt(attachOperationColumn);

                changeCursor.close();

                long lastChangeRecordId = FeatureChanges.getLastChangeRecordId(changeTableName);

                if (0 == (changeOperation & Constants.CHANGE_OPERATION_ATTACH)) {

                    if (0 != (changeOperation & Constants.CHANGE_OPERATION_DELETE)) {
                        if (deleteFeatureOnServer(changeFeatureId, syncResult)) {
                            FeatureChanges.removeChangeRecord(changeTableName, changeRecordId);
                        } else {
                            isError = true;
                            if (Constants.DEBUG_MODE) {
                                Log.d(Constants.TAG, "proceed deleteFeatureOnServer() failed");
                            }
                        }

                    } else if (0 != (changeOperation & Constants.CHANGE_OPERATION_NEW)) {
                        HyperLog.v(Constants.TAG, "NGWVectorLayer: feature add start featureID = "  + changeFeatureId );

                        FeaturePushResult pushResult =
                                addFeatureOnServer(changeFeatureId, syncResult, accountData);
                        if (pushResult.success) {
                            FeatureChanges.removeChangeRecord(changeTableName, changeRecordId);
                            FeatureChanges.removeChangesToLast(changeTableName, changeFeatureId,
                                    Constants.CHANGE_OPERATION_CHANGED, lastChangeRecordId);
                            refreshFeatureFromServerAfterPush(
                                    pushResult.remoteFeatureId, accountData, "addFeature");
                        } else {
                            HyperLog.v(Constants.TAG, "NGWVectorLayer: feature add FAILED featureID = "  + changeFeatureId );

                            isError = true;
                            if (Constants.DEBUG_MODE) {
                                Log.d(Constants.TAG, "proceed addFeatureOnServer() failed");
                            }
                        }

                    } else if (0 != (changeOperation & Constants.CHANGE_OPERATION_CHANGED)) {
                        HyperLog.v(Constants.TAG, "NGWVectorLayer: feature change start featureID = "  + changeFeatureId );

                        if (changeFeatureOnServer(changeFeatureId, syncResult, accountData)) {
                            FeatureChanges.removeChangeRecord(changeTableName, changeRecordId);
                            FeatureChanges.removeChangesToLast(changeTableName, changeFeatureId,
                                    Constants.CHANGE_OPERATION_CHANGED, lastChangeRecordId);
                            refreshFeatureFromServerAfterPush(
                                    changeFeatureId, accountData, "changeFeature");
                        } else {
                            HyperLog.v(Constants.TAG, "NGWVectorLayer: feature change FAILED featureID = "  + changeFeatureId );

                            isError = true;
                            if (Constants.DEBUG_MODE) {
                                Log.d(Constants.TAG, "proceed changeFeatureOnServer() failed");
                            }
                        }
                    }
                }

                //process attachments
                else { // 0 != (changeOperation & CHANGE_OPERATION_ATTACH)

                    if (changeAttachOperation == Constants.CHANGE_OPERATION_DELETE) {
                        HyperLog.v(Constants.TAG, "NGWVectorLayer: changeAttacheDelete start");

                        if (deleteAttachOnServer(changeFeatureId, changeAttachId, syncResult)) {
                            FeatureChanges.removeChangeRecord(changeTableName, changeRecordId);
                        } else {
                            HyperLog.v(Constants.TAG, "NGWVectorLayer: changeAttacheDelete FAILED");
                            isError = true;
                            if (Constants.DEBUG_MODE) {
                                Log.d(Constants.TAG, "proceed deleteAttachOnServer() failed");
                            }
                        }

                    } else if (changeAttachOperation == Constants.CHANGE_OPERATION_NEW) {
                        HyperLog.v(Constants.TAG, "NGWVectorLayer: changeAttachNew start with Fid =" + changeFeatureId + " attachId= "+ changeAttachId);

                        if (sendAttachOnServer(changeFeatureId, changeAttachId, true, syncResult)) {

                            FeatureChanges.removeChangeRecord(changeTableName, changeRecordId);
                            FeatureChanges.removeAttachChangesToLast(changeTableName,
                                    changeFeatureId, changeAttachId,
                                    Constants.CHANGE_OPERATION_CHANGED, lastChangeRecordId);
                        } else {
                            HyperLog.v(Constants.TAG, "NGWVectorLayer: changeAttachNew FAILED");

                            isError = true;
                            if (Constants.DEBUG_MODE) {
                                Log.d(Constants.TAG, "proceed sendAttachOnServer() failed");
                            }
                        }

                    } else if (changeAttachOperation == Constants.CHANGE_OPERATION_CHANGED) {
                        HyperLog.v(Constants.TAG, "NGWVectorLayer: changeAttachChange start with Fid =" + changeFeatureId + " attachId= "+ changeAttachId);

                        if (changeAttachOnServer(changeFeatureId, changeAttachId, syncResult)) {
                            FeatureChanges.removeAttachChangesToLast(changeTableName,
                                    changeFeatureId, changeAttachId,
                                    Constants.CHANGE_OPERATION_CHANGED, lastChangeRecordId);
                        } else {
                            HyperLog.v(Constants.TAG, "NGWVectorLayer: changeAttachChange FAILED");
                            isError = true;
                            if (Constants.DEBUG_MODE) {
                                Log.d(Constants.TAG, "proceed changeAttachOnServer() failed");
                            }
                        }
                    }
                }
            }

            // check records count changing
            if (changesCount != FeatureChanges.getChangeCount(changeTableName)) {
//                mCache.save(new File(mPath, RTREE));  // useless due to save in notifyUpdate
//                if (DEBUG_MODE)
//                    Log.d(Constants.TAG, "mCache: saving sendLocalChanges");
                //notify to reload changes
                getContext().sendBroadcast(
                        new Intent(SyncAdapter.SYNC_CHANGES)
                                .setPackage(getContext().getPackageName())
                );
            }

        } catch (SQLiteException e) {
            HyperLog.v(Constants.TAG, "NGWVectorLayer: " + getName() + " SQLiteException " + e.getMessage());
            isError = true;
            syncResult.stats.numConflictDetectedExceptions++;
            if (Constants.DEBUG_MODE) {
                Log.d(Constants.TAG, "proceed sendLocalChanges() failed");
            }
            e.printStackTrace();
        }

        if (isError) {
            HyperLog.w(Constants.TAG, "NGW sendLocalChanges incomplete layer=\""
                    + ProdLogUtil.truncateForLog(getName(), 100) + "\" res=" + mRemoteId
                    + " pendingChanges=" + FeatureChanges.getChangeCount(changeTableName));
        }

        return !isError;
    }


    private boolean changeAttachOnServer(
            long featureId,
            long attachId,
            SyncResult syncResult)
    {
        if (!mNet.isNetworkAvailable()) {
            HyperLog.v(Constants.TAG, "NGWVectorLayer: changeAttachOnServer !mNet.isNetworkAvailable()");
            SyncResultUtil.markNetworkUnavailable(syncResult);
            return false;
        }

        AttachItem attach = getAttach("" + featureId, "" + attachId);
        if (null == attach) {   // just remove buggy item
            return true;
        }

        try {
            JSONObject putData = new JSONObject();
            //putData.put(JSON_ID_KEY, attach.getAttachId());
            putData.put(Constants.JSON_NAME_KEY, attach.getDisplayName());
            //putData.put("mime_type", attach.getMimetype());
            putData.put("description", attach.getDescription());

            HttpResponse response = changeAttachOnServer(featureId, attachId, putData.toString());

            if (!response.isOk()) {
                reportSyncHttpFailure("changeAttach", featureId, attachId, syncResult, response);
                return false;
            }

            return true;
        } catch (JSONException e) {
            HyperLog.v(Constants.TAG, "NGWVectorLayer: JSONException " + e.getMessage());
            log(e, "changeAttachOnServer JSONException");
            syncResult.stats.numParseExceptions++;
            return false;
        } catch (IOException e) {
            HyperLog.v(Constants.TAG, "NGWVectorLayer: IOException " + e.getMessage());
            log(e, "changeAttachOnServer IOException");
            SyncResultUtil.markConnectFailed(syncResult);
            syncResult.stats.numUpdates++;
            return false;
        } catch (IllegalStateException e) {
            HyperLog.v(Constants.TAG, "NGWVectorLayer: IllegalStateException " + e.getMessage());
            log(e, "changeAttachOnServer IllegalStateException");
            syncResult.stats.numAuthExceptions++;
            return false;
        }
    }


    protected HttpResponse changeAttachOnServer(long featureId, long attachId, String putData) throws IOException {
        AccountUtil.AccountData accountData = AccountUtil.getAccountData(mContext, mAccountName);
        String url = NGWUtil.getFeatureAttachmentUrl(accountData.url, mRemoteId, featureId) + attachId;
        return NetworkUtil.put(url, putData, accountData.login,
                accountData.password, false);
    }

    private boolean deleteAttachOnServer(
            long featureId,
            long attachId,
            SyncResult syncResult)
    {
        if (!mNet.isNetworkAvailable()) {
            SyncResultUtil.markNetworkUnavailable(syncResult);
            return false;
        }

        try {
            HttpResponse response = deleteAttachOnServer(featureId, attachId);

            if (!response.isOk()) {
                reportSyncHttpFailure("deleteAttach", featureId, attachId, syncResult, response);
                return false;
            }

            return true;
        } catch (IOException e) {
            HyperLog.v(Constants.TAG, "deleteAttachOnServer IOException: " + e.getMessage());
            log(e, "deleteAttachOnServer IOException");
            SyncResultUtil.markConnectFailed(syncResult);
            syncResult.stats.numDeletes++;
            return false;
        } catch (IllegalStateException e) {
            HyperLog.v(Constants.TAG, "deleteAttachOnServer IllegalStateException: " + e.getMessage());
            log(e, "deleteAttachOnServer IllegalStateException");
            syncResult.stats.numAuthExceptions++;
            return false;
        }
    }

    protected HttpResponse deleteAttachOnServer(long featureId, long attachId) throws IOException {
        AccountUtil.AccountData accountData = AccountUtil.getAccountData(mContext, mAccountName);

        return NetworkUtil.delete(NGWUtil.getFeatureAttachmentUrl(accountData.url, mRemoteId, featureId)
                + attachId, accountData.login, accountData.password, false);
    }


    protected boolean sendAttachOnServer(
            long featureId,
            long attachId,
            boolean useTus,
            SyncResult syncResult)
    {
        if (!mNet.isNetworkAvailable()) {
            SyncResultUtil.markNetworkUnavailable(syncResult);
            return false;
        }

        AttachItem attach = getAttach("" + featureId, "" + attachId);
        if (null == attach) {   //just remove buggy item
            return true;
        }
        boolean fisrtSendPhase = true;

        try {
            HttpResponse response;
            JSONObject result;
            if (useTus) {

                response = sendAttachOnServerViaTus(featureId, attach);
                if (!response.isOk()) {
                    reportSyncHttpFailure("sendAttachTus", featureId, attachId, syncResult, response);
                    return false;
                }
                fisrtSendPhase = false;

                result = new JSONObject(response.getResponseBody());
                if (!proceedAttachFromTus(result, syncResult)) {
                    return false;
                }
            } else {
                fisrtSendPhase = false;
                response = sendAttachOnServerOldStyle(featureId, attach);

                if (!response.isOk()) {
                    reportSyncHttpFailure("sendAttachOld", featureId, attachId, syncResult, response);
                    return false;
                }
                result = new JSONObject(response.getResponseBody());

                if (!proceedAttachOldStyle(result, syncResult)) {
                    return false;
                }
                result = (JSONObject) result.getJSONArray("upload_meta").get(0);

            }

            response = sendFeatureAttachOnServer(result, featureId, attach);
            if (!response.isOk()) {
                reportSyncHttpFailure("sendFeatureAttach", featureId, attachId, syncResult, response);
                return false;
            }

            // set new local id for attach
            result = new JSONObject(response.getResponseBody());
                if (!result.has(Constants.JSON_ID_KEY)) {
                    HyperLog.w(Constants.TAG, "sendAttach missing id in response layer=\""
                            + ProdLogUtil.truncateForLog(getName(), 100) + "\" res=" + mRemoteId
                            + " json=\"" + ProdLogUtil.truncateForLog(result.toString(), 500) + "\"");

                    if (Constants.DEBUG_MODE) {
                        Log.d(Constants.TAG, "Problem sendAttachOnServer(), result has not ID key, result: " + result.toString());
                    }
                syncResult.stats.numParseExceptions++;
                return false;
            }

            // need delete attach locally ?
            FeatureChanges.removeAttachChanges(getChangeTableName(), featureId, attachId);
            deleteAttach(String.valueOf(featureId), String.valueOf(attachId));
//            long newAttachId = result.getLong(Constants.JSON_ID_KEY);
//            setNewAttachId("" + featureId, attach, "" + newAttachId);
            // now sended attach deleted from device - it becomes online attach

            return true;
        } catch (IOException e) {
            if (fisrtSendPhase && e instanceof SocketException ){ // try usual way to send attaach
                HyperLog.v(Constants.TAG, "NGWVectorLayer: sendAttachOnServer IOException : SocketException " + e.getMessage());
                HyperLog.v(Constants.TAG, "NGWVectorLayer: try to send not using TUS ");
                return sendAttachOnServer(featureId,attachId, false,syncResult);
            }
            HyperLog.v(Constants.TAG, "NGWVectorLayer: sendAttachOnServer IOException " + e.getMessage());
            log(e, "sendAttachOnServer IOException");
            SyncResultUtil.markConnectFailed(syncResult);
            syncResult.stats.numInserts++;
            return false;
        }  catch (JSONException e) {
            HyperLog.v(Constants.TAG, "NGWVectorLayer: sendAttachOnServer JSONException " + e.getMessage());
            log(e, "sendAttachOnServer JSONException");
            syncResult.stats.numParseExceptions++;
            return false;
        } catch (IllegalStateException e) {
            HyperLog.v(Constants.TAG, "NGWVectorLayer: sendAttachOnServer IllegalStateException " + e.getMessage());

            log(e, "sendAttachOnServer IllegalStateException");
            syncResult.stats.numAuthExceptions++;
            return false;
        }
    }


    protected boolean proceedAttachFromTus(JSONObject result, SyncResult syncResult) throws JSONException {
        // get attach info // old json  answer
        //        if (!result.has("upload_meta")) {
        //            if (Constants.DEBUG_MODE) {
        //                Log.d(Constants.TAG, "Problem sendAttachOnServer(), result has not upload_meta, result: " + result.toString());
        //            }
        //            syncResult.stats.numParseExceptions++;
        //            return false;
        //        }

        if (!(result.has("id" ) || result.has("size") || result.has("mime_type") )){
            if (Constants.DEBUG_MODE) {
                Log.d(Constants.TAG, "Problem sendAttachOnServer(), result upload_meta length() == 0");
            }
            HyperLog.v(Constants.TAG, "NGWVectorLayer: sendAttachOnServer  proceedAttach FAILED with result " + result.toString());
            syncResult.stats.numParseExceptions++;
            return false;
        }

        //old json  answer
        //        JSONArray uploadMetaArray = result.getJSONArray("upload_meta");
        //        if (uploadMetaArray.length() == 0) {
        //            if (Constants.DEBUG_MODE) {
        //                Log.d(Constants.TAG, "Problem sendAttachOnServer(), result upload_meta length() == 0");
        //            }
        //            syncResult.stats.numParseExceptions++;
        //            return false;
        //        }
        return true;
    }


    protected boolean proceedAttachOldStyle(JSONObject result, SyncResult syncResult) throws JSONException {
        // get attach info
        if (!result.has("upload_meta")) {
            if (Constants.DEBUG_MODE) {
                Log.d(Constants.TAG, "Problem sendAttachOnServer(), result has not upload_meta, result: " + result.toString());
            }
            syncResult.stats.numParseExceptions++;
            return false;
        }

        JSONArray uploadMetaArray = result.getJSONArray("upload_meta");
        if (uploadMetaArray.length() == 0) {
            if (Constants.DEBUG_MODE) {
                Log.d(Constants.TAG, "Problem sendAttachOnServer(), result upload_meta length() == 0");
            }
            syncResult.stats.numParseExceptions++;
            return false;
        }

        return true;

    }

    protected HttpResponse sendFeatureAttachOnServer(JSONObject result, long featureId, AttachItem attach) throws JSONException, IOException {

        // add attachment to row
        JSONObject postJsonData = new JSONObject();
        //JSONArray uploadMetaArray = result.getJSONArray("upload_meta");
        //postJsonData.put("file_upload", uploadMetaArray.get(0));
        postJsonData.put("file_upload", result);
        postJsonData.put("description", attach.getDescription());
        postJsonData.put("name",attach.getDisplayName() ); //  result.has("name") ? result.getString("name") : "");
        String postload = postJsonData.toString();
        if (Constants.DEBUG_MODE) {
            Log.d(Constants.TAG, "postload: " + postload);
        }

        // get account data
        AccountUtil.AccountData accountData = AccountUtil.getAccountData(mContext, mAccountName);

        // upload file
        String url = NGWUtil.getFeatureAttachmentUrl(accountData.url, mRemoteId, featureId);

        HyperLog.v(Constants.TAG, "sendFeatureAttachOnServer start url = " + url + " data" + postload);

        // update record in NGW
        return NetworkUtil.post(url, postload, accountData.login, accountData.password, false);
    }

    protected HttpResponse sendAttachOnServerViaTus(long featureId, AttachItem attach) throws IOException {
        // fill attach info
        String fileName = attach.getDisplayName();
        File filePath = new File(mPath, featureId + File.separator + attach.getAttachId());
        long length = 0;
        if (filePath.exists())
            length = filePath.length();

        String fileMime = attach.getMimetype();

        // get account data
        AccountUtil.AccountData accountData = AccountUtil.getAccountData(mContext, mAccountName);

        // upload file
        String url = NGWUtil.getFileUploadUrlViaTus(accountData.url);

        HyperLog.v(Constants.TAG, "sendAttachOnServer start url = " + url + " filename = "+ fileName + " filepath=" + filePath);

        return NetworkUtil.postFileViaTus(url, fileName, filePath, length, fileMime, accountData.login, accountData.password, false);
    }


    protected HttpResponse sendAttachOnServerOldStyle(long featureId, AttachItem attach) throws IOException {
        // fill attach info
        String fileName = attach.getDisplayName();
        File filePath = new File(mPath, featureId + File.separator + attach.getAttachId());
        long length = 0;
        if (filePath.exists())
            length = filePath.length();

        String fileMime = attach.getMimetype();

        // get account data
        AccountUtil.AccountData accountData = AccountUtil.getAccountData(mContext, mAccountName);

        // upload file
        String url = NGWUtil.getFileUploadUrlOld(accountData.url);

        HyperLog.v(Constants.TAG, "sendAttachOnServer start url = " + url + " filename = "+ fileName + " filepath=" + filePath);

        HyperLog.v(Constants.TAG, "NGWVectorLayer: start sent attach to " + url);

        return NetworkUtil.postFileOld(url, fileName, filePath, fileMime, accountData.login, accountData.password, false);
    }

    /**
     * Classifies HTTP failure into {@link SyncResult} counters and writes one bounded diagnostic line.
     */
    protected void reportSyncHttpFailure(
            String operation,
            long featureId,
            long attachId,
            SyncResult syncResult,
            HttpResponse response) {
        if (response != null) {
            log(syncResult, String.valueOf(response.getResponseCode()));
        }
        HyperLog.w(Constants.TAG, ProdLogUtil.ngwHttpFailure(operation, getName(), mRemoteId, featureId, attachId,
                response));
    }

    protected void log(SyncResult syncResult, String code) {
        int responseCode;
        try {
            responseCode = Integer.parseInt(code);
        } catch (NumberFormatException e) {
            HyperLog.w(Constants.TAG, "NGWVectorLayer bad HTTP token layer=\""
                    + ProdLogUtil.truncateForLog(getName(), 100) + "\" res=" + mRemoteId
                    + " token=\"" + ProdLogUtil.truncateForLog(code, 48) + "\"");
            syncResult.stats.numIoExceptions++;
            syncResult.stats.numEntries++;
            return;
        }
        switch (responseCode) {
            case HttpURLConnection.HTTP_UNAUTHORIZED:
            case HttpURLConnection.HTTP_FORBIDDEN:
                syncResult.stats.numAuthExceptions++;
                break;
            case 1:
                syncResult.stats.numParseExceptions++;
                break;
            case 0:
                SyncResultUtil.markConnectFailed(syncResult);
                break;
            case HttpURLConnection.HTTP_NOT_FOUND:
            case HttpURLConnection.HTTP_INTERNAL_ERROR:
                syncResult.stats.numIoExceptions++;
                syncResult.stats.numEntries++;
                break;
            default:
                syncResult.stats.numIoExceptions++;
                syncResult.stats.numEntries++;
                break;
        }
    }


    protected void log(Exception e, String tag) {
        e.printStackTrace();
        HyperLog.w(Constants.TAG, tag + ": " + e.getClass().getSimpleName()
                + (e.getMessage() != null ? " " + ProdLogUtil.truncateForLog(e.getMessage(), 360) : ""));
        if (Constants.DEBUG_MODE) {
            String error = e.getLocalizedMessage() == null ? tag + ": Exception" : e.getLocalizedMessage();
            Log.d(Constants.TAG, error);
        }
    }


    protected void changeFeatureId(
            long oldFeatureId,
            long newFeatureId)
    {
        if (oldFeatureId == newFeatureId) {
//            Log.e("FEA", "changeFeatureId equals  - exit");

            return;
        }

        MapContentProviderHelper map = (MapContentProviderHelper) MapBase.getInstance();
        if (null == map) {
            throw new IllegalArgumentException(
                    "The map should extends MapContentProviderHelper or inherited");
        }
        //update id in DB
        if (Constants.DEBUG_MODE) {
            Log.d(Constants.TAG, "old id: " + oldFeatureId + " new id: " + newFeatureId);
        }
        SQLiteDatabase db = map.getDatabase(false);
        ContentValues values = new ContentValues();
        values.put(Constants.FIELD_ID, newFeatureId);
        if (db.update(mPath.getName(), values, Constants.FIELD_ID + " = " + oldFeatureId, null)
                != 1) {
            Log.w(Constants.TAG, "failed to set new id");
//            Log.e("FEA", "changeFeatureId failed!!!");

        }

        //update id in cache
        Intent notify = new Intent(Constants.NOTIFY_UPDATE);
        notify.putExtra(Constants.FIELD_OLD_ID, oldFeatureId);
        notify.putExtra(Constants.FIELD_ID, newFeatureId);
        notify.putExtra(Constants.ATTRIBUTES_ONLY, true);
        notify.putExtra(Constants.NOTIFY_LAYER_NAME, mPath.getName());
        notify.setPackage(getContext().getPackageName());
        getContext().sendBroadcast(notify);

        //rename photo id folder if exist
        File photoFolder = new File(mPath, "" + oldFeatureId);
        if (photoFolder.exists()) {
            if (photoFolder.renameTo(new File(mPath, "" + newFeatureId))) {

                int chRes = FeatureChanges.changeFeatureIdForAttaches(getChangeTableName(),
                        oldFeatureId, newFeatureId);
                if (chRes <= 0) {
                    if (Constants.DEBUG_MODE) {
                        Log.d(Constants.TAG,
                                "Feature ID for attaches not changed, oldFeatureId: " + oldFeatureId
                                        + ", newFeatureId: " + newFeatureId);
                    }
                }

            } else {
                if (Constants.DEBUG_MODE) {
                    Log.d(Constants.TAG, "rename photo folder " + oldFeatureId + "failed");
                }
            }
        }
    }

    // true - 404 was and proceed, false - no 404 happen
    public void clearLayerSync(final NGWVectorLayer ngwVectorLayer) {

        // 404 response  on get feature - reature not on server (deleted)
        // need to turn off sync,
        ngwVectorLayer.setSyncType(Constants.SYNC_NONE);
        VectorLayerRenderCache.invalidateOnDataChange(ngwVectorLayer);
        ngwVectorLayer.toVectorLayer(ngwVectorLayer.getUniqId());


        String message = String.format(getContext().getString(R.string.warning_layer_not_exist),
                ngwVectorLayer.getName());
        String title = getContext().getString(R.string.warning_layer_not_exist_title);

        Intent msg = new Intent(MESSAGE_ALERT_INTENT);
        msg.putExtra(MESSAGE_EXTRA, message);
        msg.putExtra(MESSAGE_TITLE_EXTRA, title);
        msg.setPackage(getContext().getPackageName());
        getContext().sendBroadcast(msg);
        com.nextgis.maplib.datasource.ngw.SyncAdapter.showNotify(getContext(), message, title);
        return;
    }

    private enum ConfigRefreshOutcome {
        CONTINUE_TO_FEATURES,
        FINISH_LAYERSYNC_OK,
        /** Device has no network; pull cannot run. */
        NETWORK_UNAVAILABLE,
    }

    /**
     * Fetches NGW resource metadata and reconciles server description (style/config) with local
     * state. Used from full data sync and from SYNC_NONE paths so server-side description updates
     * are always applied.
     */
    private ConfigRefreshOutcome tryRefreshServerResourceMetaAndConfig() {
        if (!mNet.isNetworkAvailable()) {
            HyperLog.v(Constants.TAG, "NGWVectorLayer: " + getName() + " network is unavailable -stop getChangesFromServer");
            return ConfigRefreshOutcome.NETWORK_UNAVAILABLE;
        }

        if (Constants.DEBUG_MODE) {
            Log.d(Constants.TAG, "The network is available. Get changes from server");
        }

        if (!hasLocalDataTable()) {
            HyperLog.w(Constants.TAG, "NGWVectorLayer: " + getName()
                    + " — SQLite data table missing, scheduling full refill from server");
            try {
                ((IGISApplication) mContext.getApplicationContext())
                        .scheduleNgwLayerRebuildAfterSchemaMismatch(this);
            } catch (Exception rebuildEx) {
                HyperLog.w(Constants.TAG, "NGWVectorLayer: refill scheduling failed", rebuildEx);
            }
            return ConfigRefreshOutcome.FINISH_LAYERSYNC_OK;
        }

        try {
            AccountUtil.AccountData accountDataForSchema = AccountUtil.getAccountData(mContext, mAccountName);
            if (accountDataForSchema.url != null) {
                JSONObject resourceMeta = NGWLayerSchemaCompat.fetchResourceMetaJson(
                        getResourceMetaUrl(accountDataForSchema),
                        accountDataForSchema.login,
                        accountDataForSchema.password);
                if (resourceMeta != null
                        && !NGWLayerSchemaCompat.localSchemaMatchesServerMeta(
                                this,
                                resourceMeta,
                                mNgwVersionMajor,
                                getRequiredCls())) {
                    HyperLog.v(Constants.TAG, "NGWVectorLayer: " + getName()
                            + " server schema mismatch — scheduling layer rebuild");
                    ((IGISApplication) mContext.getApplicationContext())
                            .scheduleNgwLayerRebuildAfterSchemaMismatch(this);
                    return ConfigRefreshOutcome.FINISH_LAYERSYNC_OK;
                }

                if (resourceMeta != null) {
                    try {
                        String descriptionRaw = LayerConfigUtil.extractNgwResourceDescriptionJson(resourceMeta);
                        if (descriptionRaw != null && !descriptionRaw.trim().isEmpty()) {
                            String descHash = LayerConfigUtil.md5(descriptionRaw.trim());
                            String lastHash = getPreferences().getString(
                                    SettingsConstants.KEY_PREF_LAST_CONFIG_HASH, "");
                            if (descHash.equals(lastHash)) {
                                HyperLog.v(Constants.TAG, "NGWVectorLayer: " + getName()
                                        + " config description unchanged (hash match), skipping");
                            } else if (lastHash.isEmpty()) {
                                HyperLog.v(Constants.TAG, "NGWVectorLayer: " + getName()
                                        + " no previous config hash stored, saving current hash (skip comparison)");
                                getPreferences().edit()
                                        .putString(SettingsConstants.KEY_PREF_LAST_CONFIG_HASH, descHash)
                                        .apply();
                            } else {
                                JSONObject serverCfg = LayerConfigUtil.parseLayerConfigObject(descriptionRaw);
                                LayerConfigDiff configDiff = LayerConfigDiff.compare(serverCfg, this);
                                if (configDiff.isHard()) {
                                    HyperLog.v(Constants.TAG, "NGWVectorLayer: " + getName()
                                            + " config hard mismatch: " + configDiff.getHardReason()
                                            + " — scheduling rebuild");
                                    ((IGISApplication) mContext.getApplicationContext())
                                            .scheduleNgwLayerRebuildAfterSchemaMismatch(this);
                                    return ConfigRefreshOutcome.FINISH_LAYERSYNC_OK;
                                }
                                boolean softUpdateIncomplete = false;
                                if (configDiff.isSoftOnly()) {
                                    HyperLog.v(Constants.TAG, "NGWVectorLayer: " + getName()
                                            + " applying soft config update: " + configDiff);
                                    applySoftConfigUpdate(configDiff);
                                    softUpdateIncomplete = wasLastSoftConfigUpdateIncomplete();
                                }
                                if (softUpdateIncomplete) {
                                    // A schema change (e.g. ALTER TABLE) failed; keep the old hash so
                                    // the update is retried next sync instead of being marked applied.
                                    HyperLog.w(Constants.TAG, "NGWVectorLayer: " + getName()
                                            + " soft config update incomplete - not advancing config hash");
                                } else {
                                    getPreferences().edit()
                                            .putString(SettingsConstants.KEY_PREF_LAST_CONFIG_HASH, descHash)
                                            .apply();
                                }
                            }
                        }
                    } catch (JSONException cfgEx) {
                        HyperLog.w(Constants.TAG, "NGWVectorLayer: " + getName()
                                + " config parse failed (ignored): " + cfgEx.getMessage());
                    }
                }
            }
        } catch (IllegalStateException ignored) {
            // account missing; getFeatures will report auth if needed
        }

        return ConfigRefreshOutcome.CONTINUE_TO_FEATURES;
    }

    /**
     * Reconciles NGW resource meta and description when vector data sync is off ({@code SYNC_NONE}).
     * Invoked from {@link com.nextgis.maplib.datasource.ngw.SyncAdapter} for layers excluded
     * from the main sync list.
     *
     * @param authority content resolver authority (for parity with {@link #sync})
     * @param syncResult  sync result object (unchanged; reserved for error reporting)
     */
    @SuppressWarnings("unused")
    public void syncNgwResourceConfigOnly(String authority, SyncResult syncResult) {
        tryRefreshServerResourceMetaAndConfig();
    }

    public boolean getChangesFromServer(
            String authority,
            SyncResult syncResult)
    {
        Log.d("SSYNC", "getChangesFromServer " + getName());

        int countChanges = 0;
        int createNewFeatureCount = 0;
        mLoggedCreateInsertSqlError = false;
        HyperLog.v(Constants.TAG, "NGWVectorLayer: getChangesFromServer " + getName());
        ConfigRefreshOutcome refreshOutcome = tryRefreshServerResourceMetaAndConfig();
        if (refreshOutcome == ConfigRefreshOutcome.NETWORK_UNAVAILABLE) {
            SyncResultUtil.markNetworkUnavailable(syncResult);
            return true;
        }
        if (refreshOutcome == ConfigRefreshOutcome.FINISH_LAYERSYNC_OK) {
            return true;
        }

        applyDistrictFilterFromProjectGroup();

        HyperLog.d(Constants.TAG, LOG_DISTRICT_FILTER + " sync layer=\"" + getName() + "\" remoteId=" + mRemoteId
                + (mDistrictFilterActive ? " filtered serverWhere=" + mServerWhere : " no district filter"));

        List<Feature> features, added = new ArrayList<>(), deleted =  new ArrayList<>(), changed =  new ArrayList<>();
        List<Long> deleteItems = new ArrayList<>();

        ExistFeatureResult result =  getFeatures(syncResult, mTracked);
        switch (NgwPullDecision.decide(result)) {
            case ABORT_404:
                HyperLog.v(Constants.TAG, "NGWVectorLayer: " + getName() + " 404 from getFeatures - stop getChangesFromServer");
                clearLayerSync(this);
                return false;
            case ABORT_FAILED:
                // Pull failed (result null, or I/O / parse / OOM / connect): getFeatures already bumped
                // syncResult.stats. Treating this as success would push local changes + advance the
                // tracked timestamp as if the pull succeeded, permanently skipping server deltas.
                HyperLog.w(Constants.TAG, "NGWVectorLayer: " + getName()
                        + " getFeatures failed (code=" + (result == null ? "null" : result.code)
                        + ") - abort sync for this layer " + ProdLogUtil.formatSyncResultStats(syncResult));
                return false;
            case EMPTY_OK:
                return true;
            case PROCEED:
            default:
                break;
        }
        if (! (result.object instanceof  HashMap)){
            return true;
        }
        HashMap<Integer, List<Feature>> tracked = (HashMap<Integer, List<Feature>>)result.object;


        if (tracked == null) {
            HyperLog.v(Constants.TAG, "NGWVectorLayer: " + getName() + " tracked = null");
            return true;
        }

        if (mTracked) {
            added = tracked.get(0);
            changed = tracked.get(1);
            deleted = tracked.get(2);
            features = new ArrayList<>();
            if(null != added)
                features.addAll(added);
            if(null != changed)
                features.addAll(changed);
            if(null != deleted)
                features.addAll(deleted);

            if (Constants.DEBUG_MODE) {
                Log.d(TAG, "Layer " + mName + " is tracked for history");
                Log.d(Constants.TAG, "added: " + added.size() + " | changed: " + changed.size() + " | deleted: " + deleted.size());
            }
        } else
            features = tracked.get(0);

        if (features == null) {
            HyperLog.v(Constants.TAG, "NGWVectorLayer: " + getName() + " features = null");
            return true;
        }

        if (Constants.DEBUG_MODE) {
            Log.d(Constants.TAG, "Got " + features.size() + " feature(s) from server");
        }

        try {
            if (!mCacheLoaded) {
                reloadCache();
            }

            String changeTableName = getChangeTableName();
            HashSet<Long> remoteIdSet = null;
            if (mTracked) {
                proceedAddedFeatures(added, authority, changeTableName);
                proceedChangedFeatures(changed, authority, changeTableName);
                proceedDeletedFeatures(deleted, changeTableName);
            } else {
                remoteIdSet = new HashSet<>(Math.max(16, features.size() * 2));
                for (Feature f : features) {
                    remoteIdSet.add(f.getId());
                }

                // analyse feature
                HyperLog.v(Constants.TAG, "NGWVectorLayer: " + getName() + " analyzing " + features.size() + " features");
                int missingLocalRowCount = 0;
                int skippedCreateDueToPendingChange = 0;
                for (Feature remoteFeature : features) {
                    Cursor cursor = query(null, Constants.FIELD_ID + " = " + remoteFeature.getId(), null, null, null);

                    try {
                        //no local feature
                        if (null == cursor || cursor.getCount() == 0) {
                            missingLocalRowCount++;
                            //if we have changes (delete) not create new feature
                            boolean createNewFeature =
                                    !FeatureChanges.isChanges(changeTableName, remoteFeature.getId());

                            if (!createNewFeature) {
                                skippedCreateDueToPendingChange++;
                            }
                            //create new feature with remoteId
                            if (createNewFeature) {
                                createNewFeature(remoteFeature, authority);
                                createNewFeatureCount++;
                            }
                        } else {
                            countChanges += compareFeature(cursor, authority, remoteFeature, changeTableName);
                        }
                    } catch (Exception e) {
                        String innerMsg = e.getMessage();
                        HyperLog.v(Constants.TAG, "NGWVectorLayer: " + getName() + " getChangesFromServer Exception error " + innerMsg);
                        if (e instanceof SQLiteException && sqliteMessageNeedsLayerRefillFromServer(innerMsg)) {
                            HyperLog.w(Constants.TAG, "NGWVectorLayer: " + getName()
                                    + " — SQLite storage error (" + innerMsg + "), scheduling rebuild");
                            try {
                                ((IGISApplication) mContext.getApplicationContext())
                                        .scheduleNgwLayerRebuildAfterSchemaMismatch(this);
                            } catch (Exception rebuildEx) {
                                HyperLog.w(Constants.TAG, "rebuild scheduling failed", rebuildEx);
                            }
                            if (null != cursor) cursor.close();
                            return true;
                        }
                    } finally {
                        if (null != cursor) {
                            cursor.close();
                        }
                    }
                }
                if (missingLocalRowCount > 0) {
                    HyperLog.v(Constants.TAG, "NGWVectorLayer: " + getName() + " pull: no local row for "
                            + missingLocalRowCount + " server feature(s); created " + createNewFeatureCount
                            + ", skipped create (pending change) " + skippedCreateDueToPendingChange);
                }

                // remove features not exist on server from local layer
                // if no operation is in changes array or change operation for local feature present
                try {
                    for (Long featureId : queryAllFeatureIdsFromDb()) {
                        boolean bDeleteFeature = !remoteIdSet.contains(featureId)
                                && !FeatureChanges.isChanges(changeTableName, featureId,
                                        Constants.CHANGE_OPERATION_NEW)
                                && !FeatureChanges.hasFeatureFlags(changeTableName, featureId);

                        if (bDeleteFeature) {
                            deleteItems.add(featureId);
                        }
                    }
                } catch (Exception ex){
                    HyperLog.v(Constants.TAG, "NGWVectorLayer: " + getName() + " getChangesFromServer remove features Exception error " + ex.getMessage());

                    Log.e(TAG, "error on query:" + ex.getMessage());
                }
                HyperLog.v(Constants.TAG, "NGWVectorLayer: " + getName() + " delete features " + deleteItems.size());
                deleteFeatures(deleteItems);
            }

            if (!mTracked) {
                Cursor changeCursor = FeatureChanges.getChanges(changeTableName);
                HyperLog.v(Constants.TAG, "NGWVectorLayer: " + getName() + " changeCursorSize is " + changeCursor.getCount());
                // remove changes already applied on server (delete already deleted id or add already added)
                if (null != changeCursor) {
                    try {
                        if (changeCursor.moveToFirst()) {
                            int recordIdColumn = changeCursor.getColumnIndex(Constants.FIELD_ID);
                            int featureIdColumn =
                                    changeCursor.getColumnIndex(Constants.FIELD_FEATURE_ID);
                            int operationColumn =
                                    changeCursor.getColumnIndex(Constants.FIELD_OPERATION);
                            int attachOperationColumn =
                                    changeCursor.getColumnIndex(Constants.FIELD_ATTACH_OPERATION);

                            do {
                                long changeRecordId = changeCursor.getLong(recordIdColumn);
                                long changeFeatureId = changeCursor.getLong(featureIdColumn);
                                int changeOperation = changeCursor.getInt(operationColumn);
                                int attachChangeOperation = changeCursor.getInt(attachOperationColumn);

                                boolean bDeleteChange = true; // if feature not exist on server
                                if (remoteIdSet != null && remoteIdSet.contains(changeFeatureId)) {
                                    if (0 != (changeOperation & Constants.CHANGE_OPERATION_NEW)) {
                                        // if feature already exist, just change it
                                        FeatureChanges.setOperation(changeTableName, changeRecordId,
                                                Constants.CHANGE_OPERATION_CHANGED);
                                    }
                                    bDeleteChange = false; // in other cases just apply
                                }

                                if ((0 != (changeOperation & Constants.CHANGE_OPERATION_NEW) || 0 != (
                                        attachChangeOperation & Constants.CHANGE_OPERATION_NEW))
                                        && bDeleteChange) {

                                    bDeleteChange = false;
                                }

                                if (bDeleteChange) {
                                    if (Constants.DEBUG_MODE) {
                                        Log.d(Constants.TAG,
                                                "Delete change for feature #" + changeFeatureId +
                                                        ", changeOperation " + changeOperation +
                                                        ", attachChangeOperation " +
                                                        attachChangeOperation);
                                    }
                                    // TODO: analise for operation, remove all equal
                                    FeatureChanges.removeChangeRecord(changeTableName, changeRecordId);
                                }

                            } while (changeCursor.moveToNext());
                        }
                    } catch (Exception e) {
                        HyperLog.v(Constants.TAG, "NGWVectorLayer: " + getName() + " getChangesFromServer Exception " + e.getMessage());
                        //Log.d(TAG, e.getLocalizedMessage());
                    } finally {
                        changeCursor.close();
                    }
                }
            }
        } catch (SQLiteException | ConcurrentModificationException e) {
            String errMsg = e.getMessage();
            HyperLog.v(Constants.TAG, "NGWVectorLayer: " + getName() + " getChangesFromServer Exception " + errMsg);
            syncResult.stats.numConflictDetectedExceptions++;
            if (Constants.DEBUG_MODE) {
                Log.d(Constants.TAG, "proceed getChangesFromServer() failed");
            }
            e.printStackTrace();

            if (e instanceof SQLiteException && sqliteMessageNeedsLayerRefillFromServer(errMsg)) {
                HyperLog.w(Constants.TAG, "NGWVectorLayer: " + getName()
                        + " — SQLite storage error, scheduling rebuild: " + errMsg);
                try {
                    ((IGISApplication) mContext.getApplicationContext())
                            .scheduleNgwLayerRebuildAfterSchemaMismatch(this);
                } catch (Exception rebuildEx) {
                    HyperLog.w(Constants.TAG, "NGWVectorLayer: rebuild scheduling failed", rebuildEx);
                }
            }
            return true;
        }

        int trackedAddedCount = added == null ? 0 : added.size();
        int trackedChangedCount = changed == null ? 0 : changed.size();
        int trackedDeletedCount = deleted == null ? 0 : deleted.size();
        HyperLog.v(Constants.TAG, "NGWVectorLayer: " + getName()
                + " pull summary updated=" + countChanges
                + " created=" + createNewFeatureCount
                + " deleted=" + deleteItems.size()
                + " trackedAdded=" + trackedAddedCount
                + " trackedChanged=" + trackedChangedCount
                + " trackedDeleted=" + trackedDeletedCount);
        HyperLog.v(Constants.TAG, "NGWVectorLayer: " + getName() + " getChangesFromServer END");
        // call reload on maplibre if changes > 0
        boolean hasRemoteDataChanges = countChanges > 0 || createNewFeatureCount > 0
                || !deleteItems.isEmpty() || trackedAddedCount > 0 || trackedChangedCount > 0
                || trackedDeletedCount > 0;
        if (hasRemoteDataChanges) {
            try {
                rebuildCache(null);
            } catch (RuntimeException e) {
                HyperLog.w(Constants.TAG, "NGWVectorLayer: " + getName()
                        + " spatial cache rebuild after pull failed: " + e.getMessage(), e);
            }
            VectorLayerRenderCache.invalidateOnDataChange(this);
            ((IGISApplication)getContext().getApplicationContext()).reloadLayerByID(getId());
        }
        return true;
    }


    protected void proceedAddedFeatures(List<Feature> added, String authority, String changeTableName) {
        if (added != null) {
            for (Feature remoteFeature : added) {
                Cursor cursor = query(null, Constants.FIELD_ID + " = " + remoteFeature.getId(), null, null, null);
                boolean hasFeature = false;
                if (cursor != null) {
                    if (cursor.moveToFirst()) {
                        compareFeature(cursor, authority, remoteFeature, changeTableName);
                        hasFeature = true;
                    }
                    cursor.close();
                }

                if (!hasFeature)
                    createNewFeature(remoteFeature, authority);
            }
        }
    }


    protected void proceedChangedFeatures(List<Feature> changed, String authority, String changeTableName) {
        if (changed != null) {
            for (Feature remoteFeature : changed) {
                Cursor cursor = query(null, Constants.FIELD_ID + " = " + remoteFeature.getId(), null, null, null);
                if (cursor != null) {
                    if (cursor.moveToFirst()) {
                        compareFeature(cursor, authority, remoteFeature, changeTableName);
                    }
                    cursor.close();
                }
            }
        }
    }


    protected void proceedDeletedFeatures(List<Feature> deleted, String changeTableName) {
        List<Long> deleteItems = new ArrayList<>();
        if (deleted != null) {
            for (Feature remoteFeature : deleted)
                deleteItems.add(remoteFeature.getId());

            deleteFeatures(deleteItems);
        }
    }


    protected void createNewFeature(Feature remoteFeature, String authority) {
        ContentValues values = remoteFeature.getContentValues(true);
        Uri uri = Uri.parse("content://" + authority + "/" + getPath().getName());
        //prevent add changes and events
        uri = uri.buildUpon().fragment(NO_SYNC).build();
        Uri newFeatureUri = insert(uri, values);
        if (Constants.DEBUG_MODE) {
            Log.d(Constants.TAG, "Add new feature from server - " + newFeatureUri);
        }
        if (newFeatureUri == null) {
            HyperLog.v(Constants.TAG, "NGWVectorLayer: " + getName()
                    + " createNewFeature insert failed (remote _id=" + remoteFeature.getId() + ")");
            if (!mLoggedCreateInsertSqlError) {
                mLoggedCreateInsertSqlError = true;
                try {
                    MapContentProviderHelper map = (MapContentProviderHelper) MapBase.getInstance();
                    if (map != null) {
                        map.getDatabase(false).insertOrThrow(mPath.getName(), null, values);
                    }
                } catch (SQLiteException e) {
                    HyperLog.v(Constants.TAG, "NGWVectorLayer: " + getName()
                            + " createNewFeature SQLite (sample): " + e.getMessage());
                }
            }
        }
    }


    protected void deleteFeatures(List<Long> deleteItems) {
        for (long itemId : deleteItems) {
            if (Constants.DEBUG_MODE) {
                Log.d(Constants.TAG, "Delete feature #" + itemId + " not exist on server");
            }
            delete(itemId, Constants.FIELD_ID + " = " + itemId, null);
        }
    }

    protected int compareFeature(Cursor cursor, String authority, Feature remoteFeature, String changeTableName) {
        int count = 0;
        cursor.moveToFirst();
        // with the given ID (remoteFeature.getId()) must be only one feature
        Feature currentFeature = cursorToFeature(cursor);

        //compare features
        boolean eqData = remoteFeature.equalsData(currentFeature);
        boolean eqAttach = remoteFeature.equalsAttachments(currentFeature);

        if (!eqAttach) {
            // delete all online attachments
            FeatureAttachments.deleteAllAttachments(getAttachmentsTableName(), remoteFeature.getId());

            // put all to db
            for (AttachItem item : remoteFeature.getAttachments().values()) {
                FeatureAttachments.add(getAttachmentsTableName(), remoteFeature.getId(),
                        Long.valueOf(item.getAttachId()),
                        item.getDescription(), item.getDisplayName(), item.getMimetype());
            }
        }





        //process data
        if (eqData) {
            //remove from changes
            if (FeatureChanges.isChanges(changeTableName, remoteFeature.getId())) {
                if (eqAttach && !FeatureChanges.isAttachesForDelete(
                        changeTableName, remoteFeature.getId())
                        || !FeatureChanges.isAttachChanges(
                        changeTableName, remoteFeature.getId())) {

                    FeatureChanges.removeChanges(
                            changeTableName, remoteFeature.getId());
                }
            }
        } else {
            // we have local changes ready for sent to server
            boolean isChangedLocal = FeatureChanges.isChanges(changeTableName,
                    remoteFeature.getId());

            //no local changes - update local feature
            if (!isChangedLocal) {
                ContentValues values = remoteFeature.getContentValues(false);

                Uri uri = Uri.parse(
                        "content://" + authority + "/" + getPath().getName());
                Uri updateUri =
                        ContentUris.withAppendedId(uri, remoteFeature.getId());
                updateUri = updateUri.buildUpon().fragment(NO_SYNC).build();
                //prevent add changes
                count = update(updateUri, values, null, null);
                if (Constants.DEBUG_MODE) {
                    Log.d(Constants.TAG,
                            "Update feature (" + count + ") from server - " +
                                    remoteFeature.getId());
                }
            }
        }

        //process attachments
        if (eqAttach) {
            if (FeatureChanges.isChanges(changeTableName, remoteFeature.getId())
                    && (eqData || FeatureChanges.isAttachChanges(
                    changeTableName, remoteFeature.getId()))) {

                if (Constants.DEBUG_MODE) {
                    Log.d(Constants.TAG, "The feature " + remoteFeature.getId() +
                            " already changed on server. Remove changes for it");
                }

                FeatureChanges.removeChanges(
                        changeTableName, remoteFeature.getId());
            }

        } else {
            boolean isChangedLocal = FeatureChanges.isAttachChanges(changeTableName,
                    remoteFeature.getId());

            if (!isChangedLocal) {
                Iterator<String> iterator =
                        currentFeature.getAttachments().keySet().iterator();

                while (iterator.hasNext()) {
                    String attachId = iterator.next();

                    //delete attachment which not exist on server
                    if (!remoteFeature.getAttachments().containsKey(attachId)) {
                        iterator.remove();
                        saveAttach("" + currentFeature.getId(),
                                currentFeature.getAttachments());

                    } else { //or change attachment properties
                        AttachItem currentItem =
                                currentFeature.getAttachments().get(attachId);
                        AttachItem remoteItem =
                                remoteFeature.getAttachments().get(attachId);

                        if (null != currentItem && !currentItem.equals(
                                remoteItem)) {
                            long attachIdL =
                                    Long.parseLong(remoteItem.getAttachId());
                            boolean changeOnServer =
                                    !FeatureChanges.isAttachChanges(changeTableName,
                                            remoteFeature.getId(), attachIdL);

                            if (changeOnServer) {
                                currentItem.setDescription(
                                        remoteItem.getDescription());
                                currentItem.setMimetype(remoteItem.getMimetype());
                                currentItem.setDisplayName(
                                        remoteItem.getDisplayName());
                                saveAttach("" + currentFeature.getId(),
                                        currentFeature.getAttachments());
                            }
                        }
                    }
                }
            }
        }
        return count;
    }


    protected void authenticate(AccountUtil.AccountData accountData, HttpURLConnection connection) {
        final String basicAuth = NetworkUtil.getHTTPBaseAuth(accountData.login, accountData.password);
        if (null != basicAuth) {
            connection.setRequestProperty("Authorization", basicAuth);
        }
    }

    protected HttpURLConnection getConnection(AccountUtil.AccountData accountData) throws IOException {
        URL url = new URL(getFeaturesUrl(accountData));
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        connection.setRequestProperty("User-Agent", getUserAgent(Constants.MAPLIB_USER_AGENT_PART));
        connection.setRequestProperty("connection", "keep-alive");

        authenticate(accountData, connection);

        if (connection.getResponseCode() == HttpURLConnection.HTTP_MOVED_PERM && url.getProtocol().equals("http")) {
            url = new URL(url.toString().replace("http", "https"));
            configureSSLdefault();
            connection = (HttpsURLConnection) url.openConnection();
            connection.setRequestProperty("User-Agent",
                    getUserAgent(Constants.MAPLIB_USER_AGENT_PART));
            authenticate(accountData, connection);
        }
        return connection;
    }


    // read layer contents as string
    protected ExistFeatureResult getFeatures(SyncResult syncResult, boolean tracked) {
        AccountUtil.AccountData accountData;
        try {
            accountData = AccountUtil.getAccountData(mContext, mAccountName);
        } catch (IllegalStateException e) {
            log(e, "getFeatures(): account is null");
            syncResult.stats.numAuthExceptions++;
            return null;
        }

        for (int attempt = 1; attempt <= NGW_SYNC_PULL_MAX_ATTEMPTS; attempt++) {
            HashMap<Integer, List<Feature>> results = new HashMap<>();
            HttpURLConnection urlConnection = null;
            try {
                urlConnection = getConnection(accountData);
                if (Constants.DEBUG_MODE)
                    Log.d("SSYNC", "url: " + urlConnection.getURL().toString());

                int code = urlConnection.getResponseCode();
                if (code < 200 || code >= 300) {
                    String urlStr = "";
                    try {
                        if (urlConnection.getURL() != null) {
                            urlStr = urlConnection.getURL().toString();
                        }
                    } catch (Exception ignored) {
                    }
                    String responseMessage = urlConnection.getResponseMessage();
                    String errorBody = null;
                    try {
                        errorBody = NetworkUtil.responseToString(urlConnection.getErrorStream());
                    } catch (IOException bodyError) {
                        HyperLog.w(Constants.TAG, "NGW sync pull error body read failed: "
                                + bodyError.getMessage(), bodyError);
                    }
                    HttpResponse response = new HttpResponse(code, responseMessage, errorBody);
                    HyperLog.w(Constants.TAG, ProdLogUtil.ngwHttpFailure(
                            "syncPull", getName(), mRemoteId, -1, -1, response)
                            + " attempt=" + attempt + "/" + NGW_SYNC_PULL_MAX_ATTEMPTS
                            + " url=" + ProdLogUtil.scrubUrlForLog(urlStr));
                    if (code == 404){
                        Log.d("SSYNC", "url: " + urlConnection.getURL().toString() + " = FAIL 404");
                        return new ExistFeatureResult(null, false, 404);
                    }
                    if (NetworkUtil.isTransientNgwHttpError(code, errorBody, responseMessage)
                            && attempt < NGW_SYNC_PULL_MAX_ATTEMPTS) {
                        sleepBeforeNgwSyncPullRetry(attempt, "HTTP " + code);
                        continue;
                    }
                    if (NetworkUtil.isTransientNgwHttpError(code, errorBody, responseMessage)) {
                        SyncResultUtil.markConnectFailed(syncResult);
                    } else {
                        syncResult.stats.numIoExceptions++;
                    }
                    return new ExistFeatureResult(null, false, 0);
                }

//            if (code == 403){
//                return new ExistFeatureResult(null, false, 404);
//            }
                if (Constants.DEBUG_MODE)
                    Log.d(TAG, "code: " + code);

                InputStream in = new ProgressBufferedInputStream(urlConnection.getInputStream(),
                        urlConnection.getContentLength());
                JsonReader reader = new JsonReader(new InputStreamReader(in, "UTF-8"));

                if (tracked) {
                    List<Feature> added = new LinkedList<>(), changed = new LinkedList<>(), deleted = new LinkedList<>();
                    reader.beginObject();
                    while (reader.hasNext()) {
                        String name = reader.nextName();
                        switch (name) {
                            case "deleted":
                                reader.beginArray();
                                while (reader.hasNext())
                                    deleted.add(new Feature(reader.nextLong(), getFields()));
                                reader.endArray();
                                break;
                            case "added":
                                readFeatures(reader, added);
                                break;
                            case "changed":
                                readFeatures(reader, changed);
                                break;
                        }
                    }
                    reader.endObject();

                    results.put(0, added);
                    results.put(1, changed);
                    results.put(2, deleted);
                } else {
                    List<Feature> features = new LinkedList<>();
                    readFeatures(reader, features);
                    results.put(0, features);
                }
                reader.close();

                //MapUtil.logFeatures(results);
                return new ExistFeatureResult(results, true, 200);
            } catch (MalformedURLException e) {
                log(e, "getFeatures(): MalformedURLException");
                syncResult.stats.numIoExceptions++;
                return new ExistFeatureResult(null, false, 0);
            } catch (FileNotFoundException e) {
                log(e, "getFeatures(): FileNotFoundException");
                SyncResultUtil.markConnectFailed(syncResult);
                return new ExistFeatureResult(null, false, 0);
            } catch (IOException e) {
                if (NetworkUtil.isTransientNetworkFailure(e)
                        && attempt < NGW_SYNC_PULL_MAX_ATTEMPTS) {
                    sleepBeforeNgwSyncPullRetry(attempt, e.getMessage());
                    continue;
                }
                log(e, "getFeatures(): IOException");
                SyncResultUtil.markConnectFailed(syncResult);
                return new ExistFeatureResult(null, false, 0);
            } catch (NGException e) {
                log(e, "getFeatures(): NGException");
                syncResult.stats.numParseExceptions++;
                return new ExistFeatureResult(null, false, 0);
            } catch (OutOfMemoryError e) {
                HyperLog.w(Constants.TAG, "NGW pull OOM layer=\"" + ProdLogUtil.truncateForLog(getName(), 100)
                        + "\" res=" + mRemoteId);
                e.printStackTrace();
                syncResult.stats.numIoExceptions++;
                syncResult.stats.numSkippedEntries++;
                return new ExistFeatureResult(null, false, 0);
            } catch (IllegalStateException | NumberFormatException e) {
                log(e, "getFeatures(): IllegalStateException | NumberFormatException");
                syncResult.stats.numParseExceptions++;
                return new ExistFeatureResult(null, false, 0);
            } finally {
                if (urlConnection != null) {
                    urlConnection.disconnect();
                }
            }
        }

        return new ExistFeatureResult(null, false, 0);
    }

    private void sleepBeforeNgwSyncPullRetry(int attempt, String reason) {
        HyperLog.w(Constants.TAG, "NGW sync pull retry layer=\""
                + ProdLogUtil.truncateForLog(getName(), 100)
                + "\" res=" + mRemoteId
                + " attempt=" + attempt + "/" + NGW_SYNC_PULL_MAX_ATTEMPTS
                + (TextUtils.isEmpty(reason) ? "" : " reason=\""
                + ProdLogUtil.truncateForLog(reason, 160) + "\""));
        SystemClock.sleep(NGW_SYNC_PULL_RETRY_DELAY_MS * attempt);
    }

    protected void readFeatures(JsonReader reader, List<Feature> features) throws IOException, IllegalStateException,
            NumberFormatException, OutOfMemoryError, NGException {
        reader.beginArray();
        while (reader.hasNext()) {
            final Feature feature = NGWUtil.readNGWFeature(reader, getFields(), mCRS);
            if (feature.getGeometry() == null || !feature.getGeometry().isValid())
                continue;
            features.add(feature);
        }
        reader.endArray();
    }

    /**
     * A pending change references a feature row that is not present locally, so we currently drop the
     * change (treat as handled). Returning false instead would keep the change but risks a perpetual
     * sync error when the row is genuinely orphaned. Logged at WARN with full context for diagnosis.
     * TODO (further review): distinguish a transient query failure from a truly missing row and decide
     * whether such changes should be repaired/retried rather than dropped.
     */
    private void logBuggyChangeDrop(String op, long featureId) {
        HyperLog.w(Constants.TAG, op + ": missing local feature row, dropping pending change layer=\""
                + ProdLogUtil.truncateForLog(getName(), 100) + "\" res=" + mRemoteId
                + " fid=" + featureId);
    }

    protected FeaturePushResult addFeatureOnServer(
            long featureId,
            SyncResult syncResult,
            AccountUtil.AccountData accountData )
            throws SQLiteException
    {

//        Log.e("FEA", "addFeatureOnServer " + featureId );

        if (!mNet.isNetworkAvailable()) {
            HyperLog.v(Constants.TAG, "addFeatureOnServer !mNet.isNetworkAvailable() no network!!! ");
            SyncResultUtil.markNetworkUnavailable(syncResult);
            return FeaturePushResult.failed();
        }
        Uri uri = ContentUris.withAppendedId(getContentUri(), featureId);
        uri = uri.buildUpon().fragment(NO_SYNC).build();

        Cursor cursor = query(uri, null, null, null, "_id", null);
        if (null == cursor) {
            logBuggyChangeDrop("addFeatureOnServer (null cursor)", featureId);
            return FeaturePushResult.handledWithoutRemoteId(); //just remove buggy data
        }

        try {
            if (cursor.moveToFirst()) {
                // feature to string
                String payload = cursorToJson(cursor);
                if (Constants.DEBUG_MODE) {
                    Log.d(Constants.TAG, "payload: " + payload);
                }

                // post to NGW
                HttpResponse response = addFeatureOnServer(payload, accountData);


                // add 403 processinge
                if (!response.isOk()) {
//                    Log.e("FEA", "addFeatureOnServer 403" );
                    if (response.getResponseCode() == 403){
                        // no access right
                        ((IGISApplication)mContext.getApplicationContext()).setError(
                                getAccountName(),
                                getContext().getResources().getString(R.string.error_no_access_403),
                                403);
                    }
                    reportSyncHttpFailure("addFeature", featureId, -1, syncResult, response);
                    return FeaturePushResult.failed();
                }

                //set new id from server // like: {"id": 24}
                JSONObject result = new JSONObject(response.getResponseBody());
                long id = Constants.NOT_FOUND;
                if (result.has(Constants.JSON_ID_KEY)) {
                    id = result.getLong(Constants.JSON_ID_KEY);
//                    Log.e("FEA", "addFeatureOnServer start changeFeatureId from " + featureId + " to " + id  );
                    changeFeatureId(featureId, id);
                }

                return FeaturePushResult.success(id);
            } else {
                logBuggyChangeDrop("addFeatureOnServer (empty cursor)", featureId);
                return FeaturePushResult.handledWithoutRemoteId(); //just remove buggy data
            }

        } catch (JSONException e) {
            HyperLog.v(Constants.TAG, "addFeatureOnServer JSONException: " + e.getMessage());
            log(e, "addFeatureOnServer JSONException");
            syncResult.stats.numParseExceptions++;
            return FeaturePushResult.failed();
        } catch (IOException e) {
            HyperLog.v(Constants.TAG, "addFeatureOnServer IOException: " + e.getMessage());
            log(e, "addFeatureOnServer IOException");
            SyncResultUtil.markConnectFailed(syncResult);
            syncResult.stats.numInserts++;
            return FeaturePushResult.failed();
        } catch (SQLiteConstraintException e) {
            HyperLog.v(Constants.TAG, "addFeatureOnServer SQLiteConstraintException: " + e.getMessage());
            log(e, "addFeatureOnServer SQLiteConstraintException");
            syncResult.stats.numConflictDetectedExceptions++;
            return FeaturePushResult.failed();
        } catch (IllegalStateException e) {
            HyperLog.v(Constants.TAG, "addFeatureOnServer IllegalStateException: " + e.getMessage());
            log(e, "addFeatureOnServer IllegalStateException");
            syncResult.stats.numAuthExceptions++;
            return FeaturePushResult.failed();
        } finally {
            cursor.close();
        }
    }

    protected HttpResponse addFeatureOnServer(String payload, AccountUtil.AccountData accountData) throws IOException {
//        AccountUtil.AccountData accountData = AccountUtil.getAccountData(mContext, mAccountName);

        return NetworkUtil.post(NGWUtil.getFeaturesUrl(accountData.url, mRemoteId) + appendix(),
                payload, accountData.login, accountData.password, false);
    }

    protected boolean deleteFeatureOnServer(
            long featureId,
            SyncResult syncResult)
    {
        if (!mNet.isNetworkAvailable()) {
            HyperLog.v(Constants.TAG, "deleteFeatureOnServer !mNet.isNetworkAvailable()");
            SyncResultUtil.markNetworkUnavailable(syncResult);
            return false;
        }

        try {
            HttpResponse response = deleteFeatureOnServer(featureId);
            if (!response.isOk()) {
                reportSyncHttpFailure("deleteFeature", featureId, -1, syncResult, response);
                return false;
            }
            return true;
        } catch (IOException e) {
            HyperLog.v(Constants.TAG, "deleteFeatureOnServer  IOException: " + e.getMessage());
            log(e, "deleteFeatureOnServer IOException");
            SyncResultUtil.markConnectFailed(syncResult);
            syncResult.stats.numDeletes++;
            return false;
        } catch (IllegalStateException e) {
            HyperLog.v(Constants.TAG, "deleteFeatureOnServer  IllegalStateException: " + e.getMessage());
            log(e, "deleteFeatureOnServer IllegalStateException");
            syncResult.stats.numAuthExceptions++;
            return false;
        }
    }

    protected HttpResponse deleteFeatureOnServer(long featureId) throws IOException {
        AccountUtil.AccountData accountData = AccountUtil.getAccountData(mContext, mAccountName);

        return NetworkUtil.delete(NGWUtil.getFeatureUrl(accountData.url, mRemoteId, featureId),
                accountData.login, accountData.password, false);
    }

    protected boolean changeFeatureOnServer(
            long featureId,
            SyncResult syncResult,
            AccountUtil.AccountData accountData)
            throws SQLiteException
    {
        if (!mNet.isNetworkAvailable()) {
            HyperLog.v(Constants.TAG, "changeFeatureOnServer !mNet.isNetworkAvailable()");
            SyncResultUtil.markNetworkUnavailable(syncResult);
            return false;
        }

        // get uri for feature
        Uri uri = ContentUris.withAppendedId(getContentUri(), featureId);
        uri = uri.buildUpon().fragment(NO_SYNC).build();

        // get it's cursor
        Cursor cursor = query(uri, null, null, null, null, null);
        if (null == cursor) {
            logBuggyChangeDrop("changeFeatureOnServer (null cursor)", featureId);
            return true; //just remove buggy data
        }

        try {
            if (cursor.moveToFirst()) {
                // get payload from cursor
                String payload = cursorToJson(cursor);
                if (Constants.DEBUG_MODE) {
                    Log.d(Constants.TAG, "payload: " + payload);
                }

                // change on server ERRROR
                HttpResponse response = changeFeatureOnServer(featureId, payload, accountData);

                if (!response.isOk()) {
                    reportSyncHttpFailure("changeFeature", featureId, -1, syncResult, response);
                    return false;
                }

                return true;
            } else {
                logBuggyChangeDrop("changeFeatureOnServer (empty cursor)", featureId);
                return true; //just remove buggy data
            }
        } catch (IllegalStateException e) {
            HyperLog.v(Constants.TAG, "changeFeatureOnServer IllegalStateException: " + e.getMessage());
            log(e, "changeFeatureOnServer IllegalStateException");
            syncResult.stats.numAuthExceptions++;
            return false;
        } catch (IOException e) {
            HyperLog.v(Constants.TAG, "changeFeatureOnServer IOException: " + e.getMessage());
            log(e, "changeFeatureOnServer IOException");
            SyncResultUtil.markConnectFailed(syncResult);
            syncResult.stats.numUpdates++;
            return false;
        } catch (JSONException e) {
            HyperLog.v(Constants.TAG, "changeFeatureOnServer JSONException: " + e.getMessage());
            log(e, "changeFeatureOnServer JSONException");
            syncResult.stats.numParseExceptions++;
            return false;
        } finally {
            cursor.close();
        }
    }


    protected HttpResponse changeFeatureOnServer(long featureId, String payload,
                                                 AccountUtil.AccountData accountData) throws IOException {
//        AccountUtil.AccountData accountData = AccountUtil.getAccountData(mContext, mAccountName);

        // change on server
        String url = NGWUtil.getFeatureUrl(accountData.url, mRemoteId, featureId);
        return NetworkUtil.put(url, payload, accountData.login, accountData.password, false);
    }

    private void refreshFeatureFromServerAfterPush(
            long featureId,
            AccountUtil.AccountData accountData,
            String sourceOperation)
    {
        if (featureId == Constants.NOT_FOUND) {
            return;
        }

        String changeTableName = getChangeTableName();
        if (hasPendingFeatureDataChanges(changeTableName, featureId)) {
            HyperLog.d(Constants.TAG, "NGW post-push refresh skip: pending local data changes layer=\""
                    + ProdLogUtil.truncateForLog(getName(), 100)
                    + "\" res=" + mRemoteId
                    + " fid=" + featureId
                    + " op=" + sourceOperation);
            return;
        }

        try {
            Feature serverFeature = getFeatureFromServer(featureId, accountData);
            if (serverFeature == null) {
                return;
            }

            if (serverFeature.getId() != featureId) {
                HyperLog.w(Constants.TAG, "NGW post-push refresh id mismatch layer=\""
                        + ProdLogUtil.truncateForLog(getName(), 100)
                        + "\" res=" + mRemoteId
                        + " requestedFid=" + featureId
                        + " responseFid=" + serverFeature.getId());
                return;
            }

            if (hasPendingFeatureDataChanges(changeTableName, featureId)) {
                HyperLog.d(Constants.TAG, "NGW post-push refresh skip after fetch: pending local data changes layer=\""
                        + ProdLogUtil.truncateForLog(getName(), 100)
                        + "\" res=" + mRemoteId
                        + " fid=" + featureId
                        + " op=" + sourceOperation);
                return;
            }

            int updated = applyServerFeatureDataOnly(serverFeature);
            HyperLog.d(Constants.TAG, "NGW post-push refresh layer=\""
                    + ProdLogUtil.truncateForLog(getName(), 100)
                    + "\" res=" + mRemoteId
                    + " fid=" + featureId
                    + " op=" + sourceOperation
                    + " updated=" + updated);
        } catch (IOException e) {
            HyperLog.w(Constants.TAG, "NGW post-push refresh IOException layer=\""
                    + ProdLogUtil.truncateForLog(getName(), 100)
                    + "\" res=" + mRemoteId
                    + " fid=" + featureId
                    + " op=" + sourceOperation
                    + (e.getMessage() == null ? "" : " " + ProdLogUtil.truncateForLog(e.getMessage(), 180)), e);
        } catch (JSONException | NGException | IllegalStateException | NumberFormatException e) {
            HyperLog.w(Constants.TAG, "NGW post-push refresh failed layer=\""
                    + ProdLogUtil.truncateForLog(getName(), 100)
                    + "\" res=" + mRemoteId
                    + " fid=" + featureId
                    + " op=" + sourceOperation
                    + (e.getMessage() == null ? "" : " " + ProdLogUtil.truncateForLog(e.getMessage(), 180)), e);
        } catch (SQLiteException e) {
            HyperLog.w(Constants.TAG, "NGW post-push refresh SQLite failed layer=\""
                    + ProdLogUtil.truncateForLog(getName(), 100)
                    + "\" res=" + mRemoteId
                    + " fid=" + featureId
                    + " op=" + sourceOperation
                    + (e.getMessage() == null ? "" : " " + ProdLogUtil.truncateForLog(e.getMessage(), 180)), e);
        } catch (OutOfMemoryError e) {
            HyperLog.w(Constants.TAG, "NGW post-push refresh OOM layer=\""
                    + ProdLogUtil.truncateForLog(getName(), 100)
                    + "\" res=" + mRemoteId
                    + " fid=" + featureId
                    + " op=" + sourceOperation);
        }
    }

    protected Feature getFeatureFromServer(
            long featureId,
            AccountUtil.AccountData accountData)
            throws IOException, JSONException, NGException
    {
        String url = NGWUtil.getFeatureUrl(accountData.url, mRemoteId, featureId);
        HttpResponse response = NetworkUtil.get(
                url, accountData.login, accountData.password, true);
        if (!response.isOk()) {
            HyperLog.w(Constants.TAG, ProdLogUtil.ngwHttpFailure(
                    "postPushFeatureRefresh", getName(), mRemoteId, featureId, -1, response)
                    + " url=" + ProdLogUtil.scrubUrlForLog(url));
            return null;
        }

        if (TextUtils.isEmpty(response.getResponseBody())) {
            HyperLog.w(Constants.TAG, "NGW post-push refresh empty response layer=\""
                    + ProdLogUtil.truncateForLog(getName(), 100)
                    + "\" res=" + mRemoteId
                    + " fid=" + featureId
                    + " url=" + ProdLogUtil.scrubUrlForLog(url));
            return null;
        }

        JsonReader reader = new JsonReader(new StringReader(response.getResponseBody()));
        try {
            Feature feature = NGWUtil.readNGWFeature(reader, getFields(), mCRS);
            if (feature.getGeometry() == null || !feature.getGeometry().isValid()) {
                HyperLog.w(Constants.TAG, "NGW post-push refresh invalid geometry layer=\""
                        + ProdLogUtil.truncateForLog(getName(), 100)
                        + "\" res=" + mRemoteId
                        + " fid=" + featureId);
                return null;
            }
            return feature;
        } finally {
            reader.close();
        }
    }

    private boolean hasPendingFeatureDataChanges(String changeTableName, long featureId) {
        if (FeatureChanges.hasFeatureFlags(changeTableName, featureId)) {
            return true;
        }

        Cursor cursor = FeatureChanges.getChanges(changeTableName, featureId);
        if (cursor == null) {
            return true;
        }

        try {
            int operationColumn = cursor.getColumnIndex(Constants.FIELD_OPERATION);
            while (cursor.moveToNext()) {
                int operation = cursor.getInt(operationColumn);
                if (0 == (operation & Constants.CHANGE_OPERATION_ATTACH)) {
                    return true;
                }
            }
            return false;
        } finally {
            cursor.close();
        }
    }

    private int applyServerFeatureDataOnly(Feature serverFeature) {
        ContentValues values = serverFeature.getContentValues(false);
        Uri uri = Uri.parse("content://" + mAuthority + "/" + getPath().getName());
        Uri updateUri = ContentUris.withAppendedId(uri, serverFeature.getId())
                .buildUpon()
                .fragment(NO_SYNC)
                .build();
        int updated = update(updateUri, values, null, null);
        if (updated > 0) {
            VectorLayerRenderCache.invalidateOnDataChange(this);
        }
        return updated;
    }

    private String unNormalizeName(String name){
        if (!TextUtils.isEmpty(name)  && name.length() > 2 ){
            if (name.charAt(0) == '\"' && name.charAt(name.length()-1) == '\"'){
                name = name.substring(1, name.length()-1);
                return name;
            }
        }
        return name;
    }

    protected String cursorToJson(Cursor cursor)
            throws JSONException, IOException
    {
        JSONObject rootObject = new JSONObject();
        if (0 != (mSyncType & Constants.SYNC_ATTRIBUTES)) {
            JSONObject valueObject = new JSONObject();

            for (int i = 0; i < cursor.getColumnCount(); i++) {
                String columnName = cursor.getColumnName(i);
                if (columnName.equals(Constants.FIELD_ID) || columnName.equals(Constants.FIELD_GEOM)) {
                    continue;
                }

                String fieldName = unNormalizeName(columnName);

                Field field = mFields.get(columnName);
                if (null == field) {
                    continue;
                }

                int type = field.getType();

                switch (type) {
                    case GeoConstants.FTReal:
                        valueObject.put(fieldName, cursor.getDouble(i));
                        break;

                    case GeoConstants.FTInteger:
                        valueObject.put(fieldName, cursor.getInt(i));
                        break;

                    case GeoConstants.FTLong:
                        valueObject.put(fieldName, cursor.getLong(i));
                        break;

                    case GeoConstants.FTString:
                        String stringVal = cursor.getString(i);
                        if (stringVal != null && !stringVal.equals("null")) {
                            valueObject.put(fieldName, stringVal);
                        }
                        break;

                    case GeoConstants.FTDateTime:
                    case GeoConstants.FTDate:
                    case GeoConstants.FTTime:
                        if (cursor.isNull(i)) {
                            valueObject.put(fieldName, JSONObject.NULL);
                            break;
                        }

                        long millis = cursor.getLong(i);
                        String ngwString = millisToNGWString(millis, type);

                        if (ngwString != null) {
                            valueObject.put(fieldName, ngwString);
                        } else {
                            valueObject.put(fieldName, JSONObject.NULL);
                        }
                        break;

                    default:
                        break;
                }
            }

            rootObject.put(NGWUtil.NGWKEY_FIELDS, valueObject);
        }

        if (0 != (mSyncType & Constants.SYNC_GEOMETRY)) {
            //may be found geometry in cache by id is faster
            int geomCol = cursor.getColumnIndexOrThrow(Constants.FIELD_GEOM);
            GeoGeometry geometry = GeoGeometryFactory.fromBlob(cursor.getBlob(geomCol));

            geometry.setCRS(GeoConstants.CRS_WEB_MERCATOR);
            if (mCRS != GeoConstants.CRS_WEB_MERCATOR)
                geometry.project(mCRS);

            rootObject.put(NGWUtil.NGWKEY_GEOM, geometry.toWKT(true));
            //rootObject.put("id", cursor.getLong(cursor.getColumnIndex(FIELD_ID)));
        }

        return rootObject.toString();
    }

    /**
     * Преобразует millis (хранится в UTC) в строку, которую принимает NextGIS NGW
     */
    private String millisToNGWString(long millis, int fieldType) {
        if (millis == 0) {
            return "";
        }

        SimpleDateFormat sdf;

        switch (fieldType) {
            case GeoConstants.FTDate:
                sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
                break;

            case GeoConstants.FTTime:
                sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
                break;

            case GeoConstants.FTDateTime:
                //  RFC 3339 datetime
                sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault());
                break;

            default:
                return null;
        }

        // time on device
        Date currentTime = new Date(millis);

        TimeZone timeZoneT = TimeZone.getDefault();
        TimeZone timeZoneUTC = TimeZone.getDefault();
        timeZoneUTC.setRawOffset(0); // set to UTC

        // convert time to UTC zone time
        Date targetTime = convertTime(currentTime, timeZoneT, timeZoneUTC);
        return sdf.format(targetTime);
    }


    /**
     * get synchronization type
     *
     * @return the synchronization type - the OR of this values: SYNC_NONE - no synchronization
     * SYNC_DATA - synchronize only data SYNC_ATTACH - synchronize only attachments SYNC_ALL -
     * synchronize everything
     */
    @Override
    public int getSyncType()
    {
        return mSyncType;
    }


    protected synchronized void applySync(int syncType)
    {
        if (syncType == Constants.SYNC_NONE) {
            FeatureChanges.removeAllChanges(getChangeTableName());
        } else {
            if (mTracked)
                return;

            for (Long featureId : queryAllFeatureIdsFromDb()) {
                addChange(featureId, Constants.CHANGE_OPERATION_NEW);
                //add attach
                File attacheFolder = new File(mPath, "" + featureId);
                if (attacheFolder.isDirectory()) {
                    for (File attach : attacheFolder.listFiles()) {
                        String attachId = attach.getName();
                        if (attachId.equals(META)) {
                            continue;
                        }
                        Long attachIdL = Long.parseLong(attachId);
                        if (attachIdL >= Constants.MIN_LOCAL_FEATURE_ID) {
                            addChange(featureId, attachIdL, Constants.CHANGE_OPERATION_NEW);
                        }
                    }
                }
            }
        }
    }


    @Override
    public void setSyncType(int syncType)
    {
        if (!isSyncable()) {
            return;
        }

        if (mSyncType == syncType) {
            return;
        }

        mSyncType = syncType;
        // Commented due to useless changes addition/removing. We lose the history if someone changes the sync type and then return previous setting.
//        if (syncType == Constants.SYNC_NONE) {
//            new Thread(new Runnable()
//            {
//                public void run()
//                {
//                    android.os.Process.setThreadPriority(
//                            android.os.Process.THREAD_PRIORITY_BACKGROUND);
//                    applySync(Constants.SYNC_NONE);
//                }
//            }).start();
//        } else if (mSyncType == Constants.SYNC_NONE && 0 != (syncType & Constants.SYNC_DATA)) {
//            new Thread(new Runnable()
//            {
//                public void run()
//                {
//                    android.os.Process.setThreadPriority(
//                            android.os.Process.THREAD_PRIORITY_BACKGROUND);
//                    applySync(Constants.SYNC_ALL);
//                }
//            }).start();
//        }
    }


    @Override
    public boolean delete(boolean keepTrack)
            throws SQLiteException
    {
        FeatureChanges.delete(getChangeTableName());
        FeatureAttachments.delete(getAttachmentsTableName());

        return super.delete(keepTrack);
    }


    /**
     * Indicate if layer can sync data with remote server
     *
     * @return true if layer can sync or false
     */
    public boolean isSyncable()
    {
        return true;
    }


    /**
     * Indicate if layer can send changes to remote server
     *
     * @return true if layer can send changes to remote server or false
     */
    public boolean isRemoteReadOnly()
    {
        return !(mNGWLayerType == Connection.NGWResourceTypeVectorLayer || mNGWLayerType == NGWResourceTypePostgisLayer);
    }


    @Override
    protected Cursor queryInternal(
            Uri uri,
            int uriType,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder,
            String limit)
    {
        String featureId;
        String attachId;
        List<String> pathSegments;

        String changeTableName = getChangeTableName();

        switch (uriType) {
            case TYPE_CHANGES_TABLE: {
                return FeatureChanges.query(
                        changeTableName, projection, selection, selectionArgs, sortOrder, limit);
            }

            case TYPE_CHANGES_FEATURE: {
                featureId = uri.getLastPathSegment();

                String changeSel = FIELD_FEATURE_ID + " = " + featureId;

                if (TextUtils.isEmpty(selection)) {
                    selection = changeSel;
                } else {
                    selection += " AND " + changeSel;
                }

                return FeatureChanges.query(
                        changeTableName, projection, selection, selectionArgs, sortOrder, limit);
            }

            case TYPE_CHANGES_ATTACH: {
                pathSegments = uri.getPathSegments();
                featureId = pathSegments.get(pathSegments.size() - 2);

                String changeSel = FIELD_FEATURE_ID + " = " + featureId + " AND " + "( 0 != ( "
                        + FIELD_OPERATION + " & " + CHANGE_OPERATION_ATTACH + " ) )";

                if (TextUtils.isEmpty(selection)) {
                    selection = changeSel;
                } else {
                    selection += " AND " + changeSel;
                }

                return FeatureChanges.query(
                        changeTableName, projection, selection, selectionArgs, sortOrder, limit);
            }

            case TYPE_CHANGES_ATTACH_ID: {
                pathSegments = uri.getPathSegments();
                featureId = pathSegments.get(pathSegments.size() - 3);
                attachId = uri.getLastPathSegment();

                String changeSel = FIELD_FEATURE_ID + " = " + featureId + " AND " + "( 0 != ( "
                        + FIELD_OPERATION + " & " + CHANGE_OPERATION_ATTACH + " ) ) AND "
                        + FIELD_ATTACH_ID + " = " + attachId;

                if (TextUtils.isEmpty(selection)) {
                    selection = changeSel;
                } else {
                    selection += " AND " + changeSel;
                }

                return FeatureChanges.query(
                        changeTableName, projection, selection, selectionArgs, sortOrder, limit);
            }

            default: {
                return super.queryInternal(
                        uri, uriType, projection, selection, selectionArgs, sortOrder, limit);
            }
        }
    }


    @Override
    protected int deleteInternal(
            Uri uri,
            int uriType,
            String selection,
            String[] selectionArgs)
    {
        String featureId;
        String attachId;
        List<String> pathSegments;

        String changeTableName = getChangeTableName();

        switch (uriType) {

            case TYPE_CHANGES_TABLE: {
                return FeatureChanges.delete(changeTableName, selection, selectionArgs);
            }

            case TYPE_CHANGES_FEATURE: {
                featureId = uri.getLastPathSegment();

                String changeSel = FIELD_FEATURE_ID + " = " + featureId;

                if (TextUtils.isEmpty(selection)) {
                    selection = changeSel;
                } else {
                    selection += " AND " + changeSel;
                }

                return FeatureChanges.delete(changeTableName, selection, selectionArgs);
            }

            case TYPE_CHANGES_ATTACH: {
                pathSegments = uri.getPathSegments();
                featureId = pathSegments.get(pathSegments.size() - 2);

                String changeSel = FIELD_FEATURE_ID + " = " + featureId + " AND " + "( 0 != ( "
                        + FIELD_OPERATION + " & " + CHANGE_OPERATION_ATTACH + " ) )";

                if (TextUtils.isEmpty(selection)) {
                    selection = changeSel;
                } else {
                    selection += " AND " + changeSel;
                }

                return FeatureChanges.delete(changeTableName, selection, selectionArgs);
            }

            case TYPE_CHANGES_ATTACH_ID: {
                pathSegments = uri.getPathSegments();
                featureId = pathSegments.get(pathSegments.size() - 3);
                attachId = uri.getLastPathSegment();

                String changeSel = FIELD_FEATURE_ID + " = " + featureId + " AND " + "( 0 != ( "
                        + FIELD_OPERATION + " & " + CHANGE_OPERATION_ATTACH + " ) ) AND "
                        + FIELD_ATTACH_ID + " = " + attachId;

                if (TextUtils.isEmpty(selection)) {
                    selection = changeSel;
                } else {
                    selection += " AND " + changeSel;
                }

                return FeatureChanges.delete(changeTableName, selection, selectionArgs);
            }

            default: {
                return super.deleteInternal(uri, uriType, selection, selectionArgs);
            }
        }
    }


    @Override
    public boolean isChanges()
    {
        return FeatureChanges.isChanges(getChangeTableName());
    }


    @Override
    protected boolean haveFeaturesNotSyncFlag()
    {
        return FeatureChanges.haveFeaturesNotSyncFlag(getChangeTableName());
    }


    @Override
    protected boolean hasFeatureChanges(long featureId)
    {
        return FeatureChanges.isChanges(getChangeTableName(), featureId);
    }


    @Override
    protected boolean hasAttachChanges(
            long featureId,
            long attachId)
    {
        return FeatureChanges.isAttachChanges(getChangeTableName(), featureId, attachId);
    }


    @Override
    public Cursor queryFirstTempFeatureFlags()
    {
        // TODO: move work with temp features into VectorLayer
        String selection = "( 0 != ( " + FIELD_OPERATION + " & " + CHANGE_OPERATION_TEMP + " ) )";

        Cursor cursor =
                FeatureChanges.query(getChangeTableName(), selection, FIELD_ID + " ASC", "1");

        if (null != cursor) {

            if (cursor.moveToFirst()) {
                return cursor;
            }

            cursor.close();
        }

        return null;
    }


    @Override
    public Cursor queryFirstTempAttachFlags()
    {
        // TODO: move work with temp features into VectorLayer
        String selection = "( 0 != ( " + FIELD_OPERATION + " & " + CHANGE_OPERATION_ATTACH +
                " ) ) AND " +
                "( 0 != ( " + FIELD_ATTACH_OPERATION + " & " + CHANGE_OPERATION_TEMP + " ) )";

        Cursor cursor =
                FeatureChanges.query(getChangeTableName(), selection, FIELD_ID + " ASC", "1");

        if (null != cursor) {

            if (cursor.moveToFirst()) {
                return cursor;
            }

            cursor.close();
        }

        return null;
    }


    @Override
    public boolean hasFeatureTempFlag(long featureId)
    {
        // TODO: move work with temp features into VectorLayer
        return FeatureChanges.hasFeatureTempFlag(getChangeTableName(), featureId);
    }


    @Override
    public boolean hasFeatureNotSyncFlag(long featureId)
    {
        return FeatureChanges.hasFeatureNotSyncFlag(getChangeTableName(), featureId);
    }


    @Override
    public boolean hasAttachTempFlag(
            long featureId,
            long attachId)
    {
        // TODO: move work with temp features into VectorLayer
        return FeatureChanges.hasAttachTempFlag(getChangeTableName(), featureId, attachId);
    }


    @Override
    public boolean hasAttachNotSyncFlag(
            long featureId,
            long attachId)
    {
        return FeatureChanges.hasAttachNotSyncFlag(getChangeTableName(), featureId, attachId);
    }


    @Override
    public long setFeatureTempFlag(
            long featureId,
            boolean flag)
    {
        // TODO: move work with temp features into VectorLayer
        if (flag) {
            return FeatureChanges.setFeatureTempFlag(getChangeTableName(), featureId);
        } else {
            return FeatureChanges.deleteFeatureTempFlag(getChangeTableName(), featureId);
        }
    }


    @Override
    public long setFeatureNotSyncFlag(
            long featureId,
            boolean flag)
    {
        if (flag) {
            return FeatureChanges.setFeatureNotSyncFlag(getChangeTableName(), featureId);
        } else {
            return FeatureChanges.deleteFeatureNotSyncFlag(getChangeTableName(), featureId);
        }
    }


    @Override
    public long setAttachTempFlag(
            long featureId,
            long attachId,
            boolean flag)
    {
        // TODO: move work with temp features into VectorLayer
        if (flag) {
            return FeatureChanges.setAttachTempFlag(getChangeTableName(), featureId, attachId);
        } else {
            return FeatureChanges.deleteAttachTempFlag(getChangeTableName(), featureId, attachId);
        }
    }


    @Override
    public long setAttachNotSyncFlag(
            long featureId,
            long attachId,
            boolean flag)
    {
        if (flag) {
            return FeatureChanges.setAttachNotSyncFlag(getChangeTableName(), featureId, attachId);
        } else {
            return FeatureChanges.deleteAttachNotSyncFlag(
                    getChangeTableName(), featureId, attachId);
        }
    }


    @Override
    public int deleteAllTempFeaturesFlags()
    {
        // TODO: move work with temp features into VectorLayer
        String selection = "( 0 != ( " + FIELD_OPERATION + " & " + CHANGE_OPERATION_TEMP + " ) )";

        return FeatureChanges.delete(getChangeTableName(), selection);
    }


    @Override
    public int deleteAllTempAttachesFlags()
    {
        // TODO: move work with temp features into VectorLayer
        String selection = "( 0 != ( " + FIELD_OPERATION + " & " + CHANGE_OPERATION_ATTACH +
                " ) ) AND " +
                "( 0 != ( " + FIELD_ATTACH_OPERATION + " & " + CHANGE_OPERATION_TEMP + " ) )";

        return FeatureChanges.delete(getChangeTableName(), selection);
    }
}

/*
 * Project:  NextGIS Mobile
 * Purpose:  Mobile GIS for Android.
 * Author:   Dmitry Baryshnikov (aka Bishop), bishop.dev@gmail.com
 * Author:   NikitaFeodonit, nfeodonit@yandex.com
 * Author:   Stanislav Petriakov, becomeglory@gmail.com
 * *****************************************************************************
 * Copyright (c) 2015-2017, 2019-2021 NextGIS, info@nextgis.com
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

package com.nextgis.maplib.datasource.ngw;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SyncResult;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import com.hypertrack.hyperlog.HyperLog;
import com.nextgis.maplib.R;
import com.nextgis.maplib.api.IGISApplication;
import com.nextgis.maplib.api.ILayer;
import com.nextgis.maplib.api.INGWLayer;
import com.nextgis.maplib.map.LayerGroup;
import com.nextgis.maplib.map.MapBase;
import com.nextgis.maplib.map.MapContentProviderHelper;
import com.nextgis.maplib.map.NGWVectorLayer;
import com.nextgis.maplib.map.TrackLayer;
import com.nextgis.maplib.util.Constants;
import com.nextgis.maplib.util.ProdLogUtil;
import com.nextgis.maplib.util.NGWUtil;
import com.nextgis.maplib.util.NetworkUtil;
import com.nextgis.maplib.util.SettingsConstants;
import com.nextgis.maplib.util.SyncResultUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static android.content.Context.MODE_MULTI_PROCESS;
import static com.nextgis.maplib.util.Constants.MESSAGE_ALERT_INTENT;
import static com.nextgis.maplib.util.Constants.MESSAGE_EXTRA;
import static com.nextgis.maplib.util.Constants.MESSAGE_NOTIFY_INTENT;
import static com.nextgis.maplib.util.Constants.MESSAGE_TITLE_EXTRA;
import static com.nextgis.maplib.util.Constants.SYNC_NONE;
import static com.nextgis.maplib.util.Constants.TAG;

/* useful links
https://udinic.wordpress.com/2013/07/24/write-your-own-android-sync-adapter/#more-507
http://www.fussylogic.co.uk/blog/?p=1031
http://www.fussylogic.co.uk/blog/?p=1035
http://www.fussylogic.co.uk/blog/?p=1037
http://developer.android.com/training/sync-adapters/creating-sync-adapter.html
https://github.com/elegion/ghsync
http://habrahabr.ru/company/e-Legion/blog/206210/
http://habrahabr.ru/company/e-Legion/blog/216857/
http://stackoverflow.com/questions/5486228/how-do-we-control-an-android-sync-adapter-preference
https://books.google.ru/books?id=SXlMAQAAQBAJ&pg=PA158&lpg=PA158&dq=android:syncAdapterSettingsAction&source=bl&ots=T832S7VvKb&sig=vgNNDHfwyMzvINeHfdfDhu9tREs&hl=ru&sa=X&ei=YviqVIPMF9DgaPOUgOgP&ved=0CFUQ6AEwBw#v=onepage&q=android%3AsyncAdapterSettingsAction&f=false
*/


public class SyncAdapter
        extends AbstractThreadedSyncAdapter
{
    public static final String SYNC_START    = "com.nextgis.maplib.sync_start";
    public static final String SYNC_FINISH   = "com.nextgis.maplib.sync_finish";
    public static final String SYNC_CANCELED = "com.nextgis.maplib.sync_canceled";
    public static final String SYNC_CHANGES  = "com.nextgis.maplib.sync_changes";


    public static final String ACTION_LPATH = "com.nextgis.mobile.util.action.LPATH";

    public static final String EXCEPTION = "exception";
    protected String mError;

    private HashMap<String, Pair<Integer, Integer>> mVersions;

    public SyncAdapter(
            Context context,
            boolean autoInitialize)
    {
        super(context, autoInitialize);
    }


    public SyncAdapter(
            Context context,
            boolean autoInitialize,
            boolean allowParallelSyncs)
    {
        super(context, autoInitialize, allowParallelSyncs);
    }


    /**
     * Warning! When you stop the sync service by ContentResolver.cancelSync() then onPerformSync
     * stops after end of syncing of current NGWVectorLayer. The data structure of the current
     * NGWVectorLayer will be saved.
     * <p/>
     * <b>Description copied from class:</b> AbstractThreadedSyncAdapter Perform a sync for this
     * account. SyncAdapter-specific parameters may be specified in extras, which is guaranteed to
     * not be null. Invocations of this method are guaranteed to be serialized.
     */
    @Override
    public void onPerformSync(
            Account account,
            Bundle bundle,
            String authority,
            ContentProviderClient contentProviderClient,
            SyncResult syncResult)
    {
        IGISApplication gisApp = (IGISApplication) getContext().getApplicationContext();
        Log.d("SSYNC", "super.onPerformSync for " + account.name);
        gisApp.setError(null, null, 0);

        // completePerformSync emits SYNC_FINISH (or SYNC_CANCELED) in the normal/offline-manual paths.
        // Track that so the finally below can broadcast a safety SYNC_FINISH on any early return or
        // uncaught failure — otherwise a UI spinner waiting on finish could hang forever.
        boolean finishBroadcast = false;
        try {
            if (gisApp.isLayerFillServiceBusy()) {
                HyperLog.v(Constants.TAG, "SyncAdapter: onPerformSync skipped (layer fill in progress) for " + account.name);
                Log.d(TAG, "onPerformSync skipped: LayerFillService busy");
                return;
            }

            final boolean manualSync = isManualSync(bundle);
            final NetworkUtil networkUtil = new NetworkUtil(getContext());
            if (!networkUtil.isNetworkAvailable()) {
                HyperLog.v(Constants.TAG, "SyncAdapter: aborted — network unavailable, manual=" + manualSync);
                if (!manualSync) {
                    return;
                }
                SyncResultUtil.beginSync();
                try {
                    gisApp.stopHandler();
                    getContext().sendBroadcast(
                            (new Intent(SYNC_START)).setPackage(getContext().getPackageName()));
                    SyncResultUtil.markNetworkUnavailable(syncResult);
                    completePerformSync(account, syncResult, (MapContentProviderHelper) MapBase.getInstance(), true);
                    finishBroadcast = true;
                } finally {
                    SyncResultUtil.endSync();
                }
                return;
            }

            gisApp.stopHandler();
            HyperLog.v(Constants.TAG, "SyncAdapter: onPerformSync for" + account.name + " ngw part start manual=" + manualSync);
            Log.d(TAG, "onPerformSync");

            SyncResultUtil.beginSync();
            try {
                MapContentProviderHelper mapContentProviderHelper = (MapContentProviderHelper) MapBase.getInstance();

                getContext().sendBroadcast(
                        (new Intent(SYNC_START)).setPackage(getContext().getPackageName()));

                mVersions = new HashMap<>();
                HyperLog.v(Constants.TAG, "SyncAdapter: mapContentProviderHelper is " + mapContentProviderHelper);
                if (null != mapContentProviderHelper) {
                    Log.d("SSYNC", "mapContentProviderHelper!=null start sync");

                    sync(account, mapContentProviderHelper, authority, syncResult, bundle);
                    if (!isCanceled()
                            && (bundle == null || bundle.getString(ACTION_LPATH) == null)) {
                        syncNgwConfigForSyncDisabledLayers(
                                account, mapContentProviderHelper, authority, syncResult);
                    }
                } else {
                    Log.d("SSYNC", "mapContentProviderHelper=null");
                }

                completePerformSync(account, syncResult, mapContentProviderHelper, manualSync);
                finishBroadcast = true;
            } finally {
                SyncResultUtil.endSync();
            }
        } catch (Throwable t) {
            // Never let a sync crash silently: log with stack and mark an I/O error so the framework
            // reschedules instead of treating this as a clean success.
            HyperLog.w(Constants.TAG, "SyncAdapter.onPerformSync uncaught for " + account.name, t);
            syncResult.stats.numIoExceptions++;
        } finally {
            if (!finishBroadcast) {
                Intent finish = new Intent(SYNC_FINISH).setPackage(getContext().getPackageName());
                HyperLog.v(Constants.TAG, "SyncAdapter: SYNC_FINISH (safety/early-exit) sent");
                getContext().sendBroadcast(finish);
            }
        }
    }

    protected static boolean isManualSync(Bundle bundle) {
        return bundle != null && bundle.getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, false);
    }

    private void buildSyncErrorMessage(SyncResult syncResult) {
        mError = "";
        if (syncResult.stats.numIoExceptions > 0) {
            mError += SyncResultUtil.ioErrorMessage(getContext(), syncResult);
        }
        if (syncResult.stats.numParseExceptions > 0) {
            if (mError.length() > 0) {
                mError += "\r\n";
            }
            mError += getContext().getString(R.string.sync_error_parse);
        }
        if (syncResult.stats.numAuthExceptions > 0) {
            if (mError.length() > 0) {
                mError += "\r\n";
            }
            mError += getContext().getString(R.string.error_auth_and_forbidden);
        }
        if (syncResult.stats.numConflictDetectedExceptions > 0) {
            if (mError.length() > 0) {
                mError += "\r\n";
            }
            mError += getContext().getString(R.string.sync_error_conflict);
        }
        if (syncResult.stats.numInserts > 0) {
            if (mError.length() > 0) {
                mError += "\r\n";
            }
            mError += getContext().getString(R.string.sync_error_insert);
        }
        if (syncResult.stats.numUpdates > 0) {
            if (mError.length() > 0) {
                mError += "\r\n";
            }
            mError += getContext().getString(R.string.sync_error_change);
        }
        if (syncResult.stats.numDeletes > 0) {
            if (mError.length() > 0) {
                mError += "\r\n";
            }
            mError += getContext().getString(R.string.sync_error_delete);
        }
        if (syncResult.stats.numEntries > 0) {
            if (mError.length() > 0) {
                mError += "\r\n";
            }
            mError += getContext().getString(R.string.sync_error_server);
        }
        if (syncResult.stats.numSkippedEntries > 0) {
            if (mError.length() > 0) {
                mError += "\r\n";
            }
            mError += getContext().getString(R.string.sync_error_oom);
        }
    }

    private void completePerformSync(
            Account account,
            SyncResult syncResult,
            MapContentProviderHelper mapContentProviderHelper,
            boolean manualSync)
    {
        if (isCanceled()) {
            Log.d(Constants.TAG, "onPerformSync - SYNC_CANCELED is sent");
            HyperLog.v(Constants.TAG, "SyncAdapter: SYNC_CANCELED is sent");
            getContext().sendBroadcast(new Intent(SYNC_CANCELED).setPackage(getContext().getPackageName()));
            return;
        }

        if (manualSync) {
            buildSyncErrorMessage(syncResult);
        } else {
            mError = "";
            if (syncResult.hasError()) {
                HyperLog.v(Constants.TAG, "SyncAdapter: auto sync finished with errors (silent) "
                        + ProdLogUtil.formatSyncResultStats(syncResult));
            }
        }

        if (!TextUtils.isEmpty(mError) || syncResult.hasError()) {
            HyperLog.w(Constants.TAG, "SyncAdapter finish account=\""
                    + ProdLogUtil.truncateForLog(account.name, 96) + "\" manual=" + manualSync
                    + " userMsg=\"" + ProdLogUtil.truncateForLog(mError, 640) + "\" "
                    + ProdLogUtil.formatSyncResultStats(syncResult));
        }

        if (mapContentProviderHelper != null && !syncResult.hasError()) {
            final String accountNameHash = "_" + account.name.hashCode();
            SharedPreferences settings = getContext().getSharedPreferences(Constants.PREFERENCES, MODE_MULTI_PROCESS);
            SharedPreferences.Editor editor = settings.edit();
            long now = System.currentTimeMillis();
            editor.putLong(SettingsConstants.KEY_PREF_LAST_SYNC_TIMESTAMP + accountNameHash, now);
            editor.putLong(SettingsConstants.KEY_PREF_LAST_SYNC_TIMESTAMP, now);
            editor.apply();
        }

        Log.d("SSYNC", "onPerformSync END account - " + account.name);
        Log.d("SSYNC", "onPerformSync END error - " + mError);

        Intent finish = new Intent(SYNC_FINISH);
        if (manualSync && !TextUtils.isEmpty(mError)) {
            finish.putExtra(EXCEPTION, mError);
        }
        HyperLog.v(Constants.TAG, "SyncAdapter: SYNC_FINISH is sent / mError is "
                + (TextUtils.isEmpty(mError) ? null : mError));
        finish.setPackage(getContext().getPackageName());
        getContext().sendBroadcast(finish);
    }


    /**
     * @return true if the map contains an {@link NGWVectorLayer} for this account (any sync type);
     *         used so onPerformSync can run to reconcile NGW description/config from the server
     *         when data sync is disabled for all such layers.
     */
    private static boolean hasNgwVectorLayerForAccount(LayerGroup group, String accountName) {
        for (int i = 0; i < group.getLayerCount(); i++) {
            ILayer layer = group.getLayer(i);
            if (layer instanceof LayerGroup) {
                if (hasNgwVectorLayerForAccount((LayerGroup) layer, accountName)) {
                    return true;
                }
            } else if (layer instanceof NGWVectorLayer) {
                if (accountName.equals(((INGWLayer) layer).getAccountName())) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isSomeToSync(Account account){
        Log.d("SSYNC", "isSomeToSync start for " + account.name);

        String name = getContext().getPackageName() + "_preferences";
        SharedPreferences mSharedPreferences = getContext().getSharedPreferences(name, MODE_MULTI_PROCESS);
        boolean trackSync = mSharedPreferences.getBoolean(SettingsConstants.KEY_PREF_TRACK_SEND, false);

        MapContentProviderHelper layerGroup =(MapContentProviderHelper) MapBase.getInstance();
        if (layerGroup == null) {
            Log.d("SSYNC", "isSomeToSync: map is null, nothing to sync");
            return false;
        }
        if (hasNgwVectorLayerForAccount(layerGroup, account.name)) {
            Log.d("SSYNC", "isSomeToSync result  true (has NGW vector for account)");
            return true;
        }
        List<ILayer> layersToSync = new ArrayList<>();
        for (int i = 0; i < layerGroup.getLayerCount(); i++){
            ILayer layer = layerGroup.getLayer(i);

            if (layer instanceof INGWLayer && !account.name.equals(((INGWLayer)layer).getAccountName()))
                continue;

            if (layer instanceof  INGWLayer && ((INGWLayer) layer).getSyncType() == SYNC_NONE)
                continue;

            // only ngw and track
            if (! ((layer instanceof INGWLayer) || (layer instanceof TrackLayer && trackSync ) ) )
                continue;


            boolean exists = false;
            for (ILayer added : layersToSync){
                if (added.getPath().equals(layer.getPath())){
                    exists = true;
                    break;
                }
            }
            if (!exists)
                layersToSync.add(layer);
        }

        Log.d("SSYNC", "isSomeToSync result  " + String.valueOf(layersToSync.size()>0));

        return layersToSync.size()>0;
    }


    protected void sync(
            Account account,
            LayerGroup layerGroup,
            String authority,
            SyncResult syncResult,
            Bundle bundle)
    {
        Log.d("SSYNC", "sync syncAdapter account - " + account.name);

        HyperLog.v(Constants.TAG, "SyncAdapter: StartSynchronization");
        HyperLog.v(Constants.TAG, "SyncAdapter: total layers for sync in " + layerGroup + " is " + layerGroup.getLayerCount());


        String name = getContext().getPackageName() + "_preferences";
        SharedPreferences mSharedPreferences = getContext().getSharedPreferences(name, MODE_MULTI_PROCESS);
        boolean trackSync = mSharedPreferences.getBoolean(SettingsConstants.KEY_PREF_TRACK_SEND, false);

        List<ILayer> layersToSync = new ArrayList<>();

        Log.d("SSYNC", "pre check bundle != null && bundle.getString(ACTION_LPATH) != null" );

        if (bundle != null && bundle.getString(ACTION_LPATH) != null){
            Log.d("SSYNC", "bundle.getString(ACTION_LPATH) != null PASS" );

            String lpath = bundle.getString(ACTION_LPATH);
            Log.d("SSYNC", "lpath = " + lpath );

            for (int i = 0; i < layerGroup.getLayerCount(); i++) {

                ILayer layer = layerGroup.getLayer(i);
                Log.d("SSYNC", "check layer  " + layer.getName() );
                if (layer instanceof INGWLayer && !account.name.equals(((INGWLayer)layer).getAccountName())) {
                    Log.d("SSYNC", "continue" );
                    continue;
                }

                Log.d("SSYNC", "layer.getPath() : " + layer.getPath().toString() );

                if (layer.getPath().toString().equals(lpath)){
                    Log.d("SSYNC", "layer.getPath().equals(lpath)" );
                    layersToSync.add(layer);
                    break;
                } else
                    Log.d("SSYNC", "NOT layer.getPath().equals(lpath)" );
            }
        }else {
            Log.d("SSYNC", "check bundle != null && bundle.getString(ACTION_LPATH) != null  ELSEEEE" );


            for (int i = 0; i < layerGroup.getLayerCount(); i++) {
                ILayer layer = layerGroup.getLayer(i);

                // no other account
                if (layer instanceof INGWLayer && !account.name.equals(((INGWLayer) layer).getAccountName()))
                    continue;

                if (layer instanceof INGWLayer && ((INGWLayer) layer).getSyncType() == SYNC_NONE)
                    continue;

                boolean exists = false;

                // only ngw and track
                if (!((layer instanceof INGWLayer) || (layer instanceof TrackLayer && trackSync)))
                    continue;

                for (ILayer added : layersToSync) {
                    if (added.getPath().equals(layer.getPath())) {
                        exists = true;
                        break;
                    }
                }
                if (!exists)
                    layersToSync.add(layer);
            }
        }

        for (ILayer layer : layersToSync) {
//            Log.e("RRFRSH", "sync iterate for " + layer.getName());

            if (isCanceled()) {
                HyperLog.v(Constants.TAG, "SyncAdapter: Sync canceled");
                return;
            }
            if (layer instanceof LayerGroup) {
                HyperLog.v(Constants.TAG, "SyncAdapter: start sync " + layer.getName() + " is a layer group");
                sync(account, (LayerGroup) layer, authority, syncResult, bundle);
            } else if (layer instanceof INGWLayer) {
                HyperLog.v(Constants.TAG, "SyncAdapter: start sync " + layer.getName() + " is a NGW layer");
                INGWLayer ngwLayer = (INGWLayer) layer;
                String accountName = ngwLayer.getAccountName();
                if (!mVersions.containsKey(accountName))
                    mVersions.put(accountName, NGWUtil.getNgwVersion(getContext(), accountName));

                Pair<Integer, Integer> ver = mVersions.get(accountName);
                ngwLayer.sync(authority, ver, syncResult);
            } else if (layer instanceof TrackLayer) {
                HyperLog.v(Constants.TAG, "SyncAdapter: start sync" + layer.getName() + " is a tracking layer");
                ((TrackLayer) layer).sync();
            }
            HyperLog.v(Constants.TAG, "SyncAdapter: Sync Ended for " + layer.getName() + " layer");
        }
        Log.d("SSYNC", "END sync syncAdapter account - " + account.name);
    }

    private void syncNgwConfigForSyncDisabledLayers(
            Account account,
            LayerGroup layerGroup,
            String authority,
            SyncResult syncResult) {
        if (isCanceled()) {
            return;
        }
        for (int i = 0; i < layerGroup.getLayerCount(); i++) {
            if (isCanceled()) {
                return;
            }
            ILayer layer = layerGroup.getLayer(i);
            if (layer instanceof LayerGroup) {
                syncNgwConfigForSyncDisabledLayers(
                        account, (LayerGroup) layer, authority, syncResult);
            } else if (layer instanceof NGWVectorLayer) {
                INGWLayer ngw = (INGWLayer) layer;
                if (account.name.equals(ngw.getAccountName()) && ngw.getSyncType() == SYNC_NONE) {
                    ((NGWVectorLayer) layer).syncNgwResourceConfigOnly(authority, syncResult);
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    public static void setSyncPeriod(
            IGISApplication application,
            Bundle extras,
            long pollFrequency)
    {
        Context context = ((Context) application).getApplicationContext();
        final AccountManager accountManager = AccountManager.get(context);
        Log.d(TAG, "SyncAdapter: AccountManager.get(" + context + ")");

//      for (Account account : accountManager.getAccountsByType(application.getAccountsType())) {
//          ContentResolver.addPeriodicSync(account, application.getAuthority(), extras, pollFrequency);
//      }

    }

    public boolean isCanceled()
    {
        return Thread.currentThread().isInterrupted();
    }


    // send broadcast for  MESSAGE_NOTIFY_INTENT
    static public void showNotify(final Context context, final String message , final String title){
        // send broadcast to show notify
        Intent msg = new Intent(MESSAGE_NOTIFY_INTENT);
        msg.putExtra(MESSAGE_EXTRA, message);
        msg.putExtra(MESSAGE_TITLE_EXTRA, title);
        msg.setPackage(context.getPackageName());
        context.sendBroadcast(msg);

    }

}

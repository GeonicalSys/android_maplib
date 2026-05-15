/*
 * Project:  NextGIS Mobile
 * Purpose:  Mobile GIS for Android.
 * Author:   Dmitry Baryshnikov (aka Bishop), bishop.dev@gmail.com
 * Author:   NikitaFeodonit, nfeodonit@yandex.com
 * Author:   Stanislav Petriakov, becomeglory@gmail.com
 * *****************************************************************************
 * Copyright (c) 2012-2017 NextGIS, info@nextgis.com
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

package com.nextgis.maplib.api;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerFuture;
import android.app.Activity;
import android.os.Bundle;

import com.nextgis.maplib.datasource.ngw.Connection;
import com.nextgis.maplib.location.GpsEventSource;
import com.nextgis.maplib.map.LayerFactory;
import com.nextgis.maplib.map.LayerGroup;
import com.nextgis.maplib.map.MLP.AuthInterceptorNG;
import com.nextgis.maplib.map.MapBase;
import com.nextgis.maplib.map.MaplibreMapInteraction;
import com.nextgis.maplib.map.NGWVectorLayer;
import com.nextgis.maplib.util.Constants;

import java.util.ArrayList;
import java.util.List;


/**
 * Interface that all applications using the library should implements. It used in content
 * provider. If your application will not implement this interface - the synchronization vector
 * layers with server will not work.
 *
 * If you plan to fix maplib or maplibui libraries, you nee to clone the sources such way:
 * <ul>
 *     <li>clone maplib and/or maplibui as submodules</li>
 *     <pre>
 *         <code>
 *             git submodule add https://github.com/nextgis/android_maplib.git maplib
 *             git submodule add https://github.com/nextgis/android_maplibui.git maplibui
 *         </code>
 *     </pre>
 *     <li>Modify settings.gradle:</li>
 *     <pre>
 *         <code>
 *             from: include ':app'
 *             to: include ':app', ':maplib', ':maplibui'
 *         </code>
 *     </pre>
 * </ul>
 * <p>
 * Also add https://jitpack.io/ to build.gradle file:
 * <pre>
 *    <code>
 *       allprojects {
 *         repositories {
 *           jcenter()
 *           maven { url "https://jitpack.io" }
 *         }
 *       }
 *       dependencies {
 *         compile 'com.github.User:Repo:Tag'
 *       }
 *    </code>
 * </pre>
 * </p>
 * <p>
 * Note: Expected that project was created via Android studio new project wizard.
 * </p>
 * @author Dmitry Baryshnikov <dmitry.baryshnikov@nextgis.com>
 */
public interface IGISApplication
{
    /**
     * @return A MapBase or any inherited classes or null if not created in application
     */
    MapBase getMap();

    /**
     * @return A authority for sync purposes or empty string if not sync anything
     */
    String getAuthority();

    /**
     * Add account to android account storage
     * @param name Account name (must be uniq)
     * @param url NextGIS Web Server URL
     * @param login User login
     * @param password User password
     * @param token A token returned from NextGIS Web Server (may be empty string)
     * @return true on success or false
     */
    boolean addAccount(String name, String url, String login, String password, String token);

    /**
     * Update account information
     * @param name Account name (the account must be exist)
     * @param key The account key to change (i.e. URL)
     * @param value The new value for key
     */
    void setUserData(String name, String key, String value);

    /**
     * Change password for account
     * @param name Account name (the account must be exist)
     * @param value New password
     */
    void setPassword(String name,String value);

    /**
     * @param accountName Account name
     * @return Account by its name
     */
    Account getAccount(String accountName);

    /**
     * Remove an account
     * @param account Account to remove
     * @return An @see AccountManagerFuture which resolves to a Boolean, true if the account has been successfully removed
     */
    AccountManagerFuture<Boolean> removeAccount(Account account);

    /**
     * @param account Account object
     * @return Account URL
     */
    String getAccountUrl(Account account);

    /**
     * Return some account data
     * @param account account object
     * @param key key to return
     * @return value in user key - value map
     */
    String getAccountUserData(Account account, String key);

    /**
     * @param account Account object
     * @return Account login
     */
    String getAccountLogin(Account account);

    /**
     * @param account Account object
     * @return Account password
     */
    String getAccountPassword(Account account);

    /**
     * @return A GpsEventSource or null if not needed or created in application
     */
    GpsEventSource getGpsEventSource();

    /**
     * Show settings Activity or nothing
     */
    void showSettings(String setting, int code, final Activity activity);

    /**
     * Send target event to analytics
     */
    void sendEvent(String category, String action, String label);

    /**
     * Send screen hit to analytics
     */
    void sendScreen(String name);

    /**
     * Get accounts authenticator type
     */
    String getAccountsType();

    /**
     * Get LayerFactory
     */
    LayerFactory getLayerFactory();




    void stopHandler();

    void startRunnable (final Runnable externalRunnable);


    public void setError (String account, String errorMessage, int erorrCode);

    public String getAccountError();

    public String getErrorMessage();

    public int getErrorCode();

    public boolean isCollectorApplication();

    // part for maplibre interaction
    public void reloadLayerByID(int id);

    public void deleteLayerByID(int id);

    public void addLayerByID(int id);

    public AuthInterceptorNG getAuthInterceptor();

    public void updateAuthPair( String[] authPart);


    public MapBase getMapBase( );

//    public AuthInterceptorNG getAuthInterceptor(){ return interceptorNG;};
//
//    public void updateAuthPair(String layerPart, String authPart){
//        interceptorNG.addAuth(layerPart,authPart);
//    };

    boolean getGetingStyleInProgress();

    void setGetingStyleInProgress(boolean value);


    void startCreateNGWLayerSync(String lpath);

    /* Upstream: refresh offline raster layers after tile download completes. */
    void setLayerToRefresh(int id);
    void removeLayerToRefresh(int id);
    List<Integer> getlayersToRefresh();

    /* Upstream: ensure the default tracks layer exists. */
    void checkTracksLayerExist();

    /**
     * While true, map UI may skip heavy MapLibre reload on layer-changed events
     * (e.g. during a multi-layer {@code LayerFillService} queue).
     */
    boolean isLayerFillBatchDeferringHeavyMapReload();

    void setLayerFillBatchDeferringHeavyMapReload(boolean defer);

    /**
     * After a deferred batch finishes, reload map style and all layers (main thread).
     */
    void requestMapReloadAfterLayerFillBatch();

    /**
     * If {@link #requestMapReloadAfterLayerFillBatch} could not run (e.g. fragment not ready), call from
     * {@code MapFragment} when the map UI is attached again.
     */
    void flushPendingMapReloadAfterLayerFillIfNeeded(MaplibreMapInteraction mapFragment);

    /**
     * Clears the internal &quot;pending map reload after layer fill&quot; flag after a successful reload
     * (e.g. from {@link com.nextgis.maplib.map.MaplibreMapInteraction#reloadMapStyleAndLayersAfterLayerFillBatch()} scheduled retries).
     */
    void clearMapReloadAfterLayerFillPending();

    /**
     * True while {@code LayerFillService} has a non-empty queue or is draining it.
     * Used to skip {@link com.nextgis.maplib.datasource.ngw.SyncAdapter} work so sync does not
     * compete with NGW layer import.
     */
    boolean isLayerFillServiceBusy();

    void setLayerFillServiceBusy(boolean busy);

    /**
     * Register expected NGW vector layers for a collector import (layers being downloaded this run).
     * Clears any previous batch state.
     *
     * @param fullCollectorProjectRemoteIds remote ids of <em>all</em> vector layers in the collector project,
     *        in project list order (as returned by the collector resource). Used to insert new or repaired
     *        layers next to already-present siblings when the map stacks new layers at the top.
     * @param formIds server form id per layer, or 0 when the layer has no form (plain NGW fill)
     */
    void registerCollectorImportBatch(
            int groupId,
            String accountName,
            long[] remoteIds,
            String[] names,
            String[] configJsons,
            long[] formIds,
            long[] fullCollectorProjectRemoteIds);

    /**
     * Called when a collector import task finishes ({@code success} false = unzip/NGW fill failed or cancelled mid-task).
     * {@code remoteId} is the collector resource id (stable tracking id on the fill intent).
     */
    void notifyCollectorLayerFillResult(long remoteId, boolean success);

    /**
     * After the import queue drains, compare the map to the registered batch; remove broken layers and re-enqueue fill.
     * No-op if no batch was registered. Safe to call on the main thread only.
     */
    void finalizeCollectorImportVerifyAndRepairIfNeeded();

    /**
     * If {@code LayerFillService} is already running (e.g. drain worker is blocked in finalize), enqueue repair
     * bundles on that instance synchronously so the queue is non-empty before the worker resumes — avoids
     * {@code startForegroundService} racing with {@code stopSelf}. Call from the main thread only.
     *
     * @param repairBundles same shape as {@code LayerFillService.KEY_REPAIR_BATCH_EXTRAS}
     * @param deferMapReload when true, {@link #setLayerFillBatchDeferringHeavyMapReload(boolean)} with true
     * @return true if at least one task was enqueued on the active service (caller should skip {@code startForegroundService})
     */
    boolean tryEnqueueLayerFillRepairBatch(ArrayList<Bundle> repairBundles, boolean deferMapReload);

    /**
     * After a successful standalone vector/NGW fill (not a collector batch), register extras for post-drain verification.
     * The bundle must contain layer group id and the same extras used to enqueue the fill task (for re-queue on repair).
     * Local GeoJSON fills should also include the map layer id under key {@code standalone_verify_layer_id}.
     */
    void registerStandaloneLayerFillVerifyAfterSuccess(Bundle fillTaskIntentExtrasCopy);

    /**
     * After the import queue drains, verify standalone fills: missing layer or missing SQLite table → delete and re-enqueue
     * (same idea as {@link #finalizeCollectorImportVerifyAndRepairIfNeeded()}, limited repair waves).
     * Safe to call on the main thread only.
     */
    void finalizeStandaloneLayerFillVerifyIfNeeded();

    /**
     * Drop an in-progress collector import batch without verification (e.g. user cancelled fill).
     */
    void clearCollectorImportBatch();

    /**
     * True while a collector import batch is registered ({@link #registerCollectorImportBatch}) and not yet cleared.
     * Used with {@link #isLayerFillBatchDeferringHeavyMapReload()} to keep blocking progress UI until verify/repair finishes.
     */
    boolean hasCollectorImportBatchRegistered();

    /**
     * Local NGW vector layer schema no longer matches server metadata during sync.
     * Implementation should remove the layer and enqueue {@code LayerFillService} refill on the main thread,
     * unless unsynced local edits exist (then notify user only).
     */
    void scheduleNgwLayerRebuildAfterSchemaMismatch(NGWVectorLayer layer);

}

package com.nextgis.maplib.reliability;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerFuture;
import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import com.nextgis.maplib.datasource.ngw.CollectorProjectItem;
import com.nextgis.maplib.datasource.ngw.Connection;
import com.nextgis.maplib.location.GpsEventSource;
import com.nextgis.maplib.map.LayerFactory;
import com.nextgis.maplib.map.LayerGroup;
import com.nextgis.maplib.map.MLP.AuthInterceptorNG;
import com.nextgis.maplib.map.MapBase;
import com.nextgis.maplib.map.MaplibreMapInteraction;
import com.nextgis.maplib.map.NGWRasterLayer;
import com.nextgis.maplib.map.NGWVectorLayer;
import com.nextgis.maplib.util.Constants;
import java.util.ArrayList;
import java.util.List;
import android.app.Application;
import com.nextgis.maplib.api.IGISApplication;
public class TestGISApplication extends Application implements IGISApplication {
    @Override public MapBase getMap() { return null; }
    @Override public String getAuthority() { return "test.reliability"; }
    @Override public boolean addAccount(String name, String url, String login, String password, String token) { return false; }
    @Override public void setUserData(String name, String key, String value) {  }
    @Override public void setPassword(String name,String value) {  }
    @Override public Account getAccount(String accountName) { return null; }
    @Override public AccountManagerFuture<Boolean> removeAccount(Account account) { return null; }
    @Override public String getAccountUrl(Account account) { return null; }
    @Override public String getAccountUserData(Account account, String key) { return null; }
    @Override public String getAccountLogin(Account account) { return null; }
    @Override public String getAccountPassword(Account account) { return null; }
    @Override public GpsEventSource getGpsEventSource() { return null; }
    @Override public void showSettings(String setting, int code, final Activity activity) {  }
    @Override public void sendEvent(String category, String action, String label) {  }
    @Override public void sendScreen(String name) {  }
    @Override public String getAccountsType() { return null; }
    @Override public LayerFactory getLayerFactory() { return null; }
    @Override public void stopHandler() {  }
    @Override public void startRunnable(final Runnable externalRunnable) {  }
    @Override public void setError(String account, String errorMessage, int erorrCode) {  }
    @Override public String getAccountError() { return null; }
    @Override public String getErrorMessage() { return null; }
    @Override public int getErrorCode() { return 0; }
    @Override public boolean isCollectorApplication() { return false; }
    @Override public void reloadLayerByID(int id) {  }
    @Override public void deleteLayerByID(int id) {  }
    @Override public void addLayerByID(int id) {  }
    @Override public AuthInterceptorNG getAuthInterceptor() { return null; }
    @Override public void updateAuthPair(String[] authPart) {  }
    @Override public MapBase getMapBase() { return null; }
    @Override public boolean getGetingStyleInProgress() { return false; }
    @Override public void setGetingStyleInProgress(boolean value) {  }
    @Override public void startCreateNGWLayerSync(String lpath) {  }
    @Override public void setLayerToRefresh(int id) {  }
    @Override public void removeLayerToRefresh(int id) {  }
    @Override public List<Integer> getlayersToRefresh() { return null; }
    @Override public void checkTracksLayerExist() {  }
    @Override public boolean isLayerFillBatchDeferringHeavyMapReload() { return false; }
    @Override public void setLayerFillBatchDeferringHeavyMapReload(boolean defer) {  }
    @Override public void requestMapReloadAfterLayerFillBatch() {  }
    @Override public void flushPendingMapReloadAfterLayerFillIfNeeded(MaplibreMapInteraction mapFragment) {  }
    @Override public void clearMapReloadAfterLayerFillPending() {  }
    @Override public boolean isLayerFillServiceBusy() { return false; }
    @Override public void setLayerFillServiceBusy(boolean busy) {  }
    @Override public boolean repairProjectIntegrityBeforeSync(String accountName) { return false; }
    @Override public boolean registerCollectorImportBatch(int groupId,
            String accountName,
            String collectorProjectUid,
            long[] remoteIds,
            String[] names,
            String[] configJsons,
            long[] formIds,
            boolean[] collectorEditables,
            long[] fullCollectorProjectRemoteIds) { return false; }
    @Override public void notifyCollectorLayerFillResult(long remoteId, boolean success) {  }
    @Override public void finalizeCollectorImportVerifyAndRepairIfNeeded() {  }
    @Override public boolean tryEnqueueLayerFillRepairBatch(ArrayList<Bundle> repairBundles, boolean deferMapReload) { return false; }
    @Override public void registerStandaloneLayerFillVerifyAfterSuccess(Bundle fillTaskIntentExtrasCopy) {  }
    @Override public void finalizeStandaloneLayerFillVerifyIfNeeded() {  }
    @Override public void clearCollectorImportBatch() {  }
    @Override public boolean hasCollectorImportBatchRegistered() { return false; }
    @Override public void scheduleNgwLayerRebuildAfterSchemaMismatch(NGWVectorLayer layer,
            String mismatchSignature) {  }
    @Override public void scheduleCollectorProjectLayerFills(int groupId,
            String accountName,
            String collectorProjectUid,
            long[] remoteIds,
            String[] names,
            String[] configJsons,
            long[] formIds,
            boolean[] collectorEditables,
            long[] fullCollectorProjectRemoteIds) {  }
    @Override public void addCollectorRasterStyleLayers(int groupId,
            String accountName,
            String collectorProjectUid,
            CollectorProjectItem[] items,
            int[] collectorOrders,
            long[] fullCollectorProjectRemoteIds) {  }
    @Override public void applyCollectorRasterStyleProjectState(NGWRasterLayer layer,
            CollectorProjectItem item,
            int collectorOrder,
            long[] fullCollectorProjectRemoteIds) {  }
    @Override public void removeCollectorRasterStyleLayer(NGWRasterLayer layer) {  }
    @Override public void applyCollectorLayerForm(NGWVectorLayer layer,
            long formId,
            String formHash) {  }
    @Override public void scheduleCollectorLayerRebuildFromProject(NGWVectorLayer layer,
            long formId,
            int collectorOrder,
            long[] fullCollectorProjectRemoteIds,
            boolean collectorEditable,
            String layerConfigJson) {  }
    @Override public void applyCollectorLayerProjectState(NGWVectorLayer layer,
            int collectorOrder,
            long[] fullCollectorProjectRemoteIds,
            boolean collectorEditable) {  }
    @Override public void scheduleCollectorLayerRemovalWithBackup(NGWVectorLayer layer) {  }
    @Override public boolean backupEditableLayerFeatures(NGWVectorLayer layer,
            java.util.Collection<Long> featureIds,
            String reason) { return false; }
    @Override public boolean backupEditableLayerData(NGWVectorLayer layer, String reason) { return false; }
    @Override public Context getSelfContext() { return this; }
}

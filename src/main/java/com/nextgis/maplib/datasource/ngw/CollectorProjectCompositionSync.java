/*
 * Project:  NextGIS Mobile
 * Purpose:  Collector project composition sync foundation.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.nextgis.maplib.datasource.ngw;

import android.accounts.Account;
import android.content.Context;
import android.text.TextUtils;

import com.hypertrack.hyperlog.HyperLog;
import com.nextgis.maplib.api.IGISApplication;
import com.nextgis.maplib.api.ILayer;
import com.nextgis.maplib.map.CollectorProjectMetadata;
import com.nextgis.maplib.map.LayerGroup;
import com.nextgis.maplib.map.LayerOriginMetadata;
import com.nextgis.maplib.map.NGWVectorLayer;
import com.nextgis.maplib.util.AccountUtil;
import com.nextgis.maplib.util.Constants;
import com.nextgis.maplib.util.HttpResponse;
import com.nextgis.maplib.util.LayerConfigDiff;
import com.nextgis.maplib.util.LayerConfigUtil;
import com.nextgis.maplib.util.LayerFormHashUtil;
import com.nextgis.maplib.util.NGWUtil;
import com.nextgis.maplib.util.NetworkUtil;
import com.nextgis.maplib.util.NgwResmetaUtil;
import com.nextgis.maplib.util.SettingsConstants;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Synchronizes local project-managed layers with live NGW Collector project contents.
 *
 * Collector architecture foundation: snapshot and diff logic are intentionally kept in maplib,
 * while destructive or UI-bound actions are delegated to {@link IGISApplication}. This keeps the
 * future multi-project composition/form sync model testable without adding a maplib -> maplibui
 * dependency.
 */
public final class CollectorProjectCompositionSync {
    private static final String LOG_PREFIX = "Collector composition sync";
    private static final String DRY_RUN_PREFIX = "Collector composition dry-run";

    private static final String TYPE_ADD = "add";
    private static final String TYPE_REMOVE = "remove";
    private static final String TYPE_REORDER = "reorder";
    private static final String TYPE_UPDATE_FORM = "update_form";
    private static final String TYPE_UPDATE_CONFIG = "update_config";
    private static final String TYPE_UPDATE_EDITABLE = "update_editable";

    private CollectorProjectCompositionSync() {
    }

    public static void runDryRunForAccount(
            Context context,
            Account account,
            LayerGroup rootGroup) {
        runForAccount(context, account, rootGroup, false);
    }

    public static void runApplyForAccount(
            Context context,
            Account account,
            LayerGroup rootGroup) {
        runForAccount(context, account, rootGroup, true);
    }

    private static void runForAccount(
            Context context,
            Account account,
            LayerGroup rootGroup,
            boolean apply) {
        if (context == null || account == null || rootGroup == null) {
            return;
        }
        List<LayerGroup> projectGroups = new ArrayList<>();
        collectProjectGroups(rootGroup, account.name, projectGroups);
        if (projectGroups.isEmpty()) {
            HyperLog.v(Constants.TAG, logPrefix(apply) + ": no local collector projects for account="
                    + account.name);
            return;
        }

        AccountUtil.AccountData accountData;
        try {
            accountData = AccountUtil.getAccountData(context, account.name);
        } catch (IllegalStateException e) {
            HyperLog.w(Constants.TAG, logPrefix(apply) + ": account data missing for "
                    + account.name + ": " + e.getMessage());
            return;
        }

        for (LayerGroup projectGroup : projectGroups) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            CollectorProjectMetadata metadata = projectGroup.getCollectorProjectMetadata();
            CollectorProjectSnapshot remote = fetchRemoteSnapshot(accountData, metadata);
            if (remote == null) {
                HyperLog.w(Constants.TAG, logPrefix(apply) + ": snapshot unavailable projectUid="
                        + metadata.getProjectUid());
                recordCompositionDiagnostics(
                        projectGroup,
                        metadata,
                        "snapshot_unavailable",
                        true,
                        "snapshot_unavailable");
                continue;
            }
            CollectorProjectDiff diff = CollectorProjectDiff.compare(projectGroup, metadata, remote);
            logDiff(metadata, remote, diff, apply);
            recordCompositionDiagnostics(
                    projectGroup,
                    metadata,
                    diff.toDiagnosticSummary(remote),
                    remote.isIncomplete(),
                    remote.isIncomplete() ? "snapshot_incomplete" : null);
            if (apply) {
                applyDiff(context, projectGroup, metadata, remote, diff);
            }
        }
    }

    private static void recordCompositionDiagnostics(
            LayerGroup projectGroup,
            CollectorProjectMetadata metadata,
            String summary,
            boolean incomplete,
            String error) {
        if (projectGroup == null || metadata == null) {
            return;
        }
        metadata.setLastCompositionDiagnostics(
                System.currentTimeMillis(),
                summary,
                incomplete,
                error);
        try {
            if (!projectGroup.save()) {
                HyperLog.w(Constants.TAG, LOG_PREFIX
                        + ": failed to save composition diagnostics uid="
                        + metadata.getProjectUid());
            }
        } catch (RuntimeException e) {
            HyperLog.w(Constants.TAG, LOG_PREFIX
                    + ": diagnostics save crashed uid=" + metadata.getProjectUid()
                    + ": " + e.getMessage(), e);
        }
    }

    private static void collectProjectGroups(
            LayerGroup group,
            String accountName,
            List<LayerGroup> out) {
        CollectorProjectMetadata metadata = group.getCollectorProjectMetadata();
        if (metadata != null
                && metadata.isValid()
                && metadata.isCompositionSyncEnabled()
                && accountName.equals(metadata.getAccountName())) {
            out.add(group);
        }
        for (int i = 0; i < group.getLayerCount(); i++) {
            ILayer child = group.getLayer(i);
            if (child instanceof LayerGroup) {
                collectProjectGroups((LayerGroup) child, accountName, out);
            }
        }
    }

    private static CollectorProjectSnapshot fetchRemoteSnapshot(
            AccountUtil.AccountData accountData,
            CollectorProjectMetadata metadata) {
        if (accountData == null || metadata == null || !metadata.isValid()) {
            return null;
        }
        try {
            String url = NGWUtil.getResourceUrl(accountData.url, metadata.getProjectRemoteId());
            HttpResponse response = NetworkUtil.get(url, accountData.login, accountData.password, false);
            if (!response.isOk()) {
                HyperLog.w(Constants.TAG, LOG_PREFIX + ": project GET HTTP "
                        + response.getResponseCode() + " projectUid=" + metadata.getProjectUid());
                return null;
            }
            JSONObject root = new JSONObject(response.getResponseBody());
            JSONObject collectorProject = extractCollectorProject(root);
            if (collectorProject == null) {
                HyperLog.w(Constants.TAG, LOG_PREFIX + ": no collector_project in resource "
                        + metadata.getProjectRemoteId());
                return null;
            }
            CollectorProjectSnapshot snapshot = new CollectorProjectSnapshot(
                    metadata.getProjectUid(),
                    metadata.getAccountName(),
                    metadata.getProjectRemoteId(),
                    readProjectName(root, metadata.getName()),
                    readProjectDistrict(root, metadata.getDistrict()));
            JSONObject rootItem = collectorProject.optJSONObject("root_item");
            JSONArray children = rootItem != null ? rootItem.optJSONArray("children") : null;
            if (children == null) {
                HyperLog.w(Constants.TAG, LOG_PREFIX + ": collector_project has no root children "
                        + metadata.getProjectUid());
                snapshot.setIncomplete(true);
                return snapshot;
            }
            walkCollectorItems(accountData, children, snapshot);
            return snapshot;
        } catch (IOException | JSONException e) {
            HyperLog.w(Constants.TAG, LOG_PREFIX + ": fetch failed projectUid="
                    + metadata.getProjectUid() + ": " + e.getMessage(), e);
            return null;
        }
    }

    private static JSONObject extractCollectorProject(JSONObject envelope) {
        JSONObject res = envelope.optJSONObject("resource");
        if (res != null && res.has("collector_project")) {
            return res.optJSONObject("collector_project");
        }
        return envelope.optJSONObject("collector_project");
    }

    private static String readProjectName(JSONObject root, String fallback) {
        JSONObject res = root.optJSONObject("resource");
        String name = res != null ? res.optString("display_name", null) : null;
        return TextUtils.isEmpty(name) ? fallback : name;
    }

    private static String readProjectDistrict(JSONObject root, String fallback) {
        String district = NgwResmetaUtil.getResmetaItemString(root, "district");
        return TextUtils.isEmpty(district) ? fallback : district;
    }

    private static void walkCollectorItems(
            AccountUtil.AccountData accountData,
            JSONArray children,
            CollectorProjectSnapshot snapshot) throws JSONException {
        for (int i = 0; i < children.length(); i++) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            JSONObject item = children.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String itemType = item.optString("item_type", "");
            if ("group".equalsIgnoreCase(itemType) && item.has("children")) {
                walkCollectorItems(accountData, item.getJSONArray("children"), snapshot);
                continue;
            }
            if (item.has("children") && !item.has("resource")) {
                walkCollectorItems(accountData, item.getJSONArray("children"), snapshot);
                continue;
            }
            tryAddLayer(accountData, item, snapshot);
        }
    }

    private static void tryAddLayer(
            AccountUtil.AccountData accountData,
            JSONObject item,
            CollectorProjectSnapshot snapshot) {
        long resourceId = extractLayerResourceId(item);
        if (resourceId <= 0L || snapshot.hasLayer(resourceId)) {
            return;
        }

        boolean collectorEditable = parseCollectorItemEditable(item);
        JSONObject localResource = item.optJSONObject("resource");
        String fallbackName = readDisplayName(localResource, "resource-" + resourceId);
        String fallbackCls = readCls(localResource);
        JSONObject envelope = null;
        try {
            envelope = fetchResourceEnvelope(accountData, resourceId);
        } catch (IOException | JSONException e) {
            snapshot.setIncomplete(true);
            HyperLog.w(Constants.TAG, LOG_PREFIX + ": layer fetch failed rid=" + resourceId
                    + ": " + e.getMessage(), e);
        }

        JSONObject resource = envelope != null ? envelope.optJSONObject("resource") : localResource;
        String cls = readCls(resource);
        if (TextUtils.isEmpty(cls)) {
            cls = fallbackCls;
        }
        if (!isVectorLikeLayer(cls)) {
            return;
        }
        String name = readDisplayName(resource, fallbackName);
        long formId = fetchFirstFormId(accountData, resourceId);
        String formHash = formId > 0L ? fetchFormHash(accountData, formId) : "";
        String descriptionRaw = envelope != null
                ? LayerConfigUtil.extractNgwResourceDescriptionJson(envelope)
                : null;
        String configHash = TextUtils.isEmpty(descriptionRaw)
                ? ""
                : LayerConfigUtil.md5(descriptionRaw.trim());

        snapshot.addLayer(new CollectorProjectLayerSnapshot(
                resourceId,
                snapshot.getLayerCount(),
                name,
                cls,
                collectorEditable,
                formId,
                formHash,
                configHash,
                descriptionRaw));
    }

    private static JSONObject fetchResourceEnvelope(
            AccountUtil.AccountData accountData,
            long resourceId) throws IOException, JSONException {
        String url = NGWUtil.getResourceUrl(accountData.url, resourceId);
        HttpResponse response = NetworkUtil.get(url, accountData.login, accountData.password, false);
        if (!response.isOk()) {
            throw new IOException("HTTP " + response.getResponseCode());
        }
        JSONObject root = new JSONObject(response.getResponseBody());
        if (root.has("resource")) {
            return root;
        }
        if (root.has("cls") && root.has("id")) {
            JSONObject out = new JSONObject();
            out.put("resource", root);
            return out;
        }
        throw new JSONException("resource envelope missing");
    }

    private static long fetchFirstFormId(AccountUtil.AccountData accountData, long resourceId) {
        try {
            String url = NGWUtil.getResourceChildrenUrl(accountData.url, resourceId);
            HttpResponse response = NetworkUtil.get(url, accountData.login, accountData.password, false);
            if (!response.isOk()) {
                return 0L;
            }
            JSONArray children = new JSONArray(response.getResponseBody());
            for (int i = 0; i < children.length(); i++) {
                JSONObject child = children.optJSONObject(i);
                JSONObject resource = child != null ? child.optJSONObject("resource") : null;
                if (resource != null
                        && "formbuilder_form".equals(resource.optString("cls", ""))) {
                    return resource.optLong("id", 0L);
                }
            }
        } catch (IOException | JSONException e) {
            HyperLog.w(Constants.TAG, LOG_PREFIX + ": form lookup failed rid=" + resourceId
                    + ": " + e.getMessage());
        }
        return 0L;
    }

    private static String fetchFormHash(AccountUtil.AccountData accountData, long formId) {
        if (accountData == null || formId <= 0L) {
            return "";
        }
        HttpURLConnection conn = null;
        try {
            String url = NGWUtil.getFormUrl(accountData.url, formId);
            conn = NetworkUtil.getHttpConnection(
                    NetworkUtil.HTTP_GET, url, accountData.login, accountData.password);
            if (conn == null) {
                return "";
            }
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM
                    && "http".equals(conn.getURL().getProtocol())) {
                String https = conn.getURL().toString().replace("http", "https");
                conn.disconnect();
                conn = NetworkUtil.getHttpConnection(
                        NetworkUtil.HTTP_GET, https, accountData.login, accountData.password);
                code = conn != null ? conn.getResponseCode() : -1;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                HyperLog.w(Constants.TAG, LOG_PREFIX + ": form hash HTTP " + code
                        + " formId=" + formId);
                return "";
            }
            InputStream in = conn.getInputStream();
            try {
                return LayerFormHashUtil.md5NgfpZip(in);
            } finally {
                in.close();
            }
        } catch (IOException e) {
            HyperLog.w(Constants.TAG, LOG_PREFIX + ": form hash failed formId=" + formId
                    + ": " + e.getMessage());
            return "";
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static boolean isVectorLikeLayer(String cls) {
        return "vector_layer".equals(cls) || "postgis_layer".equals(cls);
    }

    private static String readCls(JSONObject resource) {
        return resource == null ? "" : resource.optString("cls", "");
    }

    private static String readDisplayName(JSONObject resource, String fallback) {
        if (resource == null) {
            return fallback;
        }
        String name = resource.optString("display_name", null);
        return TextUtils.isEmpty(name) ? fallback : name;
    }

    private static boolean parseCollectorItemEditable(JSONObject item) {
        final String[] keys = {"editable", "layer_editable", "is_editable"};
        for (String key : keys) {
            if (item.has(key) && !item.isNull(key)) {
                return item.optBoolean(key, true);
            }
        }
        return true;
    }

    private static long extractLayerResourceId(JSONObject item) {
        JSONObject resource = item.optJSONObject("resource");
        if (resource != null) {
            long rid = readIdKey(resource, "id");
            if (rid > 0L) {
                return rid;
            }
        }
        String[] keys = {
                "resource_id",
                "layer_id",
                "vector_layer_id",
                "feature_layer_id",
                "layer_resource_id"
        };
        for (String key : keys) {
            long rid = readIdKey(item, key);
            if (rid > 0L) {
                return rid;
            }
        }
        return readIdKey(item, "id");
    }

    private static long readIdKey(JSONObject item, String key) {
        if (item == null || !item.has(key) || item.isNull(key)) {
            return -1L;
        }
        long value = item.optLong(key, -1L);
        if (value > 0L) {
            return value;
        }
        String raw = item.optString(key, "");
        if (TextUtils.isEmpty(raw)) {
            return -1L;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static void logDiff(
            CollectorProjectMetadata metadata,
            CollectorProjectSnapshot remote,
            CollectorProjectDiff diff,
            boolean apply) {
        String prefix = logPrefix(apply);
        HyperLog.d(Constants.TAG, prefix
                + " project=\"" + safe(metadata.getName()) + "\""
                + " uid=" + metadata.getProjectUid()
                + " remoteLayers=" + remote.getLayerCount()
                + " localManagedLayers=" + diff.getLocalManagedLayerCount()
                + " incomplete=" + remote.isIncomplete()
                + " add=" + diff.count(TYPE_ADD)
                + " remove=" + diff.count(TYPE_REMOVE)
                + " reorder=" + diff.count(TYPE_REORDER)
                + " updateForm=" + diff.count(TYPE_UPDATE_FORM)
                + " updateConfig=" + diff.count(TYPE_UPDATE_CONFIG)
                + " updateEditable=" + diff.count(TYPE_UPDATE_EDITABLE));
        for (DiffEntry entry : diff.getEntries()) {
            HyperLog.d(Constants.TAG, prefix + " diff " + entry);
        }
    }

    private static void applyDiff(
            Context context,
            LayerGroup projectGroup,
            CollectorProjectMetadata metadata,
            CollectorProjectSnapshot remote,
            CollectorProjectDiff diff) {
        if (context == null || projectGroup == null || metadata == null || remote == null
                || diff == null || diff.getEntries().isEmpty()) {
            return;
        }
        if (remote.isIncomplete()) {
            HyperLog.w(Constants.TAG, LOG_PREFIX + ": apply skipped for incomplete snapshot uid="
                    + metadata.getProjectUid());
            return;
        }
        if (!(context.getApplicationContext() instanceof IGISApplication)) {
            HyperLog.w(Constants.TAG, LOG_PREFIX + ": application does not implement IGISApplication");
            return;
        }
        IGISApplication app = (IGISApplication) context.getApplicationContext();
        long[] fullOrder = remote.getRemoteIdsInOrder();

        List<CollectorProjectLayerSnapshot> additions = new ArrayList<>();
        for (DiffEntry entry : diff.getEntries()) {
            if (TYPE_ADD.equals(entry.getType()) && entry.getRemoteLayer() != null) {
                additions.add(entry.getRemoteLayer());
            }
        }

        if (!additions.isEmpty()) {
            scheduleAdditions(app, projectGroup, metadata, additions, fullOrder);
        }

        for (DiffEntry entry : diff.getEntries()) {
            String type = entry.getType();
            if (TYPE_REMOVE.equals(type)) {
                if (entry.getLocalLayer() != null) {
                    app.scheduleCollectorLayerRemovalWithBackup(entry.getLocalLayer());
                }
            } else if (TYPE_UPDATE_FORM.equals(type)) {
                CollectorProjectLayerSnapshot remoteLayer = entry.getRemoteLayer();
                NGWVectorLayer localLayer = entry.getLocalLayer();
                if (remoteLayer != null && localLayer != null) {
                    app.applyCollectorLayerForm(
                            localLayer,
                            remoteLayer.getFormId(),
                            remoteLayer.getFormHash());
                }
            } else if (TYPE_REORDER.equals(type) || TYPE_UPDATE_EDITABLE.equals(type)) {
                if (entry.getLocalLayer() != null
                        && entry.getRemoteLayer() != null) {
                    CollectorProjectLayerSnapshot remoteLayer = entry.getRemoteLayer();
                    app.applyCollectorLayerProjectState(
                            entry.getLocalLayer(),
                            remoteLayer.getOrder(),
                            fullOrder,
                            remoteLayer.isCollectorEditable());
                }
            } else if (TYPE_UPDATE_CONFIG.equals(type)) {
                applyCollectorLayerConfig(app, entry.getLocalLayer(), entry.getRemoteLayer(), fullOrder);
            }
        }
    }

    private static void applyCollectorLayerConfig(
            IGISApplication app,
            NGWVectorLayer localLayer,
            CollectorProjectLayerSnapshot remoteLayer,
            long[] fullOrder) {
        if (app == null || localLayer == null || remoteLayer == null) {
            return;
        }
        String rawConfig = remoteLayer.getConfigJson();
        if (TextUtils.isEmpty(rawConfig)) {
            HyperLog.v(Constants.TAG, LOG_PREFIX + ": config diff has no raw config rid="
                    + remoteLayer.getRemoteId());
            return;
        }
        String configHash = remoteLayer.getConfigHash();
        if (TextUtils.isEmpty(configHash)) {
            configHash = LayerConfigUtil.md5(rawConfig.trim());
        }
        try {
            JSONObject serverCfg = LayerConfigUtil.parseLayerConfigObject(rawConfig);
            LayerConfigDiff configDiff = LayerConfigDiff.compare(serverCfg, localLayer);
            if (configDiff.isHard()) {
                HyperLog.v(Constants.TAG, LOG_PREFIX + ": config hard diff rid="
                        + remoteLayer.getRemoteId()
                        + " layer=\"" + localLayer.getName() + "\" reason="
                        + configDiff.getHardReason() + " - scheduling backup-aware refill");
                app.scheduleCollectorLayerRebuildFromProject(
                        localLayer,
                        remoteLayer.getFormId(),
                        remoteLayer.getOrder(),
                        fullOrder,
                        remoteLayer.isCollectorEditable(),
                        rawConfig);
                return;
            }

            boolean changed = false;
            if (configDiff.isSoftOnly()) {
                changed = localLayer.applySoftConfigUpdate(configDiff);
            }
            if (localLayer.wasLastSoftConfigUpdateIncomplete()) {
                HyperLog.w(Constants.TAG, LOG_PREFIX + ": config soft update incomplete rid="
                        + remoteLayer.getRemoteId() + " layer=\"" + localLayer.getName()
                        + "\" - keeping old hash for retry");
                return;
            }
            localLayer.getPreferences().edit()
                    .putString(SettingsConstants.KEY_PREF_LAST_CONFIG_HASH, configHash)
                    .apply();
            if (!changed && configDiff.isMatch()) {
                HyperLog.v(Constants.TAG, LOG_PREFIX + ": config hash recorded after match rid="
                        + remoteLayer.getRemoteId() + " layer=\"" + localLayer.getName() + "\"");
            } else {
                HyperLog.v(Constants.TAG, LOG_PREFIX + ": config soft update applied rid="
                        + remoteLayer.getRemoteId() + " layer=\"" + localLayer.getName()
                        + "\" changed=" + changed);
            }
        } catch (JSONException e) {
            HyperLog.w(Constants.TAG, LOG_PREFIX + ": config parse failed rid="
                    + remoteLayer.getRemoteId() + " layer=\"" + localLayer.getName()
                    + "\": " + e.getMessage());
        }
    }

    private static void scheduleAdditions(
            IGISApplication app,
            LayerGroup projectGroup,
            CollectorProjectMetadata metadata,
            List<CollectorProjectLayerSnapshot> additions,
            long[] fullOrder) {
        int n = additions.size();
        long[] remoteIds = new long[n];
        String[] names = new String[n];
        String[] configJsons = new String[n];
        long[] formIds = new long[n];
        boolean[] editables = new boolean[n];
        for (int i = 0; i < n; i++) {
            CollectorProjectLayerSnapshot layer = additions.get(i);
            remoteIds[i] = layer.getRemoteId();
            names[i] = layer.getName();
            configJsons[i] = layer.getConfigJson();
            formIds[i] = layer.getFormId();
            editables[i] = layer.isCollectorEditable();
        }
        app.scheduleCollectorProjectLayerFills(
                projectGroup.getId(),
                metadata.getAccountName(),
                metadata.getProjectUid(),
                remoteIds,
                names,
                configJsons,
                formIds,
                editables,
                fullOrder);
    }

    private static String logPrefix(boolean apply) {
        return apply ? LOG_PREFIX : DRY_RUN_PREFIX;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class CollectorProjectSnapshot {
        private final String mProjectUid;
        private final String mAccountName;
        private final long mProjectRemoteId;
        private final String mName;
        private final String mDistrict;
        private final List<CollectorProjectLayerSnapshot> mLayers = new ArrayList<>();
        private final Map<Long, CollectorProjectLayerSnapshot> mLayersByRemoteId =
                new LinkedHashMap<>();
        private boolean mIncomplete;

        CollectorProjectSnapshot(
                String projectUid,
                String accountName,
                long projectRemoteId,
                String name,
                String district) {
            mProjectUid = projectUid;
            mAccountName = accountName;
            mProjectRemoteId = projectRemoteId;
            mName = name;
            mDistrict = district;
        }

        void addLayer(CollectorProjectLayerSnapshot layer) {
            mLayers.add(layer);
            mLayersByRemoteId.put(layer.getRemoteId(), layer);
        }

        boolean hasLayer(long remoteId) {
            return mLayersByRemoteId.containsKey(remoteId);
        }

        CollectorProjectLayerSnapshot getLayer(long remoteId) {
            return mLayersByRemoteId.get(remoteId);
        }

        List<CollectorProjectLayerSnapshot> getLayers() {
            return mLayers;
        }

        long[] getRemoteIdsInOrder() {
            long[] ids = new long[mLayers.size()];
            for (int i = 0; i < mLayers.size(); i++) {
                ids[i] = mLayers.get(i).getRemoteId();
            }
            return ids;
        }

        int getLayerCount() {
            return mLayers.size();
        }

        boolean isIncomplete() {
            return mIncomplete;
        }

        void setIncomplete(boolean incomplete) {
            mIncomplete = incomplete;
        }

        @Override
        public String toString() {
            return "Snapshot{"
                    + "uid=" + mProjectUid
                    + ", account=" + mAccountName
                    + ", projectRemoteId=" + mProjectRemoteId
                    + ", name=" + mName
                    + ", district=" + mDistrict
                    + ", layers=" + mLayers.size()
                    + ", incomplete=" + mIncomplete
                    + '}';
        }
    }

    private static final class CollectorProjectLayerSnapshot {
        private final long mRemoteId;
        private final int mOrder;
        private final String mName;
        private final String mCls;
        private final boolean mCollectorEditable;
        private final long mFormId;
        private final String mFormHash;
        private final String mConfigHash;
        private final String mConfigJson;

        CollectorProjectLayerSnapshot(
                long remoteId,
                int order,
                String name,
                String cls,
                boolean collectorEditable,
                long formId,
                String formHash,
                String configHash,
                String configJson) {
            mRemoteId = remoteId;
            mOrder = order;
            mName = name;
            mCls = cls;
            mCollectorEditable = collectorEditable;
            mFormId = formId;
            mFormHash = TextUtils.isEmpty(formHash) ? "" : formHash;
            mConfigHash = TextUtils.isEmpty(configHash) ? "" : configHash;
            mConfigJson = configJson;
        }

        long getRemoteId() {
            return mRemoteId;
        }

        int getOrder() {
            return mOrder;
        }

        String getName() {
            return mName;
        }

        boolean isCollectorEditable() {
            return mCollectorEditable;
        }

        long getFormId() {
            return mFormId;
        }

        String getFormHash() {
            return mFormHash;
        }

        String getConfigHash() {
            return mConfigHash;
        }

        String getConfigJson() {
            return mConfigJson;
        }

        @Override
        public String toString() {
            return "Layer{"
                    + "rid=" + mRemoteId
                    + ", order=" + mOrder
                    + ", name=" + mName
                    + ", cls=" + mCls
                    + ", editable=" + mCollectorEditable
                    + ", formId=" + mFormId
                    + ", formHash=" + mFormHash
                    + ", configHash=" + mConfigHash
                    + '}';
        }
    }

    private static final class CollectorProjectDiff {
        private final List<DiffEntry> mEntries = new ArrayList<>();
        private int mLocalManagedLayerCount;

        static CollectorProjectDiff compare(
                LayerGroup projectGroup,
                CollectorProjectMetadata metadata,
                CollectorProjectSnapshot remote) {
            CollectorProjectDiff diff = new CollectorProjectDiff();
            Map<Long, NGWVectorLayer> local = new LinkedHashMap<>();
            collectLocalManagedLayers(projectGroup, metadata.getProjectUid(), local);
            diff.mLocalManagedLayerCount = local.size();

            for (CollectorProjectLayerSnapshot remoteLayer : remote.getLayers()) {
                NGWVectorLayer localLayer = local.get(remoteLayer.getRemoteId());
                if (localLayer == null) {
                    diff.add(TYPE_ADD, remoteLayer.getRemoteId(), remoteLayer.getName(),
                            null, remoteLayer,
                            "remote order=" + remoteLayer.getOrder());
                    continue;
                }
                LayerOriginMetadata origin = localLayer.getLayerOriginMetadata();
                if (origin == null) {
                    continue;
                }
                if (origin.getCollectorOrder() != remoteLayer.getOrder()) {
                    diff.add(TYPE_REORDER, remoteLayer.getRemoteId(), localLayer.getName(),
                            localLayer, remoteLayer,
                            "localOrder=" + origin.getCollectorOrder()
                                    + " remoteOrder=" + remoteLayer.getOrder());
                }
                if (origin.getFormId() != remoteLayer.getFormId()) {
                    diff.add(TYPE_UPDATE_FORM, remoteLayer.getRemoteId(), localLayer.getName(),
                            localLayer, remoteLayer,
                            "localForm=" + origin.getFormId()
                                    + " remoteForm=" + remoteLayer.getFormId());
                } else if (remoteLayer.getFormId() > 0L
                        && !TextUtils.isEmpty(remoteLayer.getFormHash())) {
                    String localFormHash = localLayer.getPreferences().getString(
                            SettingsConstants.KEY_PREF_LAST_FORM_HASH, "");
                    if (TextUtils.isEmpty(localFormHash)) {
                        try {
                            localFormHash = LayerFormHashUtil.md5LocalNgfpFiles(
                                    localLayer.getPath(), remoteLayer.getFormId());
                        } catch (IOException e) {
                            HyperLog.w(Constants.TAG, LOG_PREFIX
                                    + ": local form hash failed layer=\"" + localLayer.getName()
                                    + "\": " + e.getMessage());
                        }
                    }
                    if (!TextUtils.isEmpty(localFormHash)
                            && !remoteLayer.getFormHash().equals(localFormHash)) {
                        diff.add(TYPE_UPDATE_FORM, remoteLayer.getRemoteId(), localLayer.getName(),
                                localLayer, remoteLayer,
                                "formHash local=" + localFormHash
                                        + " remote=" + remoteLayer.getFormHash());
                    }
                }
                String remoteHash = remoteLayer.getConfigHash();
                if (!TextUtils.isEmpty(remoteHash)) {
                    String localHash = localLayer.getPreferences().getString(
                            SettingsConstants.KEY_PREF_LAST_CONFIG_HASH, "");
                    if (TextUtils.isEmpty(localHash)) {
                        diff.add(TYPE_UPDATE_CONFIG, remoteLayer.getRemoteId(), localLayer.getName(),
                                localLayer, remoteLayer,
                                "localHash=<empty> remoteHash=" + remoteHash);
                    } else if (!remoteHash.equals(localHash)) {
                        diff.add(TYPE_UPDATE_CONFIG, remoteLayer.getRemoteId(), localLayer.getName(),
                                localLayer, remoteLayer,
                                "localHash=" + localHash + " remoteHash=" + remoteHash);
                    }
                }
                if (localLayer.isCollectorEditable() != remoteLayer.isCollectorEditable()) {
                    diff.add(TYPE_UPDATE_EDITABLE, remoteLayer.getRemoteId(), localLayer.getName(),
                            localLayer, remoteLayer,
                            "localEditable=" + localLayer.isCollectorEditable()
                                    + " remoteEditable=" + remoteLayer.isCollectorEditable());
                }
            }

            for (Map.Entry<Long, NGWVectorLayer> localEntry : local.entrySet()) {
                if (remote.getLayer(localEntry.getKey()) == null) {
                    diff.add(TYPE_REMOVE, localEntry.getKey(), localEntry.getValue().getName(),
                            localEntry.getValue(), null,
                            "local managed layer not found in remote project");
                }
            }
            return diff;
        }

        private static void collectLocalManagedLayers(
                LayerGroup group,
                String projectUid,
                Map<Long, NGWVectorLayer> out) {
            for (int i = 0; i < group.getLayerCount(); i++) {
                ILayer child = group.getLayer(i);
                if (child instanceof LayerGroup) {
                    collectLocalManagedLayers((LayerGroup) child, projectUid, out);
                } else if (child instanceof NGWVectorLayer) {
                    NGWVectorLayer layer = (NGWVectorLayer) child;
                    LayerOriginMetadata origin = layer.getLayerOriginMetadata();
                    if (origin != null
                            && origin.isManagedByProject()
                            && projectUid.equals(origin.getProjectUid())) {
                        out.put(layer.getRemoteId(), layer);
                    }
                }
            }
        }

        private void add(
                String type,
                long remoteId,
                String name,
                NGWVectorLayer localLayer,
                CollectorProjectLayerSnapshot remoteLayer,
                String detail) {
            mEntries.add(new DiffEntry(type, remoteId, name, localLayer, remoteLayer, detail));
        }

        List<DiffEntry> getEntries() {
            return mEntries;
        }

        int getLocalManagedLayerCount() {
            return mLocalManagedLayerCount;
        }

        int count(String type) {
            int count = 0;
            for (DiffEntry entry : mEntries) {
                if (type.equals(entry.getType())) {
                    count++;
                }
            }
            return count;
        }

        String toDiagnosticSummary(CollectorProjectSnapshot remote) {
            int remoteCount = remote != null ? remote.getLayerCount() : 0;
            return "remote=" + remoteCount
                    + " local=" + mLocalManagedLayerCount
                    + " add=" + count(TYPE_ADD)
                    + " remove=" + count(TYPE_REMOVE)
                    + " reorder=" + count(TYPE_REORDER)
                    + " updateForm=" + count(TYPE_UPDATE_FORM)
                    + " updateConfig=" + count(TYPE_UPDATE_CONFIG)
                    + " updateEditable=" + count(TYPE_UPDATE_EDITABLE);
        }
    }

    private static final class DiffEntry {
        private final String mType;
        private final long mRemoteId;
        private final String mName;
        private final NGWVectorLayer mLocalLayer;
        private final CollectorProjectLayerSnapshot mRemoteLayer;
        private final String mDetail;

        DiffEntry(
                String type,
                long remoteId,
                String name,
                NGWVectorLayer localLayer,
                CollectorProjectLayerSnapshot remoteLayer,
                String detail) {
            mType = type;
            mRemoteId = remoteId;
            mName = name;
            mLocalLayer = localLayer;
            mRemoteLayer = remoteLayer;
            mDetail = detail;
        }

        String getType() {
            return mType;
        }

        long getRemoteId() {
            return mRemoteId;
        }

        NGWVectorLayer getLocalLayer() {
            return mLocalLayer;
        }

        CollectorProjectLayerSnapshot getRemoteLayer() {
            return mRemoteLayer;
        }

        @Override
        public String toString() {
            return "type=" + mType
                    + " rid=" + mRemoteId
                    + " name=\"" + safe(mName) + "\""
                    + " detail=" + safe(mDetail);
        }
    }
}

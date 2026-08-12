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
import com.nextgis.maplib.api.INGWLayer;
import com.nextgis.maplib.map.CollectorProjectMetadata;
import com.nextgis.maplib.map.LayerGroup;
import com.nextgis.maplib.map.LayerOriginMetadata;
import com.nextgis.maplib.map.NGWRasterLayer;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private static final String TYPE_UPDATE_RASTER = "update_raster";
    private static final String TYPE_REPAIR_ORIGIN = "repair_origin";
    private static final String TYPE_IDENTITY_CONFLICT = "identity_conflict";

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
            if (Thread.currentThread().isInterrupted()) {
                remote.setIncomplete(true);
            }
            if (remote.isIncomplete()) {
                String summary = "snapshot_incomplete remote=" + remote.getLayerCount();
                HyperLog.w(Constants.TAG, logPrefix(apply) + ": " + summary
                        + " uid=" + metadata.getProjectUid() + " - diff/apply skipped");
                recordCompositionDiagnostics(
                        projectGroup,
                        metadata,
                        summary,
                        true,
                        "snapshot_incomplete");
                continue;
            }
            CollectorProjectDiff diff = CollectorProjectDiff.compare(projectGroup, metadata, remote);
            logDiff(metadata, remote, diff, apply);
            recordCompositionDiagnostics(
                    projectGroup,
                    metadata,
                    diff.toDiagnosticSummary(remote),
                    false,
                    diff.isUnsafe() ? "local_identity_conflict" : null);
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
            if (Thread.currentThread().isInterrupted()) {
                snapshot.setIncomplete(true);
            }
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
                snapshot.setIncomplete(true);
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
        boolean vector = isVectorLikeLayer(cls);
        boolean rasterStyle = isSupportedRasterStyle(cls);
        if (!vector && !rasterStyle) {
            return;
        }
        if (resource == null) {
            snapshot.setIncomplete(true);
            return;
        }
        if (rasterStyle) {
            CollectorProjectItem projectItem =
                    CollectorProjectItem.rasterStyle(item, resource);
            snapshot.addLayer(new CollectorProjectLayerSnapshot(
                    projectItem,
                    snapshot.getLayerCount(),
                    "",
                    "",
                    null));
            return;
        }

        FormLookupResult formLookup = fetchFirstFormId(accountData, resourceId);
        if (!formLookup.isComplete()) {
            snapshot.setIncomplete(true);
            return;
        }
        long formId = formLookup.getFormId();
        String formHash = "";
        if (formId > 0L) {
            FormHashResult formHashResult = fetchFormHash(accountData, formId);
            if (!formHashResult.isComplete()) {
                snapshot.setIncomplete(true);
                return;
            }
            formHash = formHashResult.getHash();
        }
        String descriptionRaw = envelope != null
                ? LayerConfigUtil.extractNgwResourceDescriptionJson(envelope)
                : null;
        String configHash = TextUtils.isEmpty(descriptionRaw)
                ? ""
                : LayerConfigUtil.md5(descriptionRaw.trim());

        CollectorProjectItem projectItem = CollectorProjectItem.vector(
                item,
                resource,
                collectorEditable,
                formId,
                descriptionRaw);
        snapshot.addLayer(new CollectorProjectLayerSnapshot(
                projectItem,
                snapshot.getLayerCount(),
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

    private static FormLookupResult fetchFirstFormId(
            AccountUtil.AccountData accountData,
            long resourceId) {
        try {
            String url = NGWUtil.getResourceChildrenUrl(accountData.url, resourceId);
            HttpResponse response = NetworkUtil.get(url, accountData.login, accountData.password, false);
            if (!response.isOk()) {
                HyperLog.w(Constants.TAG, LOG_PREFIX + ": form lookup HTTP "
                        + response.getResponseCode() + " rid=" + resourceId);
                return FormLookupResult.failure();
            }
            JSONArray children = new JSONArray(response.getResponseBody());
            for (int i = 0; i < children.length(); i++) {
                JSONObject child = children.optJSONObject(i);
                JSONObject resource = child != null ? child.optJSONObject("resource") : null;
                if (resource != null
                        && "formbuilder_form".equals(resource.optString("cls", ""))) {
                    long formId = resource.optLong("id", 0L);
                    return formId > 0L
                            ? FormLookupResult.success(formId)
                            : FormLookupResult.failure();
                }
            }
            return FormLookupResult.success(0L);
        } catch (IOException | JSONException e) {
            HyperLog.w(Constants.TAG, LOG_PREFIX + ": form lookup failed rid=" + resourceId
                    + ": " + e.getMessage());
            return FormLookupResult.failure();
        }
    }

    private static FormHashResult fetchFormHash(
            AccountUtil.AccountData accountData,
            long formId) {
        if (accountData == null || formId <= 0L) {
            return FormHashResult.failure();
        }
        HttpURLConnection conn = null;
        try {
            String url = NGWUtil.getFormUrl(accountData.url, formId);
            conn = NetworkUtil.getHttpConnection(
                    NetworkUtil.HTTP_GET, url, accountData.login, accountData.password);
            if (conn == null) {
                return FormHashResult.failure();
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
                return FormHashResult.failure();
            }
            InputStream in = conn.getInputStream();
            try {
                String hash = LayerFormHashUtil.md5NgfpZip(in);
                return TextUtils.isEmpty(hash)
                        ? FormHashResult.failure()
                        : FormHashResult.success(hash);
            } finally {
                in.close();
            }
        } catch (IOException e) {
            HyperLog.w(Constants.TAG, LOG_PREFIX + ": form hash failed formId=" + formId
                    + ": " + e.getMessage());
            return FormHashResult.failure();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static boolean isVectorLikeLayer(String cls) {
        return "vector_layer".equals(cls) || "postgis_layer".equals(cls);
    }

    static boolean isSupportedRasterStyle(String cls) {
        return "qgis_vector_style".equals(cls) || "qgis_raster_style".equals(cls);
    }

    private static String readCls(JSONObject resource) {
        return resource == null ? "" : resource.optString("cls", "");
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
                + " localPhysicalLayers=" + diff.getLocalPhysicalLayerCount()
                + " incomplete=" + remote.isIncomplete()
                + " unsafe=" + diff.isUnsafe()
                + " add=" + diff.count(TYPE_ADD)
                + " remove=" + diff.count(TYPE_REMOVE)
                + " reorder=" + diff.count(TYPE_REORDER)
                + " updateForm=" + diff.count(TYPE_UPDATE_FORM)
                + " updateConfig=" + diff.count(TYPE_UPDATE_CONFIG)
                + " updateEditable=" + diff.count(TYPE_UPDATE_EDITABLE)
                + " updateRaster=" + diff.count(TYPE_UPDATE_RASTER)
                + " repairOrigin=" + diff.count(TYPE_REPAIR_ORIGIN)
                + " identityConflict=" + diff.count(TYPE_IDENTITY_CONFLICT));
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
        if (diff.isUnsafe()) {
            HyperLog.w(Constants.TAG, LOG_PREFIX
                    + ": apply skipped because local NGW identity is ambiguous uid="
                    + metadata.getProjectUid() + " conflicts="
                    + diff.count(TYPE_IDENTITY_CONFLICT));
            return;
        }
        if (!(context.getApplicationContext() instanceof IGISApplication)) {
            HyperLog.w(Constants.TAG, LOG_PREFIX + ": application does not implement IGISApplication");
            return;
        }
        IGISApplication app = (IGISApplication) context.getApplicationContext();
        long[] fullOrder = remote.getRemoteIdsInOrder();

        for (DiffEntry entry : diff.getEntries()) {
            if (TYPE_REPAIR_ORIGIN.equals(entry.getType())) {
                repairCollectorLayerOrigin(app, metadata, entry, fullOrder);
            }
        }

        for (DiffEntry entry : diff.getEntries()) {
            if (!TYPE_REMOVE.equals(entry.getType()) || entry.getLocalLayer() == null) {
                continue;
            }
            ILayer localLayer = entry.getLocalLayer();
            if (localLayer instanceof NGWVectorLayer) {
                app.scheduleCollectorLayerRemovalWithBackup((NGWVectorLayer) localLayer);
            } else if (localLayer instanceof NGWRasterLayer) {
                app.removeCollectorRasterStyleLayer((NGWRasterLayer) localLayer);
            }
        }

        List<CollectorProjectLayerSnapshot> additions = new ArrayList<>();
        for (DiffEntry entry : diff.getEntries()) {
            if (TYPE_ADD.equals(entry.getType()) && entry.getRemoteLayer() != null) {
                additions.add(entry.getRemoteLayer());
            }
        }

        if (!additions.isEmpty() && app.hasCollectorImportBatchRegistered()) {
            HyperLog.w(Constants.TAG, LOG_PREFIX + ": additions deferred while a durable Collector"
                    + " import batch is active uid=" + metadata.getProjectUid()
                    + " count=" + additions.size());
        } else if (!additions.isEmpty()) {
            scheduleAdditions(app, projectGroup, metadata, additions, fullOrder);
        }

        for (DiffEntry entry : diff.getEntries()) {
            String type = entry.getType();
            if (TYPE_UPDATE_FORM.equals(type)) {
                CollectorProjectLayerSnapshot remoteLayer = entry.getRemoteLayer();
                ILayer localLayer = entry.getLocalLayer();
                if (remoteLayer != null && localLayer instanceof NGWVectorLayer) {
                    app.applyCollectorLayerForm(
                            (NGWVectorLayer) localLayer,
                            remoteLayer.getFormId(),
                            remoteLayer.getFormHash());
                }
            } else if (TYPE_REORDER.equals(type) || TYPE_UPDATE_EDITABLE.equals(type)) {
                if (entry.getLocalLayer() instanceof NGWVectorLayer
                        && entry.getRemoteLayer() != null) {
                    CollectorProjectLayerSnapshot remoteLayer = entry.getRemoteLayer();
                    app.applyCollectorLayerProjectState(
                            (NGWVectorLayer) entry.getLocalLayer(),
                            remoteLayer.getOrder(),
                            fullOrder,
                            remoteLayer.isCollectorEditable());
                }
            } else if (TYPE_UPDATE_CONFIG.equals(type)) {
                if (entry.getLocalLayer() instanceof NGWVectorLayer) {
                    applyCollectorLayerConfig(
                            app,
                            (NGWVectorLayer) entry.getLocalLayer(),
                            entry.getRemoteLayer(),
                            fullOrder);
                }
            } else if (TYPE_UPDATE_RASTER.equals(type)
                    && entry.getLocalLayer() instanceof NGWRasterLayer
                    && entry.getRemoteLayer() != null) {
                CollectorProjectLayerSnapshot remoteLayer = entry.getRemoteLayer();
                app.applyCollectorRasterStyleProjectState(
                        (NGWRasterLayer) entry.getLocalLayer(),
                        remoteLayer.getItem(),
                        remoteLayer.getOrder(),
                        fullOrder);
            }
        }
    }

    private static void repairCollectorLayerOrigin(
            IGISApplication app,
            CollectorProjectMetadata metadata,
            DiffEntry entry,
            long[] fullOrder) {
        if (app == null || metadata == null || entry == null
                || entry.getLocalLayer() == null || entry.getRemoteLayer() == null) {
            return;
        }
        ILayer localLayer = entry.getLocalLayer();
        CollectorProjectLayerSnapshot remoteLayer = entry.getRemoteLayer();
        LayerOriginMetadata recovered = LayerOriginMetadata.collectorLayer(
                metadata.getProjectUid(),
                -1,
                remoteLayer.isVector() ? remoteLayer.getFormId() : 0L);
        if (localLayer instanceof NGWVectorLayer) {
            NGWVectorLayer vector = (NGWVectorLayer) localLayer;
            vector.setLayerOriginMetadata(recovered);
            if (!vector.save()) {
                vector.setLayerOriginMetadata(null);
                HyperLog.w(Constants.TAG, LOG_PREFIX + ": origin repair save failed layer=\""
                        + vector.getName() + "\" remoteId=" + vector.getRemoteId());
                return;
            }
            app.applyCollectorLayerProjectState(
                    vector,
                    remoteLayer.getOrder(),
                    fullOrder,
                    remoteLayer.isCollectorEditable());
            HyperLog.w(Constants.TAG, LOG_PREFIX + ": restored project ownership layer=\""
                    + vector.getName() + "\" remoteId=" + vector.getRemoteId());
        } else if (localLayer instanceof NGWRasterLayer) {
            NGWRasterLayer raster = (NGWRasterLayer) localLayer;
            raster.setLayerOriginMetadata(recovered);
            if (!raster.save()) {
                raster.setLayerOriginMetadata(null);
                HyperLog.w(Constants.TAG, LOG_PREFIX + ": raster origin repair save failed layer=\""
                        + raster.getName() + "\" remoteId=" + raster.getRemoteId());
                return;
            }
            app.applyCollectorRasterStyleProjectState(
                    raster,
                    remoteLayer.getItem(),
                    remoteLayer.getOrder(),
                    fullOrder);
            HyperLog.w(Constants.TAG, LOG_PREFIX + ": restored raster project ownership layer=\""
                    + raster.getName() + "\" remoteId=" + raster.getRemoteId());
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
        List<CollectorProjectLayerSnapshot> vectors = new ArrayList<>();
        List<CollectorProjectLayerSnapshot> rasterStyles = new ArrayList<>();
        for (CollectorProjectLayerSnapshot addition : additions) {
            if (addition.isVector()) {
                vectors.add(addition);
            } else if (addition.isRasterStyle()) {
                rasterStyles.add(addition);
            }
        }

        int n = vectors.size();
        if (n > 0) {
            long[] remoteIds = new long[n];
            String[] names = new String[n];
            String[] configJsons = new String[n];
            long[] formIds = new long[n];
            boolean[] editables = new boolean[n];
            for (int i = 0; i < n; i++) {
                CollectorProjectLayerSnapshot layer = vectors.get(i);
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

        int rasterCount = rasterStyles.size();
        if (rasterCount > 0) {
            CollectorProjectItem[] items = new CollectorProjectItem[rasterCount];
            int[] orders = new int[rasterCount];
            for (int i = 0; i < rasterCount; i++) {
                CollectorProjectLayerSnapshot layer = rasterStyles.get(i);
                items[i] = layer.getItem();
                orders[i] = layer.getOrder();
            }
            app.addCollectorRasterStyleLayers(
                    projectGroup.getId(),
                    metadata.getAccountName(),
                    metadata.getProjectUid(),
                    items,
                    orders,
                    fullOrder);
        }
    }

    private static String logPrefix(boolean apply) {
        return apply ? LOG_PREFIX : DRY_RUN_PREFIX;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class FormLookupResult {
        private final boolean mComplete;
        private final long mFormId;

        private FormLookupResult(boolean complete, long formId) {
            mComplete = complete;
            mFormId = formId;
        }

        static FormLookupResult success(long formId) {
            return new FormLookupResult(true, formId);
        }

        static FormLookupResult failure() {
            return new FormLookupResult(false, 0L);
        }

        boolean isComplete() {
            return mComplete;
        }

        long getFormId() {
            return mFormId;
        }
    }

    private static final class FormHashResult {
        private final boolean mComplete;
        private final String mHash;

        private FormHashResult(boolean complete, String hash) {
            mComplete = complete;
            mHash = hash;
        }

        static FormHashResult success(String hash) {
            return new FormHashResult(true, hash);
        }

        static FormHashResult failure() {
            return new FormHashResult(false, "");
        }

        boolean isComplete() {
            return mComplete;
        }

        String getHash() {
            return mHash;
        }
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
        private final CollectorProjectItem mItem;
        private final int mOrder;
        private final String mFormHash;
        private final String mConfigHash;
        private final String mConfigJson;

        CollectorProjectLayerSnapshot(
                CollectorProjectItem item,
                int order,
                String formHash,
                String configHash,
                String configJson) {
            mItem = item;
            mOrder = order;
            mFormHash = TextUtils.isEmpty(formHash) ? "" : formHash;
            mConfigHash = TextUtils.isEmpty(configHash) ? "" : configHash;
            mConfigJson = configJson;
        }

        long getRemoteId() {
            return mItem.getRemoteId();
        }

        int getOrder() {
            return mOrder;
        }

        String getName() {
            return mItem.getName();
        }

        CollectorProjectItem getItem() {
            return mItem;
        }

        boolean isVector() {
            return mItem.isVector();
        }

        boolean isRasterStyle() {
            return mItem.isRasterStyle();
        }

        boolean isCollectorEditable() {
            return mItem.isCollectorEditable();
        }

        long getFormId() {
            return mItem.getFormId();
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
                    + "rid=" + getRemoteId()
                    + ", order=" + mOrder
                    + ", name=" + getName()
                    + ", cls=" + mItem.getResourceClass()
                    + ", editable=" + isCollectorEditable()
                    + ", formId=" + getFormId()
                    + ", formHash=" + mFormHash
                    + ", configHash=" + mConfigHash
                    + '}';
        }
    }

    private static final class CollectorProjectDiff {
        private final List<DiffEntry> mEntries = new ArrayList<>();
        private int mLocalManagedLayerCount;
        private int mLocalPhysicalLayerCount;
        private boolean mUnsafe;

        static CollectorProjectDiff compare(
                LayerGroup projectGroup,
                CollectorProjectMetadata metadata,
                CollectorProjectSnapshot remote) {
            CollectorProjectDiff diff = new CollectorProjectDiff();
            LocalLayerIndex localIndex = new LocalLayerIndex();
            collectLocalLayers(
                    projectGroup,
                    metadata.getProjectUid(),
                    metadata.getAccountName(),
                    localIndex);
            diff.mLocalManagedLayerCount = localIndex.mManagedLayerCount;
            diff.mLocalPhysicalLayerCount = localIndex.mPhysicalLayerCount;
            for (IdentityConflict conflict : localIndex.mConflicts) {
                diff.markUnsafe(
                        conflict.mRemoteId,
                        conflict.mLayer == null ? "" : conflict.mLayer.getName(),
                        conflict.mLayer,
                        null,
                        conflict.mDetail);
            }
            Map<Long, ILayer> local = localIndex.getUniqueManagedLayers();
            Set<Long> seenRemoteIds = new HashSet<>();

            for (CollectorProjectLayerSnapshot remoteLayer : remote.getLayers()) {
                long remoteId = remoteLayer.getRemoteId();
                if (!seenRemoteIds.add(remoteId)) {
                    diff.markUnsafe(
                            remoteId,
                            remoteLayer.getName(),
                            null,
                            remoteLayer,
                            "remote project contains duplicate resource identity");
                    continue;
                }

                List<ILayer> managedMatches = localIndex.getManaged(remoteId);
                List<ILayer> physicalMatches = localIndex.getPhysical(remoteId);
                ILayer candidate = managedMatches.size() == 1
                        ? managedMatches.get(0)
                        : physicalMatches.size() == 1 ? physicalMatches.get(0) : null;
                boolean expectedKind = candidate != null
                        && isExpectedLayerKind(candidate, remoteLayer);
                boolean originMissing = candidate != null && getOrigin(candidate) == null;
                LocalIdentityAction action = decideLocalIdentityAction(
                        managedMatches.size(),
                        physicalMatches.size(),
                        expectedKind,
                        originMissing);

                if (action == LocalIdentityAction.ADD) {
                    diff.add(TYPE_ADD, remoteLayer.getRemoteId(), remoteLayer.getName(),
                            null, remoteLayer,
                            "remote order=" + remoteLayer.getOrder());
                    continue;
                }
                if (action == LocalIdentityAction.REPAIR_ORIGIN) {
                    diff.add(
                            TYPE_REPAIR_ORIGIN,
                            remoteId,
                            candidate.getName(),
                            candidate,
                            remoteLayer,
                            "account+remoteId match; project ownership metadata missing");
                    continue;
                }
                if (action == LocalIdentityAction.BLOCK) {
                    diff.markUnsafe(
                            remoteId,
                            candidate == null ? remoteLayer.getName() : candidate.getName(),
                            candidate,
                            remoteLayer,
                            "ambiguous local identity managedMatches=" + managedMatches.size()
                                    + " physicalMatches=" + physicalMatches.size()
                                    + " expectedKind=" + expectedKind
                                    + " originMissing=" + originMissing);
                    continue;
                }

                ILayer localLayer = candidate;
                LayerOriginMetadata origin = getOrigin(localLayer);

                if (remoteLayer.isRasterStyle()) {
                    NGWRasterLayer raster = (NGWRasterLayer) localLayer;
                    CollectorProjectItem item = remoteLayer.getItem();
                    boolean orderChanged =
                            origin.getCollectorOrder() != remoteLayer.getOrder();
                    boolean stateChanged =
                            !TextUtils.equals(raster.getName(), item.getName())
                                    || raster.isVisible() != item.isVisible()
                                    || Float.compare(
                                    raster.getMinZoom(), item.getMinZoom()) != 0
                                    || Float.compare(
                                    raster.getMaxZoom(), item.getMaxZoom()) != 0
                                    || raster.getTileMaxAge() != item.getTileMaxAge()
                                    || raster.getExtentRemoteId() != item.getExtentRemoteId();
                    if (orderChanged || stateChanged) {
                        diff.add(
                                TYPE_UPDATE_RASTER,
                                remoteLayer.getRemoteId(),
                                raster.getName(),
                                raster,
                                remoteLayer,
                                "orderChanged=" + orderChanged
                                        + " stateChanged=" + stateChanged);
                    }
                    continue;
                }

                NGWVectorLayer vector = (NGWVectorLayer) localLayer;
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
                    String localFormHash = vector.getPreferences().getString(
                            SettingsConstants.KEY_PREF_LAST_FORM_HASH, "");
                    if (TextUtils.isEmpty(localFormHash)) {
                        try {
                            localFormHash = LayerFormHashUtil.md5LocalNgfpFiles(
                                    vector.getPath(), remoteLayer.getFormId());
                        } catch (IOException e) {
                            HyperLog.w(Constants.TAG, LOG_PREFIX
                                    + ": local form hash failed layer=\"" + vector.getName()
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
                    String localHash = vector.getPreferences().getString(
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
                if (vector.isCollectorEditable() != remoteLayer.isCollectorEditable()) {
                    diff.add(TYPE_UPDATE_EDITABLE, remoteLayer.getRemoteId(), localLayer.getName(),
                            localLayer, remoteLayer,
                            "localEditable=" + vector.isCollectorEditable()
                                    + " remoteEditable=" + remoteLayer.isCollectorEditable());
                }
            }

            for (Map.Entry<Long, ILayer> localEntry : local.entrySet()) {
                if (remote.getLayer(localEntry.getKey()) == null) {
                    diff.add(TYPE_REMOVE, localEntry.getKey(), localEntry.getValue().getName(),
                            localEntry.getValue(), null,
                            "local managed layer not found in remote project");
                }
            }
            return diff;
        }

        private static void collectLocalLayers(
                LayerGroup group,
                String projectUid,
                String accountName,
                LocalLayerIndex out) {
            for (int i = 0; i < group.getLayerCount(); i++) {
                ILayer child = group.getLayer(i);
                if (child instanceof LayerGroup) {
                    collectLocalLayers((LayerGroup) child, projectUid, accountName, out);
                    continue;
                }
                if (!(child instanceof NGWVectorLayer)
                        && !(child instanceof NGWRasterLayer)) {
                    continue;
                }

                INGWLayer ngwLayer = (INGWLayer) child;
                long remoteId = ngwLayer.getRemoteId();
                boolean accountMatches = TextUtils.equals(
                        accountName, ngwLayer.getAccountName());
                LayerOriginMetadata origin = getOrigin(child);
                boolean managedByThisProject = origin != null
                        && origin.isManagedByProject()
                        && TextUtils.equals(projectUid, origin.getProjectUid());
                boolean identityIncomplete = TextUtils.isEmpty(ngwLayer.getAccountName())
                        || remoteId <= 0L;

                if (accountMatches && remoteId > 0L) {
                    out.addPhysical(remoteId, child);
                }
                if (identityIncomplete) {
                    out.mConflicts.add(new IdentityConflict(
                            remoteId,
                            child,
                            "NGW layer has incomplete identity account=\""
                                    + safe(ngwLayer.getAccountName()) + "\" remoteId=" + remoteId
                                    + " managedByThisProject=" + managedByThisProject));
                }
                if (managedByThisProject) {
                    out.mManagedLayerCount++;
                    if (identityIncomplete) {
                        continue;
                    }
                    if (!accountMatches) {
                        out.mConflicts.add(new IdentityConflict(
                                remoteId,
                                child,
                                "managed layer has mismatched identity account=\""
                                        + safe(ngwLayer.getAccountName()) + "\" expectedAccount=\""
                                        + safe(accountName) + "\" remoteId=" + remoteId));
                    } else {
                        out.addManaged(remoteId, child);
                    }
                }
            }
        }

        private static boolean isExpectedLayerKind(
                ILayer localLayer,
                CollectorProjectLayerSnapshot remoteLayer) {
            return (remoteLayer.isVector() && localLayer instanceof NGWVectorLayer)
                    || (remoteLayer.isRasterStyle() && localLayer instanceof NGWRasterLayer);
        }

        private static LayerOriginMetadata getOrigin(ILayer layer) {
            if (layer instanceof NGWVectorLayer) {
                return ((NGWVectorLayer) layer).getLayerOriginMetadata();
            }
            if (layer instanceof NGWRasterLayer) {
                return ((NGWRasterLayer) layer).getLayerOriginMetadata();
            }
            return null;
        }

        private void add(
                String type,
                long remoteId,
                String name,
                ILayer localLayer,
                CollectorProjectLayerSnapshot remoteLayer,
                String detail) {
            mEntries.add(new DiffEntry(type, remoteId, name, localLayer, remoteLayer, detail));
        }

        private void markUnsafe(
                long remoteId,
                String name,
                ILayer localLayer,
                CollectorProjectLayerSnapshot remoteLayer,
                String detail) {
            mUnsafe = true;
            add(TYPE_IDENTITY_CONFLICT, remoteId, name, localLayer, remoteLayer, detail);
        }

        List<DiffEntry> getEntries() {
            return mEntries;
        }

        int getLocalManagedLayerCount() {
            return mLocalManagedLayerCount;
        }

        int getLocalPhysicalLayerCount() {
            return mLocalPhysicalLayerCount;
        }

        boolean isUnsafe() {
            return mUnsafe;
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
                    + " physical=" + mLocalPhysicalLayerCount
                    + " unsafe=" + mUnsafe
                    + " add=" + count(TYPE_ADD)
                    + " remove=" + count(TYPE_REMOVE)
                    + " reorder=" + count(TYPE_REORDER)
                    + " updateForm=" + count(TYPE_UPDATE_FORM)
                    + " updateConfig=" + count(TYPE_UPDATE_CONFIG)
                    + " updateEditable=" + count(TYPE_UPDATE_EDITABLE)
                    + " updateRaster=" + count(TYPE_UPDATE_RASTER)
                    + " repairOrigin=" + count(TYPE_REPAIR_ORIGIN)
                    + " identityConflict=" + count(TYPE_IDENTITY_CONFLICT);
        }
    }

    enum LocalIdentityAction {
        USE_MANAGED,
        REPAIR_ORIGIN,
        ADD,
        BLOCK
    }

    static LocalIdentityAction decideLocalIdentityAction(
            int managedMatches,
            int physicalMatches,
            boolean expectedKind,
            boolean originMissing) {
        if (managedMatches == 1 && physicalMatches == 1 && expectedKind) {
            return LocalIdentityAction.USE_MANAGED;
        }
        if (managedMatches == 0 && physicalMatches == 0) {
            return LocalIdentityAction.ADD;
        }
        if (managedMatches == 0 && physicalMatches == 1
                && expectedKind && originMissing) {
            return LocalIdentityAction.REPAIR_ORIGIN;
        }
        return LocalIdentityAction.BLOCK;
    }

    private static final class LocalLayerIndex {
        private final Map<Long, List<ILayer>> mPhysicalByRemoteId = new LinkedHashMap<>();
        private final Map<Long, List<ILayer>> mManagedByRemoteId = new LinkedHashMap<>();
        private final List<IdentityConflict> mConflicts = new ArrayList<>();
        private int mManagedLayerCount;
        private int mPhysicalLayerCount;

        void addPhysical(long remoteId, ILayer layer) {
            addToIndex(mPhysicalByRemoteId, remoteId, layer);
            mPhysicalLayerCount++;
        }

        void addManaged(long remoteId, ILayer layer) {
            addToIndex(mManagedByRemoteId, remoteId, layer);
        }

        List<ILayer> getPhysical(long remoteId) {
            List<ILayer> layers = mPhysicalByRemoteId.get(remoteId);
            return layers == null ? new ArrayList<>() : layers;
        }

        List<ILayer> getManaged(long remoteId) {
            List<ILayer> layers = mManagedByRemoteId.get(remoteId);
            return layers == null ? new ArrayList<>() : layers;
        }

        Map<Long, ILayer> getUniqueManagedLayers() {
            Map<Long, ILayer> result = new LinkedHashMap<>();
            for (Map.Entry<Long, List<ILayer>> entry : mManagedByRemoteId.entrySet()) {
                if (entry.getValue().size() == 1) {
                    result.put(entry.getKey(), entry.getValue().get(0));
                }
            }
            return result;
        }

        private static void addToIndex(
                Map<Long, List<ILayer>> index,
                long remoteId,
                ILayer layer) {
            List<ILayer> layers = index.get(remoteId);
            if (layers == null) {
                layers = new ArrayList<>();
                index.put(remoteId, layers);
            }
            layers.add(layer);
        }
    }

    private static final class IdentityConflict {
        private final long mRemoteId;
        private final ILayer mLayer;
        private final String mDetail;

        IdentityConflict(long remoteId, ILayer layer, String detail) {
            mRemoteId = remoteId;
            mLayer = layer;
            mDetail = detail;
        }
    }

    private static final class DiffEntry {
        private final String mType;
        private final long mRemoteId;
        private final String mName;
        private final ILayer mLocalLayer;
        private final CollectorProjectLayerSnapshot mRemoteLayer;
        private final String mDetail;

        DiffEntry(
                String type,
                long remoteId,
                String name,
                ILayer localLayer,
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

        ILayer getLocalLayer() {
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

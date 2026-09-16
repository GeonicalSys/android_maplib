package com.nextgis.maplib.util;

import com.hypertrack.hyperlog.HyperLog;
import com.nextgis.maplib.datasource.ngw.CollectorResource;
import com.nextgis.maplib.datasource.ngw.Connection;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves the Web GIS group with keyname {@link LisaCatalog#KEYNAME} and lists
 * collector projects under it.
 */
public final class LisaCatalogLookup {
    private static final int MAX_GROUPS = 200;

    private LisaCatalogLookup() {
    }

    public static LisaCatalog.Ref findCatalog(Connection connection) {
        if (connection == null) {
            return null;
        }
        LisaCatalog.Ref fromSearch = findCatalogBySearch(connection);
        if (fromSearch != null) {
            return fromSearch;
        }
        return findCatalogByWalk(connection);
    }

    public static List<LisaCatalog.Ref> listCollectorProjects(
            Connection connection,
            long catalogId) {
        List<LisaCatalog.Ref> collectors = LisaCatalog.emptyRefList();
        if (connection == null || catalogId <= 0L) {
            return collectors;
        }
        ArrayDeque<Long> queue = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        queue.add(catalogId);
        while (!queue.isEmpty() && seen.size() < MAX_GROUPS) {
            long parent = queue.removeFirst();
            if (!seen.add(parent)) {
                continue;
            }
            JSONArray children = loadChildren(connection, parent);
            if (children == null) {
                continue;
            }
            try {
                List<Long> nested = LisaCatalog.emptyIdList();
                LisaCatalog.collectChildren(children, collectors, nested);
                for (Long id : nested) {
                    if (id != null && !seen.contains(id)) {
                        queue.add(id);
                    }
                }
            } catch (JSONException e) {
                HyperLog.w(Constants.TAG, "Lisa catalog: children JSON " + e.getMessage());
            }
        }
        LisaCatalog.sortByDisplayName(collectors);
        return collectors;
    }

    public static CollectorResource loadCollector(Connection connection, long remoteId) {
        if (connection == null || remoteId <= 0L) {
            return null;
        }
        try {
            HttpResponse response = NetworkUtil.get(
                    NGWUtil.getResourceUrl(connection.getURL(), remoteId),
                    connection.getLogin(),
                    connection.getPassword(),
                    false);
            if (response == null || !response.isOk()) {
                return null;
            }
            JSONObject data = new JSONObject(response.getResponseBody());
            return new CollectorResource(data, connection);
        } catch (IOException | JSONException | RuntimeException e) {
            HyperLog.exception(Constants.TAG, e);
            return null;
        }
    }

    private static LisaCatalog.Ref findCatalogBySearch(Connection connection) {
        try {
            HttpResponse response = NetworkUtil.get(
                    NGWUtil.getResourceSearchUrl(connection.getURL(), LisaCatalog.KEYNAME),
                    connection.getLogin(),
                    connection.getPassword(),
                    false);
            if (response == null || !response.isOk()) {
                return null;
            }
            JSONArray payload = new JSONArray(response.getResponseBody());
            return LisaCatalog.toRef(LisaCatalog.pickCatalog(payload, LisaCatalog.KEYNAME));
        } catch (IOException | JSONException | RuntimeException e) {
            HyperLog.w(Constants.TAG, "Lisa catalog: search failed " + e.getMessage());
            return null;
        }
    }

    private static LisaCatalog.Ref findCatalogByWalk(Connection connection) {
        ArrayDeque<Long> queue = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        queue.add(0L);
        while (!queue.isEmpty() && seen.size() < MAX_GROUPS) {
            long parent = queue.removeFirst();
            if (!seen.add(parent)) {
                continue;
            }
            JSONArray children = loadChildren(connection, parent);
            if (children == null) {
                continue;
            }
            try {
                JSONObject found = LisaCatalog.findCatalogInChildren(children, LisaCatalog.KEYNAME);
                LisaCatalog.Ref ref = LisaCatalog.toRef(found);
                if (ref != null) {
                    return ref;
                }
                List<Long> nested = LisaCatalog.emptyIdList();
                LisaCatalog.collectChildren(children, null, nested);
                for (Long id : nested) {
                    if (id != null && !seen.contains(id)) {
                        queue.add(id);
                    }
                }
            } catch (JSONException e) {
                HyperLog.w(Constants.TAG, "Lisa catalog: walk JSON " + e.getMessage());
            }
        }
        return null;
    }

    private static JSONArray loadChildren(Connection connection, long parentId) {
        try {
            HttpResponse response = NetworkUtil.get(
                    NGWUtil.getResourceChildrenUrl(connection.getURL(), parentId),
                    connection.getLogin(),
                    connection.getPassword(),
                    false);
            if (response == null || !response.isOk()) {
                return null;
            }
            return new JSONArray(response.getResponseBody());
        } catch (IOException | JSONException | RuntimeException e) {
            HyperLog.w(Constants.TAG, "Lisa catalog: children failed " + e.getMessage());
            return null;
        }
    }
}

package com.nextgis.maplib.util;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Parse NGW resource search/children payloads for the shared Lisa catalog key.
 */
public final class LisaCatalog {
    public static final String KEYNAME = "lisa";
    public static final String RESOURCE_GROUP_CLS = "resource_group";
    public static final String COLLECTOR_CLS = "collector_project";

    public static final class Ref {
        public final long remoteId;
        public final String displayName;
        public final String cls;

        public Ref(long remoteId, String displayName, String cls) {
            this.remoteId = remoteId;
            this.displayName = displayName == null ? "" : displayName;
            this.cls = cls == null ? "" : cls;
        }
    }

    private LisaCatalog() {
    }

    public static JSONObject unwrapResource(JSONObject item) throws JSONException {
        if (item == null) {
            return null;
        }
        if (item.has(Constants.JSON_RESOURCE_KEY)
                && item.opt(Constants.JSON_RESOURCE_KEY) instanceof JSONObject) {
            return item.getJSONObject(Constants.JSON_RESOURCE_KEY);
        }
        return item;
    }

    public static JSONObject pickCatalog(JSONArray payload, String keyname) throws JSONException {
        if (payload == null || keyname == null || keyname.isEmpty()) {
            return null;
        }
        JSONObject group = null;
        JSONObject any = null;
        for (int i = 0; i < payload.length(); i++) {
            JSONObject resource = unwrapResource(payload.getJSONObject(i));
            if (resource == null) {
                continue;
            }
            if (!keyname.equals(resource.optString(NGWUtil.NGWKEY_KEYNAME, ""))) {
                continue;
            }
            String cls = resource.optString(NGWUtil.NGWKEY_CLS, "");
            if (RESOURCE_GROUP_CLS.equals(cls)) {
                if (group == null) {
                    group = resource;
                }
            } else if (any == null) {
                any = resource;
            }
        }
        return group != null ? group : any;
    }

    public static Ref toRef(JSONObject resource) {
        if (resource == null) {
            return null;
        }
        long id = resource.optLong(NGWUtil.NGWKEY_ID, 0L);
        if (id <= 0L) {
            return null;
        }
        return new Ref(
                id,
                resource.optString(NGWUtil.NGWKEY_DISPLAY_NAME, ""),
                resource.optString(NGWUtil.NGWKEY_CLS, ""));
    }

    public static void collectChildren(
            JSONArray children,
            List<Ref> collectors,
            List<Long> nestedGroups) throws JSONException {
        if (children == null) {
            return;
        }
        for (int i = 0; i < children.length(); i++) {
            JSONObject resource = unwrapResource(children.getJSONObject(i));
            if (resource == null) {
                continue;
            }
            String cls = resource.optString(NGWUtil.NGWKEY_CLS, "");
            long id = resource.optLong(NGWUtil.NGWKEY_ID, 0L);
            if (id <= 0L) {
                continue;
            }
            if (COLLECTOR_CLS.equals(cls) && collectors != null) {
                collectors.add(new Ref(id, resource.optString(NGWUtil.NGWKEY_DISPLAY_NAME, ""), cls));
            } else if (RESOURCE_GROUP_CLS.equals(cls) && nestedGroups != null) {
                nestedGroups.add(id);
            }
        }
    }

    public static JSONObject findCatalogInChildren(JSONArray children, String keyname)
            throws JSONException {
        if (children == null || keyname == null || keyname.isEmpty()) {
            return null;
        }
        JSONArray asSearch = new JSONArray();
        for (int i = 0; i < children.length(); i++) {
            asSearch.put(children.getJSONObject(i));
        }
        return pickCatalog(asSearch, keyname);
    }

    public static void sortByDisplayName(List<Ref> refs) {
        if (refs == null) {
            return;
        }
        Collections.sort(refs, new Comparator<Ref>() {
            @Override
            public int compare(Ref left, Ref right) {
                String a = left == null ? "" : left.displayName;
                String b = right == null ? "" : right.displayName;
                return a.compareToIgnoreCase(b);
            }
        });
    }

    public static String[] displayNames(List<Ref> refs) {
        if (refs == null) {
            return new String[0];
        }
        String[] names = new String[refs.size()];
        for (int i = 0; i < refs.size(); i++) {
            Ref ref = refs.get(i);
            names[i] = ref == null ? "" : ref.displayName;
        }
        return names;
    }

    public static List<Long> emptyIdList() {
        return new ArrayList<>();
    }

    public static List<Ref> emptyRefList() {
        return new ArrayList<>();
    }
}

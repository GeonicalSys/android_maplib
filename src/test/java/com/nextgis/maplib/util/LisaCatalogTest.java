package com.nextgis.maplib.util;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LisaCatalogTest {

    private static final String SERVER = "https://demo.nextgis.com";

    @Test
    public void getResourceSearchUrl_encodesKeyname() {
        assertEquals(
                NGWUtil.getBaseUrl(SERVER) + "search/?keyname=lisa",
                NGWUtil.getResourceSearchUrl(SERVER, "lisa"));
        String url = NGWUtil.getResourceSearchUrl(SERVER, "a b");
        assertTrue(url.contains("search/?keyname="));
        assertTrue(url.contains("a+b") || url.contains("a%20b"));
    }

    @Test
    public void pickCatalog_prefersResourceGroup() throws Exception {
        JSONArray payload = new JSONArray();
        payload.put(wrapped(11, "vector_layer", "lisa", "Layer"));
        payload.put(wrapped(7, "resource_group", "lisa", "ЛИСА"));
        payload.put(wrapped(9, "resource_group", "other", "Other"));
        JSONObject picked = LisaCatalog.pickCatalog(payload, "lisa");
        assertNotNull(picked);
        assertEquals(7L, picked.getLong("id"));
        assertEquals("resource_group", picked.getString("cls"));
    }

    @Test
    public void collectChildren_collectorsAndNestedGroups() throws Exception {
        JSONArray children = new JSONArray();
        children.put(wrapped(21, "collector_project", null, "Olonets"));
        children.put(wrapped(22, "resource_group", null, "Nested"));
        children.put(wrapped(23, "vector_layer", null, "Skip"));
        children.put(wrapped(24, "collector_project", null, "West"));
        List<LisaCatalog.Ref> collectors = LisaCatalog.emptyRefList();
        List<Long> groups = LisaCatalog.emptyIdList();
        LisaCatalog.collectChildren(children, collectors, groups);
        LisaCatalog.sortByDisplayName(collectors);
        assertEquals(2, collectors.size());
        assertEquals("Olonets", collectors.get(0).displayName);
        assertEquals("West", collectors.get(1).displayName);
        assertEquals(1, groups.size());
        assertEquals(Long.valueOf(22L), groups.get(0));
    }

    @Test
    public void pickCatalog_missingKey_returnsNull() throws Exception {
        JSONArray payload = new JSONArray();
        payload.put(wrapped(1, "resource_group", "lisa_root", "ЛИСА"));
        assertNull(LisaCatalog.pickCatalog(payload, "lisa"));
    }

    private static JSONObject wrapped(long id, String cls, String keyname, String name)
            throws Exception {
        JSONObject resource = new JSONObject();
        resource.put("id", id);
        resource.put("cls", cls);
        resource.put("display_name", name);
        if (keyname != null) {
            resource.put("keyname", keyname);
        }
        JSONObject item = new JSONObject();
        item.put("resource", resource);
        return item;
    }
}

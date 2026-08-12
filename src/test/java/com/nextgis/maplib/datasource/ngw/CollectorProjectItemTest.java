package com.nextgis.maplib.datasource.ngw;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class CollectorProjectItemTest {
    @Test
    public void rasterStyleKeepsStyleIdentityAndParentExtent() throws Exception {
        JSONObject collectorItem = new JSONObject()
                .put("display_name", "Rendered parcels")
                .put("visible", false)
                .put("min_zoom", "4.5")
                .put("max_zoom", 17)
                .put("tile_cache_ttl", 30);
        JSONObject resource = new JSONObject()
                .put("id", 501L)
                .put("display_name", "QGIS style")
                .put("cls", "qgis_vector_style")
                .put("parent", new JSONObject().put("id", 99L));

        CollectorProjectItem item =
                CollectorProjectItem.rasterStyle(collectorItem, resource);

        assertTrue(item.isRasterStyle());
        assertFalse(item.isVector());
        assertEquals(501L, item.getRemoteId());
        assertEquals(99L, item.getExtentRemoteId());
        assertEquals("Rendered parcels", item.getName());
        assertEquals("qgis_vector_style", item.getResourceClass());
        assertFalse(item.isCollectorEditable());
        assertFalse(item.isVisible());
        assertEquals(4.5f, item.getMinZoom(), 0f);
        assertEquals(17f, item.getMaxZoom(), 0f);
        assertEquals(30L * 60_000L, item.getTileMaxAge());
    }

    @Test
    public void vectorCarriesCollectorEditabilityFormAndConfig() throws Exception {
        JSONObject resource = new JSONObject()
                .put("id", 42L)
                .put("display_name", "Inspections")
                .put("cls", "vector_layer");

        CollectorProjectItem item = CollectorProjectItem.vector(
                new JSONObject(),
                resource,
                false,
                77L,
                "{\"mobile\":{\"label\":\"name\"}}");

        assertTrue(item.isVector());
        assertEquals(42L, item.getRemoteId());
        assertEquals(42L, item.getExtentRemoteId());
        assertEquals("Inspections", item.getName());
        assertFalse(item.isCollectorEditable());
        assertEquals(77L, item.getFormId());
        assertEquals("{\"mobile\":{\"label\":\"name\"}}", item.getConfigJson());
    }
}

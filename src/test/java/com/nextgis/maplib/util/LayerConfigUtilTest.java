package com.nextgis.maplib.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.nextgis.maplib.map.LayerOriginMetadata;

import org.json.JSONObject;
import org.junit.Test;

public class LayerConfigUtilTest {
    @Test
    public void extractsTopLevelMobileRenderMode() throws Exception {
        JSONObject cfg = new JSONObject("{\"mobile_render_mode\":\"local-vector-tiles\"}");

        assertEquals(
                LayerOriginMetadata.RENDER_MODE_LOCAL_VECTOR_TILES,
                LayerConfigUtil.extractRenderMode(cfg));
    }

    @Test
    public void extractsNestedLayerOriginRenderMode() throws Exception {
        JSONObject cfg = new JSONObject("{\"layer_origin\":{\"render_mode\":\"local_vector_tiles\"}}");

        assertEquals(
                LayerOriginMetadata.RENDER_MODE_LOCAL_VECTOR_TILES,
                LayerConfigUtil.extractRenderMode(cfg));
    }

    @Test
    public void returnsNullWhenRenderModeIsAbsent() throws Exception {
        JSONObject cfg = new JSONObject("{\"name\":\"Layer\"}");

        assertNull(LayerConfigUtil.extractRenderMode(cfg));
    }
}

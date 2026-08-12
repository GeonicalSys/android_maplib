package com.nextgis.maplib.datasource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.nextgis.maplib.util.Constants;

import org.json.JSONObject;
import org.junit.Test;

public class GeoEnvelopeJsonTest {

    @Test
    public void uninitializedEnvelope_serializesWithoutBboxValues() throws Exception {
        JSONObject json = new GeoEnvelope().toJSON();

        assertEquals(0, json.length());
        assertFalse(json.has(Constants.JSON_BBOX_MINX_KEY));
    }

    @Test
    public void initializedEnvelope_serializesAllBboxValues() throws Exception {
        JSONObject json = new GeoEnvelope(1.25, 5.5, -3.0, 8.75).toJSON();

        assertEquals(1.25, json.getDouble(Constants.JSON_BBOX_MINX_KEY), 0.0);
        assertEquals(-3.0, json.getDouble(Constants.JSON_BBOX_MINY_KEY), 0.0);
        assertEquals(5.5, json.getDouble(Constants.JSON_BBOX_MAXX_KEY), 0.0);
        assertEquals(8.75, json.getDouble(Constants.JSON_BBOX_MAXY_KEY), 0.0);
    }
}

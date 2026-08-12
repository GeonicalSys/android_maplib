package com.nextgis.maplib.util;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class NgwResmetaUtilTest {

    @Test
    public void getResmetaItemString_readsDistrict() throws Exception {
        JSONObject envelope = new JSONObject(
                "{\"resmeta\":{\"items\":{\"district\":\"vologda\"}}}");
        assertEquals("vologda", NgwResmetaUtil.getResmetaItemString(envelope, "district"));
    }

    @Test
    public void getResmetaItemString_missingKey_returnsNull() throws Exception {
        JSONObject envelope = new JSONObject("{\"resmeta\":{\"items\":{}}}");
        assertNull(NgwResmetaUtil.getResmetaItemString(envelope, "district"));
    }

    @Test
    public void getResmetaItemString_emptyValue_returnsNull() throws Exception {
        JSONObject envelope = new JSONObject(
                "{\"resmeta\":{\"items\":{\"district\":\"\"}}}");
        assertNull(NgwResmetaUtil.getResmetaItemString(envelope, "district"));
    }

    @Test
    public void getResmetaItemString_noResmeta_returnsNull() throws Exception {
        assertNull(NgwResmetaUtil.getResmetaItemString(new JSONObject("{}"), "district"));
    }
}

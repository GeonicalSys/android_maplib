package com.nextgis.maplib.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class NGWUtilFeaturesUrlTest {

    private static final String SERVER = "https://demo.nextgis.com";
    private static final long REMOTE_ID = 42L;

    @Test
    public void getFeaturesUrl_emptyWhere_unchangedLegacyQuery() {
        String url = NGWUtil.getFeaturesUrl(SERVER, REMOTE_ID, "");
        assertEquals(
                NGWUtil.getResourceUrl(SERVER, REMOTE_ID) + "/feature/?dt_format=iso&extensions=attachment",
                url);
    }

    @Test
    public void getFeaturesUrl_withDistrictFilter_appendsWhere() {
        String url = NGWUtil.getFeaturesUrl(SERVER, REMOTE_ID, "fld_district__like=%25olonec%25");
        assertTrue(url.contains("fld_district__like=%25olonec%25"));
        assertTrue(url.contains("dt_format=iso"));
        assertTrue(url.contains("extensions=attachment"));
    }

    @Test
    public void getFeatureCountUrl_emptyWhere_hasNoQuery() {
        assertEquals(
                NGWUtil.getResourceUrl(SERVER, REMOTE_ID) + "/feature_count",
                NGWUtil.getFeatureCountUrl(SERVER, REMOTE_ID, ""));
    }

    @Test
    public void getFeatureCountUrl_withDistrictFilter_appendsWhere() {
        String url = NGWUtil.getFeatureCountUrl(
                SERVER, REMOTE_ID, "fld_district__like=%25olonec%25");
        assertEquals(
                NGWUtil.getResourceUrl(SERVER, REMOTE_ID)
                        + "/feature_count?fld_district__like=%25olonec%25",
                url);
    }

    @Test
    public void getFeaturesIdOnlyUrl_omitsGeomAndFields() {
        String url = NGWUtil.getFeaturesIdOnlyUrl(
                SERVER, REMOTE_ID, "fld_district__like=%25olonec%25");
        assertTrue(url.contains("geom=no"));
        assertTrue(url.contains("fld_district__like=%25olonec%25"));
        assertTrue(url.contains("/feature/"));
    }
}

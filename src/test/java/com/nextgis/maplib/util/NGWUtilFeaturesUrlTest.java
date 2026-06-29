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
        String url = NGWUtil.getFeaturesUrl(SERVER, REMOTE_ID, "fld_district=vologda");
        assertTrue(url.contains("fld_district=vologda"));
        assertTrue(url.contains("dt_format=iso"));
        assertTrue(url.contains("extensions=attachment"));
    }
}

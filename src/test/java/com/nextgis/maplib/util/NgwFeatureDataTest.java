package com.nextgis.maplib.util;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class NgwFeatureDataTest {
    @Test public void exactComparisonCountsAllPartsAndHolesInBothDirections() throws Exception {
        String one = "GEOMETRYCOLLECTION (POINT (1 2))";
        String two = "GEOMETRYCOLLECTION (POINT (1 2), POINT (3 4))";
        assertFalse(NgwFeatureData.sameGeometry(one, two));
        assertFalse(NgwFeatureData.sameGeometry(two, one));
        String shell = "POLYGON ((0 0, 4 0, 4 4, 0 4, 0 0))";
        String hole = "POLYGON ((0 0, 4 0, 4 4, 0 4, 0 0), (1 1, 2 1, 2 2, 1 1))";
        assertFalse(NgwFeatureData.sameGeometry(shell, hole));
        assertFalse(NgwFeatureData.sameGeometry(hole, shell));
        assertTrue(NgwFeatureData.sameGeometry(hole, hole));
    }

    @Test public void geometryFormattingDoesNotCreateToleranceOrDiscardZAndM() throws Exception {
        assertTrue(NgwFeatureData.sameGeometry("POINT (1.000 2)", "POINT(1 2.0)"));
        assertTrue(NgwFeatureData.sameGeometry("POINT (-0 0)", "POINT(0 0)"));
        assertFalse(NgwFeatureData.sameGeometry("POINT (1 2)", "POINT(1.0000000001 2)"));
        assertFalse(NgwFeatureData.sameGeometry("POINT Z (1 2 3)", "POINT Z (1 2 4)"));
        assertFalse(NgwFeatureData.sameGeometry("POINT M (1 2 3)", "POINT M (1 2 4)"));
        assertFalse(NgwFeatureData.sameGeometry("POINT (1 2)", "POINT Z (1 2 0)"));
        assertFalse(NgwFeatureData.sameGeometry("POINT (1 2)", "MULTIPOINT ((1 2))"));
    }

    @Test public void submittedFieldsAreExactAndAdditionalServerFieldsAreAllowed() throws Exception {
        JSONObject sent = new JSONObject("{\"fields\":{\"name\":\"old\",\"n\":9007199254740993,\"nullable\":null},\"geom\":\"POINT(1 2)\"}");
        JSONObject server = new JSONObject(sent.toString());
        server.getJSONObject("fields").put("new_server_field", "kept");
        assertTrue(NgwFeatureData.sameSubmittedData(sent, server));
        server.getJSONObject("fields").put("n", 9007199254740992L);
        assertFalse(NgwFeatureData.sameSubmittedData(sent, server));
        server = new JSONObject(sent.toString()); server.getJSONObject("fields").put("nullable", 0);
        assertFalse(NgwFeatureData.sameSubmittedData(sent, server));
        server = new JSONObject(sent.toString()); server.getJSONObject("fields").remove("name");
        assertFalse(NgwFeatureData.sameSubmittedData(sent, server));
    }

    @Test public void resourceCrsComesFromResourceComponentAndMustBeAnExplicitPositiveInteger() throws Exception {
        JSONObject meta = new JSONObject("{\"resource\":{\"cls\":\"postgis_layer\"},\"postgis_layer\":{\"srs\":{\"id\":3857},\"geometry_srid\":32636}}");
        assertEquals(3857, NgwFeatureData.resourceSrs(meta));
        for (Object bad : new Object[]{"32636", 32636.0, -1, 0, JSONObject.NULL, Long.MAX_VALUE}) {
            meta.getJSONObject("postgis_layer").getJSONObject("srs").put("id", bad);
            assertEquals(-1, NgwFeatureData.resourceSrs(meta));
        }
        assertEquals(-1, NgwFeatureData.resourceSrs(new JSONObject()));
    }
}

package com.nextgis.maplib.util;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class NGWLayerSchemaCompatTest {
    @Test
    public void schemaFingerprintIgnoresFieldOrderAndUnrelatedResourceMetadata() throws Exception {
        JSONObject left = new JSONObject("{"
                + "\"resource\":{\"display_name\":\"Old\",\"updated\":1},"
                + "\"feature_layer\":{\"fields\":["
                + "{\"keyname\":\"name\",\"display_name\":\"Name\",\"datatype\":\"STRING\"},"
                + "{\"keyname\":\"count\",\"display_name\":\"Count\",\"datatype\":\"INTEGER\"}]},"
                + "\"vector_layer\":{\"geometry_type\":\"POINT\"}} ");
        JSONObject right = new JSONObject("{"
                + "\"resource\":{\"display_name\":\"New\",\"updated\":999},"
                + "\"feature_layer\":{\"fields\":["
                + "{\"keyname\":\"count\",\"display_name\":\"Counter\",\"datatype\":\"INTEGER\"},"
                + "{\"keyname\":\"name\",\"display_name\":\"Renamed\",\"datatype\":\"STRING\"}]},"
                + "\"vector_layer\":{\"geometry_type\":\"POINT\"}} ");

        assertEquals(
                NGWLayerSchemaCompat.schemaFingerprint(left, 4, "vector_layer"),
                NGWLayerSchemaCompat.schemaFingerprint(right, 4, "vector_layer"));
    }

    @Test
    public void schemaFingerprintChangesWithGeometryOrFieldType() throws Exception {
        JSONObject point = new JSONObject("{\"feature_layer\":{\"fields\":["
                + "{\"keyname\":\"name\",\"display_name\":\"Name\",\"datatype\":\"STRING\"}]},"
                + "\"vector_layer\":{\"geometry_type\":\"POINT\"}}");
        JSONObject line = new JSONObject("{\"feature_layer\":{\"fields\":["
                + "{\"keyname\":\"name\",\"display_name\":\"Name\",\"datatype\":\"STRING\"}]},"
                + "\"vector_layer\":{\"geometry_type\":\"LINESTRING\"}}");

        assertNotEquals(
                NGWLayerSchemaCompat.schemaFingerprint(point, 4, "vector_layer"),
                NGWLayerSchemaCompat.schemaFingerprint(line, 4, "vector_layer"));
    }
}

package com.nextgis.maplib.util;

import android.content.ContentValues;
import android.content.Context;
import android.database.DatabaseUtils;
import android.os.Parcel;

import com.nextgis.maplib.api.IGISApplication;
import com.nextgis.maplib.datasource.Field;
import com.nextgis.maplib.datasource.GeoPoint;
import com.nextgis.maplib.map.MapBase;
import com.nextgis.maplib.map.MapContentProviderHelper;
import com.nextgis.maplib.map.NGWVectorLayer;
import com.nextgis.maplib.reliability.TestGISApplication;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.Collections;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application=TestGISApplication.class, sdk={26,36})
public class FieldMetadataTest {
    private static class TestMap extends MapContentProviderHelper {
        TestMap(Context context, File path) {
            super(context, path, ((IGISApplication) context).getLayerFactory());
        }
        static void restore(MapBase map) { mInstance = map; }
    }

    @Test public void requiredSurvivesNgwJsonLocalJsonAndParcel() throws Exception {
        JSONArray input = new JSONArray("[{\"keyname\":\"auditor\","
                + "\"display_name\":\"Аудитор\",\"datatype\":\"STRING\",\"required\":true}]");
        Field field = NGWUtil.getFieldsFromJson(input).get(0);
        assertTrue(field.isRequired());
        Field restored = new Field();
        restored.fromJSON(field.toJSON());
        assertTrue(restored.isRequired());
        Parcel parcel = Parcel.obtain();
        try {
            restored.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            Field copy = Field.CREATOR.createFromParcel(parcel);
            assertTrue(copy.isRequired());
            assertEquals("Аудитор", copy.getAlias());
            assertEquals("auditor", copy.getName());
        } finally { parcel.recycle(); }
        input.getJSONObject(0).remove("required");
        assertFalse(NGWUtil.getFieldsFromJson(input).get(0).isRequired());
        JSONObject legacy = field.toJSON();
        legacy.remove("required");
        restored.fromJSON(legacy);
        assertFalse(restored.isRequired());
    }

    @Test public void togglingRequiredRepairsOnlyMetadataAndKeepsFeaturesAndOutbox() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        MapBase previous = null;
        try { previous = MapBase.getInstance(); } catch (IllegalArgumentException ignored) { }
        File root = new File(app.getCacheDir(), "required-" + UUID.randomUUID());
        assertTrue(root.mkdirs());
        TestMap map = new TestMap(app, new File(root, "test.ngm"));
        NGWVectorLayer layer = new NGWVectorLayer(app, new File(root, "audit"));
        layer.beginBulkImport();
        map.addLayer(layer);
        try {
            layer.create(GeoConstants.GTPoint, Collections.singletonList(
                    new Field(GeoConstants.FTString, "auditor", "Аудитор")));
            ContentValues values = new ContentValues();
            values.put(Constants.FIELD_GEOM, new GeoPoint(10, 20).toBlob());
            values.put("auditor", "Existing auditor");
            long id = layer.insertAddChanges(values);
            long changes = DatabaseUtils.queryNumEntries(map.getDatabase(false), layer.getChangeTableName());
            long generation = layer.getDataGeneration();
            JSONObject remote = new JSONObject("{\"resource\":{\"cls\":\"postgis_layer\"},"
                    + "\"feature_layer\":{\"fields\":[{\"keyname\":\"auditor\","
                    + "\"display_name\":\"Аудитор\",\"datatype\":\"STRING\",\"required\":true}]},"
                    + "\"postgis_layer\":{\"geometry_type\":\"POINT\"}}");
            String fingerprint = NGWLayerSchemaCompat.schemaFingerprint(remote, 4, "vector_layer");
            for (boolean required : new boolean[]{true, false}) {
                remote.getJSONObject("feature_layer").getJSONArray("fields").getJSONObject(0)
                        .put("required", required);
                NGWLayerSchemaCompat.SchemaComparison comparison = NGWLayerSchemaCompat
                        .compareLocalWithServerMeta(layer, remote, 4, "vector_layer", "postgis_layer");
                assertTrue(comparison.isMetadataOnlyMismatch());
                assertFalse(comparison.needsRebuild());
                assertTrue(layer.repairFieldMetadataFromVerifiedSchema(comparison.getRemoteFields()));
                assertEquals(required, layer.getFieldByName("auditor").isRequired());
                assertEquals(fingerprint, NGWLayerSchemaCompat.schemaFingerprint(remote, 4, "vector_layer"));
                assertEquals(1, layer.getSqliteTableRowCount());
                assertEquals("Existing auditor", DatabaseUtils.stringForQuery(map.getDatabase(false),
                        "SELECT auditor FROM audit WHERE _id = ?", new String[]{Long.toString(id)}));
                assertEquals(changes, DatabaseUtils.queryNumEntries(
                        map.getDatabase(false), layer.getChangeTableName()));
                assertEquals(generation, layer.getDataGeneration());
            }
        } finally {
            map.getDatabase(false).close();
            TestMap.restore(previous);
        }
    }
}

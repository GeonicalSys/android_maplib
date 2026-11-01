package com.nextgis.maplib.map;

import android.accounts.Account;
import android.content.SyncResult;

import com.hypertrack.hyperlog.HyperLog;
import com.nextgis.maplib.datasource.Field;
import com.nextgis.maplib.datasource.ngw.Connection;
import com.nextgis.maplib.reliability.TestGISApplication;
import com.nextgis.maplib.util.GeoConstants;
import com.nextgis.maplib.util.HttpResponse;
import com.nextgis.maplib.util.NGWLayerSchemaCompat;
import com.nextgis.maplib.util.NetworkUtil;
import com.nextgis.maplib.util.LayerConfigUtil;
import com.nextgis.maplib.util.SettingsConstants;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

/** Actual metadata comparison/refresh; only transport, physical schema and save ACK are seams. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE,
        application = NGWVectorLayerClassMetadataRepairTest.ModelApplication.class,
        shadows = {NGWVectorLayerClassMetadataRepairTest.Transport.class,
                NGWVectorLayerClassMetadataRepairTest.QuietLog.class})
public class NGWVectorLayerClassMetadataRepairTest {
    private static final String VECTOR = "vector_layer", POSTGIS = "postgis_layer";

    public static class ModelApplication extends TestGISApplication {
        int rebuilds;
        @Override public Account getAccount(String name) {
            return new Account("technical-class", "technical-test");
        }
        @Override public String getAccountUrl(Account account) { return "https://example.invalid"; }
        @Override public String getAccountLogin(Account account) { return ""; }
        @Override public String getAccountPassword(Account account) { return ""; }
        @Override public void scheduleNgwLayerRebuildAfterSchemaMismatch(
                NGWVectorLayer layer, String signature) { rebuilds++; }
    }

    @Implements(HyperLog.class)
    public static class QuietLog {
        @Implementation protected static void i(String tag, String message) { }
        @Implementation protected static void v(String tag, String message) { }
        @Implementation protected static void w(String tag, String message) { }
        @Implementation protected static void d(String tag, String message) { }
    }

    @Implements(NetworkUtil.class)
    public static class Transport {
        static final ArrayDeque<HttpResponse> replies = new ArrayDeque<>();
        static int gets;
        @Implementation protected boolean isNetworkAvailable() { return true; }
        @Implementation protected static HttpResponse get(
                String url, String login, String password, boolean readError) {
            gets++;
            assertTrue("Unexpected request; no real network is allowed", !replies.isEmpty());
            return replies.removeFirst();
        }
    }

    private static final class ProbeLayer extends NGWVectorLayer {
        boolean physicalMatch = true;
        final ArrayDeque<Boolean> saveResults = new ArrayDeque<>();
        final List<JSONObject> saves = new ArrayList<>();

        ProbeLayer(String storedClass, boolean staleFields) {
            super(RuntimeEnvironment.getApplication(), new File(
                    System.getProperty("java.io.tmpdir"), "class-metadata-" + UUID.randomUUID()));
            mAccountName = "technical-class";
            mRemoteId = 7;
            mNgwVersionMajor = 4;
            mNGWLayerType = VECTOR.equals(storedClass) ? Connection.NGWResourceTypeVectorLayer
                    : POSTGIS.equals(storedClass) ? Connection.NGWResourceTypePostgisLayer
                    : Connection.NGWResourceTypeNone;
            mGeometryType = GeoConstants.GTPoint;
            mTracked = true;
            mServerWhere = "technical=1";
            mFields = new LinkedHashMap<>();
            mFields.put("note", new Field(GeoConstants.FTString, "note",
                    staleFields ? "Local label" : "Verified label", false));
            // toJSON is used to witness the real serialized save payload, never to write an R-tree.
            mCacheLoaded = false;
        }

        @Override public boolean hasLocalDataTable() { return true; }
        @Override public List<String> validateSqliteSchemaAgainstFields(List<Field> fields) {
            return physicalMatch ? Collections.emptyList()
                    : Collections.singletonList("technical physical mismatch");
        }
        @Override public boolean save() {
            try { saves.add(new JSONObject(toJSON().toString())); }
            catch (Exception error) { throw new AssertionError(error); }
            return saveResults.isEmpty() || saveResults.removeFirst();
        }
        int storedType() { return mNGWLayerType; }
        void lineGeometry() { mGeometryType = GeoConstants.GTLineString; }
    }

    @Before public void before() {
        Transport.replies.clear();
        Transport.gets = 0;
        app().rebuilds = 0;
    }
    @After public void after() { Transport.replies.clear(); }
    private static ModelApplication app() { return (ModelApplication) RuntimeEnvironment.getApplication(); }
    private static int type(String cls) {
        return VECTOR.equals(cls) ? Connection.NGWResourceTypeVectorLayer
                : Connection.NGWResourceTypePostgisLayer;
    }
    private static JSONObject remote(String cls, boolean required) throws Exception {
        JSONObject value = new JSONObject();
        JSONObject resource = new JSONObject();
        if (cls != null) resource.put("cls", cls);
        value.put("resource", resource);
        value.put("feature_layer", new JSONObject().put("fields", new JSONArray().put(
                new JSONObject().put("keyname", "note").put("datatype", "STRING")
                        .put("display_name", "Verified label").put("required", required))));
        value.put(cls == null || cls.isEmpty() ? VECTOR : cls,
                new JSONObject().put("geometry_type", "POINT"));
        return value;
    }
    private static NGWLayerSchemaCompat.SchemaComparison compare(
            ProbeLayer layer, JSONObject meta, String expected) {
        return NGWLayerSchemaCompat.compareLocalWithServerMeta(layer, meta, 4, VECTOR, expected);
    }
    private static void enqueue(JSONObject meta) {
        HttpResponse response = new HttpResponse(200, "technical metadata", meta.toString());
        response.setOk(true);
        Transport.replies.add(response);
    }
    private static String refresh(ProbeLayer layer) throws Exception {
        Method method = NGWVectorLayer.class.getDeclaredMethod(
                "tryRefreshServerResourceMetaAndConfig", SyncResult.class);
        method.setAccessible(true);
        try { return method.invoke(layer, new SyncResult()).toString(); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Error) throw (Error) cause;
            if (cause instanceof Exception) throw (Exception) cause;
            throw new AssertionError(cause);
        }
    }
    private static void unchangedIdentityAndSync(JSONObject before, JSONObject after)
            throws Exception {
        JSONObject left = new JSONObject(before.toString());
        JSONObject right = new JSONObject(after.toString());
        for (String key : new String[]{"account", "id", "sync_type", "sync_direction",
                "tracked", "server_where", "geometry_type", "crs"}) {
            assertEquals("Unrelated serialized value changed: " + key, left.get(key), right.get(key));
        }
    }

    @Test public void knownClassOnlyMismatchIsRepairableInBothDirections() throws Exception {
        for (String actual : new String[]{VECTOR, POSTGIS}) {
            String stored = VECTOR.equals(actual) ? POSTGIS : VECTOR;
            NGWLayerSchemaCompat.SchemaComparison c = compare(
                    new ProbeLayer(stored, false), remote(actual, false), stored);
            assertTrue(c.isComparable());
            assertFalse(c.isResourceClassMatch());
            assertTrue(c.isSerializedFieldsMatch());
            assertFalse(c.needsRebuild());
            assertTrue("A known class mismatch must reach metadata repair", c.isMetadataOnlyMismatch());
            assertFalse(c.isFullyCompatible());
        }
    }

    @Test public void knownClassAndFieldDriftAreStillMetadataOnly() throws Exception {
        for (String actual : new String[]{VECTOR, POSTGIS}) {
            String stored = VECTOR.equals(actual) ? POSTGIS : VECTOR;
            NGWLayerSchemaCompat.SchemaComparison c = compare(
                    new ProbeLayer(stored, true), remote(actual, true), stored);
            assertFalse(c.isSerializedFieldsMatch());
            assertFalse(c.needsRebuild());
            assertTrue(c.isMetadataOnlyMismatch());
        }
    }

    @Test public void sameClassFieldRepairAndLegacyWildcardKeepTheirPolicy() throws Exception {
        for (String expected : new String[]{VECTOR, ""}) {
            NGWLayerSchemaCompat.SchemaComparison c = compare(
                    new ProbeLayer(VECTOR, true), remote(VECTOR, true), expected);
            assertTrue(c.isResourceClassMatch());
            assertTrue(c.isMetadataOnlyMismatch());
            assertFalse(c.needsRebuild());
        }
    }

    @Test public void missingUnknownAndUnsupportedClassesAreNotNewlyAdmitted() throws Exception {
        for (String expected : new String[]{null, "unsupported_layer"}) {
            NGWLayerSchemaCompat.SchemaComparison c = compare(
                    new ProbeLayer(null, true), remote(POSTGIS, true), expected);
            assertFalse(c.isMetadataOnlyMismatch());
            assertFalse(c.needsRebuild());
        }
        for (String actual : new String[]{null, "", "unsupported_layer"}) {
            NGWLayerSchemaCompat.SchemaComparison c = compare(
                    new ProbeLayer(VECTOR, false), remote(actual, false), VECTOR);
            assertFalse(c.isMetadataOnlyMismatch());
            assertFalse(c.needsRebuild());
        }
        // Missing actual class historically still admits field-only repair under the wildcard match.
        assertTrue(compare(new ProbeLayer(VECTOR, true), remote(null, true), VECTOR)
                .isMetadataOnlyMismatch());
    }

    @Test public void geometryAndPhysicalSchemaMismatchStillRequireRebuild() throws Exception {
        ProbeLayer geometry = new ProbeLayer(VECTOR, false);
        geometry.lineGeometry();
        ProbeLayer physical = new ProbeLayer(VECTOR, false);
        physical.physicalMatch = false;
        for (ProbeLayer layer : new ProbeLayer[]{geometry, physical}) {
            NGWLayerSchemaCompat.SchemaComparison c = compare(layer, remote(POSTGIS, false), VECTOR);
            assertTrue(c.needsRebuild());
            assertFalse(c.isMetadataOnlyMismatch());
        }
    }

    @Test public void unparsedMetadataRemainsFailOpenWithoutRepair() throws Exception {
        NGWLayerSchemaCompat.SchemaComparison c = compare(
                new ProbeLayer(VECTOR, false), new JSONObject(), VECTOR);
        assertFalse(c.isComparable());
        assertFalse(c.needsRebuild());
        assertFalse(c.isMetadataOnlyMismatch());
        assertTrue(c.isFullyCompatible());
    }

    @Test public void actualRefreshRepairsClassAndSerializedPayloadWithoutRefill() throws Exception {
        for (String actual : new String[]{VECTOR, POSTGIS}) {
            String stored = VECTOR.equals(actual) ? POSTGIS : VECTOR;
            ProbeLayer layer = new ProbeLayer(stored, false);
            JSONObject before = layer.toJSON();
            enqueue(remote(actual, false));
            assertEquals("CONTINUE_TO_FEATURES", refresh(layer));
            assertEquals(type(actual), layer.storedType());
            assertEquals(2, layer.saves.size());
            assertEquals(type(stored), layer.saves.get(0).getInt("ngw_layer_type"));
            assertEquals(type(actual), layer.saves.get(1).getInt("ngw_layer_type"));
            unchangedIdentityAndSync(before, layer.saves.get(1));
            assertEquals(before.getJSONArray("fields").toString(),
                    layer.saves.get(1).getJSONArray("fields").toString());
        }
        assertEquals(0, app().rebuilds);
        assertEquals(2, Transport.gets);
        assertTrue(Transport.replies.isEmpty());
    }

    @Test public void actualRefreshRepairsBeforeUnchangedDescriptionHashShortcut() throws Exception {
        ProbeLayer layer = new ProbeLayer(VECTOR, false);
        JSONObject meta = remote(POSTGIS, false);
        String description = "{\"technical\":true}";
        meta.getJSONObject("resource").put("description", description);
        String hash = LayerConfigUtil.md5(description);
        assertTrue(layer.getPreferences().edit()
                .putString(SettingsConstants.KEY_PREF_LAST_CONFIG_HASH, hash).commit());
        enqueue(meta);
        assertEquals("CONTINUE_TO_FEATURES", refresh(layer));
        assertEquals(type(POSTGIS), layer.storedType());
        assertEquals(2, layer.saves.size());
        assertEquals(hash, layer.getPreferences()
                .getString(SettingsConstants.KEY_PREF_LAST_CONFIG_HASH, ""));
        assertEquals(0, app().rebuilds);
    }

    @Test public void failedFieldSaveDefersAndRestoresPreviousMetadata() throws Exception {
        ProbeLayer layer = new ProbeLayer(VECTOR, true);
        JSONObject before = layer.toJSON();
        layer.saveResults.add(false);
        enqueue(remote(POSTGIS, true));
        assertEquals("FINISH_LAYERSYNC_OK", refresh(layer));
        assertEquals(type(VECTOR), layer.storedType());
        assertEquals(1, layer.saves.size());
        assertEquals(before.getJSONArray("fields").toString(), layer.toJSON()
                .getJSONArray("fields").toString());
        unchangedIdentityAndSync(before, layer.toJSON());
        assertEquals(0, app().rebuilds);
    }

    @Test public void failedClassSaveRestoresClassAndTheNextRefreshRetries() throws Exception {
        ProbeLayer layer = new ProbeLayer(VECTOR, true);
        JSONObject before = layer.toJSON();
        layer.saveResults.add(true);
        layer.saveResults.add(false);
        enqueue(remote(POSTGIS, true));
        assertEquals("FINISH_LAYERSYNC_OK", refresh(layer));
        assertEquals(type(VECTOR), layer.storedType());
        assertEquals(2, layer.saves.size());
        // The successful first field save is not an atomic whole-operation rollback.
        assertTrue(layer.getFieldByName("note").isRequired());
        enqueue(remote(POSTGIS, true));
        assertEquals("CONTINUE_TO_FEATURES", refresh(layer));
        assertEquals(type(POSTGIS), layer.storedType());
        assertEquals(4, layer.saves.size());
        unchangedIdentityAndSync(before, layer.toJSON());
        assertEquals(0, app().rebuilds);
    }

    @Test public void actualRefreshKeepsUnknownClassAndPhysicalRebuildNeighbors() throws Exception {
        ProbeLayer unknown = new ProbeLayer(null, false);
        enqueue(remote(POSTGIS, false));
        assertEquals("CONTINUE_TO_FEATURES", refresh(unknown));
        assertEquals(Connection.NGWResourceTypeNone, unknown.storedType());
        assertTrue(unknown.saves.isEmpty());
        ProbeLayer physical = new ProbeLayer(VECTOR, false);
        physical.physicalMatch = false;
        enqueue(remote(POSTGIS, false));
        assertEquals("FINISH_LAYERSYNC_OK", refresh(physical));
        assertTrue(physical.saves.isEmpty());
        assertEquals(1, app().rebuilds);
    }
}

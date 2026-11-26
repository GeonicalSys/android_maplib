package com.nextgis.maplib.map;

import android.accounts.Account;
import android.content.SyncResult;
import android.database.MatrixCursor;

import com.hypertrack.hyperlog.HyperLog;
import com.nextgis.maplib.datasource.Field;
import com.nextgis.maplib.datasource.ngw.Connection;
import com.nextgis.maplib.reliability.TestGISApplication;
import com.nextgis.maplib.util.AccountUtil;
import com.nextgis.maplib.util.Constants;
import com.nextgis.maplib.util.GeoConstants;
import com.nextgis.maplib.util.HttpResponse;
import com.nextgis.maplib.util.LayerConfigUtil;
import com.nextgis.maplib.util.LisaHistoryFields;
import com.nextgis.maplib.util.NetworkUtil;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

/** Actual outbound serializer and metadata refresh, with synthetic authoritative responses only. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE,
        application = NGWVectorLayerHistoryFieldsTest.ModelApplication.class,
        shadows = {NGWVectorLayerHistoryFieldsTest.Transport.class,
                NGWVectorLayerHistoryFieldsTest.QuietLog.class})
public class NGWVectorLayerHistoryFieldsTest {
    private static final String ACCOUNT = "history-test";
    private static final String URL = "https://history.example.invalid";
    private static final long RESOURCE_ID = 172;
    private static final String[] HISTORY = {"created_at", "created_by", "updated_at",
            "updated_by", "deleted_at", "deleted_by", "deletion_operation_id"};
    private static final String[] LEGACY_HISTORY = {"created_at", "created_by", "updated_at", "updated_by"};

    public static class ModelApplication extends TestGISApplication {
        String url = URL;
        int rebuilds;
        @Override public Account getAccount(String name) { return new Account(name, "history-test"); }
        @Override public String getAccountUrl(Account account) { return url; }
        @Override public String getAccountLogin(Account account) { return "synthetic"; }
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
            assertFalse("Unexpected request; no real network is allowed", replies.isEmpty());
            return replies.removeFirst();
        }
    }

    private static class ProbeLayer extends NGWVectorLayer {
        int saves;
        int reviewReads;
        JSONObject reviewMetadata;

        ProbeLayer() {
            super(RuntimeEnvironment.getApplication(), new File(
                    System.getProperty("java.io.tmpdir"), "history-fields-" + UUID.randomUUID()));
            setName("Synthetic history layer");
            setAccountName(ACCOUNT);
            setRemoteId(RESOURCE_ID);
            mNgwVersionMajor = 4;
            mNGWLayerType = Connection.NGWResourceTypePostgisLayer;
            mGeometryType = GeoConstants.GTPoint;
            mCRS = GeoConstants.CRS_WEB_MERCATOR;
            mSyncType = Constants.SYNC_ATTRIBUTES;
            mFields = new LinkedHashMap<>();
            for (Field field : localFields()) mFields.put(field.getName(), field);
            mCacheLoaded = false;
        }
        @Override public boolean hasLocalDataTable() { return true; }
        @Override public int getSqliteTableRowCount() { return 0; }
        @Override public List<String> validateSqliteSchemaAgainstFields(List<Field> fields) {
            return Collections.emptyList();
        }
        @Override public boolean save() { saves++; return true; }
        @Override protected JSONObject readResourceMetadataForHistory(AccountUtil.AccountData account) {
            reviewReads++;
            return reviewMetadata;
        }
        void verify(JSONObject meta) { verifyLisaHistoryMetadata(meta, app().url); }
        JSONObject snapshot() throws Exception {
            mCacheLoaded = false;
            return toJSON();
        }
        JSONObject serialize(boolean nullNumbers) throws Exception {
            try (MatrixCursor cursor = new MatrixCursor(mFields.keySet().toArray(new String[0]))) {
                Object[] row = new Object[mFields.size()];
                int i = 0;
                for (Field field : mFields.values()) {
                    switch (field.getName()) {
                        case "created_by": case "updated_by": case "deleted_by":
                            row[i] = "server-owned"; break;
                        case "deletion_operation_id": row[i] = null; break;
                        case "ordinary_integer": row[i] = nullNumbers ? null : 0; break;
                        case "ordinary_long": row[i] = nullNumbers ? null : 0L; break;
                        case "ordinary_real": row[i] = nullNumbers ? null : 0.0; break;
                        case "created_at_note": row[i] = "user data"; break;
                        default: row[i] = null;
                    }
                    i++;
                }
                cursor.addRow(row);
                assertTrue(cursor.moveToFirst());
                return new JSONObject(cursorToJson(cursor));
            }
        }
    }

    @Before public void before() {
        app().url = URL;
        app().rebuilds = 0;
        Transport.replies.clear();
        Transport.gets = 0;
    }
    @After public void after() { Transport.replies.clear(); }
    private static ModelApplication app() { return (ModelApplication) RuntimeEnvironment.getApplication(); }
    private static List<Field> localFields() {
        List<Field> fields = new ArrayList<>();
        for (String name : HISTORY) {
            int type = name.endsWith("_at") ? GeoConstants.FTDateTime
                    : name.endsWith("_by") ? GeoConstants.FTString : GeoConstants.FTLong;
            fields.add(new Field(type, name, name));
        }
        fields.add(new Field(GeoConstants.FTInteger, "ordinary_integer", "ordinary_integer"));
        fields.add(new Field(GeoConstants.FTLong, "ordinary_long", "ordinary_long"));
        fields.add(new Field(GeoConstants.FTReal, "ordinary_real", "ordinary_real"));
        fields.add(new Field(GeoConstants.FTString, "created_at_note", "created_at_note"));
        return fields;
    }
    private static JSONObject metadata() throws Exception {
        JSONArray fields = new JSONArray();
        for (Field field : localFields()) {
            String datatype;
            switch (field.getType()) {
                case GeoConstants.FTDateTime: datatype = "DATETIME"; break;
                case GeoConstants.FTLong: datatype = "BIGINT"; break;
                case GeoConstants.FTInteger: datatype = "INTEGER"; break;
                case GeoConstants.FTReal: datatype = "REAL"; break;
                default: datatype = "STRING";
            }
            fields.put(new JSONObject().put("keyname", field.getName()).put("datatype", datatype)
                    .put("display_name", field.getAlias()).put("required", false));
        }
        return new JSONObject()
                .put("resource", new JSONObject().put("cls", "postgis_layer").put("id", RESOURCE_ID))
                .put("postgis_layer", new JSONObject().put("schema", "dynamic")
                        .put("table", "field_points").put("geometry_type", "POINT"))
                .put("feature_layer", new JSONObject().put("fields", fields));
    }
    private static JSONObject metadataWithHistory(String... historyNames) throws Exception {
        JSONObject meta = metadata();
        JSONArray original = meta.getJSONObject("feature_layer").getJSONArray("fields");
        JSONArray filtered = new JSONArray();
        for (int i = 0; i < original.length(); i++) {
            JSONObject field = original.getJSONObject(i);
            String name = field.getString("keyname");
            if (!LisaHistoryFields.isServerManaged(name) || Arrays.asList(historyNames).contains(name)) {
                filtered.put(field);
            }
        }
        meta.getJSONObject("feature_layer").put("fields", filtered);
        return meta;
    }
    private static LisaHistoryFields proof(JSONObject meta) {
        return LisaHistoryFields.fromResourceMetadata(meta, ACCOUNT, URL, RESOURCE_ID);
    }
    private static void enqueue(int status, String body) {
        HttpResponse response = new HttpResponse(status, "synthetic metadata", body);
        response.setOk(status >= 200 && status < 300);
        Transport.replies.add(response);
    }
    private static Object invoke(String name, Class<?>[] types, Object receiver, Object... args)
            throws Exception {
        Method method = NGWVectorLayer.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try { return method.invoke(receiver, args); }
        catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Error) throw (Error) cause;
            if (cause instanceof Exception) throw (Exception) cause;
            throw new AssertionError(cause);
        }
    }
    private static String refresh(ProbeLayer layer) throws Exception {
        return invoke("tryRefreshServerResourceMetaAndConfig", new Class<?>[]{SyncResult.class},
                layer, new SyncResult()).toString();
    }
    private static void refreshForReview(ProbeLayer layer) throws Exception {
        invoke("refreshLisaHistoryFromServer", new Class<?>[]{AccountUtil.AccountData.class}, layer,
                AccountUtil.getAccountData(RuntimeEnvironment.getApplication(), layer.getAccountName()));
    }
    private static JSONObject fields(ProbeLayer layer) throws Exception {
        return layer.serialize(true).getJSONObject("fields");
    }
    private static void assertHistoryAbsent(JSONObject fields) {
        for (String name : HISTORY) assertFalse("Server field leaked: " + name, fields.has(name));
    }
    private static void assertHistoryPresent(JSONObject fields) {
        for (String name : HISTORY) assertTrue("Generic field lost: " + name, fields.has(name));
        assertEquals(0L, fields.optLong("deletion_operation_id", -1));
    }
    private static void assertOrdinaryZeros(JSONObject fields) throws Exception {
        assertEquals(0, fields.getInt("ordinary_integer"));
        assertEquals(0L, fields.getLong("ordinary_long"));
        assertEquals(0.0, fields.getDouble("ordinary_real"), 0.0);
        assertEquals("user data", fields.getString("created_at_note"));
    }

    @Test public void onlyExactCanonicalPostgisTablesWithCompatibleHistoryReceiveProof() throws Exception {
        for (String table : new String[]{"field_lines", "field_points", "field_polygons", "find_rum",
                "line_tech", "low_storage", "plancut", "point_tech", "poly_tech", "storage",
                "tracks", "type_roads", "zarub"}) {
            JSONObject meta = metadata();
            meta.getJSONObject("postgis_layer").put("table", table);
            assertNotNull(table, proof(meta));
        }
        for (String table : new String[]{"field_points_other", "Field_points", "type_roads_exist",
                "other", "*", ""}) {
            JSONObject meta = metadata();
            meta.getJSONObject("postgis_layer").put("table", table);
            assertNull(table, proof(meta));
        }
        JSONObject wrongSchema = metadata();
        wrongSchema.getJSONObject("postgis_layer").put("schema", "public");
        assertNull(proof(wrongSchema));
        JSONObject wrongClass = metadata();
        wrongClass.getJSONObject("resource").put("cls", "vector_layer");
        assertNull(proof(wrongClass));
    }

    @Test public void metadataRequiresExactPositiveResourceIdAndTarget() throws Exception {
        for (Object id : new Object[]{0, -1, RESOURCE_ID + 1, "172", 172.0, 172.5, JSONObject.NULL}) {
            JSONObject meta = metadata();
            meta.getJSONObject("resource").put("id", id);
            assertNull("Invalid resource id: " + id, proof(meta));
        }
        assertNull(LisaHistoryFields.fromResourceMetadata(metadata(), ACCOUNT, URL, 0));
        assertNull(LisaHistoryFields.fromResourceMetadata(metadata(), null, URL, RESOURCE_ID));
        assertNull(LisaHistoryFields.fromResourceMetadata(metadata(), ACCOUNT, "", RESOURCE_ID));
        LisaHistoryFields verified = proof(metadata());
        assertNotNull(verified);
        assertTrue(verified.matchesTarget(ACCOUNT, URL, RESOURCE_ID));
        assertFalse(verified.matchesTarget(ACCOUNT + "2", URL, RESOURCE_ID));
        assertFalse(verified.matchesTarget(ACCOUNT, URL + "/", RESOURCE_ID));
        assertFalse(verified.matchesTarget(ACCOUNT, URL, RESOURCE_ID + 1));
    }

    @Test public void missingAllWrongTypeAndDuplicateHistoryFieldsNeverReceiveProof() throws Exception {
        assertNull(proof(null));
        assertNull(proof(new JSONObject()));
        assertNull(proof(metadataWithHistory()));
        for (int i = 0; i < HISTORY.length; i++) {
            JSONObject missing = metadata();
            missing.getJSONObject("feature_layer").getJSONArray("fields").remove(i);
            assertNotNull("Missing optional field " + HISTORY[i], proof(missing));
            JSONObject wrongType = metadata();
            wrongType.getJSONObject("feature_layer").getJSONArray("fields")
                    .getJSONObject(i).put("datatype", "INTEGER");
            assertNull("Wrong type " + HISTORY[i], proof(wrongType));
            JSONObject duplicate = metadata();
            JSONArray fields = duplicate.getJSONObject("feature_layer").getJSONArray("fields");
            fields.put(new JSONObject(fields.getJSONObject(i).toString()));
            assertNull("Duplicate " + HISTORY[i], proof(duplicate));
        }
        JSONObject malformed = metadata();
        malformed.getJSONObject("feature_layer").getJSONArray("fields").put("not a field");
        assertNull(proof(malformed));
    }

    @Test public void localHistorySubsetsAllowMetadataLookupButRequireEveryPresentExactType() {
        assertTrue(LisaHistoryFields.hasCompatibleLocalSchema(localFields()));
        assertFalse(LisaHistoryFields.hasCompatibleLocalSchema(null));
        assertFalse(LisaHistoryFields.hasCompatibleLocalSchema(Collections.emptyList()));
        assertFalse(LisaHistoryFields.hasCompatibleLocalSchema(Collections.singletonList(
                new Field(GeoConstants.FTInteger, "ordinary_integer", "ordinary_integer"))));
        for (int i = 0; i < HISTORY.length; i++) {
            List<Field> missing = localFields();
            missing.remove(i);
            assertTrue(LisaHistoryFields.hasCompatibleLocalSchema(missing));
            assertTrue(LisaHistoryFields.hasCompatibleLocalSchema(
                    Collections.singletonList(localFields().get(i))));
            List<Field> wrongType = localFields();
            wrongType.set(i, new Field(GeoConstants.FTInteger, HISTORY[i], HISTORY[i]));
            assertFalse(LisaHistoryFields.hasCompatibleLocalSchema(wrongType));
        }
        List<Field> duplicate = localFields();
        duplicate.add(duplicate.get(0));
        assertFalse(LisaHistoryFields.hasCompatibleLocalSchema(duplicate));
        List<Field> malformed = localFields();
        malformed.add(new Field(GeoConstants.FTString, "", "Empty name"));
        assertFalse(LisaHistoryFields.hasCompatibleLocalSchema(malformed));
    }

    @Test public void oldServerHistorySubsetsStillRequireCanonicalSourceAndExactPresentTypes() throws Exception {
        assertNotNull(proof(metadataWithHistory(LEGACY_HISTORY)));
        for (String name : HISTORY) {
            assertNotNull("Compatible history subset: " + name, proof(metadataWithHistory(name)));
        }
        JSONObject foreign = metadataWithHistory(LEGACY_HISTORY);
        foreign.getJSONObject("postgis_layer").put("table", "foreign_points");
        assertNull(proof(foreign));
        JSONObject wrongType = metadataWithHistory(LEGACY_HISTORY);
        wrongType.getJSONObject("feature_layer").getJSONArray("fields")
                .getJSONObject(0).put("datatype", "STRING");
        assertNull(proof(wrongType));
    }

    @Test public void oldFourFieldLocalSchemaSerializesAgainstOldAndNewServerWithoutChangingLocalFields() throws Exception {
        for (JSONObject meta : new JSONObject[]{metadataWithHistory(LEGACY_HISTORY), metadata()}) {
            ProbeLayer layer = new ProbeLayer();
            for (String name : HISTORY) {
                if (!Arrays.asList(LEGACY_HISTORY).contains(name)) layer.mFields.remove(name);
            }
            assertEquals(8, layer.getFields().size());
            String originalSchema = layer.snapshot().getJSONArray("fields").toString();
            layer.verify(meta);
            JSONObject filtered = fields(layer);
            assertHistoryAbsent(filtered);
            assertOrdinaryZeros(filtered);
            assertEquals(4, filtered.length());
            assertEquals("Uploading must retain the old local field set", originalSchema,
                    layer.snapshot().getJSONArray("fields").toString());
        }
    }

    @Test public void genericNumericNullAndRealZeroKeepTheirExistingOutboundValues() throws Exception {
        ProbeLayer layer = new ProbeLayer();
        assertHistoryPresent(fields(layer));
        assertOrdinaryZeros(fields(layer));
        assertOrdinaryZeros(layer.serialize(false).getJSONObject("fields"));
        assertTrue(fields(layer).isNull("created_at"));
    }

    @Test public void verifiedSourceOmitsOnlyTheSevenServerFieldsIncludingNonNullValues() throws Exception {
        ProbeLayer layer = new ProbeLayer();
        layer.verify(metadata());
        JSONObject filtered = fields(layer);
        assertHistoryAbsent(filtered);
        assertEquals(4, filtered.length());
        assertOrdinaryZeros(filtered);
        assertOrdinaryZeros(layer.serialize(false).getJSONObject("fields"));
        assertFalse(LisaHistoryFields.isServerManaged("Created_at"));
        assertFalse(LisaHistoryFields.isServerManaged("created_at_note"));
        assertFalse(LisaHistoryFields.isServerManaged(null));
    }

    @Test public void foreignSourcesAndIncompatibleLocalHistoryTypesRemainGeneric() throws Exception {
        ProbeLayer layer = new ProbeLayer();
        JSONObject foreign = metadata();
        foreign.getJSONObject("postgis_layer").put("table", "foreign_points");
        layer.verify(foreign);
        assertHistoryPresent(fields(layer));
        foreign = metadata();
        foreign.getJSONObject("resource").put("cls", "vector_layer");
        layer.verify(foreign);
        assertHistoryPresent(fields(layer));
        layer.verify(metadata());
        layer.mFields.put("created_at", new Field(GeoConstants.FTInteger, "created_at", "created_at"));
        assertTrue(fields(layer).has("deletion_operation_id"));
    }

    @Test public void unchangedSchemaAndDescriptionStillRefreshAuthoritativeProof() throws Exception {
        ProbeLayer layer = new ProbeLayer();
        JSONObject meta = metadata();
        String description = "{\"technical\":true}";
        meta.getJSONObject("resource").put("description", description);
        String hash = LayerConfigUtil.md5(description);
        assertTrue(layer.getPreferences().edit()
                .putString(SettingsConstants.KEY_PREF_LAST_CONFIG_HASH, hash).commit());
        enqueue(200, meta.toString());
        assertEquals("CONTINUE_TO_FEATURES", refresh(layer));
        assertHistoryAbsent(fields(layer));
        assertEquals(0, layer.saves);
        assertEquals(0, app().rebuilds);
        assertEquals(1, Transport.gets);
        assertEquals(hash, layer.getPreferences().getString(SettingsConstants.KEY_PREF_LAST_CONFIG_HASH, ""));
    }

    @Test public void unsuccessfulMalformedAndWrongClassRefreshDiscardPreviousProof() throws Exception {
        ProbeLayer layer = new ProbeLayer();
        for (String body : new String[]{"{}", "broken"}) {
            layer.verify(metadata());
            enqueue(200, body);
            refresh(layer);
            assertHistoryPresent(fields(layer));
        }
        layer.verify(metadata());
        enqueue(503, "synthetic failure");
        refresh(layer);
        assertHistoryPresent(fields(layer));
        layer.verify(metadata());
        JSONObject vector = metadata();
        vector.getJSONObject("resource").put("cls", "vector_layer");
        vector.put("vector_layer", new JSONObject().put("geometry_type", "POINT"));
        enqueue(200, vector.toString());
        refresh(layer);
        assertHistoryPresent(fields(layer));
    }

    @Test public void urlAccountAndResourceRebindingCannotReuseProof() throws Exception {
        ProbeLayer layer = new ProbeLayer();
        layer.verify(metadata());
        app().url = URL + "/different";
        assertHistoryPresent(fields(layer));
        app().url = URL;
        assertHistoryPresent(fields(layer));
        layer.verify(metadata());
        layer.setAccountName(ACCOUNT + "-other");
        assertHistoryPresent(fields(layer));
        layer.setAccountName(ACCOUNT);
        assertHistoryPresent(fields(layer));
        layer.verify(metadata());
        layer.setRemoteId(RESOURCE_ID + 1);
        assertHistoryPresent(fields(layer));
        layer.setRemoteId(RESOURCE_ID);
        assertHistoryPresent(fields(layer));
    }

    @Test public void savedJsonDoesNotPersistProofAndPresentationConfigCannotForgeIt() throws Exception {
        ProbeLayer layer = new ProbeLayer();
        JSONObject config = layer.snapshot();
        config.put("lisa_history_fields", true).put("postgis_layer",
                metadata().getJSONObject("postgis_layer"));
        layer.fromJSON(config);
        assertHistoryPresent(fields(layer));
        layer.verify(metadata());
        layer.applyMobileConfigPreservingAuthoritativeSchema(config);
        assertHistoryAbsent(fields(layer));
        layer.fromJSON(layer.snapshot());
        assertHistoryPresent(fields(layer));
    }

    @Test public void explicitReviewAfterRestartFetchesFreshProofAndClearsItOnFailure() throws Exception {
        ProbeLayer layer = new ProbeLayer();
        layer.reviewMetadata = metadata();
        refreshForReview(layer);
        assertHistoryAbsent(fields(layer));
        assertEquals(1, layer.reviewReads);
        layer.reviewMetadata = null;
        refreshForReview(layer);
        assertHistoryPresent(fields(layer));
        assertEquals(2, layer.reviewReads);
        for (String name : HISTORY) layer.mFields.remove(name);
        refreshForReview(layer);
        assertEquals("Generic layers do not request history metadata", 2, layer.reviewReads);
    }

    @Test public void comparisonCopiesRemoveOnlyHistoryAndLeaveFrozenPayloadBytesUntouched() throws Exception {
        JSONObject submitted = new ProbeLayer().serialize(true);
        submitted.put("extensions", new JSONObject().put("attachment", new JSONArray().put(12)));
        String frozen = submitted.toString();
        JSONObject copied = LisaHistoryFields.withoutServerManagedFields(submitted);
        assertEquals(frozen, submitted.toString());
        assertHistoryPresent(submitted.getJSONObject("fields"));
        assertHistoryAbsent(copied.getJSONObject("fields"));
        assertOrdinaryZeros(copied.getJSONObject("fields"));
        copied.getJSONObject("extensions").getJSONArray("attachment").put(13);
        assertEquals(1, submitted.getJSONObject("extensions").getJSONArray("attachment").length());
        JSONObject noFields = new JSONObject().put("id", 5);
        assertEquals(noFields.toString(), LisaHistoryFields.withoutServerManagedFields(noFields).toString());
        assertNull(LisaHistoryFields.withoutServerManagedFields(null));
    }
}

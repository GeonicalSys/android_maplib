package com.nextgis.maplib.util;

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKBReader;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class NgwFeatureTransactionTest {
    private static final String TARGET = "verified-workspace-account-resource";
    private static final String PAYLOAD = "{\"fields\":{\"label\":\"Старая точка\",\"count\":0},\"geom\":\"POINT(12.5 34.75)\"}";
    private static final String TYPES = "{\"label\":\"STRING\",\"count\":\"INTEGER\"}";

    private static AccountUtil.AccountData account() {
        AccountUtil.AccountData account = new AccountUtil.AccountData();
        account.url = "https://ngw.test/prefix/"; account.login = "test-user"; account.password = "test-password";
        return account;
    }

    private static UploadOperationJournal.Entry receipt(SQLiteDatabase db, String payload, int srs, String types, boolean optIn) throws Exception {
        FeatureChanges.initialize(db, "points_changes");
        ContentValues row = new ContentValues();
        row.put(Constants.FIELD_ID, 1); row.put(Constants.FIELD_FEATURE_ID, 10000000);
        row.put(Constants.FIELD_OPERATION, Constants.CHANGE_OPERATION_NEW);
        row.put(Constants.FIELD_ATTACH_ID, -1); row.put(Constants.FIELD_ATTACH_OPERATION, 0);
        FeatureChanges.insert(db, "points_changes", row);
        return UploadOperationJournal.begin(db, "points", "points_changes", 1, 10000000, -1, "create", TARGET, () -> {
            if (optIn) UploadTransactionJournal.reserve(db, FeatureChanges.operationId(db, "points_changes", 1),
                    "points", TARGET, UploadTransactionJournal.sha256(payload), srs, types);
            return payload;
        }, 1);
    }

    private static NgwFeatureTransaction.Result execute(SQLiteDatabase db, UploadOperationJournal.Entry entry, FakeServer server) {
        return NgwFeatureTransaction.execute(db, entry, TARGET, 172, account(), server);
    }

    private static HttpResponse response(int code, String body) {
        HttpResponse response = new HttpResponse(code, "fixture", body);
        response.setOk(code >= 200 && code < 300); return response;
    }

    private static final class SimulatedDeath extends Error { }
    private static final class Transaction {
        String operation;
        boolean committed;
        long fid;
    }

    private static final class FakeServer implements NgwFeatureTransaction.Transport {
        final Map<Long, Transaction> transactions = new HashMap<>();
        final List<String> calls = new ArrayList<>(), stagedBodies = new ArrayList<>();
        long nextTid = 1;
        int features, allocations, commits, gets, transforms;
        boolean loseAllocation, loseStaging, loseCommit, dieAfterCommit, loseCommitBeforeApply, resultsUnavailable,
                externalCommitWithoutReceipt, invalidResult, rejectPreparation;
        String transformedWkt = "POINT(400000 5000000)";
        Runnable beforeCommit;

        @Override public HttpResponse request(String method, String url, String body, AccountUtil.AccountData account) throws IOException {
            calls.add(method + " " + url);
            assertEquals("test-user", account.login);
            assertTrue(url.startsWith("https://ngw.test/prefix/api/"));
            try {
                if (url.contains("/geom_transform")) {
                    assertEquals("POST", method); transforms++;
                    JSONObject input = new JSONObject(body);
                    assertEquals(3857, input.getInt("srs"));
                    assertEquals("POINT(12.5 34.75)", input.getString("geom"));
                    return response(200, new JSONObject().put("geom", transformedWkt).toString());
                }
                if (url.endsWith("/transaction/")) {
                    assertEquals("POST", method); assertEquals("{}", body);
                    long tid = nextTid++; transactions.put(tid, new Transaction()); allocations++;
                    if (loseAllocation) { loseAllocation = false; throw new IOException("synthetic allocation response loss"); }
                    return response(200, "{\"id\":" + tid + ",\"started\":\"2026-11-26T00:00:00\"}");
                }
                long tid = Long.parseLong(url.substring(url.lastIndexOf('/') + 1));
                Transaction transaction = transactions.get(tid);
                if (transaction == null) return response(404, "{\"title\":\"Transaction not found\"}");
                if ("PUT".equals(method)) {
                    JSONArray batch = new JSONArray(body); assertEquals(1, batch.length());
                    JSONArray pair = batch.getJSONArray(0); assertEquals(0, pair.getInt(0));
                    String operation = pair.getJSONObject(1).toString();
                    if (transaction.operation != null) assertEquals(transaction.operation, operation);
                    transaction.operation = operation; stagedBodies.add(body);
                    if (loseStaging) { loseStaging = false; throw new IOException("synthetic staging response loss"); }
                    return response(200, "null");
                }
                if ("POST".equals(method)) {
                    commits++; if (beforeCommit != null) beforeCommit.run();
                    assertNotNull(transaction.operation);
                    if (rejectPreparation) return response(200,
                            "{\"status\":\"errors\",\"errors\":[[0,{\"error\":\"invalid_fixture_field\",\"status_code\":422}]]}");
                    if (loseCommitBeforeApply) { loseCommitBeforeApply = false; throw new IOException("synthetic loss before apply"); }
                    if (!transaction.committed) {
                        transaction.fid = 100 + ++features;
                        if (!externalCommitWithoutReceipt) transaction.committed = true;
                    }
                    if (externalCommitWithoutReceipt) return response(503, "{\"title\":\"External commit receipt missing\"}");
                    if (dieAfterCommit) { dieAfterCommit = false; throw new SimulatedDeath(); }
                    if (loseCommit) { loseCommit = false; throw new IOException("synthetic commit response loss"); }
                    return response(200, "{\"status\":\"committed\",\"committed\":\"2026-11-26T00:00:00\"}");
                }
                assertEquals("GET", method); gets++;
                if (resultsUnavailable) return response(503, "{\"title\":\"Results temporarily unavailable\"}");
                if (!transaction.committed) return response(422, "{\"title\":\"Transaction not committed\"}");
                return response(200, invalidResult ? "[[0,{\"action\":\"feature.create\",\"fid\":\"101\"}]]"
                        : "[[0,{\"action\":\"feature.create\",\"fid\":" + transaction.fid + "}]]");
            } catch (org.json.JSONException error) { throw new AssertionError(error); }
        }
    }

    @Test public void reviewedCreationUsesFrozenWkbAndRecordsTheRemoteIdOnce() throws Exception {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            UploadOperationJournal.Entry entry = receipt(db, PAYLOAD, 3857, TYPES, true);
            FakeServer server = new FakeServer();
            server.beforeCommit = () -> {
                assertFalse(db.inTransaction());
                UploadTransactionJournal.Entry active = UploadTransactionJournal.find(db, entry.layer, entry.id);
                assertTrue(active.commitStarted);
                try {
                    LayerDatabaseTransaction.run(db, () -> {
                        UploadTransactionJournal.resetAfterExplicitReview(db, active, entry); return null;
                    });
                    fail("An active transaction cannot be reset even without an outer upload Flight");
                } catch (SQLiteException expected) { }
            };
            NgwFeatureTransaction.Result result = execute(db, entry, server);
            assertTrue(result.accepted); assertEquals(101, result.remoteId);
            UploadTransactionJournal.Entry saved = UploadTransactionJournal.find(db, entry.layer, entry.id);
            JSONObject operation = new JSONObject(saved.operationJson);
            assertEquals("feature.create", operation.getString("action"));
            assertEquals("Старая точка", operation.getJSONObject("fields").getString("label"));
            assertEquals(0, operation.getJSONObject("fields").getInt("count"));
            byte[] wkb = Base64.decode(operation.getString("geom"), Base64.NO_WRAP);
            Geometry geometry = new WKBReader().read(wkb);
            assertEquals(12.5, geometry.getCoordinate().x, 0); assertEquals(34.75, geometry.getCoordinate().y, 0);
            assertEquals(21, wkb.length); // OGC 2D point: byte order + type + two doubles, no embedded SRID.
            assertEquals(saved.operationJson, new JSONArray(server.stagedBodies.get(0)).getJSONArray(0).getJSONObject(1).toString());
            assertEquals(UploadTransactionJournal.COMMITTED, saved.phase);
            assertTrue(execute(db, entry, server).accepted);
            assertEquals(1, server.features); assertEquals(1, server.commits); assertEquals(1, server.gets);
            assertEquals(PAYLOAD, UploadOperationJournal.find(db, entry.layer, entry.id).payload);
        }
    }

    @Test public void aReceiptWithoutAtomicOptInNeverStartsTransactionHttp() throws Exception {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            UploadOperationJournal.Entry entry = receipt(db, PAYLOAD, 3857, TYPES, false);
            FakeServer server = new FakeServer();
            NgwFeatureTransaction.Result result = execute(db, entry, server);
            assertFalse(result.accepted); assertEquals("transaction_opt_in_missing", result.reason);
            assertTrue(server.calls.isEmpty());
        }
    }

    @Test public void aLostAllocationResponseCanOnlyLeaveAnEmptyOrphanTransaction() throws Exception {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            UploadOperationJournal.Entry entry = receipt(db, PAYLOAD, 3857, TYPES, true);
            FakeServer server = new FakeServer(); server.loseAllocation = true;
            NgwFeatureTransaction.Result first = execute(db, entry, server);
            assertTrue(first.retryable); assertFalse(first.uncertain); assertEquals(0, server.features);
            assertNull(server.transactions.get(1L).operation);
            assertTrue(execute(db, entry, server).accepted);
            assertEquals(2, server.allocations); assertEquals(1, server.features); assertEquals(1, server.commits);
            assertNull(server.transactions.get(1L).operation);
        }
    }

    @Test public void aLostStagingResponseRepeatsTheSameOperationInTheSameTransaction() throws Exception {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            UploadOperationJournal.Entry entry = receipt(db, PAYLOAD, 3857, TYPES, true);
            FakeServer server = new FakeServer(); server.loseStaging = true;
            assertTrue(execute(db, entry, server).retryable);
            assertEquals(0, server.features); assertEquals(1, server.allocations);
            String frozen = UploadTransactionJournal.find(db, entry.layer, entry.id).operationJson;
            assertTrue(execute(db, entry, server).accepted);
            assertEquals(2, server.stagedBodies.size());
            assertEquals(server.stagedBodies.get(0), server.stagedBodies.get(1));
            assertEquals(frozen, UploadTransactionJournal.find(db, entry.layer, entry.id).operationJson);
            assertEquals(1, server.allocations); assertEquals(1, server.features);
        }
    }

    @Test public void aLostCommitResponseIsRecoveredByResultsWithoutASecondCommit() throws Exception {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            UploadOperationJournal.Entry entry = receipt(db, PAYLOAD, 3857, TYPES, true);
            FakeServer server = new FakeServer(); server.loseCommit = true;
            assertTrue(execute(db, entry, server).accepted);
            assertTrue(execute(db, entry, server).accepted);
            assertEquals(1, server.features); assertEquals(1, server.commits); assertEquals(1, server.gets);
        }
    }

    @Test public void restartAfterCommitOnlyReadsThePersistedTransaction() throws Exception {
        File file = File.createTempFile("tx-restart-", ".db");
        FakeServer server = new FakeServer(); server.dieAfterCommit = true;
        String id;
        try {
            try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null)) {
                UploadOperationJournal.Entry entry = receipt(db, PAYLOAD, 3857, TYPES, true); id = entry.id;
                try { execute(db, entry, server); fail(); } catch (SimulatedDeath expected) { }
                assertTrue(UploadTransactionJournal.find(db, entry.layer, entry.id).commitStarted);
            }
            int before = server.calls.size();
            try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null)) {
                NgwFeatureTransaction.Result result = execute(db, UploadOperationJournal.find(db, "points", id), server);
                assertTrue(result.accepted); assertEquals(101, result.remoteId);
            }
            assertEquals(before + 1, server.calls.size());
            assertTrue(server.calls.get(before).startsWith("GET "));
            assertEquals(1, server.features); assertEquals(1, server.commits); assertEquals(1, server.allocations);
        } finally { assertTrue(file.delete()); }
    }

    @Test public void aPostgisCommitWithoutItsNgwReceiptCannotBeAutomaticallyReplayed() throws Exception {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            UploadOperationJournal.Entry entry = receipt(db, PAYLOAD, 3857, TYPES, true);
            FakeServer server = new FakeServer(); server.externalCommitWithoutReceipt = true;
            NgwFeatureTransaction.Result first = execute(db, entry, server);
            assertTrue(first.uncertain); assertFalse(first.retryable); assertEquals(1, server.features);
            String frozen = UploadTransactionJournal.find(db, entry.layer, entry.id).operationJson;
            server.externalCommitWithoutReceipt = false;
            int previous = server.calls.size();
            NgwFeatureTransaction.Result second = execute(db, entry, server);
            assertTrue(second.uncertain); assertFalse(second.retryable);
            assertEquals(previous + 1, server.calls.size()); assertTrue(server.calls.get(previous).startsWith("GET "));
            assertEquals(1, server.allocations); assertEquals(1, server.commits); assertEquals(1, server.features);
            assertEquals(frozen, UploadTransactionJournal.find(db, entry.layer, entry.id).operationJson);
            assertEquals(PAYLOAD, UploadOperationJournal.find(db, entry.layer, entry.id).payload);
        }
    }

    @Test public void knownCommitSurvivesReopenWithoutAResultId() throws Exception {
        File file = File.createTempFile("tx-proof-", ".db");
        FakeServer server = new FakeServer(); server.resultsUnavailable = true;
        String id;
        try {
            try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null)) {
                UploadOperationJournal.Entry entry = receipt(db, PAYLOAD, 3857, TYPES, true); id = entry.id;
                assertTrue(execute(db, entry, server).uncertain);
                UploadTransactionJournal.Entry pending = UploadTransactionJournal.find(db, entry.layer, entry.id);
                assertTrue(UploadTransactionJournal.hasCommittedProof(pending)); assertEquals(-1, pending.remoteId);
                assertEquals("{\"status\":\"committed\",\"committed\":\"2026-11-26T00:00:00\"}", pending.resultsJson);
            }
            try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null)) {
                UploadOperationJournal.Entry entry = UploadOperationJournal.find(db, "points", id);
                UploadTransactionJournal.Entry pending = UploadTransactionJournal.find(db, "points", id);
                assertTrue(UploadTransactionJournal.hasCommittedProof(pending));
                try {
                    LayerDatabaseTransaction.run(db, () -> {
                        UploadTransactionJournal.resetAfterExplicitReview(db, pending, entry); return null;
                    }); fail("A positive committed response cannot be reset even without the feature ID");
                } catch (SQLiteException expected) { }
                int before = server.calls.size();
                assertTrue(execute(db, entry, server).uncertain);
                assertEquals(before + 1, server.calls.size()); assertTrue(server.calls.get(before).startsWith("GET "));
                assertTrue(UploadTransactionJournal.hasCommittedProof(UploadTransactionJournal.find(db, "points", id)));
                server.resultsUnavailable = false;
                assertTrue(execute(db, entry, server).accepted);
                UploadTransactionJournal.Entry confirmed = UploadTransactionJournal.find(db, "points", id);
                assertTrue(UploadTransactionJournal.hasCommittedProof(confirmed)); assertEquals(101, confirmed.remoteId);
                assertTrue(confirmed.resultsJson.startsWith("[[0,"));
            }
            assertEquals(1, server.commits); assertEquals(1, server.allocations); assertEquals(1, server.features);
        } finally { assertTrue(file.delete()); }
    }

    @Test public void noCommitResponseAllowsOnlyAnExplicitReviewedNewAttempt() throws Exception {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            UploadOperationJournal.Entry entry = receipt(db, PAYLOAD, 3857, TYPES, true);
            FakeServer server = new FakeServer(); server.loseCommitBeforeApply = true;
            assertTrue(execute(db, entry, server).uncertain);
            UploadTransactionJournal.Entry pending = UploadTransactionJournal.find(db, entry.layer, entry.id);
            assertFalse(UploadTransactionJournal.hasCommittedProof(pending));
            assertTrue(execute(db, entry, server).uncertain);
            assertEquals(1, server.commits); assertEquals(0, server.features);
            LayerDatabaseTransaction.run(db, () -> {
                UploadTransactionJournal.resetAfterExplicitReview(db, pending, entry);
                UploadOperationJournal.state(db, entry, UploadOperationJournal.RETRY_ALLOWED, -1, "operator_confirmed_not_created");
                return null;
            });
            UploadOperationJournal.Entry retry = UploadOperationJournal.begin(db, entry.layer, "points_changes", 1, 10000000, -1,
                    "create", TARGET, () -> {
                        UploadTransactionJournal.reserve(db, entry.id, entry.layer, TARGET, UploadTransactionJournal.sha256(PAYLOAD), 3857, TYPES);
                        return PAYLOAD;
                    }, 2);
            assertTrue(execute(db, retry, server).accepted);
            assertEquals(2, server.allocations); assertEquals(2, server.commits); assertEquals(1, server.features);
        }
    }

    @Test public void invalidResultsStayUncertainUntilTheSameTransactionReturnsAValidId() throws Exception {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            UploadOperationJournal.Entry entry = receipt(db, PAYLOAD, 3857, TYPES, true);
            FakeServer server = new FakeServer(); server.invalidResult = true;
            assertTrue(execute(db, entry, server).uncertain);
            server.invalidResult = false;
            assertTrue(execute(db, entry, server).accepted);
            assertEquals(1, server.features); assertEquals(1, server.commits); assertEquals(2, server.gets);
        }
    }

    @Test public void targetDriftAndAChangedFrozenSourceCannotSendAnyHttp() throws Exception {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            UploadOperationJournal.Entry entry = receipt(db, PAYLOAD, 3857, TYPES, true);
            FakeServer server = new FakeServer();
            NgwFeatureTransaction.Result drift = NgwFeatureTransaction.execute(db, entry, "other-account-or-resource", 173, account(), server);
            assertEquals("transaction_target_changed", drift.reason); assertTrue(server.calls.isEmpty());
            ContentValues changed = new ContentValues(); changed.put("payload", PAYLOAD.replace("12.5", "99.5"));
            db.update("ngm_upload_receipts", changed, "operation_id=?", new String[]{entry.id});
            NgwFeatureTransaction.Result altered = execute(db, UploadOperationJournal.find(db, entry.layer, entry.id), server);
            assertEquals("transaction_source_changed", altered.reason); assertTrue(server.calls.isEmpty());
        }
    }

    @Test public void geometryIsTransformedToResourceSrsOnceBeforeItIsFrozen() throws Exception {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            UploadOperationJournal.Entry entry = receipt(db, PAYLOAD, 32636, TYPES, true);
            FakeServer server = new FakeServer(); server.loseStaging = true;
            assertTrue(execute(db, entry, server).retryable); assertEquals(1, server.transforms);
            String frozen = UploadTransactionJournal.find(db, entry.layer, entry.id).operationJson;
            server.transformedWkt = "POINT(999999 888888)";
            assertTrue(execute(db, entry, server).accepted); assertEquals(1, server.transforms);
            assertEquals(frozen, UploadTransactionJournal.find(db, entry.layer, entry.id).operationJson);
            Geometry geometry = new WKBReader().read(Base64.decode(new JSONObject(frozen).getString("geom"), Base64.NO_WRAP));
            assertEquals(400000, geometry.getCoordinate().x, 0); assertEquals(5000000, geometry.getCoordinate().y, 0);
        }
    }

    @Test public void datesAndBooleansUseTransactionValuesAndNumericZeroIsPreserved() throws Exception {
        String payload = "{\"fields\":{\"day\":{\"year\":2024,\"month\":2,\"day\":29},"
                + "\"time\":\"23:59:58.123\",\"when\":\"2026-11-26T01:02:03\",\"empty\":null,\"flag\":1,\"count\":0}}";
        String types = "{\"day\":\"DATE\",\"time\":\"TIME\",\"when\":\"DATETIME\",\"empty\":\"DATE\",\"flag\":\"BOOLEAN\",\"count\":\"INTEGER\"}";
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            UploadOperationJournal.Entry entry = receipt(db, payload, 3857, types, true);
            assertTrue(execute(db, entry, new FakeServer()).accepted);
            JSONObject fields = new JSONObject(UploadTransactionJournal.find(db, entry.layer, entry.id).operationJson).getJSONObject("fields");
            assertEquals("2024-02-29", fields.getString("day")); assertEquals("23:59:58.123", fields.getString("time"));
            assertEquals("2026-11-26T01:02:03", fields.getString("when")); assertTrue(fields.isNull("empty"));
            assertEquals(Boolean.TRUE, fields.get("flag")); assertEquals(0, fields.getInt("count"));
        }
    }

    @Test public void anInvalidTransformResponseMayRetryBeforeAnyFeatureTransactionExists() throws Exception {
        for (String invalid : new String[]{"invalid geometry", "LINESTRING(400000 5000000,400001 5000001)"}) try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            UploadOperationJournal.Entry entry = receipt(db, PAYLOAD, 32636, TYPES, true);
            FakeServer server = new FakeServer(); server.transformedWkt = invalid;
            NgwFeatureTransaction.Result first = execute(db, entry, server);
            assertTrue(first.retryable); assertFalse(first.uncertain);
            assertEquals("transaction_geometry_transform_failed", first.reason);
            assertEquals(0, server.allocations); assertEquals(0, server.features);
            assertNull(UploadTransactionJournal.find(db, entry.layer, entry.id).operationJson);
            server.transformedWkt = "POINT(400000 5000000)";
            assertTrue(execute(db, entry, server).accepted);
            assertEquals(2, server.transforms); assertEquals(1, server.allocations); assertEquals(1, server.features);
        }
    }

    @Test public void invalidDatesMissingMetadataAndUnsupportedGeometryFailBeforeAllocation() throws Exception {
        for (String payload : new String[]{"{\"fields\":{\"day\":\"2023-02-29\"}}", "{\"fields\":{\"missing\":1}}",
                "{\"geom\":\"POINT Z (1 2 3)\"}", "{\"geom\":\"POINT(1 2) trailing\"}",
                "{\"geom\":\"POINT(1 2) POINT(3 4)\"}", "{\"geom\":\"POINT EMPTY\"}"}) {
            try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
                UploadOperationJournal.Entry entry = receipt(db, payload, 3857, "{\"day\":\"DATE\"}", true);
                FakeServer server = new FakeServer();
                assertEquals("transaction_payload_invalid", execute(db, entry, server).reason);
                assertTrue(server.calls.isEmpty());
            }
        }
    }

    @Test public void preparationRejectionIsRecordedAndDoesNotStartAnotherCommit() throws Exception {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            UploadOperationJournal.Entry entry = receipt(db, PAYLOAD, 3857, TYPES, true);
            FakeServer server = new FakeServer(); server.rejectPreparation = true;
            NgwFeatureTransaction.Result first = execute(db, entry, server);
            assertTrue(first.rejected); assertFalse(first.uncertain); assertEquals(0, server.features);
            assertTrue(execute(db, entry, server).rejected);
            assertEquals(1, server.commits); assertEquals(0, server.gets);
            UploadTransactionJournal.Entry tx = UploadTransactionJournal.find(db, entry.layer, entry.id);
            LayerDatabaseTransaction.run(db, () -> {
                UploadTransactionJournal.resetAfterExplicitReview(db, tx, entry);
                UploadOperationJournal.state(db, entry, UploadOperationJournal.RETRY_ALLOWED, -1, "operator_confirmed_not_created");
                return null;
            });
            UploadOperationJournal.Entry retry = UploadOperationJournal.begin(db, entry.layer, "points_changes", 1, 10000000, -1,
                    "create", TARGET, () -> {
                        UploadTransactionJournal.reserve(db, entry.id, entry.layer, TARGET, UploadTransactionJournal.sha256(PAYLOAD), 3857, TYPES);
                        return PAYLOAD;
                    }, 2);
            server.rejectPreparation = false;
            assertTrue(execute(db, retry, server).accepted);
            assertEquals(2, server.allocations); assertEquals(2, server.commits); assertEquals(1, server.features);
        }
    }

    @Test public void transactionHttpCannotRunInsideTheLocalDatabaseTransaction() throws Exception {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            UploadOperationJournal.Entry entry = receipt(db, PAYLOAD, 3857, TYPES, true);
            FakeServer server = new FakeServer();
            try {
                LayerDatabaseTransaction.run(db, () -> execute(db, entry, server)); fail();
            } catch (SQLiteException expected) { }
            assertTrue(server.calls.isEmpty());
        }
    }
}

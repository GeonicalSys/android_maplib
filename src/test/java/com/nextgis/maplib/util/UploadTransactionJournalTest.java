package com.nextgis.maplib.util;

import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class UploadTransactionJournalTest {
    private static final String PAYLOAD = "{\"fields\":{\"label\":\"Старая точка\"},\"geom\":\"POINT(1 2)\"}";

    private static UploadTransactionJournal.Entry reserve(SQLiteDatabase db, String id) {
        return LayerDatabaseTransaction.run(db, () -> UploadTransactionJournal.reserve(db, id,
                "points", "target", UploadTransactionJournal.sha256(PAYLOAD), 3857, "{\"label\":\"STRING\"}"));
    }

    private static UploadOperationJournal.Entry receipt(SQLiteDatabase db) throws Exception {
        FeatureChanges.initialize(db, "points_changes");
        android.content.ContentValues row = new android.content.ContentValues();
        row.put(Constants.FIELD_ID, 1); row.put(Constants.FIELD_FEATURE_ID, 10000000);
        row.put(Constants.FIELD_OPERATION, Constants.CHANGE_OPERATION_NEW);
        row.put(Constants.FIELD_ATTACH_ID, -1); row.put(Constants.FIELD_ATTACH_OPERATION, 0);
        FeatureChanges.insert(db, "points_changes", row);
        return UploadOperationJournal.begin(db, "points", "points_changes", 1, 10000000, -1, "create", "target", () -> {
            UploadTransactionJournal.reserve(db, FeatureChanges.operationId(db, "points_changes", 1), "points", "target",
                    UploadTransactionJournal.sha256(PAYLOAD), 3857, "{\"label\":\"STRING\"}");
            return PAYLOAD;
        }, 7);
    }

    private static Map<String, String> transactionRow(SQLiteDatabase db, String table, String id) {
        Map<String, String> values = new LinkedHashMap<>();
        try (android.database.Cursor c = db.rawQuery("SELECT * FROM " + table + " WHERE operation_id=?", new String[]{id})) {
            assertTrue(c.moveToFirst());
            for (int i = 0; i < c.getColumnCount(); i++) values.put(c.getColumnName(i), c.isNull(i) ? null : c.getString(i));
        }
        return values;
    }

    private static long historyCount(SQLiteDatabase db) {
        try (android.database.Cursor c = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='ngm_upload_transaction_history'", null)) {
            if (!c.moveToFirst()) return 0;
        }
        return android.database.DatabaseUtils.longForQuery(db, "SELECT count(*) FROM ngm_upload_transaction_history", null);
    }

    @Test public void optInAndSourceSnapshotRollBackTogether() {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            String id = UUID.randomUUID().toString();
            try {
                LayerDatabaseTransaction.run(db, () -> {
                    UploadTransactionJournal.reserve(db, id, "points", "target", UploadTransactionJournal.sha256(PAYLOAD), 3857, "{}");
                    throw new IllegalStateException("synthetic snapshot failure");
                });
                fail();
            } catch (IllegalStateException expected) { }
            assertNull(UploadTransactionJournal.find(db, "points", id));
            UploadTransactionJournal.Entry reserved = reserve(db, id);
            assertEquals(UploadTransactionJournal.PREPARING, reserved.phase);
            assertEquals(UploadTransactionJournal.sha256(PAYLOAD), reserved.sourceSha);
            assertNull(reserved.operationJson);
            assertFalse(reserved.commitStarted);
        }
    }

    @Test public void reservationCannotBeMadeOutsideTheReceiptTransaction() {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            try {
                UploadTransactionJournal.reserve(db, UUID.randomUUID().toString(), "points", "target",
                        UploadTransactionJournal.sha256(PAYLOAD), 3857, "{}");
                fail();
            } catch (SQLiteException expected) { }
        }
    }

    @Test public void sourceTargetSrsAndFieldTypesAreImmutableForAnOperation() {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            String id = UUID.randomUUID().toString();
            UploadTransactionJournal.Entry initial = reserve(db, id);
            assertEquals(initial.id, reserve(db, id).id);
            for (int change = 0; change < 4; change++) {
                final int changed = change;
                try {
                    LayerDatabaseTransaction.run(db, () -> UploadTransactionJournal.reserve(db, id, "points",
                            changed == 0 ? "other-target" : "target",
                            UploadTransactionJournal.sha256(changed == 1 ? "changed" : PAYLOAD),
                            changed == 2 ? 32636 : 3857, changed == 3 ? "{\"label\":\"INTEGER\"}" : "{\"label\":\"STRING\"}"));
                    fail("Changed immutable transaction source: " + change);
                } catch (SQLiteException expected) { }
            }
            UploadTransactionJournal.Entry retained = UploadTransactionJournal.find(db, "points", id);
            assertEquals(initial.sourceSha, retained.sourceSha);
            assertEquals("target", retained.target);
            assertEquals(3857, retained.resourceSrs);
            assertEquals("{\"label\":\"STRING\"}", retained.fieldTypesJson);
        }
    }

    @Test public void fieldMetadataOrderIsCanonicalButUnsupportedTypesAreRefused() {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            String id = UUID.randomUUID().toString(), hash = UploadTransactionJournal.sha256(PAYLOAD);
            LayerDatabaseTransaction.run(db, () -> UploadTransactionJournal.reserve(db, id, "p", "t", hash,
                    3857, "{\"z\":\"DATE\",\"a\":\"STRING\"}"));
            UploadTransactionJournal.Entry same = LayerDatabaseTransaction.run(db, () -> UploadTransactionJournal.reserve(db, id, "p", "t", hash,
                    3857, "{\"a\":\"STRING\",\"z\":\"DATE\"}"));
            assertEquals("{\"a\":\"STRING\",\"z\":\"DATE\"}", same.fieldTypesJson);
            try {
                LayerDatabaseTransaction.run(db, () -> UploadTransactionJournal.reserve(db, UUID.randomUUID().toString(), "p", "t", hash,
                        3857, "{\"a\":\"UNKNOWN\"}"));
                fail();
            } catch (SQLiteException expected) { }
        }
    }

    @Test public void committedAttemptAndItsFrozenOperationSurviveDatabaseReopen() throws Exception {
        File file = File.createTempFile("tx-", ".db");
        String id = UUID.randomUUID().toString();
        String operation = "{\"action\":\"feature.create\",\"fields\":{\"label\":\"Старая точка\"}}";
        try {
            try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null)) {
                UploadTransactionJournal.Entry entry = reserve(db, id);
                entry = UploadTransactionJournal.prepared(db, entry, operation);
                entry = UploadTransactionJournal.allocated(db, entry, 13);
                entry = UploadTransactionJournal.staged(db, entry);
                UploadTransactionJournal.commitStarted(db, entry);
            }
            try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null)) {
                UploadTransactionJournal.Entry entry = UploadTransactionJournal.find(db, "points", id);
                assertTrue(entry.commitStarted);
                assertEquals(13, entry.transactionId);
                assertEquals(operation, entry.operationJson);
                try { UploadTransactionJournal.prepared(db, entry, "{}"); fail(); }
                catch (SQLiteException expected) { }
                entry = UploadTransactionJournal.committed(db, entry, 42, "[[0,{\"action\":\"feature.create\",\"fid\":42}]]");
                assertEquals(42, entry.remoteId);
            }
            try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null)) {
                UploadTransactionJournal.Entry entry = UploadTransactionJournal.find(db, "points", id);
                assertEquals(UploadTransactionJournal.COMMITTED, entry.phase);
                assertEquals(42, entry.remoteId);
                assertEquals(operation, entry.operationJson);
                assertNotNull(entry.resultsJson);
                assertNull(UploadTransactionJournal.find(db, "other-layer", id));
            }
        } finally { assertTrue(file.delete()); }
    }

    @Test public void hashIsUtf8Sha256AndDoesNotContainTheSourcePayload() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", UploadTransactionJournal.sha256("abc"));
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            UploadTransactionJournal.Entry entry = reserve(db, UUID.randomUUID().toString());
            assertEquals(64, entry.sourceSha.length());
            assertFalse(entry.sourceSha.contains("Старая точка"));
        }
    }

    @Test public void explicitReviewArchivesEveryOldTransactionColumnAndAllowsANewSnapshot() throws Exception {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            UploadOperationJournal.Entry original = receipt(db);
            UploadTransactionJournal.Entry tx = UploadTransactionJournal.find(db, original.layer, original.id);
            tx = UploadTransactionJournal.prepared(db, tx, "{\"action\":\"feature.create\",\"fields\":{\"label\":\"Старая точка\"}}");
            tx = UploadTransactionJournal.allocated(db, tx, 12);
            tx = UploadTransactionJournal.staged(db, tx);
            tx = UploadTransactionJournal.commitStarted(db, tx);
            tx = UploadTransactionJournal.failure(db, tx, "transaction_commit_outcome_unknown", 503, false);
            UploadOperationJournal.state(db, original, UploadOperationJournal.UNCERTAIN, -1, "http_503");
            UploadOperationJournal.Entry reviewed = UploadOperationJournal.find(db, original.layer, original.id);
            UploadTransactionJournal.Entry reviewedTx = tx;
            Map<String, String> old = transactionRow(db, "ngm_upload_transactions", original.id);
            LayerDatabaseTransaction.run(db, () -> {
                UploadTransactionJournal.resetAfterExplicitReview(db, reviewedTx, reviewed);
                UploadOperationJournal.state(db, reviewed, UploadOperationJournal.RETRY_ALLOWED, -1, "operator_confirmed_not_created");
                return null;
            });
            assertNull(UploadTransactionJournal.find(db, original.layer, original.id));
            Map<String, String> archive = transactionRow(db, "ngm_upload_transaction_history", original.id);
            for (Map.Entry<String, String> column : old.entrySet()) assertEquals(column.getKey(), column.getValue(), archive.get(column.getKey()));
            assertEquals(PAYLOAD, archive.get("receipt_payload")); assertEquals("uncertain", archive.get("receipt_state"));
            assertEquals("http_503", archive.get("receipt_reason")); assertEquals("7", archive.get("receipt_boundary"));
            assertEquals("[]", archive.get("receipt_ack_tokens")); assertEquals("10000000", archive.get("receipt_feature_id"));
            assertEquals(1, android.database.DatabaseUtils.longForQuery(db, "SELECT count(*) FROM points_changes", null));
            String corrected = PAYLOAD.replace("Старая точка", "Исправленная точка");
            UploadOperationJournal.Entry retry = UploadOperationJournal.begin(db, "points", "points_changes", 1, 10000000, -1, "create", "target", () -> {
                UploadTransactionJournal.reserve(db, reviewed.id, "points", "target", UploadTransactionJournal.sha256(corrected),
                        32636, "{\"label\":\"STRING\",\"date\":\"DATE\"}");
                return corrected;
            }, 9);
            assertEquals(reviewed.id, retry.id); assertTrue(retry.wasStarted());
            UploadTransactionJournal.Entry fresh = UploadTransactionJournal.find(db, retry.layer, retry.id);
            assertEquals(32636, fresh.resourceSrs); assertEquals(UploadTransactionJournal.sha256(corrected), fresh.sourceSha);
            assertEquals(UploadTransactionJournal.PREPARING, fresh.phase); assertEquals(-1, fresh.transactionId);
            assertEquals(old.get("operation_json"), transactionRow(db, "ngm_upload_transaction_history", original.id).get("operation_json"));
            assertEquals(1, historyCount(db));
        }
    }

    @Test public void resetOutsideATransactionOrDuringAnUploadCannotEraseTheAttempt() throws Exception {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            UploadOperationJournal.Entry receipt = receipt(db);
            UploadTransactionJournal.Entry tx = UploadTransactionJournal.find(db, receipt.layer, receipt.id);
            try { UploadTransactionJournal.resetAfterExplicitReview(db, tx, receipt); fail(); }
            catch (SQLiteException expected) { }
            try (UploadOperationJournal.Flight ignored = UploadOperationJournal.sending(db, receipt)) {
                try {
                    LayerDatabaseTransaction.run(db, () -> { UploadTransactionJournal.resetAfterExplicitReview(db, tx, receipt); return null; }); fail();
                } catch (SQLiteException expected) { }
            }
            assertNotNull(UploadTransactionJournal.find(db, receipt.layer, receipt.id)); assertEquals(0, historyCount(db));
        }
    }

    @Test public void resetArchiveAndRemovalRollBackTogetherAndKnownCreationIsNeverReset() throws Exception {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            UploadOperationJournal.Entry receipt = receipt(db);
            UploadTransactionJournal.Entry tx = UploadTransactionJournal.find(db, receipt.layer, receipt.id);
            try {
                LayerDatabaseTransaction.run(db, () -> {
                    UploadTransactionJournal.resetAfterExplicitReview(db, tx, receipt);
                    throw new IllegalStateException("synthetic failure before RETRY_ALLOWED");
                }); fail();
            } catch (IllegalStateException expected) { }
            assertNotNull(UploadTransactionJournal.find(db, receipt.layer, receipt.id)); assertEquals(0, historyCount(db));
            UploadTransactionJournal.Entry known = UploadTransactionJournal.prepared(db, tx, "{\"action\":\"feature.create\"}");
            known = UploadTransactionJournal.allocated(db, known, 15); known = UploadTransactionJournal.staged(db, known);
            known = UploadTransactionJournal.commitStarted(db, known); known = UploadTransactionJournal.committed(db, known, 42, "[[0,{\"action\":\"feature.create\",\"fid\":42}]]");
            UploadTransactionJournal.Entry committed = known;
            try {
                LayerDatabaseTransaction.run(db, () -> { UploadTransactionJournal.resetAfterExplicitReview(db, committed, receipt); return null; }); fail();
            } catch (SQLiteException expected) { }
            assertEquals(42, UploadTransactionJournal.find(db, receipt.layer, receipt.id).remoteId); assertEquals(0, historyCount(db));
        }
    }

    @Test public void commitProofRequiresAValidServerTimestampAndSurvivesLaterFailures() throws Exception {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            UploadOperationJournal.Entry receipt = receipt(db);
            UploadTransactionJournal.Entry pending = UploadTransactionJournal.find(db, receipt.layer, receipt.id);
            pending = UploadTransactionJournal.prepared(db, pending, "{\"action\":\"feature.create\"}");
            pending = UploadTransactionJournal.allocated(db, pending, 15); pending = UploadTransactionJournal.staged(db, pending);
            pending = UploadTransactionJournal.commitStarted(db, pending);
            for (String invalid : new String[]{"{}", "{\"status\":\"committed\"}",
                    "{\"status\":\"committed\",\"committed\":123}", "{\"status\":\"errors\",\"committed\":\"2026-11-26T00:00:00\"}",
                    "{\"status\":\"committed\",\"committed\":\"2026-02-29T00:00:00\"}",
                    "{\"status\":\"committed\",\"committed\":\"2026-11-26T24:00:00\"}"}) {
                try { UploadTransactionJournal.observeCommitted(db, pending, invalid); fail(invalid); }
                catch (SQLiteException expected) { }
                assertFalse(UploadTransactionJournal.hasCommittedProof(UploadTransactionJournal.find(db, receipt.layer, receipt.id)));
            }
            String exactProof = " {\"status\":\"committed\",\"committed\":\"2026-11-26T01:02:03.123456\"} ";
            pending = UploadTransactionJournal.observeCommitted(db, pending, exactProof);
            assertTrue(UploadTransactionJournal.hasCommittedProof(pending)); assertEquals(exactProof, pending.resultsJson);
            pending = UploadTransactionJournal.failure(db, pending, "transaction_result_response_missing", 503, false);
            assertTrue(UploadTransactionJournal.hasCommittedProof(pending)); assertEquals(exactProof, pending.resultsJson);
            UploadTransactionJournal.Entry known = pending;
            try {
                LayerDatabaseTransaction.run(db, () -> { UploadTransactionJournal.resetAfterExplicitReview(db, known, receipt); return null; }); fail();
            } catch (SQLiteException expected) { }
            assertNotNull(UploadTransactionJournal.find(db, receipt.layer, receipt.id)); assertEquals(0, historyCount(db));
        }
    }
}

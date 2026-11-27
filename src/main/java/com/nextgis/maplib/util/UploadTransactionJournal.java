package com.nextgis.maplib.util;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.Locale;
import java.util.TreeSet;

/** Durable NGW transaction state. A row is proof of opting in before any feature HTTP call. */
public final class UploadTransactionJournal {
    private static final String TABLE = "ngm_upload_transactions";
    private static final String HISTORY = "ngm_upload_transaction_history";
    private static final String ALL_COLUMNS = "operation_id,layer,target,source_sha256,resource_srs,field_types,phase,"
            + "operation_json,transaction_id,commit_started,remote_id,results_json,last_http,reason,updated";
    private static final String[] COLUMNS = {"operation_id", "layer", "target", "source_sha256",
            "resource_srs", "field_types", "phase", "transaction_id", "commit_started",
            "remote_id", "last_http", "reason"};

    public static final String PREPARING = "preparing", PREPARED = "prepared",
            ALLOCATED = "allocated", STAGED = "staged", COMMIT_STARTED = "commit_started",
            COMMITTED = "committed", REJECTED = "rejected";

    private UploadTransactionJournal() { }

    public static final class Entry {
        public final String id, layer, target, sourceSha, fieldTypesJson, phase, operationJson, resultsJson, reason;
        public final int resourceSrs, lastHttp;
        public final long transactionId, remoteId;
        public final boolean commitStarted;

        private Entry(SQLiteDatabase db, Cursor c) {
            id = c.getString(c.getColumnIndexOrThrow("operation_id"));
            layer = c.getString(c.getColumnIndexOrThrow("layer"));
            target = c.getString(c.getColumnIndexOrThrow("target"));
            sourceSha = c.getString(c.getColumnIndexOrThrow("source_sha256"));
            resourceSrs = c.getInt(c.getColumnIndexOrThrow("resource_srs"));
            fieldTypesJson = c.getString(c.getColumnIndexOrThrow("field_types"));
            phase = c.getString(c.getColumnIndexOrThrow("phase"));
            transactionId = c.getLong(c.getColumnIndexOrThrow("transaction_id"));
            commitStarted = c.getInt(c.getColumnIndexOrThrow("commit_started")) != 0;
            remoteId = c.getLong(c.getColumnIndexOrThrow("remote_id"));
            lastHttp = c.getInt(c.getColumnIndexOrThrow("last_http"));
            reason = c.getString(c.getColumnIndexOrThrow("reason"));
            operationJson = text(db, id, "operation_json");
            resultsJson = text(db, id, "results_json");
        }
    }

    private static boolean exists(SQLiteDatabase db) {
        try (Cursor c = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                new String[]{TABLE})) { return c.moveToFirst(); }
    }

    private static void initialize(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE + " (operation_id TEXT PRIMARY KEY,"
                + "layer TEXT NOT NULL,target TEXT NOT NULL,source_sha256 TEXT NOT NULL,"
                + "resource_srs INTEGER NOT NULL,field_types TEXT NOT NULL,phase TEXT NOT NULL,"
                + "operation_json TEXT,transaction_id INTEGER NOT NULL DEFAULT -1,"
                + "commit_started INTEGER NOT NULL DEFAULT 0,remote_id INTEGER NOT NULL DEFAULT -1,"
                + "results_json TEXT,last_http INTEGER NOT NULL DEFAULT 0,reason TEXT NOT NULL DEFAULT '',"
                + "updated INTEGER NOT NULL)");
    }

    /** Call inside the upload receipt's snapshot callback, in its existing SQLite transaction. */
    public static Entry reserve(SQLiteDatabase db, String id, String layer, String target,
            String sourceSha, int resourceSrs, String fieldTypesJson) {
        if (!db.inTransaction()) throw new SQLiteException("Transaction opt-in requires an atomic upload snapshot");
        if (id == null || !id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                || layer == null || layer.isEmpty() || target == null || target.isEmpty()
                || sourceSha == null || !sourceSha.matches("[0-9a-f]{64}") || resourceSrs <= 0)
            throw new SQLiteException("Invalid transaction identity");
        String types = canonicalTypes(fieldTypesJson);
        initialize(db);
        Entry prior = find(db, layer, id);
        if (prior != null) {
            if (!target.equals(prior.target) || !sourceSha.equals(prior.sourceSha)
                    || resourceSrs != prior.resourceSrs || !types.equals(prior.fieldTypesJson))
                throw new SQLiteException("Frozen transaction source changed");
            return prior;
        }
        ContentValues v = new ContentValues();
        v.put("operation_id", id); v.put("layer", layer); v.put("target", target);
        v.put("source_sha256", sourceSha); v.put("resource_srs", resourceSrs);
        v.put("field_types", types); v.put("phase", PREPARING);
        v.put("updated", System.currentTimeMillis());
        db.insertOrThrow(TABLE, null, v);
        return require(db, layer, id);
    }

    public static Entry find(SQLiteDatabase db, String layer, String id) {
        if (id == null || !exists(db)) return null;
        try (Cursor c = db.query(TABLE, COLUMNS, "layer=? AND operation_id=?",
                new String[]{layer, id}, null, null, null)) {
            return c.moveToFirst() ? new Entry(db, c) : null;
        }
    }

    /**
     * Call only after an operator has explicitly reviewed this creation on the original server.
     * The caller must set RETRY_ALLOWED in this same transaction. No feature or queue row is removed.
     */
    public static void resetAfterExplicitReview(SQLiteDatabase db, Entry expected, UploadOperationJournal.Entry receipt) {
        if (!db.inTransaction()) throw new SQLiteException("Explicit transaction review requires an atomic boundary");
        if (expected == null || receipt == null || !expected.id.equals(receipt.id)
                || !expected.layer.equals(receipt.layer) || !expected.target.equals(receipt.target)
                || receipt.payload == null || !expected.sourceSha.equals(sha256(receipt.payload)))
            throw new SQLiteException("Reviewed transaction source changed");
        if (NgwFeatureTransaction.isInFlight(db, expected.layer, expected.id)
                || UploadOperationJournal.isInFlight(db, expected.layer, expected.id))
            throw new SQLiteException("Upload transaction is still in flight");
        Entry current = require(db, expected.layer, expected.id);
        UploadOperationJournal.Entry actual = UploadOperationJournal.find(db, receipt.layer, receipt.id);
        if (!current.target.equals(expected.target) || !current.sourceSha.equals(expected.sourceSha)
                || !current.phase.equals(expected.phase) || current.transactionId != expected.transactionId
                || current.commitStarted != expected.commitStarted || current.resourceSrs != expected.resourceSrs
                || !current.fieldTypesJson.equals(expected.fieldTypesJson)
                || !java.util.Objects.equals(current.operationJson, expected.operationJson)
                || actual == null || !actual.target.equals(receipt.target) || !actual.payload.equals(receipt.payload)
                || !actual.state.equals(receipt.state) || !actual.reason.equals(receipt.reason)
                || actual.boundary != receipt.boundary || actual.changeId != receipt.changeId
                || actual.featureId != receipt.featureId || actual.attachId != receipt.attachId
                || !actual.operation.equals(receipt.operation) || actual.remoteId != receipt.remoteId
                || !actual.acknowledgedChanges.equals(receipt.acknowledgedChanges))
            throw new SQLiteException("Reviewed transaction or receipt changed");
        if (hasCommittedProof(current) || current.remoteId >= 0 || actual.remoteId >= 0
                || UploadOperationJournal.ACCEPTED.equals(actual.state) || UploadOperationJournal.CONFIRMED.equals(actual.state))
            throw new SQLiteException("A known server creation cannot be reset");
        // Do not silently drop a future column while archiving an old attempt.
        TreeSet<String> columns = new TreeSet<>();
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + TABLE + ")", null)) {
            while (c.moveToNext()) columns.add(c.getString(1));
        }
        if (!columns.equals(new TreeSet<>(java.util.Arrays.asList(ALL_COLUMNS.split(",")))))
            throw new SQLiteException("Unsupported transaction archive schema");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + HISTORY + " (attempt_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "operation_id TEXT NOT NULL,layer TEXT NOT NULL,target TEXT NOT NULL,source_sha256 TEXT NOT NULL,"
                + "resource_srs INTEGER NOT NULL,field_types TEXT NOT NULL,phase TEXT NOT NULL,operation_json TEXT,"
                + "transaction_id INTEGER NOT NULL,commit_started INTEGER NOT NULL,remote_id INTEGER NOT NULL,"
                + "results_json TEXT,last_http INTEGER NOT NULL,reason TEXT NOT NULL,updated INTEGER NOT NULL,"
                + "receipt_payload TEXT NOT NULL,receipt_state TEXT NOT NULL,receipt_reason TEXT NOT NULL,"
                + "receipt_boundary INTEGER NOT NULL,receipt_ack_tokens TEXT NOT NULL,receipt_change_id INTEGER NOT NULL,"
                + "receipt_feature_id INTEGER NOT NULL,receipt_attach_id INTEGER NOT NULL,receipt_operation TEXT NOT NULL,"
                + "receipt_remote_id INTEGER NOT NULL,receipt_updated INTEGER NOT NULL,receipt_target TEXT NOT NULL,"
                + "archived_at INTEGER NOT NULL)");
        StringBuilder txColumns = new StringBuilder();
        for (String column : ALL_COLUMNS.split(",")) {
            if (txColumns.length() > 0) txColumns.append(',');
            txColumns.append("t.").append(column);
        }
        db.execSQL("INSERT INTO " + HISTORY + " (" + ALL_COLUMNS + ",receipt_payload,receipt_state,receipt_reason,"
                        + "receipt_boundary,receipt_ack_tokens,receipt_change_id,receipt_feature_id,receipt_attach_id,"
                        + "receipt_operation,receipt_remote_id,receipt_updated,receipt_target,archived_at) SELECT " + txColumns
                        + ",r.payload,r.state,r.reason,r.boundary,r.ack_tokens,r.change_id,r.feature_id,r.attach_id,"
                        + "r.operation,r.remote_id,r.updated,r.target,? FROM " + TABLE + " t JOIN ngm_upload_receipts r"
                        + " ON r.operation_id=t.operation_id AND r.layer=t.layer WHERE t.operation_id=? AND t.layer=?",
                new Object[]{System.currentTimeMillis(), current.id, current.layer});
        if (android.database.DatabaseUtils.longForQuery(db, "SELECT changes()", null) != 1)
            throw new SQLiteException("Transaction attempt was not archived");
        if (db.delete(TABLE, "operation_id=? AND layer=? AND target=?", new String[]{current.id, current.layer, current.target}) != 1)
            throw new SQLiteException("Reviewed transaction changed");
    }

    private static Entry require(SQLiteDatabase db, String layer, String id) {
        Entry entry = find(db, layer, id);
        if (entry == null) throw new SQLiteException("Missing upload transaction");
        return entry;
    }

    public static String sha256(String value) {
        if (value == null) throw new IllegalArgumentException("Missing source payload");
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte b : hash) result.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String canonicalTypes(String json) {
        try {
            JSONObject object = new JSONObject(json);
            TreeSet<String> keys = new TreeSet<>();
            for (Iterator<String> i = object.keys(); i.hasNext();) keys.add(i.next());
            StringBuilder result = new StringBuilder("{");
            for (String key : keys) {
                Object type = object.get(key);
                if (!(type instanceof String) || !((String) type).matches("INTEGER|BIGINT|REAL|STRING|BOOLEAN|DATE|TIME|DATETIME|JSON"))
                    throw new JSONException("Invalid field type");
                if (result.length() > 1) result.append(',');
                result.append(JSONObject.quote(key)).append(':').append(JSONObject.quote((String) type));
            }
            return result.append('}').toString();
        } catch (JSONException | NullPointerException error) {
            throw new SQLiteException("Invalid frozen field metadata", error);
        }
    }

    /** Chunked reads avoid a CursorWindow limit on a large frozen geometry. */
    private static String text(SQLiteDatabase db, String id, String column) {
        int count;
        try (Cursor c = db.rawQuery("SELECT length(" + column + ") FROM " + TABLE + " WHERE operation_id=?", new String[]{id})) {
            if (!c.moveToFirst()) throw new SQLiteException("Missing upload transaction");
            if (c.isNull(0)) return null;
            long size = c.getLong(0);
            Runtime runtime = Runtime.getRuntime();
            long budget = Math.min(16L * 1024 * 1024,
                    (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory() - 8L * 1024 * 1024) / 4);
            if (size < 0 || size > budget) throw new SQLiteException("Transaction snapshot exceeds available memory");
            count = (int) size;
        }
        StringBuilder result = new StringBuilder(count);
        for (int offset = 0; offset < count;) {
            try (Cursor c = db.rawQuery("SELECT substr(" + column + ",?,?) FROM " + TABLE + " WHERE operation_id=?",
                    new String[]{Integer.toString(offset + 1), Integer.toString(Math.min(8192, count - offset)), id})) {
                if (!c.moveToFirst()) throw new SQLiteException("Missing transaction snapshot chunk");
                String part = c.getString(0);
                int characters = part.codePointCount(0, part.length());
                if (characters == 0) throw new SQLiteException("Incomplete transaction snapshot");
                result.append(part); offset += characters;
            }
        }
        return result.toString();
    }

    private interface Mutation { void apply(Entry current, ContentValues values); }

    private static Entry mutate(SQLiteDatabase db, Entry expected, Mutation mutation) {
        if (db.inTransaction()) throw new SQLiteException("Transaction HTTP state needs its own durable boundary");
        return LayerDatabaseTransaction.run(db, () -> {
            Entry current = require(db, expected.layer, expected.id);
            if (!current.target.equals(expected.target) || !current.sourceSha.equals(expected.sourceSha))
                throw new SQLiteException("Upload transaction identity changed");
            ContentValues values = new ContentValues();
            mutation.apply(current, values);
            values.put("updated", System.currentTimeMillis());
            if (db.update(TABLE, values, "operation_id=? AND layer=? AND target=?",
                    new String[]{current.id, current.layer, current.target}) != 1)
                throw new SQLiteException("Upload transaction changed");
            return require(db, current.layer, current.id);
        });
    }

    static Entry prepared(SQLiteDatabase db, Entry entry, String operation) {
        return mutate(db, entry, (current, values) -> {
            if (!PREPARING.equals(current.phase) || current.operationJson != null || current.transactionId >= 0 || current.commitStarted)
                throw new SQLiteException("Transaction payload is already frozen");
            values.put("operation_json", operation); values.put("phase", PREPARED);
        });
    }

    static Entry allocated(SQLiteDatabase db, Entry entry, long transactionId) {
        return mutate(db, entry, (current, values) -> {
            if (!PREPARED.equals(current.phase) || current.transactionId >= 0 || current.commitStarted || transactionId < 0)
                throw new SQLiteException("Invalid transaction allocation");
            values.put("transaction_id", transactionId); values.put("phase", ALLOCATED);
        });
    }

    static Entry staged(SQLiteDatabase db, Entry entry) {
        return mutate(db, entry, (current, values) -> {
            if (!ALLOCATED.equals(current.phase) || current.transactionId < 0 || current.commitStarted)
                throw new SQLiteException("Invalid transaction staging");
            values.put("phase", STAGED);
        });
    }

    static Entry commitStarted(SQLiteDatabase db, Entry entry) {
        return mutate(db, entry, (current, values) -> {
            if (!STAGED.equals(current.phase) || current.transactionId < 0 || current.commitStarted)
                throw new SQLiteException("Invalid transaction commit boundary");
            values.put("commit_started", 1); values.put("phase", COMMIT_STARTED);
        });
    }

    /** A positive commit response is durable evidence even while its feature ID is unavailable. */
    public static Entry observeCommitted(SQLiteDatabase db, Entry entry, String body) {
        if (!validCommittedBody(body)) throw new SQLiteException("Invalid server commit proof");
        return mutate(db, entry, (current, values) -> {
            if (!current.commitStarted || !COMMIT_STARTED.equals(current.phase) || current.transactionId < 0)
                throw new SQLiteException("Invalid commit proof boundary");
            values.put("results_json", body); values.put("last_http", 200);
            values.put("reason", "server_transaction_committed_results_pending");
        });
    }

    public static boolean hasCommittedProof(Entry entry) {
        return entry != null && (COMMITTED.equals(entry.phase)
                || validCommittedBody(entry.resultsJson));
    }

    static boolean validCommittedBody(String body) {
        if (body == null) return false;
        try {
            JSONObject object = new JSONObject(body);
            if (!"committed".equals(object.opt("status")) || !(object.opt("committed") instanceof String)) return false;
            String timestamp = (String) object.get("committed");
            // NGW's utcnow_naive timestamp is ISO8601 without a timezone, with optional microseconds.
            if (!timestamp.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?")) return false;
            int year = Integer.parseInt(timestamp.substring(0, 4));
            if (year < 1) return false;
            java.util.GregorianCalendar calendar = new java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC"), Locale.ROOT);
            calendar.setGregorianChange(new java.util.Date(Long.MIN_VALUE));
            calendar.setLenient(false); calendar.clear();
            calendar.set(year, Integer.parseInt(timestamp.substring(5, 7)) - 1,
                    Integer.parseInt(timestamp.substring(8, 10)), Integer.parseInt(timestamp.substring(11, 13)),
                    Integer.parseInt(timestamp.substring(14, 16)), Integer.parseInt(timestamp.substring(17, 19)));
            calendar.getTimeInMillis();
            return true;
        } catch (JSONException | IllegalArgumentException error) { return false; }
    }

    static Entry committed(SQLiteDatabase db, Entry entry, long remoteId, String results) {
        return mutate(db, entry, (current, values) -> {
            if (!current.commitStarted || !COMMIT_STARTED.equals(current.phase) || remoteId < 0)
                throw new SQLiteException("Invalid transaction result");
            values.put("remote_id", remoteId); values.put("results_json", results);
            values.put("phase", COMMITTED); values.put("reason", "server_transaction_confirmed");
            values.put("last_http", 200);
        });
    }

    static Entry failure(SQLiteDatabase db, Entry entry, String reason, int http, boolean rejected) {
        return mutate(db, entry, (current, values) -> {
            if (COMMITTED.equals(current.phase)) throw new SQLiteException("A committed transaction cannot fail");
            values.put("reason", reason); values.put("last_http", http);
            if (rejected) values.put("phase", REJECTED);
        });
    }
}

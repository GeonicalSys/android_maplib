package com.nextgis.maplib.util;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

/** Local-only idempotency journal. It is never added to a layer's schema or sent to NGW. */
public final class FeatureSaveJournal {
    public static final String URI_PARAMETER = "save_operation";
    private static final String TABLE = "ngm_feature_save_journal";
    private FeatureSaveJournal() { }

    private static void initialize(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE
                + " (layer TEXT NOT NULL, operation TEXT NOT NULL, row_id INTEGER NOT NULL,"
                + " PRIMARY KEY (layer, operation))");
    }

    public static long find(SQLiteDatabase db, String layer, String operation) {
        if (operation == null || operation.isEmpty()) return Constants.NOT_FOUND;
        initialize(db);
        try (Cursor cursor = db.query(TABLE, new String[]{"row_id"},
                "layer=? AND operation=?", new String[]{layer, operation},
                null, null, null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : Constants.NOT_FOUND;
        }
    }

    public static void record(SQLiteDatabase db, String layer, String operation, long rowId) {
        if (operation == null || operation.isEmpty()) return;
        initialize(db);
        ContentValues values = new ContentValues();
        values.put("layer", layer);
        values.put("operation", operation);
        values.put("row_id", rowId);
        db.insertOrThrow(TABLE, null, values);
    }

    public static void changeFeatureId(SQLiteDatabase db, String layer, long oldId, long newId) {
        initialize(db);
        ContentValues values = new ContentValues();
        values.put("row_id", newId);
        db.update(TABLE, values, "layer=? AND row_id=?",
                new String[]{layer, Long.toString(oldId)});
        values.clear();
        values.put("layer", layer + "/" + newId + "/attach");
        db.update(TABLE, values, "layer=?",
                new String[]{layer + "/" + oldId + "/attach"});
    }

    public static void forget(SQLiteDatabase db, String layer, String operation) {
        initialize(db);
        db.delete(TABLE, "layer=? AND operation=?", new String[]{layer, operation});
    }

    public static void deleteLayerEntries(SQLiteDatabase db, String layer) {
        try (Cursor tables = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                new String[]{TABLE})) {
            if (!tables.moveToFirst()) return;
        }
        String prefix = layer + "/";
        db.delete(TABLE, "layer=? OR substr(layer,1,?)=?",
                new String[]{layer, Integer.toString(prefix.length()), prefix});
    }
}

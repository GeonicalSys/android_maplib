package com.nextgis.maplib.util;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

/** Local-only idempotency journal. It is never added to a layer's schema or sent to NGW. */
public final class FeatureSaveJournal {
    public static final String URI_PARAMETER = "save_operation";
    private static final String TABLE = "ngm_feature_save_journal";
    private static final String HOLDS = "ngm_feature_save_holds";
    private static final String COMPLETED = "ngm_completed_form_saves";
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
        if (holdsExist(db)) {
            values.clear(); values.put("row_id", newId);
            db.update(HOLDS, values, "layer=? AND row_id=?", new String[]{layer, Long.toString(oldId)});
        }
        if(tableExists(db,COMPLETED)) {
            ContentValues complete=new ContentValues();complete.put("row_id",newId);
            db.update(COMPLETED,complete,"layer=? AND row_id=?",new String[]{layer,Long.toString(oldId)});
        }
    }

    public static void forget(SQLiteDatabase db, String layer, String operation) {
        initialize(db);
        db.delete(TABLE, "layer=? AND operation=?", new String[]{layer, operation});
        if(tableExists(db,COMPLETED))db.delete(COMPLETED,"layer=? AND operation=?",new String[]{layer,operation});
    }

    public static void deleteLayerEntries(SQLiteDatabase db, String layer) {
        if (holdsExist(db)) db.delete(HOLDS, "layer=?", new String[]{layer});
        if(tableExists(db,COMPLETED))db.delete(COMPLETED,"layer=?",new String[]{layer});
        try (Cursor tables = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                new String[]{TABLE})) {
            if (!tables.moveToFirst()) return;
        }
        String prefix = layer + "/";
        db.delete(TABLE, "layer=? OR substr(layer,1,?)=?",
                new String[]{layer, Integer.toString(prefix.length()), prefix});
    }

    /** A failed multi-stage form Save must not upload a half-copied photograph or remap its path. */
    public static void holdUpload(SQLiteDatabase db, String layer, long rowId, String operation) {
        if (rowId == Constants.NOT_FOUND || operation == null) return;
        db.execSQL("CREATE TABLE IF NOT EXISTS " + HOLDS
                + " (layer TEXT NOT NULL, row_id INTEGER NOT NULL, operation TEXT NOT NULL,"
                + "PRIMARY KEY(layer,row_id,operation))");
        ContentValues v = new ContentValues(); v.put("layer", layer);
        v.put("row_id", rowId); v.put("operation", operation);
        db.insertWithOnConflict(HOLDS, null, v, SQLiteDatabase.CONFLICT_IGNORE);
        try (Cursor c = db.query(HOLDS, new String[]{"operation"}, "layer=? AND row_id=? AND operation=?",
                new String[]{layer, Long.toString(rowId), operation}, null, null, null)) {
            if (!c.moveToFirst()) throw new android.database.sqlite.SQLiteException("Save hold was not persisted");
        }
    }

    private static boolean holdsExist(SQLiteDatabase db) {
        return tableExists(db,HOLDS);
    }
    private static boolean tableExists(SQLiteDatabase db,String table) {
        try (Cursor c = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", new String[]{table})) {
            return c.moveToFirst();
        }
    }
    public static boolean isUploadHeld(SQLiteDatabase db, String layer, long rowId) {
        if (!holdsExist(db)) return false;
        try (Cursor c = db.query(HOLDS, new String[]{"operation"}, "layer=? AND row_id=?",
                new String[]{layer, Long.toString(rowId)}, null, null, null, "1")) { return c.moveToFirst(); }
    }

    public static void releaseUpload(SQLiteDatabase db, String layer, String operation) {
        if (!holdsExist(db)) return;
        db.delete(HOLDS, "layer=? AND operation=?", new String[]{layer, operation});
    }

    private static String queueDigest(SQLiteDatabase db,String layer,long row) {
        java.security.MessageDigest digest=UnderlayFiles.digest();String changes=layer+"_changes";
        if(tableExists(db,changes)) {
            String identity=FeatureChanges.hasOperationIdentity(db,changes)?FeatureChanges.OPERATION_UUID:"NULL AS "+FeatureChanges.OPERATION_UUID;
            String[] fields={Constants.FIELD_ID,Constants.FIELD_OPERATION,Constants.FIELD_ATTACH_ID,Constants.FIELD_ATTACH_OPERATION,identity};
            int data=Constants.CHANGE_OPERATION_NEW|Constants.CHANGE_OPERATION_CHANGED|Constants.CHANGE_OPERATION_DELETE;
            try(Cursor c=db.query(changes,fields,Constants.FIELD_FEATURE_ID+"=?",new String[]{Long.toString(row)},null,null,Constants.FIELD_ID)) {
                while(c.moveToNext()) {
                    int flags=c.getInt(1),attachmentFlags=c.getInt(3);
                    if((((flags&Constants.CHANGE_OPERATION_ATTACH)!=0?attachmentFlags:flags)&data)==0)continue;
                    org.json.JSONArray item=new org.json.JSONArray().put(c.getLong(0)).put(flags).put(c.getLong(2)).put(attachmentFlags)
                            .put(c.isNull(4)?org.json.JSONObject.NULL:c.getString(4));
                    digest.update(item.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));digest.update((byte)0);
                }
            }
        }
        return UnderlayFiles.hex(digest.digest());
    }
    /** Written only after every attribute, photo and signature stage of Save has succeeded. */
    public static void completeForm(SQLiteDatabase db,String layer,String operation,long row) {
        if(!db.inTransaction()||operation==null||operation.isEmpty())throw new android.database.sqlite.SQLiteException("Form completion needs its save transaction");
        try(Cursor c=db.query(layer,new String[]{Constants.FIELD_ID},Constants.FIELD_ID+"=?",new String[]{Long.toString(row)},null,null,null)) {
            if(!c.moveToFirst())throw new android.database.sqlite.SQLiteException("Completed form feature is missing");
        }
        db.execSQL("CREATE TABLE IF NOT EXISTS "+COMPLETED+" (_id INTEGER PRIMARY KEY AUTOINCREMENT,layer TEXT NOT NULL,"
                +"operation TEXT NOT NULL,row_id INTEGER NOT NULL,queue_sha256 TEXT NOT NULL,UNIQUE(layer,operation))");
        ContentValues values=new ContentValues();values.put("layer",layer);values.put("operation",operation);values.put("row_id",row);
        values.put("queue_sha256",queueDigest(db,layer,row));
        try(Cursor c=db.rawQuery("SELECT row_id FROM "+COMPLETED+" WHERE layer=? AND operation=?",new String[]{layer,operation})) {
            if(c.moveToFirst()) {
                if(c.getLong(0)!=row)throw new android.database.sqlite.SQLiteException("Form completion identity changed");
                if(db.update(COMPLETED,values,"layer=? AND operation=?",new String[]{layer,operation})!=1)
                    throw new android.database.sqlite.SQLiteException("Form completion disappeared");
            } else db.insertOrThrow(COMPLETED,null,values);
        }
    }
    public static boolean isFormComplete(SQLiteDatabase db,String layer,String operation,long row) {
        if(!tableExists(db,COMPLETED))return false;
        try(Cursor c=db.rawQuery("SELECT 1 FROM "+COMPLETED+" WHERE layer=? AND operation=? AND row_id=?",
                new String[]{layer,operation,Long.toString(row)})) {return c.moveToFirst();}
    }
    /** Immutable restoration baseline, including retired AUTOINCREMENT IDs. */
    public static org.json.JSONObject recoveryBaseline(SQLiteDatabase db) throws org.json.JSONException {
        long watermark=0;
        if(tableExists(db,COMPLETED)) {
            watermark=android.database.DatabaseUtils.longForQuery(db,"SELECT coalesce(max(_id),0) FROM "+COMPLETED,null);
            if(tableExists(db,"sqlite_sequence"))try(Cursor c=db.rawQuery("SELECT seq FROM sqlite_sequence WHERE name=?",new String[]{COMPLETED})) {
                if(c.moveToFirst())watermark=Math.max(watermark,c.getLong(0));
            }
        }
        org.json.JSONArray holds=new org.json.JSONArray();
        if(holdsExist(db))try(Cursor c=db.rawQuery("SELECT layer,row_id,operation FROM "+HOLDS+" ORDER BY layer,row_id,operation",null)) {
            while(c.moveToNext()) {
                if(holds.length()>=16384)throw new android.database.sqlite.SQLiteException("Too many unfinished forms for recovery metadata");
                holds.put(new org.json.JSONObject().put("layer",c.getString(0)).put("row",c.getLong(1)).put("operation",c.getString(2)));
            }
        }
        return new org.json.JSONObject().put("completion_watermark",watermark).put("holds",holds);
    }
    /** Never clears a current owner's hold or accepts a pre-restore/partial Save as completion. */
    public static boolean releaseRecoveredHolds(SQLiteDatabase db,org.json.JSONObject baseline,String layer,long row) throws org.json.JSONException {
        if(!db.inTransaction())throw new android.database.sqlite.SQLiteException("Recovery form review requires a transaction");
        if(!holdsExist(db)||!tableExists(db,COMPLETED))return false;
        try(Cursor c=db.query(layer,new String[]{Constants.FIELD_ID},Constants.FIELD_ID+"=?",new String[]{Long.toString(row)},null,null,null)) {
            if(!c.moveToFirst())return false;
        }
        String digest=queueDigest(db,layer,row);long watermark=baseline.getLong("completion_watermark");
        if(watermark<0)throw new android.database.sqlite.SQLiteException("Invalid completion baseline");
        try(Cursor c=db.rawQuery("SELECT 1 FROM "+COMPLETED+" WHERE _id>? AND layer=? AND row_id=? AND queue_sha256=? LIMIT 1",
                new String[]{Long.toString(watermark),layer,Long.toString(row),digest})) {if(!c.moveToFirst())return false;}
        org.json.JSONArray holds=baseline.getJSONArray("holds");boolean changed=false;
        for(int i=0;i<holds.length();i++) {
            org.json.JSONObject old=holds.getJSONObject(i);
            if(layer.equals(old.getString("layer"))&&row==old.getLong("row"))
                changed|=db.delete(HOLDS,"layer=? AND row_id=? AND operation=?",new String[]{layer,Long.toString(row),old.getString("operation")})>0;
        }
        return changed;
    }
}

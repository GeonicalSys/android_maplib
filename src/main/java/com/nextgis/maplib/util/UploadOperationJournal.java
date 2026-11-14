package com.nextgis.maplib.util;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.io.IOException;
import org.json.JSONArray;

/** Local receipts belong to the layer's database, never to the active-map singleton. */
public final class UploadOperationJournal {
    private static final String TABLE = "ngm_upload_receipts";
    private static final String[] METADATA = {"operation_id", "layer", "change_id", "feature_id", "attach_id",
            "operation", "target", "boundary", "state", "remote_id", "reason", "updated"};
    private static final String LEGACY_COLUMNS = "operation_id,layer,change_id,feature_id,attach_id,operation,target,payload,boundary,state,remote_id,reason,updated";
    public static final String SENDING = "sending", UNCERTAIN = "uncertain",
            ACCEPTED = "accepted", CONFIRMED = "confirmed", REJECTED = "rejected",
            LOCAL_MISSING = "local_missing", RETRY_ALLOWED = "retry_allowed", READ_FAILED="read_failed",
            PREPARING="preparing", RETRY_PENDING="retry_pending";
    private static final java.util.Set<String> IN_FLIGHT=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private UploadOperationJournal() { }

    public static final class Entry {
        public final String id, layer, operation, target, payload, state, reason;
        public final long changeId, featureId, attachId, boundary, remoteId, updated;
        public final List<String> acknowledgedChanges;
        private boolean started;
        public boolean wasStarted() { return started; }
        Entry(SQLiteDatabase db, Cursor c, boolean full) {
            id = c.getString(c.getColumnIndexOrThrow("operation_id"));
            layer = c.getString(c.getColumnIndexOrThrow("layer"));
            operation = c.getString(c.getColumnIndexOrThrow("operation"));
            target = c.getString(c.getColumnIndexOrThrow("target"));
            state = c.getString(c.getColumnIndexOrThrow("state"));
            reason = c.getString(c.getColumnIndexOrThrow("reason"));
            changeId = c.getLong(c.getColumnIndexOrThrow("change_id"));
            featureId = c.getLong(c.getColumnIndexOrThrow("feature_id"));
            attachId = c.getLong(c.getColumnIndexOrThrow("attach_id"));
            boundary = c.getLong(c.getColumnIndexOrThrow("boundary"));
            remoteId = c.getLong(c.getColumnIndexOrThrow("remote_id"));
            updated = c.getLong(c.getColumnIndexOrThrow("updated"));
            payload = full ? text(db, id, "payload") : null;
            acknowledgedChanges = new ArrayList<>();
            if(full && columnExists(db,TABLE,"ack_tokens")) {
                try {
                    JSONArray tokens=new JSONArray(text(db,id,"ack_tokens"));
                    for(int i=0;i<tokens.length();i++)acknowledgedChanges.add(tokens.getString(i));
                }catch(JSONException error) {throw new SQLiteException("Invalid acknowledged-operation snapshot",error);}
            }
        }
        public boolean needsReconciliation() {
            return ACCEPTED.equals(state) || nonIdempotent(operation)
                    && (SENDING.equals(state) || UNCERTAIN.equals(state) || PREPARING.equals(state));
        }
    }

    public static boolean nonIdempotent(String operation) {
        return "create".equals(operation)||"attachment_create".equals(operation);
    }
    private static String flightKey(SQLiteDatabase db,String layer,String id) {return db.getPath()+"\n"+layer+"\n"+id;}
    public static final class Flight implements AutoCloseable {
        private final String key;
        private Flight(String key) {
            this.key=key;if(!IN_FLIGHT.add(key))throw new SQLiteException("Operation is already being sent");
        }
        @Override public void close() {IN_FLIGHT.remove(key);}
    }
    public static Flight sending(SQLiteDatabase db,Entry entry) {return new Flight(flightKey(db,entry.layer,entry.id));}
    public static boolean isInFlight(SQLiteDatabase db,String layer,String id) {return IN_FLIGHT.contains(flightKey(db,layer,id));}

    private static boolean exists(SQLiteDatabase db) {
        try (Cursor c = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                new String[]{TABLE})) { return c.moveToFirst(); }
    }

    private static void initialize(SQLiteDatabase db) {
        if(exists(db)) {
            String sql;
            try(Cursor c=db.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name=?",new String[]{TABLE})) {
                if(!c.moveToFirst())throw new SQLiteException("Missing upload journal schema");sql=c.getString(0);
            }
            if(!columnExists(db,TABLE,"ack_tokens") || sql.replaceAll("\\s","").toUpperCase(java.util.Locale.ROOT).contains("UNIQUE(LAYER,CHANGE_ID)")) {
                // A transaction preserves every old receipt; only the obsolete numeric uniqueness
                // is removed. Unknown columns/foreign-key consumers must not be silently discarded.
                int count=0;
                try(Cursor c=db.rawQuery("PRAGMA table_info("+TABLE+")",null)) {
                    while(c.moveToNext()) {
                        String name=c.getString(1);
                        if(!java.util.Arrays.asList(LEGACY_COLUMNS.split(",")).contains(name)&&!"ack_tokens".equals(name))
                            throw new SQLiteException("Unsupported upload journal column");
                        count++;
                    }
                }
                if(count!=13&&count!=14)throw new SQLiteException("Unsupported upload journal schema");
                List<String> definitions=new ArrayList<>();
                try(Cursor c=db.rawQuery("SELECT sql FROM sqlite_master WHERE tbl_name=? AND type IN ('index','trigger') AND sql IS NOT NULL",new String[]{TABLE})) {
                    while(c.moveToNext())definitions.add(c.getString(0));
                }
                try(Cursor tables=db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'",null)) {
                    while(tables.moveToNext())try(Cursor keys=db.rawQuery("PRAGMA foreign_key_list(\""+tables.getString(0).replace("\"","\"\"")+"\")",null)) {
                        while(keys.moveToNext())if(TABLE.equals(keys.getString(2)))
                            throw new SQLiteException("Unsupported upload-journal foreign-key consumer");
                    }
                }
                String temporary=TABLE+"_migration_"+UUID.randomUUID().toString().replace("-","");
                create(db,temporary);
                String tokens=columnExists(db,TABLE,"ack_tokens")?"ack_tokens":"'[]'";
                db.execSQL("INSERT INTO "+temporary+" ("+LEGACY_COLUMNS+",ack_tokens) SELECT "+LEGACY_COLUMNS+","+tokens+" FROM "+TABLE);
                db.execSQL("DROP TABLE "+TABLE);db.execSQL("ALTER TABLE "+temporary+" RENAME TO "+TABLE);
                for(String definition:definitions)db.execSQL(definition);
            }
        } else create(db,TABLE);
        db.execSQL("CREATE INDEX IF NOT EXISTS ngm_upload_receipts_layer ON "+TABLE+"(layer,feature_id,updated)");
    }

    private static void create(SQLiteDatabase db,String table) {
        db.execSQL("CREATE TABLE " + table + " (operation_id TEXT PRIMARY KEY,"
                + "layer TEXT NOT NULL, change_id INTEGER NOT NULL, feature_id INTEGER NOT NULL,"
                + "attach_id INTEGER NOT NULL, operation TEXT NOT NULL, target TEXT NOT NULL,"
                + "payload TEXT NOT NULL, boundary INTEGER NOT NULL, state TEXT NOT NULL,"
                + "remote_id INTEGER NOT NULL DEFAULT -1, reason TEXT NOT NULL, updated INTEGER NOT NULL,"
                + "ack_tokens TEXT NOT NULL DEFAULT '[]')");
    }

    private static boolean columnExists(SQLiteDatabase db,String table,String column) {
        try(Cursor c=db.rawQuery("PRAGMA table_info("+table+")",null)) {
            while(c.moveToNext())if(column.equals(c.getString(1)))return true;
        }
        return false;
    }

    private static String text(SQLiteDatabase db,String id,String column) {
        int count;
        try(Cursor c=db.rawQuery("SELECT length("+column+") FROM "+TABLE+" WHERE operation_id=?",new String[]{id})) {
            if(!c.moveToFirst()||c.isNull(0))throw new SQLiteException("Missing receipt snapshot");
            long size=c.getLong(0);Runtime runtime=Runtime.getRuntime();
            long budget=Math.min(16L*1024*1024,(runtime.maxMemory()-runtime.totalMemory()+runtime.freeMemory()-8L*1024*1024)/4);
            if(size<0||size>budget)throw new SQLiteException("Upload snapshot exceeds available memory");
            count=(int)size;
        }
        StringBuilder value=new StringBuilder(count);
        for(int offset=0;offset<count;) {
            try(Cursor c=db.rawQuery("SELECT substr("+column+",?,?) FROM "+TABLE+" WHERE operation_id=?",
                    new String[]{Integer.toString(offset+1),Integer.toString(Math.min(8192,count-offset)),id})) {
                if(!c.moveToFirst())throw new SQLiteException("Missing receipt snapshot chunk");
                String part=c.getString(0);int characters=part.codePointCount(0,part.length());
                if(characters==0)throw new SQLiteException("Incomplete receipt snapshot");
                value.append(part);offset+=characters;
            }
        }
        return value.toString();
    }

    public static Entry queued(SQLiteDatabase db,String layer,String changes,long changeId) {
        String id=FeatureChanges.operationId(db,changes,changeId);
        return id==null?null:find(db,layer,id);
    }

    public static Entry find(SQLiteDatabase db, String layer, String id) {
        if (!exists(db)) return null;
        try (Cursor c = db.query(TABLE, METADATA, "layer=? AND operation_id=?",
                new String[]{layer, id}, null, null, null)) {
            return c.moveToFirst() ? new Entry(db,c,true) : null;
        }
    }

    public static Entry metadata(SQLiteDatabase db,String layer,String id) {
        if(id==null||!exists(db))return null;
        try(Cursor c=db.query(TABLE,METADATA,"layer=? AND operation_id=?",new String[]{layer,id},null,null,null)) {
            return c.moveToFirst()?new Entry(db,c,false):null;
        }
    }

    public static String target(java.io.File workspace,String layer,String account,String url,String login,long resource) throws IOException {
        return target(workspace.getCanonicalPath(),layer,account,url,login,resource);
    }
    /** The archived path is already canonical on its source device; do not reinterpret it here. */
    public static String target(String canonicalWorkspace,String layer,String account,String url,String login,long resource) {
        String identity=canonicalWorkspace+"\n"+layer+"\n"+account+"\n"+url+"\n"+login+"\n"+resource;
        return android.util.Base64.encodeToString(UnderlayFiles.digest().digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)),android.util.Base64.NO_WRAP);
    }

    /** Reserve a snapshot for an explicit review without performing an HTTP mutation. */
    public static Entry prepareReview(SQLiteDatabase db,String layer,String changes,long changeId,long featureId,
            long attachmentId,String operation,String target,Snapshot snapshot,long boundary) {
        if(!db.inTransaction())throw new SQLiteException("Review requires an atomic queue boundary");
        String identity=FeatureChanges.operationId(db,changes,changeId);
        Entry prior=identity==null?null:find(db,layer,identity);
        if(prior!=null&&!READ_FAILED.equals(prior.state)&&!LOCAL_MISSING.equals(prior.state))return prior;
        Entry entry=reserve(db,layer,changes,changeId,featureId,attachmentId,operation,target,snapshot,boundary);
        if(entry.wasStarted()) {
            state(db,entry,nonIdempotent(operation)?UNCERTAIN:RETRY_PENDING,-1,"explicit_review_required");
            entry=find(db,layer,entry.id);
        }
        return entry;
    }

    /** Call only after independently validating an archived origin against the current account. */
    public static Entry rebindReviewed(SQLiteDatabase db,Entry entry,String target) {
        if(!db.inTransaction())throw new SQLiteException("Review target requires a transaction");
        ContentValues values=new ContentValues();values.put("target",target);
        if(db.update(TABLE,values,"operation_id=? AND layer=? AND target=?",
                new String[]{entry.id,entry.layer,entry.target})!=1)throw new SQLiteException("Review target changed");
        return find(db,entry.layer,entry.id);
    }

    public static long count(SQLiteDatabase db,String layer,String state) {
        if(!exists(db))return 0;
        return android.database.DatabaseUtils.longForQuery(db,"SELECT count(*) FROM "+TABLE+" WHERE layer=? AND state=?",new String[]{layer,state});
    }
    public static java.util.Set<String> layers(SQLiteDatabase db) {
        java.util.Set<String> result=new java.util.TreeSet<>();if(!exists(db))return result;
        try(Cursor c=db.rawQuery("SELECT DISTINCT layer FROM "+TABLE,null)) {while(c.moveToNext())result.add(c.getString(0));}
        return result;
    }

    private static String retired(SQLiteDatabase db,String changes) {
        if(!FeatureChanges.hasOperationIdentity(db,changes))return "1";
        return "NOT EXISTS (SELECT 1 FROM \""+changes.replace("\"","\"\"")+"\" q WHERE q."
                +FeatureChanges.OPERATION_UUID+"="+TABLE+".operation_id)";
    }
    public static long historyCount(SQLiteDatabase db,String layer,String changes) {
        if(!exists(db))return 0;
        return android.database.DatabaseUtils.longForQuery(db,"SELECT count(*) FROM "+TABLE+" WHERE layer=? AND "+retired(db,changes),new String[]{layer});
    }
    public static List<Entry> historyPage(SQLiteDatabase db,String layer,String changes,int limit,long offset) {
        if(limit<1||limit>500||offset<0)throw new IllegalArgumentException("Invalid history page");
        List<Entry> result=new ArrayList<>();if(!exists(db))return result;
        try(Cursor c=db.query(TABLE,METADATA,"layer=? AND "+retired(db,changes),new String[]{layer},null,null,"updated DESC,operation_id",offset+","+limit)) {
            while(c.moveToNext())result.add(new Entry(db,c,false));
        }
        return result;
    }

    /** Persist before entering a non-idempotent HTTP call. A stale SENDING is never replayed. */
    public interface Snapshot { String read() throws JSONException,IOException; }
    public static Entry begin(SQLiteDatabase db, String layer, String changes, long changeId, long featureId,
            long attachId, String operation, String target, Snapshot snapshot, long boundary) throws JSONException,IOException {
        if (db.inTransaction()) throw new SQLiteException("Upload needs a durable transaction boundary");
        return LayerDatabaseTransaction.run(db, () -> reserve(db, layer, changes,changeId, featureId,
                attachId, operation, target, snapshot, boundary));
    }

    private static Entry reserve(SQLiteDatabase db, String layer,String changes,long changeId, long featureId,
            long attachId, String operation, String target, Snapshot snapshot, long boundary) {
        initialize(db);
        String id=FeatureChanges.operationId(db,changes,changeId);boolean legacy=id==null||id.isEmpty();
        id=FeatureChanges.bindOperationId(db,changes,changeId);
        Entry prior = find(db, layer, id);
        if(prior!=null&&!target.equals(prior.target))throw new SQLiteException("Upload target changed");
        if (prior != null && prior.needsReconciliation()) return prior;
        if (prior != null && CONFIRMED.equals(prior.state))
            throw new SQLiteException("Confirmed operation still has an outbox record");
        String payload=null,readError=null;
        try {payload=snapshot.read();}catch(JSONException|IOException error) {
            readError=error instanceof JSONException?"snapshot_parse_error":"snapshot_read_error";
        }
        List<String> acknowledged=operation.startsWith("attachment_")
                ?FeatureChanges.changedAttachmentOperationIds(db,changes,featureId,attachId)
                :FeatureChanges.changedOperationIds(db,changes,featureId);
        // Reserve even the attachment's preparation before any network. A process death is
        // conservative uncertainty; a handled failure before association can explicitly retry.
        String state=readError!=null?READ_FAILED:payload==null?LOCAL_MISSING:legacy&&nonIdempotent(operation)?UNCERTAIN:SENDING;
        ContentValues v = new ContentValues();
        v.put("operation_id", id);
        v.put("layer", layer); v.put("change_id", changeId); v.put("feature_id", featureId);
        v.put("attach_id", attachId); v.put("operation", operation); v.put("target", target);
        v.put("payload", payload==null?"":payload); v.put("boundary", boundary); v.put("state", state);
        v.put("ack_tokens",new JSONArray(acknowledged).toString());
        v.put("remote_id", -1); v.put("reason", readError!=null?readError:payload==null?"local_row_missing"
                :legacy&&nonIdempotent(operation)?"legacy_creation_outcome_unknown":PREPARING.equals(state)?"preparing_upload":"awaiting_response");
        v.put("updated", System.currentTimeMillis());
        if(prior==null)db.insertOrThrow(TABLE,null,v);
        else if(db.update(TABLE,v,"operation_id=? AND layer=?",new String[]{id,layer})!=1)
            throw new SQLiteException("Upload receipt changed during reservation");
        Entry result = find(db, layer, id);
        if (result == null || !state.equals(result.state))
            throw new SQLiteException("Upload receipt was not persisted");
        result.started = SENDING.equals(state)||PREPARING.equals(state);
        return result;
    }

    public static void state(SQLiteDatabase db, Entry entry, String state, long remoteId, String reason) {
        ContentValues v = new ContentValues();
        v.put("state", state); v.put("remote_id", remoteId); v.put("reason", reason);
        v.put("updated", System.currentTimeMillis());
        if (db.update(TABLE, v, "operation_id=? AND layer=?", new String[]{entry.id, entry.layer}) != 1)
            throw new SQLiteException("Upload receipt is missing");
    }

    /** Rejected requests can be retried; 5xx or a missing response cannot prove non-creation. */
    public static void httpFailure(SQLiteDatabase db, Entry entry, int status) {
        boolean rejected = status == 400 || status == 401 || status == 403 || status == 404
                || status == 405 || status == 413 || status == 415 || status == 422 || status == 429;
        state(db, entry, nonIdempotent(entry.operation)?rejected?REJECTED:UNCERTAIN:RETRY_PENDING, -1,"http_"+status);
    }

    public static void transportFailure(SQLiteDatabase db,Entry entry,boolean finalRequestStarted) {
        state(db,entry,nonIdempotent(entry.operation)&&finalRequestStarted?UNCERTAIN:RETRY_PENDING,-1,
                finalRequestStarted?"response_uncertain":"upload_preparation_failed");
    }

    public static void acknowledge(SQLiteDatabase db,String changes,Entry entry,long featureId,long attachmentId) {
        LayerDatabaseTransaction.run(db,()->{
            FeatureChanges.acknowledgeRecord(db,changes,entry.changeId,entry.id);
            if(entry.operation.startsWith("attachment_"))
                FeatureChanges.acknowledgeChangedAttachments(db,changes,featureId,attachmentId,entry.acknowledgedChanges);
            else FeatureChanges.acknowledgeChangedOperations(db,changes,featureId,entry.acknowledgedChanges);
            state(db,entry,CONFIRMED,attachmentId>=0?attachmentId:featureId,"server_confirmed");return null;
        });
    }

    public static boolean blocksFeature(SQLiteDatabase db, String layer,String changes,long featureId) {
        if (!exists(db)) return false;
        if(!FeatureChanges.hasOperationIdentity(db,changes))return true;
        String table="\""+changes.replace("\"","\"\"")+"\"";
        try (Cursor c = db.rawQuery("SELECT 1 FROM "+TABLE+" r JOIN "+table+" q ON q."
                +FeatureChanges.OPERATION_UUID+"=r.operation_id WHERE r.layer=? AND r.feature_id=?"
                +" AND r.operation='create' AND r.state IN (?,?,?,?) LIMIT 1",
                new String[]{layer, Long.toString(featureId), SENDING, UNCERTAIN, ACCEPTED, LOCAL_MISSING})) { return c.moveToFirst(); }
    }

    public static List<Entry> entries(SQLiteDatabase db, String layer) {
        List<Entry> result = new ArrayList<>();
        if (!exists(db)) return result;
        try (Cursor c = db.query(TABLE, METADATA, "layer=?", new String[]{layer},
                null, null, "updated DESC")) { while (c.moveToNext()) result.add(new Entry(db,c,true)); }
        return result;
    }

    /** Bounded UI history never loads potentially large submitted geometry/payload text. */
    public static List<Entry> page(SQLiteDatabase db,String layer,int limit,int offset) {
        if(limit<1||limit>1000||offset<0)throw new IllegalArgumentException("Invalid receipt page");
        List<Entry> result=new ArrayList<>();if(!exists(db))return result;
        try(Cursor c=db.query(TABLE,METADATA,"layer=?",new String[]{layer},null,null,"updated DESC,operation_id",offset+","+limit)) {
            while(c.moveToNext())result.add(new Entry(db,c,false));
        }
        return result;
    }

    /** The form's immutable save identity, rather than an old numeric row ID, proves a remap. */
    public static long resolveCreatedId(SQLiteDatabase db, String layer, long localId, String saveOperation) {
        long saved = FeatureSaveJournal.find(db, layer, saveOperation);
        long candidate = saved == Constants.NOT_FOUND ? localId : saved;
        try (Cursor c = db.query(layer, new String[]{Constants.FIELD_ID}, Constants.FIELD_ID + "=?",
                new String[]{Long.toString(candidate)}, null, null, null)) {
            return c.moveToFirst() ? candidate : Constants.NOT_FOUND;
        }
    }

    /** Keep local IDs above retired receipts as well as live rows after reopening a workspace. */
    public static long lastLocalFeatureId(SQLiteDatabase db, String layer) {
        if (!exists(db)) return Constants.NOT_FOUND;
        try (Cursor c = db.rawQuery("SELECT max(feature_id) FROM " + TABLE + " WHERE layer=?",
                new String[]{layer})) {
            return c.moveToFirst() && !c.isNull(0) ? c.getLong(0) : Constants.NOT_FOUND;
        }
    }

    /** JSON coercion ("42", 42.5, true, null and overflow) is deliberately forbidden. */
    public static long serverId(JSONObject response) throws JSONException {
        Object value = response.opt(Constants.JSON_ID_KEY);
        if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() < 0)
            throw new JSONException("Missing or invalid server identifier");
        return ((Number) value).longValue();
    }
}

package com.nextgis.maplib.map;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import com.nextgis.maplib.api.IGISApplication;
import com.nextgis.maplib.datasource.GeoPoint;
import com.nextgis.maplib.util.Constants;
import com.nextgis.maplib.util.DatabaseContext;
import com.nextgis.maplib.util.FeatureSaveJournal;
import com.nextgis.maplib.util.GeoConstants;
import com.nextgis.maplib.util.PendingTrackPoints;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import static com.nextgis.maplib.map.TrackLayer.*;

/** Application-owned tracks. Project databases remain untouched rollback sources. */
public final class SharedTrackStore {
    private static final Map<String, SharedTrackStore> STORES = new HashMap<>();
    private final Context context;
    private final File directory;
    private final SQLiteOpenHelper helper;
    private final AtomicLong revision = new AtomicLong();

    public static synchronized SharedTrackStore get(Context context) {
        Context app = context.getApplicationContext();
        File directory = new File(app.getFilesDir(), "shared-tracks");
        String key = directory.getAbsolutePath();
        SharedTrackStore store = STORES.get(key);
        if (store == null) {
            store = new SharedTrackStore(app, directory);
            STORES.put(key, store);
        }
        return store;
    }

    private SharedTrackStore(Context context, File directory) {
        this.context = context;
        this.directory = directory;
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new SQLiteException("Cannot create shared track directory");
        helper = new SQLiteOpenHelper(context, new File(directory, "tracks.db").getPath(), null, 2) {
            @Override public void onCreate(SQLiteDatabase db) {
                db.execSQL(DB_CREATE_TRACKS);
                // A durable point ID must never be reused while an upload acknowledgement is in flight.
                db.execSQL(DB_CREATE_TRACKPOINTS.replace(" (", " (_id INTEGER PRIMARY KEY AUTOINCREMENT, "));
                db.execSQL("CREATE INDEX track_session_order ON trackpoints(session, _id)");
                db.execSQL("CREATE INDEX track_pending ON trackpoints(sent, time, _id)");
                db.execSQL("CREATE TABLE migrated_workspaces(source TEXT PRIMARY KEY)");
                db.execSQL("CREATE TABLE legacy_tracks(source TEXT, old_id INTEGER, new_id INTEGER NOT NULL, PRIMARY KEY(source,old_id))");
                db.execSQL("CREATE TABLE legacy_points(source TEXT, old_id INTEGER, new_id INTEGER NOT NULL, PRIMARY KEY(source,old_id))");
            }
            @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
                if (oldVersion == 1 && newVersion == 2) colorizeLegacyTracks(db);
                else throw new SQLiteException("Unsupported shared track schema upgrade");
            }
        };
    }

    public File getDirectory() { return directory; }
    public SQLiteDatabase getDatabase(boolean readOnly) {
        return readOnly ? helper.getReadableDatabase() : helper.getWritableDatabase();
    }
    public long getRevision() { return revision.get(); }

    /** Runs inside the owning upgrade/import transaction. NULL is the old automatic color. */
    private static void colorizeLegacyTracks(SQLiteDatabase db) {
        int[] colors = TrackColorPalette.shuffledColors(new java.util.Random());
        int next = 0;
        try (Cursor rows = db.query(TABLE_TRACKS, new String[]{FIELD_ID}, FIELD_COLOR + " IS NULL",
                null, null, null, FIELD_ID + " ASC")) {
            while (rows.moveToNext()) {
                ContentValues color = new ContentValues();
                color.put(FIELD_COLOR, colors[next++ % colors.length]);
                if (db.update(TABLE_TRACKS, color, FIELD_ID + "=? AND " + FIELD_COLOR + " IS NULL",
                        new String[]{rows.getString(0)}) != 1)
                    throw new SQLiteException("Legacy track color was not saved");
            }
        }
    }

    public void changed() {
        revision.incrementAndGet();
        if (context instanceof IGISApplication) {
            String base = "content://" + ((IGISApplication) context).getAuthority() + "/";
            context.getContentResolver().notifyChange(Uri.parse(base + TABLE_TRACKS), null);
            context.getContentResolver().notifyChange(Uri.parse(base + TABLE_TRACKPOINTS), null);
        }
    }

    public boolean isVisible() {
        return context.getSharedPreferences("shared-track-layer", Context.MODE_PRIVATE).getBoolean("visible", true);
    }
    public synchronized void initializeVisibility(boolean visible) {
        android.content.SharedPreferences preferences = context.getSharedPreferences("shared-track-layer", Context.MODE_PRIVATE);
        if (!preferences.contains("visible") && !preferences.edit().putBoolean("visible", visible).commit())
            throw new SQLiteException("Cannot initialize shared track visibility");
    }
    public void setVisible(boolean visible) {
        if (!context.getSharedPreferences("shared-track-layer", Context.MODE_PRIVATE).edit().putBoolean("visible", visible).commit())
            throw new SQLiteException("Cannot save shared track visibility");
    }

    private static String sourceKey(File workspace) throws IOException { return workspace.getCanonicalPath(); }
    private static boolean hasTable(SQLiteDatabase db, String table) {
        try (Cursor rows = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", new String[]{table})) {
            return rows.moveToFirst();
        }
    }
    private static long mapping(SQLiteDatabase db, String table, String source, long oldId) {
        try (Cursor rows = db.query(table, new String[]{"new_id"}, "source=? AND old_id=?",
                new String[]{source, Long.toString(oldId)}, null, null, null)) {
            return rows.moveToFirst() ? rows.getLong(0) : Constants.NOT_FOUND;
        }
    }
    private static void mapId(SQLiteDatabase db, String table, String source, long oldId, long newId) {
        ContentValues values = new ContentValues();
        values.put("source", source); values.put("old_id", oldId); values.put("new_id", newId);
        db.insertOrThrow(table, null, values);
    }
    public synchronized long migratedTrackId(File workspace, long oldId) throws IOException {
        return mapping(getDatabase(true), "legacy_tracks", sourceKey(workspace), oldId);
    }

    /** The initial-project bootstrap copied this exact workspace; it is the same history. */
    public synchronized void registerWorkspaceCopy(File original, File copy) throws IOException {
        String source = sourceKey(original), destination = sourceKey(copy);
        SQLiteDatabase db = getDatabase(false);
        db.beginTransaction();
        try {
            try (Cursor migrated = db.query("migrated_workspaces", new String[]{"source"}, "source=?",
                    new String[]{source}, null, null, null)) {
                if (!migrated.moveToFirst()) return;
            }
            for (String table : new String[]{"legacy_tracks", "legacy_points"})
                db.execSQL("INSERT OR IGNORE INTO " + table + "(source,old_id,new_id) SELECT ?,old_id,new_id FROM "
                        + table + " WHERE source=?", new Object[]{destination, source});
            ContentValues marker = new ContentValues(); marker.put("source", destination);
            db.insertWithOnConflict("migrated_workspaces", null, marker, SQLiteDatabase.CONFLICT_IGNORE);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private static ContentValues copy(Cursor rows, String... names) {
        ContentValues values = new ContentValues();
        for (String name : names) {
            int index = rows.getColumnIndex(name);
            if (index < 0) continue;
            switch (rows.getType(index)) {
                case Cursor.FIELD_TYPE_NULL: values.putNull(name); break;
                case Cursor.FIELD_TYPE_INTEGER: values.put(name, rows.getLong(index)); break;
                case Cursor.FIELD_TYPE_FLOAT: values.put(name, rows.getDouble(index)); break;
                case Cursor.FIELD_TYPE_STRING: values.put(name, rows.getString(index)); break;
                default: throw new SQLiteException("Unexpected track value type");
            }
        }
        return values;
    }

    /** One transaction includes history, lost acknowledgements, durable tail and the completion marker. */
    public synchronized void migrateWorkspace(File workspace) throws IOException {
        String source = sourceKey(workspace);
        if (source.equals(sourceKey(directory))) return;
        SQLiteDatabase target = getDatabase(false);
        try (Cursor done = target.query("migrated_workspaces", new String[]{"source"}, "source=?",
                new String[]{source}, null, null, null)) {
            if (done.moveToFirst()) return;
        }
        File sourceFile = DatabaseContext.resolveDatabaseFile(workspace, MapContentProviderHelper.DBNAME);
        // Retain compatibility with extensionless offline exports. The actual
        // application DatabaseHelper uses DatabaseContext and stores layers.db.
        if (!sourceFile.isFile()) sourceFile = new File(workspace, MapContentProviderHelper.DBNAME);
        File spool = new File(workspace, "pending-track-points");
        // A not-yet-created project can acquire a legacy database later; do not mark it imported.
        if (!sourceFile.isFile()) {
            if (spool.isDirectory() && spool.list() != null && spool.list().length > 0)
                throw new IOException("Track journal has no source database");
            return;
        }
        try (SQLiteDatabase legacy = SQLiteDatabase.openDatabase(sourceFile.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
            target.beginTransaction();
            try {
                if (!hasTable(legacy, TABLE_TRACKS) && hasTable(legacy, TABLE_TRACKPOINTS)
                        && android.database.DatabaseUtils.queryNumEntries(legacy, TABLE_TRACKPOINTS) > 0)
                    throw new SQLiteException("Legacy points have no track table");
                if (hasTable(legacy, TABLE_TRACKS)) {
                    long highWater = android.database.DatabaseUtils.longForQuery(target,
                            "SELECT COALESCE((SELECT seq FROM sqlite_sequence WHERE name='tracks'),0)", null);
                    try (Cursor tracks = legacy.query(TABLE_TRACKS, null, null, null, null, null, FIELD_ID + " ASC")) {
                        while (tracks.moveToNext()) {
                            long oldId = tracks.getLong(tracks.getColumnIndexOrThrow(FIELD_ID));
                            ContentValues values = copy(tracks, FIELD_NAME, FIELD_START, FIELD_END, FIELD_COLOR, FIELD_VISIBLE);
                            if (!values.containsKey(FIELD_VISIBLE)) values.put(FIELD_VISIBLE, 1);
                            // Preserve fresh IDs, but never reuse a deleted shared session ID while
                            // a durable recording checkpoint can still reference it.
                            if (oldId > highWater) values.put(FIELD_ID, oldId);
                            long id = target.insertOrThrow(TABLE_TRACKS, null, values);
                            highWater = Math.max(highWater, id);
                            mapId(target, "legacy_tracks", source, oldId, id);
                        }
                    }
                    if (hasTable(legacy, TABLE_TRACKPOINTS)) {
                        try (Cursor points = legacy.rawQuery("SELECT rowid AS legacy_rowid,* FROM trackpoints ORDER BY rowid", null)) {
                            while (points.moveToNext()) {
                                ContentValues values = copy(points, FIELD_LON, FIELD_LAT, FIELD_ELE, FIELD_FIX, FIELD_SAT,
                                        FIELD_SPEED, FIELD_ACCURACY, FIELD_BEARING, FIELD_TIMESTAMP, FIELD_SENT, FIELD_SEGMENT, FIELD_SESSION);
                                long session = mapping(target, "legacy_tracks", source, values.getAsLong(FIELD_SESSION));
                                if (session == Constants.NOT_FOUND) throw new SQLiteException("Legacy track session is missing");
                                values.put(FIELD_SESSION, session);
                                if (!values.containsKey(FIELD_SENT)) values.put(FIELD_SENT, 1);
                                if (!values.containsKey(FIELD_SEGMENT)) values.put(FIELD_SEGMENT, 0);
                                if (legacy.getVersion() > 0 && legacy.getVersion() <= 2) {
                                    GeoPoint point = new GeoPoint(values.getAsDouble(FIELD_LON), values.getAsDouble(FIELD_LAT));
                                    point.setCRS(GeoConstants.CRS_WGS84); point.project(GeoConstants.CRS_WEB_MERCATOR);
                                    values.put(FIELD_LON, point.getX()); values.put(FIELD_LAT, point.getY());
                                }
                                long row = target.insertOrThrow(TABLE_TRACKPOINTS, null, values);
                                mapId(target, "legacy_points", source, points.getLong(0), row);
                            }
                        }
                    }
                    if (hasTable(legacy, "ngm_feature_save_journal")) {
                        try (Cursor operations = legacy.rawQuery("SELECT operation,row_id FROM ngm_feature_save_journal WHERE layer=?",
                                new String[]{TABLE_TRACKPOINTS})) {
                            while (operations.moveToNext()) {
                                long row = mapping(target, "legacy_points", source, operations.getLong(1));
                                if (row != Constants.NOT_FOUND) FeatureSaveJournal.record(target, TABLE_TRACKPOINTS, operations.getString(0), row);
                            }
                        }
                    }
                }
                if (spool.isDirectory()) {
                    // Replay without deleting the original files; rollback leaves the entire source recoverable.
                    PendingTrackPoints.replayReadOnly(workspace, (operation, original) -> {
                        long saved = FeatureSaveJournal.find(target, TABLE_TRACKPOINTS, operation);
                        if (saved != Constants.NOT_FOUND) return saved;
                        ContentValues values = new ContentValues(original);
                        long session = mapping(target, "legacy_tracks", source, values.getAsLong(FIELD_SESSION));
                        if (session == Constants.NOT_FOUND) throw new SQLiteException("Pending track session is missing");
                        values.put(FIELD_SESSION, session);
                        long row = target.insertOrThrow(TABLE_TRACKPOINTS, null, values);
                        FeatureSaveJournal.record(target, TABLE_TRACKPOINTS, operation, row);
                        return row;
                    });
                }
                // A workspace first opened after the schema upgrade can bring more old tracks.
                colorizeLegacyTracks(target);
                ContentValues done = new ContentValues(); done.put("source", source);
                target.insertOrThrow("migrated_workspaces", null, done);
                target.setTransactionSuccessful();
            } finally { target.endTransaction(); }
        }
        changed();
    }
}

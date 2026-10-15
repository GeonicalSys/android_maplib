package com.nextgis.maplib.map;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import com.nextgis.maplib.util.FeatureSaveJournal;
import com.nextgis.maplib.util.PendingTrackPoints;
import com.nextgis.maplib.datasource.DatabaseHelper;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.util.Map;
import java.util.UUID;
import static com.nextgis.maplib.map.TrackLayer.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class SharedTrackStoreTest {
    private SharedTrackStore store;
    private Context context;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        store = SharedTrackStore.get(context);
        SQLiteDatabase db = store.getDatabase(false);
        for (String table : new String[]{"trackpoints", "tracks", "legacy_tracks", "legacy_points", "migrated_workspaces"})
            db.delete(table, null, null);
        // Each scenario models a fresh installation, independent of Robolectric's static store.
        db.delete("sqlite_sequence", "name IN ('tracks','trackpoints')", null);
    }

    private File workspace() {
        File file = new File(context.getCacheDir(), "lt-" + UUID.randomUUID());
        assertTrue(file.mkdirs());
        return file;
    }
    private SQLiteDatabase legacy(File directory) {
        SQLiteDatabase db = new DatabaseHelper(context, new File(directory, "layers"), null, 6).getWritableDatabase();
        assertEquals(new File(directory, "layers.db").getAbsolutePath(), db.getPath());
        db.execSQL(DB_CREATE_TRACKS); db.execSQL(DB_CREATE_TRACKPOINTS); db.setVersion(6);
        ContentValues track = new ContentValues();
        track.put(FIELD_ID, 1); track.put(FIELD_NAME, directory.getName()); track.put(FIELD_START, 100L);
        track.put(FIELD_END, 900L); track.put(FIELD_COLOR, 123); track.put(FIELD_VISIBLE, 1);
        db.insertOrThrow(TABLE_TRACKS, null, track);
        return db;
    }
    private ContentValues point(long time, int sent, int segment) {
        ContentValues values = new ContentValues();
        values.put(FIELD_SESSION, 1); values.put(FIELD_LON, 123.5); values.put(FIELD_LAT, 456.5);
        values.put(FIELD_TIMESTAMP, time); values.put(FIELD_SENT, sent); values.put(FIELD_SEGMENT, segment);
        values.put(FIELD_ACCURACY, 4.5); values.put(FIELD_SPEED, 3.5); values.put(FIELD_BEARING, 90);
        return values;
    }
    private long count(String table) { return DatabaseUtils.queryNumEntries(store.getDatabase(true), table); }

    @Test public void collidingProjectIdsAndTimestampsKeepBothHistoriesAndSentFlags() throws Exception {
        File first = workspace(), second = workspace();
        try (SQLiteDatabase a = legacy(first); SQLiteDatabase b = legacy(second)) {
            a.insertOrThrow(TABLE_TRACKPOINTS, null, point(500, 1, 2));
            a.insertOrThrow(TABLE_TRACKPOINTS, null, point(500, 0, 3));
            b.insertOrThrow(TABLE_TRACKPOINTS, null, point(500, 0, 4));
            store.migrateWorkspace(first); store.migrateWorkspace(second);
            assertEquals(2, count(TABLE_TRACKS)); assertEquals(3, count(TABLE_TRACKPOINTS));
            assertEquals(1, store.migratedTrackId(first, 1));
            assertNotEquals(store.migratedTrackId(first, 1), store.migratedTrackId(second, 1));
            assertEquals(1, DatabaseUtils.longForQuery(store.getDatabase(true), "SELECT count(*) FROM trackpoints WHERE sent=1", null));
            try (Cursor rows = store.getDatabase(true).rawQuery("SELECT time,segment,accuracy,speed,bearing FROM trackpoints ORDER BY rowid", null)) {
                for (int segment : new int[]{2,3,4}) {
                    assertTrue(rows.moveToNext()); assertEquals(500, rows.getLong(0)); assertEquals(segment, rows.getInt(1));
                    assertEquals(4.5, rows.getDouble(2), 0); assertEquals(3.5, rows.getDouble(3), 0); assertEquals(90, rows.getInt(4));
                }
            }
            assertEquals(2, DatabaseUtils.queryNumEntries(a, TABLE_TRACKPOINTS));
            assertEquals(1, DatabaseUtils.queryNumEntries(b, TABLE_TRACKPOINTS));
            store.migrateWorkspace(first); store.migrateWorkspace(second);
            assertEquals(3, count(TABLE_TRACKPOINTS));
            // Deleted common history cannot reappear from retained rollback databases.
            store.getDatabase(false).delete(TABLE_TRACKPOINTS, null, null);
            store.getDatabase(false).delete(TABLE_TRACKS, null, null);
            store.migrateWorkspace(first); assertEquals(0, count(TABLE_TRACKS));
        }
    }

    @Test public void durableTailAndLostReplyMigrateOnceWithoutDeletingSourceFiles() throws Exception {
        File directory = workspace();
        try (SQLiteDatabase source = legacy(directory)) {
            PendingTrackPoints queue = new PendingTrackPoints(directory);
            queue.append(point(600, 0, 1)); queue.append(point(700, 0, 2));
            queue.replay((operation, values) -> {
                if (values.getAsLong(FIELD_TIMESTAMP) == 600L) {
                    long row = source.insertOrThrow(TABLE_TRACKPOINTS, null, values);
                    FeatureSaveJournal.record(source, TABLE_TRACKPOINTS, operation, row);
                }
                return 1;
            });
            store.migrateWorkspace(directory); assertEquals(2, count(TABLE_TRACKPOINTS));
            assertEquals(2, new PendingTrackPoints(directory).size());
            assertEquals(1, DatabaseUtils.queryNumEntries(source, TABLE_TRACKPOINTS));
            store.migrateWorkspace(directory); assertEquals(2, count(TABLE_TRACKPOINTS));
        }
    }

    @Test public void failedMigrationRollsBackAndRetryKeepsOriginalHistory() throws Exception {
        File directory = workspace();
        try (SQLiteDatabase source = legacy(directory)) {
            source.insertOrThrow(TABLE_TRACKPOINTS, null, point(100, 1, 0));
            ContentValues orphan = point(200, 0, 1); orphan.put(FIELD_SESSION, 99);
            source.insertOrThrow(TABLE_TRACKPOINTS, null, orphan);
            try { store.migrateWorkspace(directory); fail("Orphan must prevent completion"); }
            catch (android.database.sqlite.SQLiteException expected) { }
            assertEquals(0, count(TABLE_TRACKS)); assertEquals(0, count(TABLE_TRACKPOINTS));
            assertEquals(0, count("migrated_workspaces")); assertEquals(2, DatabaseUtils.queryNumEntries(source, TABLE_TRACKPOINTS));
            source.delete(TABLE_TRACKPOINTS, FIELD_SESSION + "=99", null);
            store.migrateWorkspace(directory); assertEquals(1, count(TABLE_TRACKPOINTS));
        }
    }

    @Test public void bootstrapCopyDoesNotImportTheSameHistoryAgain() throws Exception {
        File original = workspace(), copy = workspace();
        try (SQLiteDatabase source = legacy(original); SQLiteDatabase duplicate = legacy(copy)) {
            source.insertOrThrow(TABLE_TRACKPOINTS, null, point(100, 0, 0));
            duplicate.insertOrThrow(TABLE_TRACKPOINTS, null, point(100, 0, 0));
            store.migrateWorkspace(original);
            store.registerWorkspaceCopy(original, copy);
            store.migrateWorkspace(copy);
            assertEquals(1, count(TABLE_TRACKS)); assertEquals(1, count(TABLE_TRACKPOINTS));
            assertEquals(store.migratedTrackId(original,1), store.migratedTrackId(copy,1));
            assertEquals(1, DatabaseUtils.queryNumEntries(source,TABLE_TRACKPOINTS));
            assertEquals(1, DatabaseUtils.queryNumEntries(duplicate,TABLE_TRACKPOINTS));
        }
    }

    @Test public void corruptionInDurableTailCannotPublishPartialHistory() throws Exception {
        File directory = workspace();
        try (SQLiteDatabase source = legacy(directory)) {
            source.insertOrThrow(TABLE_TRACKPOINTS, null, point(100, 0, 0));
            PendingTrackPoints queue = new PendingTrackPoints(directory); queue.append(point(200,0,1));
            File file = new File(directory, "pending-track-points").listFiles()[0];
            java.nio.file.Files.write(file.toPath(), "broken".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try { store.migrateWorkspace(directory); fail("Corrupt spool must stop import"); }
            catch (java.io.IOException expected) { assertTrue(file.isFile()); }
            assertEquals(0, count(TABLE_TRACKPOINTS)); assertEquals(0, count("migrated_workspaces"));
        }
    }

    @Test public void oldSchemaConvertsWgs84AndSuppliesLegacyDefaults() throws Exception {
        File directory = workspace();
        try (SQLiteDatabase source = new DatabaseHelper(context, new File(directory, "layers"), null, 2).getWritableDatabase()) {
            source.execSQL("CREATE TABLE tracks(_id INTEGER PRIMARY KEY,name TEXT,start INTEGER,end INTEGER,visible INTEGER)");
            source.execSQL("CREATE TABLE trackpoints(lon REAL,lat REAL,time INTEGER,session INTEGER)");
            source.execSQL("INSERT INTO tracks VALUES(1,'old',100,200,1)");
            source.execSQL("INSERT INTO trackpoints VALUES(10,20,150,1)"); source.setVersion(2);
            store.migrateWorkspace(directory);
            try (Cursor rows = store.getDatabase(true).rawQuery("SELECT lon,lat,sent,segment FROM trackpoints", null)) {
                assertTrue(rows.moveToFirst()); assertEquals(1113194.9, rows.getDouble(0), 1);
                assertEquals(2273030.9, rows.getDouble(1), 1); assertEquals(1, rows.getInt(2)); assertEquals(0, rows.getInt(3));
            }
        }
    }

    @Test public void missingTrackTableCannotSilentlyCompleteMigrationWithOrphanPoints() throws Exception {
        File directory = workspace();
        try (SQLiteDatabase source = legacy(directory)) {
            source.insertOrThrow(TABLE_TRACKPOINTS, null, point(100, 0, 0));
            source.execSQL("DROP TABLE tracks");
            try { store.migrateWorkspace(directory); fail("History without its sessions must remain recoverable"); }
            catch (android.database.sqlite.SQLiteException expected) { }
            assertEquals(0, count("migrated_workspaces")); assertEquals(0, count(TABLE_TRACKPOINTS));
            assertEquals(1, android.database.DatabaseUtils.queryNumEntries(source, TABLE_TRACKPOINTS));
        }
    }

    @Test public void lateImportCannotReuseADeletedSessionReferencedByAnOldCheckpoint() throws Exception {
        File first = workspace(), later = workspace();
        try (SQLiteDatabase source = legacy(first); SQLiteDatabase second = legacy(later)) {
            store.migrateWorkspace(first);
            long oldSession = store.migratedTrackId(first, 1);
            store.getDatabase(false).delete(TABLE_TRACKS, FIELD_ID + "=?", new String[]{Long.toString(oldSession)});
            store.migrateWorkspace(later);
            assertTrue(store.migratedTrackId(later, 1) > oldSession);
            assertEquals(0, android.database.DatabaseUtils.longForQuery(store.getDatabase(true),
                    "SELECT count(*) FROM tracks WHERE _id=?", new String[]{Long.toString(oldSession)}));
        }
    }

    @Test public void realLayersDatabaseImportsFinishedHistoryBesideNewSharedTracks() throws Exception {
        File directory = workspace();
        try (SQLiteDatabase source = legacy(directory)) {
            source.insertOrThrow(TABLE_TRACKPOINTS, null, point(100, 1, 3));
            ContentValues track = new ContentValues(); track.put(FIELD_NAME, "Recorded after update");
            track.put(FIELD_START, 200); track.put(FIELD_END, 300); track.put(FIELD_COLOR, 456); track.put(FIELD_VISIBLE, 1);
            long current = store.getDatabase(false).insertOrThrow(TABLE_TRACKS, null, track);
            ContentValues fresh = point(250, 0, 0); fresh.put(FIELD_SESSION, current);
            store.getDatabase(false).insertOrThrow(TABLE_TRACKPOINTS, null, fresh);
            byte[] original = java.nio.file.Files.readAllBytes(new File(directory, "layers.db").toPath());
            store.migrateWorkspace(directory); store.migrateWorkspace(directory);
            assertEquals(2, count(TABLE_TRACKS)); assertEquals(2, count(TABLE_TRACKPOINTS));
            assertNotEquals(current, store.migratedTrackId(directory, 1));
            assertEquals(1, DatabaseUtils.longForQuery(store.getDatabase(true),
                    "SELECT count(*) FROM tracks WHERE _id=? AND color=456", new String[]{Long.toString(current)}));
            assertEquals(1, DatabaseUtils.longForQuery(store.getDatabase(true),
                    "SELECT count(*) FROM tracks WHERE _id=? AND end=900 AND color=123",
                    new String[]{Long.toString(store.migratedTrackId(directory, 1))}));
            assertArrayEquals(original, java.nio.file.Files.readAllBytes(new File(directory, "layers.db").toPath()));
        }
    }

    @Test public void interruptedImportRollsBackAndRetriesOnce() throws Exception {
        File directory = workspace();
        try (SQLiteDatabase source = legacy(directory)) {
            source.insertOrThrow(TABLE_TRACKPOINTS, null, point(100, 1, 0));
            source.insertOrThrow(TABLE_TRACKPOINTS, null, point(200, 0, 1));
            SQLiteDatabase target = store.getDatabase(false);
            target.execSQL("CREATE TRIGGER interrupt_track_migration BEFORE INSERT ON trackpoints WHEN NEW.time=200 "
                    + "BEGIN SELECT RAISE(ABORT,'interrupted update'); END");
            try {
                try { store.migrateWorkspace(directory); fail("Injected update failure must roll back"); }
                catch (android.database.sqlite.SQLiteException expected) { }
                for (String table : new String[]{TABLE_TRACKS,TABLE_TRACKPOINTS,"legacy_tracks","legacy_points","migrated_workspaces"})
                    assertEquals(table, 0, count(table));
                assertEquals(2, DatabaseUtils.queryNumEntries(source, TABLE_TRACKPOINTS));
            } finally { target.execSQL("DROP TRIGGER interrupt_track_migration"); }
            store.migrateWorkspace(directory); store.migrateWorkspace(directory);
            assertEquals(1, count(TABLE_TRACKS)); assertEquals(2, count(TABLE_TRACKPOINTS));
            assertEquals(1, count("migrated_workspaces"));
        }
    }

    @Test public void crashDuringAtomicCheckpointKeepsBackupAndNewFilesUntouched() throws Exception {
        File directory = workspace();
        try (SQLiteDatabase source = legacy(directory)) {
            source.execSQL("UPDATE tracks SET end=NULL WHERE _id=1");
            PendingTrackPoints queue = new PendingTrackPoints(directory);
            queue.append(point(100,0,0)); queue.append(point(200,0,1));
            queue.replay((operation, values) -> {
                if (values.getAsLong(FIELD_TIMESTAMP) == 100) {
                    long row = source.insertOrThrow(TABLE_TRACKPOINTS,null,values);
                    FeatureSaveJournal.record(source,TABLE_TRACKPOINTS,operation,row);
                }
                return 1;
            });
            File spool = new File(directory,"pending-track-points");
            File[] files = spool.listFiles(); assertNotNull(files);
            java.util.Arrays.sort(files, java.util.Comparator.comparing(File::getName));
            java.nio.file.Files.copy(files[0].toPath(),new File(files[0]+".bak").toPath());
            java.nio.file.Files.write(files[0].toPath(),"interrupted write".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.nio.file.Files.move(files[1].toPath(),new File(files[1]+".new").toPath());
            Map<String,byte[]> before = new java.util.TreeMap<>();
            for (File file : spool.listFiles()) before.put(file.getName(),java.nio.file.Files.readAllBytes(file.toPath()));
            store.migrateWorkspace(directory); store.migrateWorkspace(directory);
            assertEquals(2,count(TABLE_TRACKPOINTS));
            assertEquals(1,DatabaseUtils.longForQuery(store.getDatabase(true),"SELECT count(*) FROM tracks WHERE end IS NULL",null));
            assertEquals(before.size(),spool.listFiles().length);
            for (File file : spool.listFiles()) assertArrayEquals(file.getName(),before.get(file.getName()),java.nio.file.Files.readAllBytes(file.toPath()));
            assertEquals(1,DatabaseUtils.queryNumEntries(source,TABLE_TRACKPOINTS));
        }
    }

    @Test public void extensionlessExportRemainsRecoverable() throws Exception {
        File directory = workspace();
        try (SQLiteDatabase source = legacy(directory)) {
            source.insertOrThrow(TABLE_TRACKPOINTS,null,point(100,1,0));
        }
        java.nio.file.Files.move(new File(directory,"layers.db").toPath(),new File(directory,"layers").toPath());
        store.migrateWorkspace(directory);
        assertEquals(1,count(TABLE_TRACKS)); assertEquals(1,count(TABLE_TRACKPOINTS));
    }

    @Test public void missingSourceIsRetriedWhenTheRealDatabaseAppears() throws Exception {
        File directory=workspace(); store.migrateWorkspace(directory);
        assertEquals(0,count("migrated_workspaces"));
        try (SQLiteDatabase source=legacy(directory)) {
            source.insertOrThrow(TABLE_TRACKPOINTS,null,point(100,1,0));
            store.migrateWorkspace(directory);
            assertEquals(1,count(TABLE_TRACKPOINTS));
        }
    }
}

package com.nextgis.maplib.map;

import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import static com.nextgis.maplib.map.TrackLayer.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class LegacyTrackColorsTest {
    private Context context() {
        File root = new File(RuntimeEnvironment.getApplication().getCacheDir(), "colors-" + UUID.randomUUID());
        assertTrue(root.mkdirs());
        return new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return root; }
        };
    }

    private SQLiteDatabase versionOne(Context context) {
        File directory = new File(context.getFilesDir(), "shared-tracks");
        assertTrue(directory.mkdirs());
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(new File(directory, "tracks.db"), null);
        db.execSQL(DB_CREATE_TRACKS); db.execSQL(DB_CREATE_TRACKPOINTS);
        db.setVersion(1);
        for (int id = 1; id <= 25; id++) db.execSQL(
                "INSERT INTO tracks(_id,name,start,end,color,visible) VALUES(?, ?,100,900,NULL,?)",
                new Object[]{id, "Old " + id, id % 2});
        db.execSQL("INSERT INTO tracks(_id,name,start,end,color,visible) VALUES(26,'My color',200,NULL,?,1)",
                new Object[]{0xff5f789c});
        db.execSQL("INSERT INTO trackpoints(session,lon,lat,time,sent,segment) VALUES(1,1,2,300,0,3)");
        db.execSQL("INSERT INTO trackpoints(session,lon,lat,time,sent,segment) VALUES(26,3,4,400,1,4)");
        return db;
    }

    @Test public void upgradeDistributesEveryHueAndKeepsAllOtherMetadataAndPoints() {
        Context context = context();
        try (SQLiteDatabase ignored = versionOne(context)) { }
        SharedTrackStore store = SharedTrackStore.get(context);
        SQLiteDatabase db = store.getDatabase(false);
        assertEquals(2, db.getVersion());
        Set<Integer> firstCycle = new HashSet<>();
        try (Cursor rows = db.rawQuery("SELECT _id,name,start,end,color,visible FROM tracks ORDER BY _id", null)) {
            for (int id = 1; id <= 25; id++) {
                assertTrue(rows.moveToNext()); assertEquals(id, rows.getLong(0));
                assertEquals("Old " + id, rows.getString(1)); assertEquals(100, rows.getLong(2));
                assertEquals(900, rows.getLong(3)); assertFalse(rows.isNull(4));
                assertEquals(id % 2, rows.getInt(5));
                if (id <= 12) firstCycle.add(rows.getInt(4));
            }
            assertTrue(rows.moveToNext()); assertEquals(0xff5f789c, rows.getInt(4)); assertTrue(rows.isNull(3));
            assertFalse(rows.moveToNext());
        }
        assertEquals(12, firstCycle.size());
        assertEquals(1, DatabaseUtils.longForQuery(db,
                "SELECT count(*) FROM trackpoints WHERE session=1 AND lon=1 AND lat=2 AND time=300 AND sent=0 AND segment=3", null));
        assertEquals(1, DatabaseUtils.longForQuery(db,
                "SELECT count(*) FROM trackpoints WHERE session=26 AND lon=3 AND lat=4 AND time=400 AND sent=1 AND segment=4", null));
        int saved = (int) DatabaseUtils.longForQuery(db,"SELECT color FROM tracks WHERE _id=1",null);
        assertEquals(saved, DatabaseUtils.longForQuery(store.getDatabase(true),"SELECT color FROM tracks WHERE _id=1",null));
        db.execSQL("UPDATE tracks SET color=123 WHERE _id=1");
        assertEquals(123, DatabaseUtils.longForQuery(store.getDatabase(false),"SELECT color FROM tracks WHERE _id=1",null));
    }

    @Test public void failedUpgradeRollsBackColorsAndVersionAndCanRetry() {
        Context context = context();
        try (SQLiteDatabase db = versionOne(context)) {
            db.execSQL("CREATE TRIGGER reject_color BEFORE UPDATE OF color ON tracks WHEN OLD._id=8 "
                    + "BEGIN SELECT RAISE(ABORT,'test failure'); END");
        }
        SharedTrackStore store = SharedTrackStore.get(context);
        try { store.getDatabase(false); fail("Upgrade must not silently partially color history"); }
        catch (android.database.sqlite.SQLiteException expected) { }
        File path = new File(context.getFilesDir(), "shared-tracks/tracks.db");
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(path.getPath(),null,SQLiteDatabase.OPEN_READWRITE)) {
            assertEquals(1, db.getVersion());
            assertEquals(25, DatabaseUtils.longForQuery(db,"SELECT count(*) FROM tracks WHERE color IS NULL",null));
            assertEquals(2, DatabaseUtils.queryNumEntries(db,TABLE_TRACKPOINTS));
            db.execSQL("DROP TRIGGER reject_color");
        }
        assertEquals(2, store.getDatabase(false).getVersion());
        assertEquals(0, DatabaseUtils.longForQuery(store.getDatabase(true),"SELECT count(*) FROM tracks WHERE color IS NULL",null));
    }
}

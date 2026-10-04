package com.nextgis.maplib.reliability;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

import com.nextgis.maplib.api.IGISApplication;
import com.nextgis.maplib.map.MapBase;
import com.nextgis.maplib.map.MapContentProviderHelper;
import com.nextgis.maplib.map.TrackLayer;
import com.nextgis.maplib.util.FeatureSaveJournal;
import com.nextgis.maplib.util.PendingTrackPoints;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;

import static org.junit.Assert.*;

/** Native SQLite plus actual durable files: failed writes, lost replies and process recovery. */
@RunWith(RobolectricTestRunner.class)
@Config(application=TestGISApplication.class, sdk={26,36})
public class TrackPersistenceTest {
    private MapBase previous;
    private TestMap map;
    private TrackLayer layer;
    private SQLiteDatabase db;
    private File root;
    private PendingTrackPoints queue;
    private long session;
    private final Uri points = Uri.parse("content://test.reliability/trackpoints");

    private static class TestMap extends MapContentProviderHelper {
        TestMap(Context app, File path) { super(app, path, ((IGISApplication) app).getLayerFactory()); }
        static void restore(MapBase map) { mInstance = map; }
    }

    @Before public void setUp() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        try { previous = MapBase.getInstance(); } catch (IllegalArgumentException ignored) { }
        root = new File(app.getCacheDir(), "track-reliability-" + UUID.randomUUID());
        assertTrue(root.mkdirs());
        map = new TestMap(app, new File(root, "test.ngm"));
        layer = new TrackLayer(app, new File(root, "tracks"));
        map.addLayer(layer);
        db = map.getDatabase(false);
        ContentValues track = new ContentValues();
        track.put(TrackLayer.FIELD_NAME, "test");
        track.put(TrackLayer.FIELD_START, 1L);
        track.put(TrackLayer.FIELD_VISIBLE, 1);
        session = android.content.ContentUris.parseId(layer.insert(
                Uri.parse("content://test.reliability/tracks"), track));
        queue = new PendingTrackPoints(root);
    }

    @After public void tearDown() {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        TestMap.restore(previous);
        if (db != null) db.close();
    }

    private ContentValues point(long time, int segment) {
        ContentValues values = new ContentValues();
        values.put(TrackLayer.FIELD_SESSION, session);
        values.put(TrackLayer.FIELD_TIMESTAMP, time);
        values.put(TrackLayer.FIELD_SEGMENT, segment);
        values.put(TrackLayer.FIELD_LON, time + .125);
        values.put(TrackLayer.FIELD_LAT, -time - .25);
        values.put(TrackLayer.FIELD_SENT, 0);
        return values;
    }

    private PendingTrackPoints.Writer writer() {
        return new PendingTrackPoints.Writer() {
            public long insert(String operation, ContentValues values) {
                return android.content.ContentUris.parseId(layer.insert(points.buildUpon()
                        .appendQueryParameter(FeatureSaveJournal.URI_PARAMETER, operation).build(), values));
            }
            public void acknowledged(String operation) {
                FeatureSaveJournal.forget(db, TrackLayer.TABLE_TRACKPOINTS, operation);
            }
        };
    }

    @Test public void failedTailSurvivesRestartAndRetainsOrderAndSegments() throws Exception {
        queue.append(point(10, 0));
        queue.append(point(20, 0));
        queue.append(point(30, 1));
        db.execSQL("CREATE TRIGGER fail_point BEFORE INSERT ON trackpoints "
                + "WHEN NEW.time=20 BEGIN SELECT RAISE(ABORT, 'injected failure'); END");
        PendingTrackPoints.DrainResult failed = queue.drain(writer());
        assertNotNull(failed.failure);
        assertEquals(1, failed.count);
        assertEquals(2, queue.size());
        assertEquals(1, DatabaseUtils.queryNumEntries(db, "trackpoints"));
        queue = new PendingTrackPoints(root); // simulate a cold process; RAM is gone
        db.execSQL("DROP TRIGGER fail_point");
        PendingTrackPoints.DrainResult saved = queue.drain(writer());
        assertNull(saved.failure);
        assertEquals(2, saved.count);
        assertEquals(0, queue.size());
        try (Cursor cursor = db.query("trackpoints", null, null, null, null, null, "rowid ASC")) {
            for (int i=0; i<3; i++) {
                assertTrue(cursor.moveToNext());
                assertEquals((i+1)*10, cursor.getLong(cursor.getColumnIndexOrThrow("time")));
                assertEquals(i == 2 ? 1 : 0, cursor.getInt(cursor.getColumnIndexOrThrow("segment")));
                assertEquals(session, cursor.getLong(cursor.getColumnIndexOrThrow("session")));
                assertEquals((i+1)*10+.125, cursor.getDouble(cursor.getColumnIndexOrThrow("lon")), 0);
            }
            assertFalse(cursor.moveToNext());
        }
    }

    @Test public void lostCommittedReplyDoesNotDuplicatePointAfterRestart() throws Exception {
        queue.append(point(10, 0));
        PendingTrackPoints.Writer normal = writer();
        PendingTrackPoints.DrainResult lost = queue.drain(new PendingTrackPoints.Writer() {
            public long insert(String operation, ContentValues values) {
                normal.insert(operation, values);
                throw new IllegalStateException("injected lost reply after commit");
            }
            public void acknowledged(String operation) { fail("No acknowledgement received"); }
        });
        assertNotNull(lost.failure);
        assertEquals(1, queue.size());
        queue = new PendingTrackPoints(root);
        assertNull(queue.drain(normal).failure);
        assertEquals(1, DatabaseUtils.queryNumEntries(db, "trackpoints"));
    }

    @Test public void activeProjectChangeCannotRedirectPendingPoints() throws Exception {
        queue.append(point(10, 0));
        TestMap other = new TestMap(layer.getContext(), new File(root, "other/other.ngm"));
        try {
            assertNull(queue.drain(writer()).failure);
            assertEquals(1, DatabaseUtils.queryNumEntries(db, "trackpoints"));
        } finally { other.getDatabase(false).close(); }
    }

    @Test public void missingSessionCannotAcknowledgeOrDiscardPendingPoint() throws Exception {
        queue.append(point(10, 0));
        db.delete("tracks", null, null);
        assertNotNull(queue.drain(writer()).failure);
        assertEquals(1, new PendingTrackPoints(root).size());
        assertEquals(0, DatabaseUtils.queryNumEntries(db, "trackpoints"));
    }

    @Test public void corruptCheckpointIsRetainedAndBlocksSilentRecovery() throws Exception {
        queue.append(point(10, 0));
        File[] files = new File(root, "pending-track-points").listFiles();
        assertNotNull(files);
        Files.write(files[0].toPath(), "broken".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try { new PendingTrackPoints(root); fail("Corruption must be explicit"); }
        catch (IOException expected) { assertTrue(files[0].exists()); }
    }

    @Test public void atomicBackupFileIsRecoveredWithoutLosingThePoint() throws Exception {
        queue.append(point(10, 0));
        File file = new File(root, "pending-track-points").listFiles()[0];
        Files.move(file.toPath(), new File(file + ".bak").toPath());
        PendingTrackPoints recovered = new PendingTrackPoints(root);
        assertEquals(1, recovered.size());
        assertNull(recovered.drain(writer()).failure);
        assertEquals(1, DatabaseUtils.queryNumEntries(db, "trackpoints"));
    }
}

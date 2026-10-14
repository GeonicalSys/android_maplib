package com.nextgis.maplib.reliability;

import android.content.ContentValues;
import android.content.Context;
import com.nextgis.maplib.datasource.Field;
import com.nextgis.maplib.datasource.GeoPoint;
import com.nextgis.maplib.datasource.ngw.Connection;
import com.nextgis.maplib.map.*;
import com.nextgis.maplib.util.*;
import java.io.File;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = LocalChangesCommitTest.App.class, sdk = 28)
public class LocalChangesCommitTest {
    public static class App extends TestGISApplication {
        final List<MapBase> notifications = new ArrayList<>();
        @Override public void onLocalChangesCommitted(MapBase map) { notifications.add(map); }
    }
    static class TestMap extends MapContentProviderHelper {
        TestMap(Context app, File file) { super(app, file, null, false); }
        void dispose() { closeSyncWorkspace(); }
    }
    static class TestLayer extends NGWVectorLayer {
        TestLayer(Context context, File path) { super(context, path); mNGWLayerType = Connection.NGWResourceTypePostgisLayer; }
    }
    static class Fixture implements AutoCloseable {
        final App app = (App) RuntimeEnvironment.getApplication();
        final TestMap map;
        final TestLayer layer;
        final byte[] geometry;
        Fixture() throws Exception {
            File root = new File(app.getCacheDir(), "local-commit-" + UUID.randomUUID());
            assertTrue(root.mkdirs());
            map = new TestMap(app, new File(root, "map.ngm"));
            layer = new TestLayer(app, new File(root, "field_points"));
            map.addLayer(layer); layer.beginBulkImport();
            layer.create(GeoConstants.GTPoint, Collections.singletonList(new Field(GeoConstants.FTString, "name", "Name")));
            layer.endBulkImport(); layer.setSyncType(Constants.SYNC_ALL);
            app.notifications.clear();
            geometry = new GeoPoint(1, 2).toBlob();
        }
        long insert() {
            ContentValues row = new ContentValues();
            row.put(Constants.FIELD_GEOM, geometry); row.put("name", "saved");
            return layer.insertAddChanges(row);
        }
        @Override public void close() { map.dispose(); }
    }
    @Test public void deliveryIsNotPublishedBeforeCommitAndUsesOwningBackgroundMap() throws Exception {
        try (Fixture f = new Fixture()) {
            LayerDatabaseTransaction.run(f.map.getDatabase(false), () -> {
                assertTrue(f.insert() >= 0);
                assertTrue(f.app.notifications.isEmpty());
                return null;
            });
            assertEquals(Collections.singletonList(f.map), f.app.notifications);
            assertEquals(1, FeatureChanges.getChangeCount(f.map.getDatabase(true), f.layer.getChangeTableName()));
        }
    }
    @Test public void rolledBackFeatureAndOutboxNeverScheduleDelivery() throws Exception {
        try (Fixture f = new Fixture()) {
            try {
                LayerDatabaseTransaction.run(f.map.getDatabase(false), () -> { f.insert(); throw new IllegalStateException("rollback"); });
                fail("The transaction must roll back");
            } catch (IllegalStateException expected) { }
            assertTrue(f.app.notifications.isEmpty());
            assertEquals(0, FeatureChanges.getChangeCount(f.map.getDatabase(true), f.layer.getChangeTableName()));
        }
    }
    @Test public void draftsAreExcludedAndRemovingSaveFlagsSchedulesDelivery() throws Exception {
        try (Fixture f = new Fixture()) {
            long id = f.insert(); f.layer.setFeatureTempFlag(id, true); f.layer.setFeatureNotSyncFlag(id, true);
            assertEquals(0, FeatureChanges.getChangeCount(f.map.getDatabase(true), f.layer.getChangeTableName()));
            f.app.notifications.clear();
            LayerDatabaseTransaction.run(f.map.getDatabase(false), () -> {
                f.layer.setFeatureTempFlag(id, false); f.layer.setFeatureNotSyncFlag(id, false);
                assertTrue(f.app.notifications.isEmpty()); return null;
            });
            assertFalse(f.app.notifications.isEmpty());
            for (MapBase owner : f.app.notifications) assertSame(f.map, owner);
            assertEquals(1, FeatureChanges.getChangeCount(f.map.getDatabase(true), f.layer.getChangeTableName()));
        }
    }
    @Test public void separateAttachmentAndWholeFeatureFlagsHoldTheAttachmentChange() throws Exception {
        try (Fixture f = new Fixture()) {
            FeatureChanges.add(f.map.getDatabase(false), f.layer.getChangeTableName(), 42, 10, Constants.CHANGE_OPERATION_NEW);
            f.layer.setAttachTempFlag(42, 10, true);
            assertEquals(0, FeatureChanges.getChangeCount(f.map.getDatabase(true), f.layer.getChangeTableName()));
            assertEquals(1, f.layer.setAttachTempFlag(42, 10, false));
            assertEquals(1, FeatureChanges.getChangeCount(f.map.getDatabase(true), f.layer.getChangeTableName()));
            f.layer.setFeatureNotSyncFlag(42, true);
            assertEquals(0, FeatureChanges.getChangeCount(f.map.getDatabase(true), f.layer.getChangeTableName()));
            assertEquals(1, f.layer.setFeatureNotSyncFlag(42, false));
            assertEquals(1, FeatureChanges.getChangeCount(f.map.getDatabase(true), f.layer.getChangeTableName()));
            try (android.database.Cursor cursor = FeatureChanges.getFirstChangeFromRecordId(
                    f.map.getDatabase(true), f.layer.getChangeTableName(), 0)) {
                assertTrue(cursor.moveToFirst());
                assertEquals(10, cursor.getLong(cursor.getColumnIndexOrThrow(Constants.FIELD_ATTACH_ID)));
            }
        }
    }
}

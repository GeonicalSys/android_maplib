package com.nextgis.maplib.map;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.nextgis.maplib.api.IGeometryCacheItem;
import com.nextgis.maplib.api.IProgressor;
import java.nio.file.Files;
import com.nextgis.maplib.datasource.Feature;
import com.nextgis.maplib.datasource.GeoPoint;
import com.nextgis.maplib.datasource.GeometryRTree;
import com.nextgis.maplib.reliability.TestGISApplication;
import com.nextgis.maplib.util.Constants;
import com.nextgis.maplib.util.GeoConstants;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/**
 * Actual create/batch/commit/cache API regression in the technical SQLite unit model.
 * Reuses the existing SqliteVectorSpatialQueryTest technical GeoPoint(1000,1000) shape;
 * no invented business fixture or real GPX/native paint/process-restart acceptance.
 * TemporaryFolder follows java.io.tmpdir; the developer test command binds it to Q.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=TestGISApplication.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class LocalVectorBulkCacheReadinessTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();

    @Test public void committedNewBatchIsNativeReadyBeforeAnyCountPropertiesOrReload() throws Exception {
        try(Fixture f=new Fixture(folder.newFolder())) {
            f.batch(true);
            Set<Long> expected=f.sqlIds();assertFalse(expected.isEmpty());
            assertEquals(expected,new TreeSet<>(f.layer.query(null)));
            assertNativeReady(f,expected);
        }
    }
    @Test public void successfulFinalSavePersistsTheCompleteIndexBeforeAnyRepair() throws Exception {
        try(Fixture f=new Fixture(folder.newFolder())) {
            f.batch(true);assertTrue(f.layer.save());
            File index=new File(f.layer.getPath(),VectorLayer.RTREE);
            assertTrue("Successful import must persist its complete RTree without properties/rebuild",index.isFile());
            GeometryRTree restored=new GeometryRTree();restored.load(index);
            Set<Long> persisted=new TreeSet<>();
            for(IGeometryCacheItem item:restored.getAll())persisted.add(item.getFeatureId());
            assertEquals(f.sqlIds(),persisted);
        }
    }
    @Test public void getCountAloneIsASeparateRecoveryControlWithoutManualRebuild() throws Exception {
        try(Fixture f=new Fixture(folder.newFolder())) {
            f.batch(true);Set<Long> expected=f.sqlIds();
            assertEquals(expected.size(),f.layer.getCount());
            assertNativeReady(f,expected);
        }
    }
    @Test public void freshFromJsonRecoversAMissingIndexSeparatelyFromARealProcessRestart() throws Exception {
        try(Fixture f=new Fixture(folder.newFolder())) {
            f.batch(true);
            org.json.JSONObject config=f.layer.toJSON();
            File index=new File(f.layer.getPath(),VectorLayer.RTREE);
            // A deliberately missing derived index is a technical source-load control.
            if(index.exists())assertTrue(index.delete());
            VectorLayer fresh=new VectorLayer(RuntimeEnvironment.getApplication(),f.layer.getPath());
            fresh.setParent(f.map);fresh.fromJSON(config);
            List<Long> actual=fresh.queryForNativeRender(null);
            assertEquals(f.sqlIds(),new TreeSet<>(actual));assertTrue(fresh.mCacheLoaded);
        }
    }
    @Test public void activeBulkStillRejectsNativeReadsEvenForAnInitiallyEmptyLayer() throws Exception {
        try(Fixture f=new Fixture(folder.newFolder())) {
            f.layer.beginBulkImport();
            try {
                assertThrows(SqliteVectorTileBackend.RetryableTileException.class,
                        ()->f.layer.queryForNativeRender(null));
            } finally {f.layer.endBulkImport();}
        }
    }
    @Test public void rolledBackBatchCannotBecomeASuccessfulNativeSnapshot() throws Exception {
        try(Fixture f=new Fixture(folder.newFolder())) {
            f.batch(false);
            assertTrue(f.sqlIds().isEmpty());assertFalse(f.layer.query(null).isEmpty());
            assertThrows(SqliteVectorTileBackend.RetryableTileException.class,
                    ()->f.layer.queryForNativeRender(null));
        }
    }
    @Test public void createInsideTheActiveBulkTransactionHasTheSameReadyResult() throws Exception {
        try(Fixture f=new Fixture(folder.newFolder(),false)) {
            f.batch(true,true);
            assertNativeReady(f,f.sqlIds());
        }
    }
    @Test public void createOverAnExistingNonemptyTableDoesNotAdmitAnEmptyLiveIndex() throws Exception {
        try(Fixture f=new Fixture(folder.newFolder())) {
            f.batch(true);
            VectorLayer fresh=new VectorLayer(RuntimeEnvironment.getApplication(),f.layer.getPath());
            fresh.setParent(f.map);fresh.create(GeoConstants.GTPoint,new ArrayList<>());
            assertFalse(fresh.mCacheLoaded);
            assertThrows(SqliteVectorTileBackend.RetryableTileException.class,
                    ()->fresh.queryForNativeRender(null));
        }
    }
    @Test public void createCannotAdmitOrPersistACanceledNonemptyRebuild() throws Exception {
        try(Fixture f=new Fixture(folder.newFolder())) {
            f.batch(true);f.layer.rebuildCache(null);
            File index=new File(f.layer.getPath(),VectorLayer.RTREE);
            byte[] complete=Files.readAllBytes(index.toPath());
            f.layer.rebuildCache(new IProgressor() {
                public void setMax(int value){} public void setValue(int value){}
                public void setIndeterminate(boolean value){} public void setMessage(String value){}
                public boolean isCanceled(){return true;}
            });
            assertFalse(f.layer.mCacheLoaded);
            f.layer.create(GeoConstants.GTPoint,new ArrayList<>());
            assertFalse(f.layer.mCacheLoaded);
            assertThrows(SqliteVectorTileBackend.RetryableTileException.class,
                    ()->f.layer.queryForNativeRender(null));
            assertArrayEquals(complete,Files.readAllBytes(index.toPath()));
        }
    }
    @Test public void unavailableTableCountDoesNotTurnAnEmptyCacheIntoReady() throws Exception {
        try(Fixture f=new Fixture(folder.newFolder())) {
            VectorLayer unavailable=new VectorLayer(RuntimeEnvironment.getApplication(),
                    new File(f.layer.getPath().getParentFile(),"count_unavailable_points")) {
                @Override public int getSqliteTableRowCount(){return -1;}
            };
            unavailable.setParent(f.map);unavailable.create(GeoConstants.GTPoint,new ArrayList<>());
            assertFalse(unavailable.mCacheLoaded);
            assertThrows(SqliteVectorTileBackend.RetryableTileException.class,
                    ()->unavailable.queryForNativeRender(null));
        }
    }
    @Test public void reopenedLayerRejectsAHeaderCountWithoutEntriesAndRebuildsFromDb() throws Exception {
        try(Fixture f=new Fixture(folder.newFolder())) {
            f.batch(true);org.json.JSONObject config=f.layer.toJSON();
            File index=new File(f.layer.getPath(),VectorLayer.RTREE);
            try(java.io.DataOutputStream out=new java.io.DataOutputStream(new java.io.FileOutputStream(index))) {
                out.writeInt(8);out.writeInt(2);out.writeInt(f.sqlIds().size());
                out.writeBoolean(true);out.writeBoolean(true);
                out.writeDouble(0);out.writeDouble(0);out.writeDouble(0);out.writeDouble(0);
                out.writeInt(0);
            }
            VectorLayer fresh=new VectorLayer(RuntimeEnvironment.getApplication(),f.layer.getPath());
            fresh.setParent(f.map);fresh.fromJSON(config);
            assertEquals(f.sqlIds(),new TreeSet<>(fresh.queryForNativeRender(null)));
            assertTrue(fresh.mCacheLoaded);
        }
    }
    @Test public void ngwPublicCreateAndRemoteIdBatchUseTheSameReadyLifecycle() throws Exception {
        try(Fixture f=new Fixture(folder.newFolder())) {
            NGWVectorLayer ngw=new NGWVectorLayer(RuntimeEnvironment.getApplication(),
                    new File(f.layer.getPath().getParentFile(),"ngw_bulk_points"));
            ngw.setParent(f.map);
            ngw.mNGWLayerType=com.nextgis.maplib.datasource.ngw.Connection.NGWResourceTypeVectorLayer;
            ngw.mNgwVersionMajor=Constants.NGW_v3;
            ngw.create(GeoConstants.GTPoint,new ArrayList<>());
            SQLiteDatabase database=f.map.getDatabase(false);ngw.beginBulkImport();database.beginTransaction();
            try {
                Feature feature=new Feature(1,ngw.getFields());feature.setGeometry(new GeoPoint(1000,1000));
                ngw.createFeatureBatch(feature,database,false);database.setTransactionSuccessful();
            } finally {database.endTransaction();ngw.endBulkImport();}
            assertTrue(ngw.save());assertTrue(SqliteVectorTileProvider.canServe(ngw));
            Set<Long> expected=new TreeSet<>();
            try(Cursor rows=database.rawQuery("SELECT _id FROM ngw_bulk_points ORDER BY _id",null)) {
                while(rows.moveToNext())expected.add(rows.getLong(0));
            }
            SqliteVectorTileBackend.RetryableTileException blocked=null;List<Long> actual=null;
            try {actual=ngw.queryForNativeRender(null);}
            catch(SqliteVectorTileBackend.RetryableTileException failure){blocked=failure;}
            assertNull("NGW public common-create/batch lifecycle must not require properties/rebuild",blocked);
            assertEquals(expected,new TreeSet<>(actual));
            GeometryRTree persisted=new GeometryRTree();persisted.load(new File(ngw.getPath(),VectorLayer.RTREE));
            Set<Long> stored=new TreeSet<>();
            for(IGeometryCacheItem item:persisted.getAll())stored.add(item.getFeatureId());
            assertEquals(expected,stored);
        }
    }
    private static void assertNativeReady(Fixture fixture,Set<Long> expected) throws Exception {
        SqliteVectorTileBackend.RetryableTileException blocked=null;List<Long> actual=null;
        try {actual=fixture.layer.queryForNativeRender(null);}
        catch(SqliteVectorTileBackend.RetryableTileException failure) {blocked=failure;}
        assertNull("Committed complete local batch must not await an unrelated count/properties call",blocked);
        assertEquals(expected,new TreeSet<>(actual));
    }
    private static final class TestMap extends MapContentProviderHelper {
        TestMap(Context context,File file){super(context,file,null,false);}
        void dispose(){closeSyncWorkspace();}
    }
    private static final class Fixture implements AutoCloseable {
        final TestMap map;final VectorLayer layer;
        Fixture(File root) throws Exception {this(root,true);}
        Fixture(File root,boolean createBeforeBulk) throws Exception {
            Context context=RuntimeEnvironment.getApplication();map=new TestMap(context,new File(root,"map.ngm"));
            layer=new VectorLayer(context,new File(root,"local_bulk_points"));map.addLayer(layer);
            layer.setName("Technical local batch");
            if(createBeforeBulk)layer.create(GeoConstants.GTPoint,new ArrayList<>());
        }
        void batch(boolean commit) throws Exception {batch(commit,false);}
        void batch(boolean commit,boolean createInsideBulk) throws Exception {
            SQLiteDatabase database=map.getDatabase(false);layer.beginBulkImport();database.beginTransaction();
            try {
                if(createInsideBulk)layer.create(GeoConstants.GTPoint,new ArrayList<>());
                Feature feature=new Feature(Constants.NOT_FOUND,layer.getFields());
                feature.setGeometry(new GeoPoint(1000,1000));
                layer.createFeatureBatch(feature,database,true);
                if(commit)database.setTransactionSuccessful();
            } finally {
                database.endTransaction();layer.endBulkImport();
            }
            if(commit)assertTrue(layer.save());
        }
        Set<Long> sqlIds() {
            Set<Long> result=new TreeSet<>();
            try(Cursor rows=map.getDatabase(true).rawQuery("SELECT _id FROM local_bulk_points ORDER BY _id",null)) {
                while(rows.moveToNext())result.add(rows.getLong(0));
            }
            return result;
        }
        public void close(){map.dispose();}
    }
}

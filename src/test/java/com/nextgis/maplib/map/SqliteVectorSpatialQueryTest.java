package com.nextgis.maplib.map;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import com.nextgis.maplib.api.IProgressor;
import com.nextgis.maplib.datasource.GeoPoint;
import com.nextgis.maplib.reliability.TestGISApplication;
import com.nextgis.maplib.util.Constants;
import com.nextgis.maplib.util.GeoConstants;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Actual SQLite/RTree producer races; no SDK/native PBF BLOB-descriptor emulation is required. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=TestGISApplication.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class SqliteVectorSpatialQueryTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();

    @Test public void pausedRealRebuildRejectsPartialPbfAndAReaderAlreadyWaitingGetsTheCompleteSqlIds() throws Exception {
        try(Fixture f=new Fixture(folder.newFolder(),16)){
            SqliteVectorTileProvider provider=new SqliteVectorTileProvider(f.layer,1,()->1,null);
            Set<Long> expected=f.sqlIds();long version=f.layer.renderContentVersion();
            Field lockField=VectorLayer.class.getDeclaredField("mSpatialCacheLock");lockField.setAccessible(true);Object lock=lockField.get(f.layer);
            CountDownLatch owns=new CountDownLatch(1),start=new CountDownLatch(1),partial=new CountDownLatch(1),finish=new CountDownLatch(1);
            AtomicReference<Throwable> failure=new AtomicReference<>();AtomicReference<List<Long>> read=new AtomicReference<>();
            Thread writer=new Thread(()->{try{synchronized(lock){owns.countDown();await(start);f.layer.rebuildCache(new Progress(){
                @Override public void setValue(int value){if(value==1){partial.countDown();await(finish);}}
            });}}catch(Throwable error){failure.set(error);}},"TechnicalRTreeRebuild");
            Thread reader=new Thread(()->{try{read.set(f.layer.queryForNativeRender(null));}catch(Throwable error){failure.set(error);}},"TechnicalRTreeReader");
            writer.start();
            try{
                assertTrue(owns.await(2,TimeUnit.SECONDS));reader.start();awaitBlocked(reader);
                start.countDown();assertTrue(partial.await(2,TimeUnit.SECONDS));
                try{provider.load(provider.snapshot(0,0,0),()->true);fail("Partial index became a successful PBF");}
                catch(SqliteVectorTileBackend.RetryableTileException expectedRetry){assertEquals("spatial_index_not_ready",expectedRetry.getMessage());}
            }finally{start.countDown();finish.countDown();writer.join(5000);reader.join(5000);provider.close();}
            assertFalse(writer.isAlive());assertFalse(reader.isAlive());assertNull(failure.get());
            assertEquals(expected,new TreeSet<>(read.get()));assertEquals(expected,f.sqlIds());
            assertEquals("Pure cache maintenance must not depend on a DB/content event",version,f.layer.renderContentVersion());
        }
    }
    @Test public void canceledRebuildDoesNotMarkOrPersistAPartialIndexAsReady() throws Exception {
        try(Fixture f=new Fixture(folder.newFolder(),16)){
            File index=new File(f.layer.getPath(),VectorLayer.RTREE);byte[] complete=Files.readAllBytes(index.toPath());
            f.layer.rebuildCache(new Progress(){int processed;
                @Override public void setValue(int value){processed=value;}
                @Override public boolean isCanceled(){return processed>=1;}
            });
            assertFalse(f.layer.mCacheLoaded);
            try{f.layer.queryForNativeRender(null);fail("Canceled partial index was accepted");}
            catch(SqliteVectorTileBackend.RetryableTileException expected){}
            f.layer.save();assertArrayEquals("A later config save must not publish partial RTree bytes",complete,Files.readAllBytes(index.toPath()));
            f.layer.rebuildCache(null);assertEquals(f.sqlIds(),new TreeSet<>(f.layer.queryForNativeRender(null)));
        }
    }
    @Test public void actualAfterCommitGenerationInvalidatesAnOldProviderBeforeTheUiNotification() throws Exception {
        try(Fixture f=new Fixture(folder.newFolder(),2)){
            MapBase previous=MapBase.mInstance;MapBase.mInstance=f.map;
            try{
                SqliteVectorTileProvider provider=new SqliteVectorTileProvider(f.layer,1,()->1,null);
                long render=f.layer.renderContentVersion(),data=f.layer.getDataGeneration();
                ContentValues row=new ContentValues();row.put(Constants.FIELD_GEOM,new GeoPoint(9000,1000).toBlob());
                assertTrue(f.layer.insertAddChanges(row)>=0);
                assertTrue(f.layer.getDataGeneration()>data);
                assertEquals("The active map broadcast has not run on the paused UI looper",render,f.layer.renderContentVersion());
                assertFalse(provider.isCurrent());provider.close();
            }finally{MapBase.mInstance=previous;}
        }
    }
    @Test public void newlyCreatedEmptySqliteLayerHasALegitimateCompleteEmptySnapshot() throws Exception {
        try(Fixture f=new Fixture(folder.newFolder(),0)){
            assertTrue(f.layer.queryForNativeRender(null).isEmpty());assertTrue(f.layer.mCacheLoaded);
        }
    }

    private static class Progress implements IProgressor {
        public void setMax(int max){}public boolean isCanceled(){return false;}public void setValue(int value){}
        public void setIndeterminate(boolean value){}public void setMessage(String value){}
    }
    private static void await(CountDownLatch latch){try{if(!latch.await(5,TimeUnit.SECONDS))throw new AssertionError("Technical latch timeout");}
        catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}}
    private static void awaitBlocked(Thread thread)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(thread.getState()!=Thread.State.BLOCKED && System.nanoTime()<until)Thread.sleep(1);
        assertEquals(Thread.State.BLOCKED,thread.getState());}
    private static final class TestMap extends MapContentProviderHelper {
        TestMap(Context context,File file){super(context,file,null,false);}void dispose(){closeSyncWorkspace();}
    }
    private static final class Fixture implements AutoCloseable {
        final TestMap map;final VectorLayer layer;
        Fixture(File root,int count)throws Exception{
            Context app=RuntimeEnvironment.getApplication();map=new TestMap(app,new File(root,"map.ngm"));
            layer=new VectorLayer(app,new File(root,"native_spatial_points"));map.addLayer(layer);
            layer.create(GeoConstants.GTPoint,new ArrayList<>());
            for(long id=1;id<=count;id++){
                // Controlled technical inputs precede the scenario; the actual rebuild producer reads SQLite.
                ContentValues row=new ContentValues();row.put(Constants.FIELD_ID,id);
                row.put(Constants.FIELD_GEOM,new GeoPoint(id*1000,1000).toBlob());
                assertEquals(id,map.getDatabase(false).insertOrThrow("native_spatial_points",null,row));
            }
            if(count>0)layer.rebuildCache(null);
        }
        Set<Long> sqlIds(){Set<Long> ids=new TreeSet<>();try(Cursor cursor=map.getDatabase(true).rawQuery(
                "SELECT _id FROM native_spatial_points ORDER BY _id",null)){while(cursor.moveToNext())ids.add(cursor.getLong(0));}return ids;}
        public void close(){map.dispose();}
    }
}

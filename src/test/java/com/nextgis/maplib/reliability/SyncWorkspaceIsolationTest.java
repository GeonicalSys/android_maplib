package com.nextgis.maplib.reliability;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import com.nextgis.maplib.datasource.Field;
import com.nextgis.maplib.datasource.GeoPoint;
import com.nextgis.maplib.datasource.LayerContentProvider;
import com.nextgis.maplib.map.MapBase;
import com.nextgis.maplib.map.MapContentProviderHelper;
import com.nextgis.maplib.map.NGWVectorLayer;
import com.nextgis.maplib.util.Constants;
import com.nextgis.maplib.util.GeoConstants;
import com.nextgis.maplib.util.SyncWorkspaceSession;
import java.io.File;
import java.util.Collections;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application=SyncWorkspaceIsolationTest.App.class,sdk={26,36})
public class SyncWorkspaceIsolationTest {
    public static class App extends TestGISApplication {
        @Override public MapBase getMap() { return MapBase.getActiveInstance(); }
    }
    static class TestMap extends MapContentProviderHelper {
        TestMap(Context context, File path, boolean active) { super(context,path,null,active); }
        void dispose() { mDatabaseHelper.close(); }
    }
    static class Fixture implements AutoCloseable {
        final Context app=RuntimeEnvironment.getApplication();
        final TestMap a,b;
        final NGWVectorLayer first,second;
        final SyncWorkspaceSession session;
        Fixture() throws Exception {
            File root=new File(app.getCacheDir(),"sync-"+UUID.randomUUID());
            assertTrue(new File(root,"a").mkdirs());assertTrue(new File(root,"b").mkdirs());
            a=new TestMap(app,new File(root,"a/map.ngm"),true);
            b=new TestMap(app,new File(root,"b/map.ngm"),false);
            first=layer(a,"A");second=layer(b,"B");
            session=new SyncWorkspaceSession(b);
        }
        NGWVectorLayer layer(TestMap map,String value) throws Exception {
            NGWVectorLayer layer=new NGWVectorLayer(app,new File(map.getPath(),"same_layer"));
            map.addLayer(layer);layer.beginBulkImport();
            layer.create(GeoConstants.GTPoint,Collections.singletonList(new Field(GeoConstants.FTString,"name","Name")));
            ContentValues values=new ContentValues();values.put(Constants.FIELD_GEOM,new GeoPoint(1,2).toBlob());values.put("name",value);
            assertTrue(layer.insertAddChanges(values)>=0);return layer;
        }
        @Override public void close() { session.close();a.dispose();b.closeSyncWorkspace(); }
    }
    @Test public void backgroundScopeAndDeferredChildKeepTheirOwner() throws Exception {
        try(Fixture f=new Fixture()) {
            assertSame(f.a,MapBase.getInstance());assertSame(f.a,MapBase.getActiveInstance());
            try(SyncWorkspaceSession.Scope ignored=f.session.enter()) {
                assertSame(f.b,MapBase.getInstance());
                SyncWorkspaceSession.post(new Handler(Looper.getMainLooper()),()->assertSame(f.b,MapBase.getInstance()));
                assertFalse(f.session.isQuiet());
            }
            assertSame(f.a,MapBase.getInstance());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(f.session.isQuiet());assertSame(f.a,MapBase.getInstance());
        }
    }
    @Test public void providerUsesExplicitOwnerEvenWithEqualLayerNames() throws Exception {
        try(Fixture f=new Fixture()) {
            LayerContentProvider provider=Robolectric.buildContentProvider(LayerContentProvider.class).create().get();
            Uri active=Uri.parse("content://test.reliability/same_layer");
            Uri background=SyncWorkspaceSession.bindUri(f.second,active);
            try(Cursor c=provider.query(background,new String[]{"name"},null,null,null)) { assertTrue(c.moveToFirst());assertEquals("B",c.getString(0)); }
            ContentValues changed=new ContentValues();changed.put("name","B updated");
            provider.update(background,changed,null,null);
            try(Cursor c=provider.query(active,new String[]{"name"},null,null,null)) { assertTrue(c.moveToFirst());assertEquals("A",c.getString(0)); }
            try(Cursor c=provider.query(background,new String[]{"name"},null,null,null)) { assertTrue(c.moveToFirst());assertEquals("B updated",c.getString(0)); }
            f.session.close();
            assertNull(provider.query(background,new String[]{"name"},null,null,null));
        }
    }
    @Test public void pendingServicePreventsPrematureCloseAndExpiredTokenNeverFallsBack() throws Exception {
        try(Fixture f=new Fixture()) {
            String ticket=f.session.reserveExternal(null);
            try { f.session.close();fail("Pending delivery must retain the workspace"); } catch(IllegalStateException expected) { }
            assertTrue(f.session.claimExternal(ticket));assertFalse(f.session.claimExternal(ticket));
            f.session.releaseExternal(ticket);assertTrue(f.session.isQuiet());
            String token=f.session.getToken();f.session.close();assertNull(SyncWorkspaceSession.resolve(token));
            try { SyncWorkspaceSession.bindUri(f.second,Uri.parse("content://test.reliability/same_layer"));fail("No active-map fallback"); }
            catch(IllegalStateException expected) { }
        }
    }
    @Test public void undeliveredServiceExpiresAndRejectedExecutorReleasesItsPendingCount() throws Exception {
        try (Fixture f = new Fixture()) {
            java.util.concurrent.atomic.AtomicBoolean rejected = new java.util.concurrent.atomic.AtomicBoolean();
            String ticket = f.session.reserveExternal(() -> rejected.set(true));
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(61));
            f.session.expireUndelivered();
            assertTrue(rejected.get());assertTrue(f.session.hasFailed());assertTrue(f.session.isQuiet());
            assertFalse(f.session.claimExternal(ticket));
            try (SyncWorkspaceSession.Scope ignored = f.session.enter()) {
                try { SyncWorkspaceSession.execute(task -> { throw new java.util.concurrent.RejectedExecutionException(); }, () -> fail()); }
                catch (java.util.concurrent.RejectedExecutionException expected) { }
            }
            assertTrue(f.session.isQuiet());
            try (SyncWorkspaceSession.Scope ignored = f.session.enter()) {
                Runnable queued = SyncWorkspaceSession.capture(() -> fail("Discarded task must not run"));
                assertFalse(f.session.isQuiet());SyncWorkspaceSession.discard(queued);
                assertTrue(f.session.isQuiet());queued.run();assertTrue(f.session.isQuiet());
            }
        }
    }
    @Test public void nestedAdapterCannotForgetCancellationInheritedFromItsWorkspace() throws Exception {
        try (Fixture f = new Fixture(); SyncWorkspaceSession.Scope ignored = f.session.enter()) {
            com.nextgis.maplib.util.NgwSyncIo.requestCancellation();
            try (com.nextgis.maplib.util.NgwSyncIo.Session nested = com.nextgis.maplib.util.NgwSyncIo.beginSession()) {
                assertTrue(com.nextgis.maplib.util.NgwSyncIo.isCancellationRequested());
                assertTrue(f.session.isCancelled());
            }
        }
    }
}

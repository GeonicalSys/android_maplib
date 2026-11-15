package com.nextgis.maplib.map;

import android.accounts.Account;
import android.content.ContentValues;
import android.content.Context;
import android.content.SyncResult;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import com.hypertrack.hyperlog.HyperLog;
import com.nextgis.maplib.datasource.Feature;
import com.nextgis.maplib.datasource.GeoPoint;
import com.nextgis.maplib.reliability.TestGISApplication;
import com.nextgis.maplib.util.AccountUtil;
import com.nextgis.maplib.util.Constants;
import com.nextgis.maplib.util.NgwSyncIo;
import com.nextgis.maplib.util.NgwSyncResourceBudget;
import com.nextgis.maplib.util.SyncWorkspaceSession;
import java.io.*;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Real SQLite rollback and original files/outbox, with a technical read-only HTTP response. */
@RunWith(RobolectricTestRunner.class)
@Config(application=NGWVectorLayerFullSnapshotResourcesTest.ModelApplication.class,
        sdk={26,36}, shadows=NGWVectorLayerFullSnapshotResourcesTest.QuietLog.class)
public class NGWVectorLayerFullSnapshotResourcesTest {
    public static class ModelApplication extends TestGISApplication {
        int selective, whole;
        boolean backupAllowed=true;
        @Override public Account getAccount(String name) { return new Account("rel03-technical","rel03-test"); }
        @Override public String getAccountUrl(Account account) { return "https://example.invalid"; }
        @Override public String getAccountLogin(Account account) { return ""; }
        @Override public String getAccountPassword(Account account) { return ""; }
        @Override public String getAuthority() { return "rel03.technical.provider"; }
        @Override public boolean backupEditableLayerFeatures(NGWVectorLayer layer,Collection<Long> ids,String reason) {
            selective++;return backupAllowed;
        }
        @Override public boolean backupEditableLayerData(NGWVectorLayer layer,String reason) {
            whole++;return backupAllowed;
        }
    }
    @Implements(HyperLog.class)
    public static class QuietLog {
        static final List<String> messages=new ArrayList<>();
        static void remember(String value){if(messages.size()<40)messages.add(value);}
        @Implementation protected static void i(String tag,String value) { remember(value); }
        @Implementation protected static void v(String tag,String value) { remember(value); }
        @Implementation protected static void w(String tag,String value) { remember(value); }
        @Implementation protected static void w(String tag,String value,Throwable error) { remember(value+": "+error); }
        @Implementation protected static void e(String tag,String value) { }
        @Implementation protected static void e(String tag,String value,Throwable error) { }
    }
    private static final class SpaceDirectory extends File {
        boolean exhausted;
        SpaceDirectory(File parent,String name) { super(parent,name); }
        @Override public long getUsableSpace() {
            return exhausted ? NgwSyncResourceBudget.DISK_RESERVE_BYTES : super.getUsableSpace();
        }
    }
    private static final class Response extends HttpURLConnection {
        byte[] body="[]".getBytes(StandardCharsets.UTF_8);
        boolean disconnected;
        Response() throws Exception { super(new URL("https://example.invalid/technical-features")); }
        @Override public int getResponseCode() { return 200; }
        @Override public int getContentLength() { return -1; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(body); }
        @Override public void disconnect() { disconnected=true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { throw new AssertionError("Technical response must not open a network"); }
    }
    private final class Layer extends NGWVectorLayer {
        boolean pressureAfterInsert,cancelAfterInsert;
        Layer(Context context,File path) { super(context,path);mAccountName="rel03-technical";mRemoteId=7; }
        @Override protected HttpURLConnection getConnection(AccountUtil.AccountData data) { return response; }
        @Override protected boolean createNewFeature(Feature feature,String authority) {
            boolean created=super.createNewFeature(feature,authority);
            if(created&&pressureAfterInsert)directory.exhausted=true;
            if(created&&cancelAfterInsert)NgwSyncIo.requestCancellation();
            return created;
        }
    }
    private Path root;
    private SpaceDirectory directory;
    private ModelApplication app;
    private MapContentProviderHelper map;
    private Layer layer;
    private SQLiteDatabase database;
    private Response response;
    private byte[] original;
    private File attachment;

    @Before public void setup() throws Exception {
        QuietLog.messages.clear();
        root=Files.createTempDirectory("rel03-full-snapshot-");
        app=(ModelApplication)RuntimeEnvironment.getApplication();response=new Response();
        map=new MapContentProviderHelper(app,root.resolve("technical.ngm").toFile(),app.getLayerFactory(),false);
        directory=new SpaceDirectory(root.toFile(),"technical_features");
        layer=new Layer(app,directory);map.addLayer(layer);
        layer.setSyncType(Constants.SYNC_DATA|Constants.SYNC_ATTACH);
        layer.beginBulkImport();layer.create(com.nextgis.maplib.util.GeoConstants.GTPoint,Collections.emptyList());
        database=map.getDatabase(false);
        add(1);add(2);
        database.delete(layer.getChangeTableName(),null,null);
        add(99); // A real unsent NEW row must survive a failed full pull.
        original=geometry(1);
        File folder=new File(directory,"2");assertTrue(folder.mkdirs());
        attachment=new File(folder,"local-photo.bin");Files.write(attachment.toPath(),new byte[]{7,8,9});
        response.body=("["+feature(1)+","+feature(3)+","+feature(4)+"]").getBytes(StandardCharsets.UTF_8);
    }
    @After public void cleanup() throws Exception {
        // Complete queued layer notifications before releasing their owning database/receiver.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        if(map!=null)map.closeSyncWorkspace();
        if(root!=null)try(Stream<Path> paths=Files.walk(root)) {
            for(Path path:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) {
                assertFalse(Files.isSymbolicLink(path));Files.delete(path);
            }
        }
    }
    private static String feature(long id) { return "{\"id\":"+id+",\"geom\":\"POINT (0 0)\",\"fields\":{}}"; }
    private void add(long id) throws Exception {
        ContentValues value=new ContentValues();value.put(Constants.FIELD_ID,id);
        value.put(Constants.FIELD_GEOM,new GeoPoint(10,20).toBlob());
        assertEquals(id,layer.insertAddChanges(value));
    }
    private byte[] geometry(long id) {
        try(Cursor row=database.rawQuery("SELECT "+Constants.FIELD_GEOM+" FROM technical_features WHERE _id=?",new String[]{Long.toString(id)})) {
            assertTrue(row.moveToFirst());return row.getBlob(0);
        }
    }
    private boolean sync(SyncResult result) throws Exception {
        Method method=NGWVectorLayer.class.getDeclaredMethod("getFullSnapshotChangesFromServerStreaming",String.class,SyncResult.class);
        method.setAccessible(true);
        try(SyncWorkspaceSession session=new SyncWorkspaceSession(map,"rel03-private-workspace");
            SyncWorkspaceSession.Scope scope=SyncWorkspaceSession.enter(session)) {
            return (Boolean)method.invoke(layer,"rel03.technical.provider",result);
        }
    }
    private void originalsAndQueueRemain() throws Exception {
        assertEquals(3,DatabaseUtils.queryNumEntries(database,"technical_features"));
        assertArrayEquals(original,geometry(1));
        assertEquals(1,DatabaseUtils.queryNumEntries(database,layer.getChangeTableName()));
        assertArrayEquals(new byte[]{7,8,9},Files.readAllBytes(attachment.toPath()));
        File[] staged=directory.listFiles((parent,name)->name.startsWith(".ngw-sync-snapshot-"));
        assertNotNull(staged);assertEquals(0,staged.length);
        assertTrue(response.disconnected);
    }
    @Test public void exhaustedDownloadSpaceDoesNotStartBackupOrApply() throws Exception {
        directory.exhausted=true;SyncResult result=new SyncResult();
        assertFalse(sync(result));assertTrue(result.stats.numIoExceptions>0);
        assertEquals(0,app.selective+app.whole);originalsAndQueueRemain();
    }
    @Test public void pressureAfterARealInsertRollsBackEarlierUpdatesAndTheWholeQueue() throws Exception {
        layer.pressureAfterInsert=true;SyncResult result=new SyncResult();
        assertFalse(sync(result));assertTrue(result+" "+QuietLog.messages,result.stats.numIoExceptions>0);originalsAndQueueRemain();
    }
    @Test public void aSqlFailureAfterAnEarlierUpdateRollsBackAndKeepsAttachmentBytes() throws Exception {
        database.execSQL("CREATE TRIGGER fail_rel03 BEFORE INSERT ON technical_features WHEN NEW._id=3 BEGIN SELECT RAISE(ABORT,'technical ENOSPC substitute'); END");
        assertFalse(sync(new SyncResult()));originalsAndQueueRemain();
    }
    @Test public void cancellationDuringApplyPreservesOriginalDatabaseAndPendingNewRow() throws Exception {
        try(NgwSyncIo.Session session=NgwSyncIo.beginSession()) {
            layer.cancelAfterInsert=true;assertFalse(sync(new SyncResult()));originalsAndQueueRemain();
        }
    }
    @Test public void failedBackupNeverStartsDestructiveApplication() throws Exception {
        app.backupAllowed=false;assertFalse(sync(new SyncResult()));
        assertTrue(app.selective>0);originalsAndQueueRemain();
    }
    @Test public void retryAfterPressureCommitsExactlyOneCompleteSnapshot() throws Exception {
        layer.pressureAfterInsert=true;assertFalse(sync(new SyncResult()));originalsAndQueueRemain();
        directory.exhausted=false;layer.pressureAfterInsert=false;
        assertTrue(QuietLog.messages.toString(),sync(new SyncResult()));
        assertEquals(4,DatabaseUtils.queryNumEntries(database,"technical_features"));
        assertEquals(1,DatabaseUtils.queryNumEntries(database,layer.getChangeTableName()));
        assertEquals(1,DatabaseUtils.longForQuery(database,"SELECT count(*) FROM technical_features WHERE _id=99",null));
        assertFalse("Attachment cleanup belongs after successful commit",attachment.exists());
    }
    @Test public void oneOversizedFeatureFailsBeforeGeometryOrBackupMaterialization() throws Exception {
        String wkt="LINESTRING ("+"0 0,".repeat(2300000)+"1 1)";
        response.body=("[{\"id\":1,\"geom\":\""+wkt+"\",\"fields\":{}}]").getBytes(StandardCharsets.UTF_8);
        SyncResult result=new SyncResult();assertFalse(sync(result));
        assertTrue(result.stats.numIoExceptions>0);assertEquals(0,app.selective+app.whole);originalsAndQueueRemain();
    }
    @Test public void largePlanUsesOneWholeLayerBackupAndRetainsTheUnsentNewRow() throws Exception {
        StringBuilder body=new StringBuilder("[");
        for(int i=1000;i<1600;i++) {
            add(i);if(i!=1000)body.append(',');body.append(feature(i));
        }
        database.delete(layer.getChangeTableName(),Constants.FIELD_FEATURE_ID+" >= 1000",null);
        response.body=body.append(']').toString().getBytes(StandardCharsets.UTF_8);
        assertTrue(sync(new SyncResult()));
        assertEquals(1,app.whole);assertEquals(0,app.selective);
        assertEquals(601,DatabaseUtils.queryNumEntries(database,"technical_features"));
        assertEquals(1,DatabaseUtils.queryNumEntries(database,layer.getChangeTableName()));
        assertFalse(Arrays.equals(original,geometry(1000)));
    }
}

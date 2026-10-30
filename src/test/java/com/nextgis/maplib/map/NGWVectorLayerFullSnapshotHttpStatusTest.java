package com.nextgis.maplib.map;

import android.accounts.Account;
import android.content.Context;
import android.content.SyncResult;
import com.hypertrack.hyperlog.HyperLog;
import com.nextgis.maplib.reliability.TestGISApplication;
import com.nextgis.maplib.util.AccountUtil;
import com.nextgis.maplib.util.NgwSyncTrace;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import static org.junit.Assert.*;

/** Technical regression of the actual private downloader without SQLite, a map or network access. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, manifest=Config.NONE, application=NGWVectorLayerFullSnapshotHttpStatusTest.ModelApplication.class,
        shadows=NGWVectorLayerFullSnapshotHttpStatusTest.QuietLog.class)
public class NGWVectorLayerFullSnapshotHttpStatusTest {
    public static class ModelApplication extends TestGISApplication {
        @Override public Account getAccount(String name) { return new Account("technical-http", "technical-test"); }
        @Override public String getAccountUrl(Account account) { return "https://example.invalid"; }
        @Override public String getAccountLogin(Account account) { return ""; }
        @Override public String getAccountPassword(Account account) { return ""; }
    }
    @Implements(HyperLog.class)
    public static class QuietLog {
        @Implementation protected static void i(String tag,String message) { }
        @Implementation protected static void v(String tag,String message) { }
        @Implementation protected static void w(String tag,String message) { }
        @Implementation protected static void e(String tag,String message) { }
    }
    private Path directory;
    @Before public void setUp() throws Exception {
        // Root runner must set java.io.tmpdir to its owned Q scratch location.
        directory=Files.createTempDirectory("ngw-snapshot-http-technical-");
    }
    @After public void cleanup() throws Exception {
        if(directory==null || !Files.exists(directory)) return;
        try(Stream<Path> paths=Files.list(directory)) {
            for(Path child:paths.toArray(Path[]::new)) {
                assertTrue("Unexpected file in exclusively owned technical directory",Files.isRegularFile(child,LinkOption.NOFOLLOW_LINKS));
                assertTrue(child.getFileName().toString().startsWith(".ngw-sync-snapshot-"));
                Files.delete(child);
            }
        }
        Files.delete(directory);
    }
    private static final class ModelConnection extends HttpURLConnection {
        final int response;
        final byte[] body="[]".getBytes(StandardCharsets.UTF_8);
        int inputOpens, errorOpens, lengthReads;
        boolean disconnected;
        ModelConnection(int code) throws Exception { super(new URL("https://example.invalid/feature/"));response=code; }
        @Override public int getResponseCode() { return response; }
        @Override public String getResponseMessage() { return response==206 ? "Partial Content" : "technical status"; }
        @Override public InputStream getErrorStream() { errorOpens++;return null; }
        @Override public int getContentLength() { lengthReads++;return body.length; }
        @Override public String getHeaderField(String name) {
            if("Content-Type".equalsIgnoreCase(name))return "application/json";
            if("Content-Range".equalsIgnoreCase(name) && response==206)return "bytes 7-8/100";
            return null;
        }
        @Override public InputStream getInputStream() { inputOpens++;return new ByteArrayInputStream(body); }
        @Override public void disconnect() { disconnected=true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { throw new AssertionError("No socket connection in technical model"); }
    }
    private static final class ProbeLayer extends NGWVectorLayer {
        final ModelConnection connection;
        ProbeLayer(Context app,File path,ModelConnection connection) {
            super(app,path);this.connection=connection;mAccountName="technical-http";mRemoteId=7;
        }
        @Override protected HttpURLConnection getConnection(AccountUtil.AccountData data) { return connection; }
    }
    private static final class Result {
        final File file;
        final int code;
        final SyncResult sync;
        final ModelConnection connection;
        Result(Object value,SyncResult sync,ModelConnection connection) throws Exception {
            Field f=value.getClass().getDeclaredField("file");f.setAccessible(true);file=(File)f.get(value);
            Field c=value.getClass().getDeclaredField("httpCode");c.setAccessible(true);code=c.getInt(value);
            this.sync=sync;this.connection=connection;
        }
    }
    private Result actual(int status) throws Exception {
        ModelConnection connection=new ModelConnection(status);
        ProbeLayer layer=new ProbeLayer(RuntimeEnvironment.getApplication(),directory.toFile(),connection);
        SyncResult sync=new SyncResult();
        Method method=NGWVectorLayer.class.getDeclaredMethod("downloadFullSnapshot",SyncResult.class,NgwSyncTrace.class);
        method.setAccessible(true);
        try { return new Result(method.invoke(layer,sync,new NgwSyncTrace(7)),sync,connection); }
        catch(InvocationTargetException failure) {
            Throwable cause=failure.getCause();
            if(cause instanceof Error)throw(Error)cause;
            if(cause instanceof Exception)throw(Exception)cause;
            throw new AssertionError(cause);
        }
    }
    private long fileCount() throws Exception { try(Stream<Path> paths=Files.list(directory)){return paths.count();} }
    @Test public void explicitPartial206CannotBecomeAnAuthoritativeEmptySnapshot() throws Exception {
        Result result=actual(HttpURLConnection.HTTP_PARTIAL);
        // RFC9110 15.3.7, independent of whether this body happens to be valid JSON.
        assertNull("Partial Content must fail before staging a full inventory",result.file);
        assertEquals(206,result.code);
        assertEquals(0,result.connection.inputOpens);
        assertEquals(0,result.connection.lengthReads);
        assertEquals(0,result.connection.errorOpens);
        assertEquals(0,fileCount());
        assertEquals(1,result.sync.stats.numIoExceptions);
        assertTrue(result.connection.disconnected);
    }
    @Test public void complete200StillStagesTheExactBody() throws Exception {
        Result result=actual(200);
        assertNotNull(result.file);assertTrue(result.file.isFile());
        assertArrayEquals("[]".getBytes(StandardCharsets.UTF_8),Files.readAllBytes(result.file.toPath()));
        assertEquals(200,result.code);assertEquals(1,result.connection.inputOpens);
        assertEquals(0,result.sync.stats.numIoExceptions);assertEquals(1,fileCount());
        assertTrue(result.connection.disconnected);
    }
    @Test public void otherPreviouslyAccepted2xx203IsNotTightened() throws Exception {
        Result result=actual(203);
        assertNotNull(result.file);assertArrayEquals("[]".getBytes(StandardCharsets.UTF_8),Files.readAllBytes(result.file.toPath()));
        assertEquals(203,result.code);assertEquals(0,result.sync.stats.numIoExceptions);
        assertEquals(1,result.connection.inputOpens);assertTrue(result.connection.disconnected);
    }
    @Test public void legacy404RemainsANonstagedResourceGoneResult() throws Exception {
        Result result=actual(404);
        assertNull(result.file);assertEquals(404,result.code);
        assertEquals(0,result.connection.inputOpens);assertEquals(0,fileCount());
        assertEquals(0,result.sync.stats.numIoExceptions);assertTrue(result.connection.disconnected);
    }
}

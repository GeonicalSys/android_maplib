package com.nextgis.maplib.map;

import android.accounts.Account;
import android.content.Context;
import android.content.SyncResult;
import android.os.Bundle;
import android.util.Pair;
import com.hypertrack.hyperlog.HyperLog;
import com.nextgis.maplib.reliability.TestGISApplication;
import com.nextgis.maplib.util.Constants;
import com.nextgis.maplib.util.HttpResponse;
import com.nextgis.maplib.util.NGWLayerSchemaCompat;
import com.nextgis.maplib.util.NetworkUtil;
import com.nextgis.maplib.util.SyncResultUtil;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
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

/** Actual SYNC_NONE entry points with technical transport and row-count seams; no DB or socket. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, manifest=Config.NONE, application=NGWSyncNoneServerFailureTest.ModelApplication.class,
        shadows={NGWSyncNoneServerFailureTest.Transport.class, NGWSyncNoneServerFailureTest.QuietLog.class})
public class NGWSyncNoneServerFailureTest {
    private static final int NONE=0, CONNECT=2, SERVER=4;

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
        @Implementation protected static void d(String tag,String message) { }
    }
    @Implements(NetworkUtil.class)
    public static class Transport {
        static final ArrayDeque<Object> replies=new ArrayDeque<>();
        static ModelConnection connection;
        static int gets,connections;
        @Implementation protected boolean isNetworkAvailable() { return true; }
        @Implementation protected static HttpResponse get(String url,String login,String password,
                                                          boolean readError) throws IOException {
            gets++;
            assertTrue("Unexpected network request: source must stop at the existing KEEP decision",!replies.isEmpty());
            Object next=replies.removeFirst();
            if(next instanceof IOException)throw(IOException)next;
            return(HttpResponse)next;
        }
        @Implementation protected static HttpURLConnection getHttpConnection(String method,String url,
                                                                              String login,String password) {
            connections++;
            assertNotNull("No actual transport is allowed",connection);
            return connection;
        }
    }
    private static class ModelConnection extends HttpURLConnection {
        final int status;
        final String message;
        final byte[] bytes;
        int inputOpens,errorOpens;
        boolean disconnected;
        ModelConnection(int status,String message,String body) throws Exception {
            super(new URL("https://example.invalid/api/resource/7/feature/?geom=no"));
            this.status=status;this.message=message;this.bytes=body.getBytes(StandardCharsets.UTF_8);
        }
        @Override public int getResponseCode() { return status; }
        @Override public String getResponseMessage() { return message; }
        @Override public InputStream getInputStream() { inputOpens++;return new ByteArrayInputStream(bytes); }
        @Override public InputStream getErrorStream() { errorOpens++;return new ByteArrayInputStream(bytes); }
        @Override public void disconnect() { disconnected=true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { throw new AssertionError("No socket in technical transport"); }
    }
    private static final class ProbeLayer extends NGWVectorLayer {
        int rowReads,filterCalls,saves;
        ProbeLayer() {
            super(RuntimeEnvironment.getApplication(),new File(System.getProperty("java.io.tmpdir"),"sync-none-no-files"));
            mAccountName="technical-http";mRemoteId=7;mSyncType=Constants.SYNC_NONE;
            mFields=new LinkedHashMap<>();setName("technical sync-none");
        }
        @Override public boolean hasLocalDataTable() { return true; }
        @Override public int getSqliteTableRowCount() { rowReads++;return 3; }
        @Override protected boolean applyDistrictFilterFromProjectGroup() { filterCalls++;return false; }
        @Override public boolean save() { saves++;throw new AssertionError("No save or local refill is expected"); }
        void where() { mServerWhere="technical=1"; }
    }
    @Before public void before() {
        Transport.replies.clear();Transport.connection=null;Transport.gets=0;Transport.connections=0;
    }
    @After public void after() {
        SyncResultUtil.endSync();
        Transport.replies.clear();Transport.connection=null;
    }
    private static HttpResponse response(int code,String body) {
        HttpResponse result=new HttpResponse(code,"technical response",body);
        result.setOk(code>=200&&code<300);
        return result;
    }
    private static int reasons(SyncResult result) {
        Bundle out=new Bundle();SyncResultUtil.exportAccountFailureSnapshot(out,result);
        return SyncResultUtil.readAccountFailureReasons(out,result);
    }
    private static void ledger(SyncResult result,long io) {
        assertArrayEquals(new long[]{io,0,0,0,0,0,0,0,0},new long[]{result.stats.numIoExceptions,
                result.stats.numParseExceptions,result.stats.numAuthExceptions,result.stats.numConflictDetectedExceptions,
                result.stats.numEntries,result.stats.numInserts,result.stats.numUpdates,result.stats.numDeletes,
                result.stats.numSkippedEntries});
        assertFalse(result.databaseError);assertFalse(result.tooManyDeletions);assertFalse(result.tooManyRetries);
        assertFalse(result.fullSyncRequested);assertFalse(result.partialSyncUnavailable);assertEquals(0,result.delayUntil);
    }
    private static void kept(ProbeLayer layer,int gets,int connections) {
        assertEquals(1,layer.rowReads);assertEquals(1,layer.filterCalls);assertEquals(0,layer.saves);
        assertEquals(gets,Transport.gets);assertEquals(connections,Transport.connections);assertTrue(Transport.replies.isEmpty());
    }

    @Test public void knownServerCountFailureRemainsUnknownAndCannotBeCleanSuccess() {
        for(int code:new int[]{429,500,503,599,422}) {
            Transport.replies.add(response(404,"unused metadata"));
            Transport.replies.add(response(code,code==422?"ExternalDatabaseError":"technical error"));
            ProbeLayer layer=new ProbeLayer();SyncResult result=new SyncResult();SyncResultUtil.beginSync(result);
            layer.syncNgwResourceConfigOnly("technical.authority",result);
            ledger(result,1);assertTrue(result.hasError());assertEquals(SERVER,reasons(result));kept(layer,2,0);
            SyncResultUtil.endSync();Transport.gets=0;
        }
    }
    @Test public void metadataFailureIsReportedEvenWhenFollowingZeroCountKeepsLocalRows() {
        Transport.replies.add(response(503,"technical error"));Transport.replies.add(response(200,"{\"total_count\":0}"));
        ProbeLayer layer=new ProbeLayer();SyncResult result=new SyncResult();SyncResultUtil.beginSync(result);
        layer.syncNgwResourceConfigOnly("technical.authority",result,true);
        ledger(result,1);assertEquals(SERVER,reasons(result));kept(layer,2,0);
    }
    @Test public void metadataAndCountFailuresAreTwoFailedRequestsWithoutDoubleCountingEither() {
        Transport.replies.add(response(500,"technical error"));Transport.replies.add(response(503,"technical error"));
        ProbeLayer layer=new ProbeLayer();SyncResult result=new SyncResult();SyncResultUtil.beginSync(result);
        layer.syncNgwResourceConfigOnly("technical.authority",result);
        ledger(result,2);assertEquals(SERVER,reasons(result));kept(layer,2,0);
    }
    @Test public void directLayerSpecificSyncNoneAlsoReportsItsMetadataServerFailure() {
        Transport.replies.add(response(503,"technical error"));
        ProbeLayer layer=new ProbeLayer();SyncResult result=new SyncResult();SyncResultUtil.beginSync(result);
        layer.sync("technical.authority",Pair.create(3,0),result);
        ledger(result,1);assertEquals(SERVER,reasons(result));assertEquals(1,Transport.gets);
        assertEquals(0,layer.rowReads);assertEquals(0,layer.filterCalls);assertEquals(0,layer.saves);
        assertEquals(0,Transport.connections);assertTrue(Transport.replies.isEmpty());
    }
    @Test public void nonServerCountStatusesKeepExistingUnknownPolicyAndConnectTokenOnce() {
        for(int code:new int[]{0,401,403,404,408,422}) {
            Transport.replies.add(response(404,"unused metadata"));Transport.replies.add(response(code,"plain validation"));
            ProbeLayer layer=new ProbeLayer();SyncResult result=new SyncResult();SyncResultUtil.beginSync(result);
            layer.syncNgwResourceConfigOnly("technical.authority",result);
            ledger(result,code==0?1:0);assertEquals(code==0?CONNECT:NONE,reasons(result));kept(layer,2,0);
            SyncResultUtil.endSync();Transport.gets=0;
        }
    }
    @Test public void knownServerFallbackStatusDoesNotReadBodiesOrReloadUnknownCount() throws Exception {
        for(int code:new int[]{429,500,503,599,422}) {
            Transport.replies.add(response(404,"unused metadata"));Transport.replies.add(response(200,"{\"total_count\":4}"));
            Transport.connection=new ModelConnection(code,code==422?"ExternalDatabaseError":"technical status","technical body");
            ProbeLayer layer=new ProbeLayer();layer.where();SyncResult result=new SyncResult();SyncResultUtil.beginSync(result);
            layer.syncNgwResourceConfigOnly("technical.authority",result);
            ledger(result,1);assertEquals(SERVER,reasons(result));kept(layer,2,1);
            assertEquals(0,Transport.connection.inputOpens);assertEquals(0,Transport.connection.errorOpens);
            assertTrue(Transport.connection.disconnected);SyncResultUtil.endSync();Transport.gets=0;Transport.connections=0;
        }
    }
    @Test public void fallbackOtherStatusesAndBodyOnly422KeepOldPolicy() throws Exception {
        for(int code:new int[]{401,403,404,408,422}) {
            Transport.replies.add(response(404,"unused metadata"));Transport.replies.add(response(200,"{\"total_count\":4}"));
            Transport.connection=new ModelConnection(code,"technical status","ExternalDatabaseError");
            ProbeLayer layer=new ProbeLayer();layer.where();SyncResult result=new SyncResult();SyncResultUtil.beginSync(result);
            layer.syncNgwResourceConfigOnly("technical.authority",result);
            ledger(result,0);assertEquals(NONE,reasons(result));kept(layer,2,1);
            assertEquals(0,Transport.connection.inputOpens);assertEquals(0,Transport.connection.errorOpens);
            assertTrue(Transport.connection.disconnected);SyncResultUtil.endSync();Transport.gets=0;Transport.connections=0;
        }
    }
    @Test public void successfulFallbackZeroKeepsExistingParserAndNoReload() throws Exception {
        Transport.replies.add(response(404,"unused metadata"));Transport.replies.add(response(200,"{\"total_count\":4}"));
        Transport.connection=new ModelConnection(200,"technical status","[]");
        ProbeLayer layer=new ProbeLayer();layer.where();SyncResult result=new SyncResult();SyncResultUtil.beginSync(result);
        layer.syncNgwResourceConfigOnly("technical.authority",result);
        ledger(result,0);assertEquals(NONE,reasons(result));kept(layer,2,1);
        assertEquals(1,Transport.connection.inputOpens);assertEquals(0,Transport.connection.errorOpens);
        assertTrue(Transport.connection.disconnected);
    }
    @Test public void legacyMetadataApiStillReturnsNullWithoutAResultOrNewGlobalReason() {
        SyncResult result=new SyncResult();SyncResultUtil.beginSync(result);
        Transport.replies.add(response(503,"technical error"));
        assertNull(NGWLayerSchemaCompat.fetchResourceMetaJson("https://example.invalid/api/resource/7","",""));
        ledger(result,0);assertEquals(NONE,reasons(result));assertEquals(1,Transport.gets);assertEquals(0,Transport.connections);
        assertTrue(Transport.replies.isEmpty());
    }
    @Test public void malformedSuccessAndIoKeepExistingPolicies() {
        Transport.replies.add(response(200,"not JSON"));Transport.replies.add(response(200,"not JSON"));
        ProbeLayer malformed=new ProbeLayer();SyncResult result=new SyncResult();SyncResultUtil.beginSync(result);
        malformed.syncNgwResourceConfigOnly("technical.authority",result);
        ledger(result,0);assertEquals(NONE,reasons(result));kept(malformed,2,0);
        SyncResultUtil.endSync();Transport.gets=0;
        Transport.replies.add(new IOException("technical optional meta"));Transport.replies.add(new IOException("technical count"));
        ProbeLayer disconnected=new ProbeLayer();result=new SyncResult();SyncResultUtil.beginSync(result);
        disconnected.syncNgwResourceConfigOnly("technical.authority",result);
        ledger(result,1);assertEquals(CONNECT,reasons(result));kept(disconnected,2,0);
    }
}

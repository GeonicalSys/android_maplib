package com.nextgis.maplib.map;

import android.content.Context;
import android.content.SyncResult;
import android.os.Bundle;
import android.util.Pair;
import com.hypertrack.hyperlog.HyperLog;
import com.nextgis.maplib.api.IProgressor;
import com.nextgis.maplib.reliability.TestGISApplication;
import com.nextgis.maplib.util.Constants;
import com.nextgis.maplib.util.HttpResponse;
import com.nextgis.maplib.util.NGException;
import com.nextgis.maplib.util.NetworkUtil;
import com.nextgis.maplib.util.SyncResultUtil;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONException;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import static org.junit.Assert.*;

/** Actual failure producers and public lookup sync; technical in-memory seams, no transport or SQL. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, manifest=Config.NONE, application=TestGISApplication.class,
        shadows=NGWHttpFailureReasonProducerTest.QuietLog.class)
public class NGWHttpFailureReasonProducerTest {
    // Independent wire-contract oracles, not values derived from a presentation implementation.
    private static final int NONE=0, CONNECT=2, SERVER=4, OTHER=8;
    private final List<Thread> ownedThreads=new ArrayList<>();
    @Implements(HyperLog.class)
    public static class QuietLog {
        @Implementation protected static void v(String tag,String message) { }
        @Implementation protected static void w(String tag,String message) { }
        @Implementation protected static void d(String tag,String message) { }
    }
    @After public void clearOnlyOwnedReasonScopeAndThreads() throws Exception {
        SyncResultUtil.endSync();
        for(Thread thread:ownedThreads) {
            if(thread.isAlive())thread.interrupt();
            thread.join(5000);
            assertFalse("Technical producer thread must finish",thread.isAlive());
        }
    }
    private static Context app() { return RuntimeEnvironment.getApplication(); }
    private static final class ProbeVector extends NGWVectorLayer {
        int logs;
        boolean suppressNumericLog;
        ProbeVector() {
            super(app(),new File(System.getProperty("java.io.tmpdir"),"http-reason-vector-no-files"));
            setName("technical reason");mRemoteId=7;
        }
        void report(SyncResult result,HttpResponse response) {
            reportSyncHttpFailure("technicalPush",101,201,result,response);
        }
        @Override protected void log(SyncResult result,String code) {
            logs++;
            if(!suppressNumericLog)super.log(result,code);
        }
    }
    private static final class ProbeLookup extends NGWLookupTable {
        Throwable readFailure,saveFailure;
        int reads,saves;
        boolean saveResult=true;
        final Map<String,String> remote=new LinkedHashMap<>();
        ProbeLookup() {
            super(app(),new File(System.getProperty("java.io.tmpdir"),"http-reason-lookup-no-files"));
            setSyncType(Constants.SYNC_ALL);setName("technical lookup");
            Map<String,String> initial=new LinkedHashMap<>();initial.put("old","cached");setData(initial);
            remote.put("new","downloaded");
        }
        @Override protected void fillFromNGW(Map<String,String> target,IProgressor progressor)
                throws IOException,NGException,JSONException {
            reads++;
            if(readFailure!=null) {
                target.put("partial","unconfirmed");
                if(readFailure instanceof IOException)throw(IOException)readFailure;
                if(readFailure instanceof NGException)throw(NGException)readFailure;
                if(readFailure instanceof JSONException)throw(JSONException)readFailure;
                if(readFailure instanceof RuntimeException)throw(RuntimeException)readFailure;
                if(readFailure instanceof Error)throw(Error)readFailure;
                throw new AssertionError(readFailure);
            }
            target.putAll(remote);
        }
        @Override public boolean save() {
            saves++;
            if(saveFailure instanceof RuntimeException)throw(RuntimeException)saveFailure;
            if(saveFailure instanceof Error)throw(Error)saveFailure;
            return saveResult;
        }
        void run(SyncResult result) { sync("technical.authority",Pair.create(3,0),result); }
    }
    private static HttpResponse response(int code,String body) {
        return new HttpResponse(code,"technical response",body);
    }
    private static NGException actualLookupHttpException(int code,String body) throws Exception {
        Class<?> type=Class.forName("com.nextgis.maplib.map.NGWLookupTable$LookupHttpException");
        Constructor<?> ctor=type.getDeclaredConstructor(Context.class,HttpResponse.class);
        ctor.setAccessible(true);
        try { return(NGException)ctor.newInstance(app(),response(code,body)); }
        catch(InvocationTargetException error) {
            Throwable cause=error.getCause();
            if(cause instanceof Error)throw(Error)cause;
            if(cause instanceof Exception)throw(Exception)cause;
            throw new AssertionError(cause);
        }
    }
    private static int exported(SyncResult result) {
        Bundle extras=new Bundle();
        SyncResultUtil.exportAccountFailureSnapshot(extras,result);
        return SyncResultUtil.readAccountFailureReasons(extras,result);
    }
    private static long[] ledger(SyncResult r) {
        return new long[]{r.stats.numIoExceptions,r.stats.numParseExceptions,r.stats.numAuthExceptions,
                r.stats.numConflictDetectedExceptions,r.stats.numEntries,r.stats.numInserts,
                r.stats.numUpdates,r.stats.numDeletes,r.stats.numSkippedEntries};
    }
    private static void errorsAndOperations(SyncResult r,long io,long parse,long auth,long entries) {
        assertArrayEquals(new long[]{io,parse,auth,0,entries,0,0,0,0},ledger(r));
        assertFalse(r.databaseError);assertFalse(r.tooManyDeletions);assertFalse(r.tooManyRetries);
        assertFalse(r.syncAlreadyInProgress);assertFalse(r.fullSyncRequested);
        assertFalse(r.partialSyncUnavailable);assertEquals(0,r.delayUntil);
    }
    private static void retainedFailedRead(ProbeLookup table,Map<String,String> cache) {
        assertSame(cache,table.getData());assertEquals(1,table.reads);assertEquals(0,table.saves);
        Map<String,String> expected=new LinkedHashMap<>();expected.put("old","cached");
        assertEquals(expected,cache);
    }

    @Test public void pushKnownServerResponsesAddOneExistingIoAndOneReasonOnly() {
        for(int code:new int[]{429,500,502,503,504,507,599,422}) {
            SyncResult result=new SyncResult();SyncResultUtil.beginSync(result);
            ProbeVector layer=new ProbeVector();
            layer.report(result,response(code,code==422?"ExternalDatabaseError":"technical body"));
            assertEquals(1,layer.logs);errorsAndOperations(result,1,0,0,1);
            assertEquals("Pure server response "+code,SERVER,exported(result));
            // Observing/exporting metadata must itself leave every result counter untouched.
            long[] before=ledger(result);assertEquals(SERVER,exported(result));assertArrayEquals(before,ledger(result));
            SyncResultUtil.endSync();
        }
    }

    @Test public void pushAuthOtherStatusesAndNoCounterDeltaCannotBecomeServer() {
        for(int code:new int[]{0,1,401,403,404,408,422}) {
            SyncResult result=new SyncResult();SyncResultUtil.beginSync(result);
            ProbeVector layer=new ProbeVector();
            // Body words cannot turn auth/404 or internal transport tokens into server.
            // Plain422 remains validation; code0 keeps the existing CONNECT marker.
            layer.report(result,response(code,code==422?"invalid geometry":"external database error"));
            boolean auth=code==401||code==403;
            errorsAndOperations(result,auth||code==1?0:1,code==1?1:0,auth?1:0,
                    auth||code==0||code==1?0:1);
            assertEquals(code==0?CONNECT:OTHER,exported(result));SyncResultUtil.endSync();
        }
        SyncResult noDelta=new SyncResult();SyncResultUtil.beginSync(noDelta);
        ProbeVector layer=new ProbeVector();layer.suppressNumericLog=true;
        layer.report(noDelta,response(503,"technical"));
        assertEquals(1,layer.logs);errorsAndOperations(noDelta,0,0,0,0);assertEquals(NONE,exported(noDelta));
        layer.report(noDelta,null);assertEquals(1,layer.logs);errorsAndOperations(noDelta,0,0,0,0);
        assertEquals(NONE,exported(noDelta));
    }

    @Test public void actualLookupTypedHttpCatchKeepsParsePolicyAndRetainsOnlyKnownServerCause()
            throws Exception {
        for(int code:new int[]{429,500,502,503,504,422,401,403,404,408}) {
            String body=code==422?"ExternalDatabaseError":"external database error";
            NGException failure=actualLookupHttpException(code,body);
            ProbeLookup table=new ProbeLookup();Map<String,String> cache=table.getData();table.readFailure=failure;
            SyncResult result=new SyncResult();SyncResultUtil.beginSync(result);table.run(result);
            retainedFailedRead(table,cache);errorsAndOperations(result,0,1,0,0);
            boolean server=code==429||code>=500||code==422;
            assertEquals("Lookup HTTP "+code,server?SERVER:OTHER,exported(result));
            // Same message/supertype remains usable by pre-existing NGException callers.
            assertEquals(NetworkUtil.getError(app(),code),failure.getLocalizedMessage());SyncResultUtil.endSync();
        }
        Throwable[] generic={new NGException("external database error"),new IOException("technical read"),
                new JSONException("technical JSON"),actualLookupHttpException(422,"invalid geometry")};
        for(Throwable failure:generic) {
            ProbeLookup table=new ProbeLookup();Map<String,String> cache=table.getData();table.readFailure=failure;
            SyncResult result=new SyncResult();SyncResultUtil.beginSync(result);table.run(result);
            retainedFailedRead(table,cache);errorsAndOperations(result,0,1,0,0);
            assertEquals(OTHER,exported(result));SyncResultUtil.endSync();
        }
    }

    @Test public void mixedAndLateParseCannotHideBehindKnownServerLookupOrPush() throws Exception {
        SyncResult both=new SyncResult();SyncResultUtil.beginSync(both);
        new ProbeVector().report(both,response(503,"technical"));
        ProbeLookup table=new ProbeLookup();table.readFailure=actualLookupHttpException(503,"technical");
        table.run(both);errorsAndOperations(both,1,1,0,1);assertEquals(SERVER,exported(both));
        Bundle captured=new Bundle();SyncResultUtil.exportAccountFailureSnapshot(captured,both);
        both.stats.numParseExceptions++;
        assertEquals(SERVER|OTHER,exported(both));
        assertEquals(SERVER|OTHER,SyncResultUtil.readAccountFailureReasons(captured,both));
        SyncResultUtil.endSync();
        SyncResult auth=new SyncResult();SyncResultUtil.beginSync(auth);
        new ProbeVector().report(auth,response(503,"technical"));
        new ProbeVector().report(auth,response(401,"technical"));
        errorsAndOperations(auth,1,0,1,1);assertEquals(SERVER|OTHER,exported(auth));
    }

    @Test public void exactOwnerNoargAndEndScopePreventGlobalOrNextAccountReasonLeak() throws Exception {
        SyncResult owner=new SyncResult(),foreign=new SyncResult();SyncResultUtil.beginSync(owner);
        new ProbeVector().report(foreign,response(503,"technical"));
        ProbeLookup foreignLookup=new ProbeLookup();foreignLookup.readFailure=actualLookupHttpException(503,"technical");
        foreignLookup.run(foreign);errorsAndOperations(foreign,1,1,0,1);
        errorsAndOperations(owner,0,0,0,0);assertEquals(NONE,exported(owner));assertEquals(OTHER,exported(foreign));
        SyncResultUtil.endSync();
        SyncResult next=new SyncResult();SyncResultUtil.beginSync(next);
        next.stats.numIoExceptions++;next.stats.numParseExceptions++;
        assertEquals(OTHER,exported(next));SyncResultUtil.endSync();
        SyncResult legacy=new SyncResult();SyncResultUtil.beginSync();
        new ProbeVector().report(legacy,response(503,"technical"));
        errorsAndOperations(legacy,1,0,0,1);assertEquals(OTHER,exported(legacy));
    }

    @Test public void threadLocalOwnershipAndExistingSaveFalseRuntimeFatalPolicyRemainIndependent()
            throws Exception {
        SyncResult main=new SyncResult();SyncResultUtil.beginSync(main);
        new ProbeVector().report(main,response(503,"technical"));
        AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread worker=new Thread(()->{
            try {
                SyncResult unscoped=new SyncResult();new ProbeVector().report(unscoped,response(503,"technical"));
                errorsAndOperations(unscoped,1,0,0,1);assertEquals(OTHER,exported(unscoped));
                SyncResult scoped=new SyncResult();SyncResultUtil.beginSync(scoped);
                new ProbeVector().report(scoped,response(503,"technical"));
                errorsAndOperations(scoped,1,0,0,1);assertEquals(SERVER,exported(scoped));
            } catch(Throwable error) { failure.set(error); }
            finally { SyncResultUtil.endSync(); }
        },"owned-http-reason-fixture");
        ownedThreads.add(worker);worker.start();worker.join(5000);
        assertFalse(worker.isAlive());assertNull(failure.get());assertEquals(SERVER,exported(main));
        errorsAndOperations(main,1,0,0,1);SyncResultUtil.endSync();
        ProbeLookup falseSave=new ProbeLookup();Map<String,String> cache=falseSave.getData();falseSave.saveResult=false;
        SyncResult local=new SyncResult();SyncResultUtil.beginSync(local);falseSave.run(local);
        assertSame(cache,falseSave.getData());assertEquals(falseSave.remote,cache);assertEquals(1,falseSave.saves);
        errorsAndOperations(local,1,0,0,0);assertEquals(OTHER,exported(local));SyncResultUtil.endSync();
        for(Throwable expected:new Throwable[]{new IllegalStateException("technical runtime"),
                new AssertionError("technical fatal")}) {
            ProbeLookup throwing=new ProbeLookup();throwing.saveFailure=expected;SyncResult result=new SyncResult();
            SyncResultUtil.beginSync(result);Throwable actual=null;
            try { throwing.run(result); } catch(RuntimeException|Error error) { actual=error; }
            assertSame(expected,actual);assertEquals(1,throwing.saves);errorsAndOperations(result,0,0,0,0);
            assertEquals(NONE,exported(result));SyncResultUtil.endSync();
        }
    }
}

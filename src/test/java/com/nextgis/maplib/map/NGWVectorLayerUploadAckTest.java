package com.nextgis.maplib.map;

import android.accounts.Account;
import android.content.ContentValues;
import android.content.Context;
import android.content.SyncResult;
import android.database.Cursor;
import com.nextgis.maplib.datasource.*;
import com.nextgis.maplib.datasource.ngw.Connection;
import com.nextgis.maplib.reliability.TestGISApplication;
import com.nextgis.maplib.util.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=NGWVectorLayerUploadAckTest.App.class)
public class NGWVectorLayerUploadAckTest {
    public static class App extends TestGISApplication {
        @Override public Account getAccount(String name) { return new Account("synthetic","test"); }
        @Override public String getAccountUrl(Account a) { return "https://example.invalid"; }
        @Override public String getAccountLogin(Account a) { return "test"; }
        @Override public String getAccountPassword(Account a) { return ""; }
    }
    static class Map extends MapContentProviderHelper {
        Map(Context app,File file) { super(app,file,null,false); }
    }
    static class Layer extends NGWVectorLayer {
        String reply="{\"id\":42}";
        int posts, status=201;
        boolean loseResponse, nullCursor, allowUpdates;
        Runnable duringPost, duringUpdate;
        String updatedPayload;
        final List<String> updatePayloads=new ArrayList<>();
        Feature candidate;
        Layer(Context context,File path) {
            super(context,path); mNGWLayerType=Connection.NGWResourceTypePostgisLayer;
            mNet=new NetworkUtil(context) { @Override public boolean isNetworkAvailable() { return true; } };
        }
        @Override protected HttpResponse addFeatureOnServer(String payload,AccountUtil.AccountData account) throws IOException {
            posts++; if(duringPost!=null) { Runnable action=duringPost;duringPost=null;action.run(); }
            if(loseResponse)throw new IOException("Synthetic response loss after acceptance");
            HttpResponse response=new HttpResponse(status,"synthetic",reply);response.setOk(status>=200&&status<300);return response;
        }
        @Override protected Feature getFeatureFromServer(long id,AccountUtil.AccountData account) { return candidate; }
        @Override protected boolean changeFeatureOnServer(long id,SyncResult result,AccountUtil.AccountData account) {
            return allowUpdates&&super.changeFeatureOnServer(id,result,account);
        }
        @Override protected HttpResponse changeFeatureOnServer(long id,String payload,AccountUtil.AccountData account) {
            updatedPayload=payload;
            updatePayloads.add(payload);
            if(duringUpdate!=null) {Runnable action=duringUpdate;duringUpdate=null;action.run();}
            HttpResponse response=new HttpResponse(200,"synthetic","{}");response.setOk(true);return response;
        }
        @Override protected boolean sendAttachOnServer(long id,long attachment,boolean tus,SyncResult result) { return false; }
        @Override public Cursor query(String[] projection,String selection,String[] args,String order,String limit) {
            return nullCursor ? null : super.query(projection,selection,args,order,limit);
        }
    }
    static class Fixture implements AutoCloseable {
        final App app=(App)RuntimeEnvironment.getApplication();
        final File root=new File(app.getCacheDir(),"ack-"+UUID.randomUUID());
        final Map map;
        final Layer layer;
        final SyncWorkspaceSession session;
        final SyncWorkspaceSession.Scope scope;
        Fixture() throws Exception {
            assertTrue(root.mkdirs());map=new Map(app,new File(root,"map.ngm"));
            layer=new Layer(app,new File(root,"points"));map.addLayer(layer);
            layer.beginBulkImport();layer.create(GeoConstants.GTPoint,
                    Collections.singletonList(new Field(GeoConstants.FTString,"name","Name")));
            layer.endBulkImport();layer.setSyncType(Constants.SYNC_ALL);layer.setSyncDirection(3);
            layer.setAccountName("synthetic");layer.setRemoteId(77);
            session=new SyncWorkspaceSession(map);scope=session.enter();
        }
        long insert(String name) throws Exception {
            ContentValues v=new ContentValues();v.put("name",name);v.put(Constants.FIELD_GEOM,new GeoPoint(1,2).toBlob());
            return layer.insertAddChanges(v);
        }
        long pending() { return FeatureChanges.getChangeCount(map.getDatabase(false),layer.getChangeTableName()); }
        Feature feature(long id) {
            // The independent DB oracle avoids Robolectric/JBR's unsupported raw PFD internals.
            try(Cursor c=map.getDatabase(false).query("points",null,"_id=?",new String[]{Long.toString(id)},null,null,null)) {
                if(!c.moveToFirst())return null;
                Feature feature=new Feature(id,layer.getFields());feature.fromCursor(c);return feature;
            }
        }
        void coalescedUpdate(long id,String name) {
            LayerDatabaseTransaction.run(map.getDatabase(false),()->{
                ContentValues value=new ContentValues();value.put("name",name);
                assertEquals(1,map.getDatabase(false).update("points",value,"_id=?",new String[]{Long.toString(id)}));
                ContentValues queued=new ContentValues();queued.put(Constants.FIELD_FEATURE_ID,id);
                queued.put(Constants.FIELD_OPERATION,Constants.CHANGE_OPERATION_CHANGED);
                queued.put(Constants.FIELD_ATTACH_ID,-1);queued.put(Constants.FIELD_ATTACH_OPERATION,0);
                FeatureChanges.replace(map.getDatabase(false),layer.getChangeTableName(),queued);return null;
            });
        }
        UploadOperationJournal.Entry receipt() {
            for(UploadOperationJournal.Entry entry:UploadOperationJournal.entries(map.getDatabase(false),"points"))
                if("create".equals(entry.operation))return entry;
            throw new AssertionError("Creation receipt is missing");
        }
        @Override public void close() { scope.close();session.close();map.closeSyncWorkspace();FileUtil.deleteRecursive(root); }
    }
    @Test public void invalidResponseRetainsOriginalRowAndOutboxAndNeverRepostsAutomatically() throws Exception {
        for(String body:new String[]{"{}","{\"id\":null}","{\"id\":-1}","{\"id\":\"42\"}","{\"id\":2.5}","broken"}) {
            try(Fixture f=new Fixture()) {
                long id=f.insert("original");f.layer.reply=body;
                assertFalse(f.layer.sendLocalChanges(new SyncResult()));assertEquals(1,f.pending());
                assertNotNull(f.feature(id));assertEquals(UploadOperationJournal.UNCERTAIN,f.receipt().state);
                f.layer.reply="{\"id\":42}";assertFalse(f.layer.sendLocalChanges(new SyncResult()));assertEquals(1,f.layer.posts);
            }
        }
    }
    @Test public void responseLossBlocksOnlyItsOwnFeatureAndOtherCreationContinues() throws Exception {
        try(Fixture f=new Fixture()) {
            f.insert("uncertain");f.layer.loseResponse=true;assertFalse(f.layer.sendLocalChanges(new SyncResult()));
            f.insert("independent");f.layer.loseResponse=false;
            assertFalse(f.layer.sendLocalChanges(new SyncResult()));assertEquals(2,f.layer.posts);assertEquals(1,f.pending());
            assertNotNull(f.feature(42));
        }
    }
    @Test public void nullReadAndOrphanAreErrorsWithoutDroppingTheOutbox() throws Exception {
        try(Fixture f=new Fixture()) {
            long id=f.insert("original");f.layer.nullCursor=true;SyncResult result=new SyncResult();
            assertFalse(f.layer.sendLocalChanges(result));assertTrue(result.databaseError);assertEquals(1,f.pending());assertEquals(0,f.layer.posts);
            f.layer.nullCursor=false;f.map.getDatabase(false).delete("points","_id=?",new String[]{Long.toString(id)});
            assertFalse(f.layer.sendLocalChanges(new SyncResult()));assertEquals(1,f.pending());
            assertEquals(UploadOperationJournal.LOCAL_MISSING,f.receipt().state);assertEquals(0,f.layer.posts);
        }
    }
    @Test public void remapAcknowledgesOnlyItsSnapshotAndMovesPhotosAndSaveUuidTogether() throws Exception {
        try(Fixture f=new Fixture()) {
            long id=f.insert("original");FeatureSaveJournal.record(f.map.getDatabase(false),"points","save-uuid",id);
            File folder=new File(f.layer.getPath(),Long.toString(id));assertTrue(folder.mkdirs());
            byte[] photo={1,9,3,7};Files.write(new File(folder,"10").toPath(),photo);
            f.layer.duringPost=()->{
                ContentValues v=new ContentValues();v.put("name","later");assertEquals(1,f.layer.updateAddChanges(v,id));
                FeatureChanges.add(f.map.getDatabase(false),f.layer.getChangeTableName(),id,10,Constants.CHANGE_OPERATION_NEW);
            };
            assertFalse("Later attachment remains queued",f.layer.sendLocalChanges(new SyncResult()));
            assertNull(f.feature(id));assertNotNull(f.feature(42));
            assertArrayEquals(photo,Files.readAllBytes(new File(f.layer.getPath(),"42/10").toPath()));
            assertEquals(42,FeatureSaveJournal.find(f.map.getDatabase(false),"points","save-uuid"));
            assertEquals("later",f.feature(42).getFieldValue("name"));
            assertEquals(UploadOperationJournal.CONFIRMED,f.receipt().state);
            assertEquals(2,f.pending());
        }
    }
    @Test public void collisionRollsBackLocalRemapAndReceiptPreventsSecondPost() throws Exception {
        try(Fixture f=new Fixture()) {
            long id=f.insert("original");ContentValues other=new ContentValues();other.put("_id",42);
            other.put(Constants.FIELD_GEOM,new GeoPoint(8,9).toBlob());other.put("name","other");f.map.getDatabase(false).insertOrThrow("points",null,other);
            assertFalse(f.layer.sendLocalChanges(new SyncResult()));assertNotNull(f.feature(id));
            assertEquals(1,f.pending());assertEquals(UploadOperationJournal.ACCEPTED,f.receipt().state);
            assertFalse(f.layer.sendLocalChanges(new SyncResult()));assertEquals(1,f.layer.posts);
            assertEquals("other",f.feature(42).getFieldValue("name"));
        }
    }
    @Test public void exactServerReadReconcilesLostResponseWithoutASecondPost() throws Exception {
        try(Fixture f=new Fixture()) {
            long id=f.insert("original");f.layer.loseResponse=true;assertFalse(f.layer.sendLocalChanges(new SyncResult()));
            f.layer.candidate=f.feature(id);f.layer.candidate.setId(91);
            assertTrue(f.layer.reconcileCreatedFeature(f.receipt().id,91));assertEquals(0,f.pending());
            assertNotNull(f.feature(91));assertEquals(1,f.layer.posts);
        }
    }
    @Test public void changingRemoteResourceCannotAuthorizeAnUncertainCreation() throws Exception {
        try(Fixture f=new Fixture()) {
            f.insert("original");f.layer.loseResponse=true;assertFalse(f.layer.sendLocalChanges(new SyncResult()));
            String operation=f.receipt().id;f.layer.setRemoteId(88);
            assertFalse(f.layer.authorizeCreationRetry(operation));assertEquals(1,f.pending());
            assertFalse(f.layer.sendLocalChanges(new SyncResult()));assertEquals(1,f.layer.posts);
        }
    }
    @Test public void recreatingTheQueueCannotReuseAConfirmedReceipt() throws Exception {
        try(Fixture f=new Fixture()) {
            long first=f.insert("first");assertTrue(f.layer.sendLocalChanges(new SyncResult()));
            String confirmed=f.receipt().id;
            FeatureChanges.delete(f.map.getDatabase(false),f.layer.getChangeTableName());
            FeatureChanges.initialize(f.map.getDatabase(false),f.layer.getChangeTableName());
            long second=f.insert("second");assertTrue(second>first);
            f.layer.reply="{\"id\":43}";
            assertTrue(f.layer.sendLocalChanges(new SyncResult()));assertEquals(0,f.pending());
            assertEquals("first",f.feature(42).getFieldValue("name"));
            assertEquals("second",f.feature(43).getFieldValue("name"));assertEquals(2,f.layer.posts);
            assertEquals(2,UploadOperationJournal.entries(f.map.getDatabase(false),"points").size());
            assertEquals(UploadOperationJournal.CONFIRMED,UploadOperationJournal.find(f.map.getDatabase(false),"points",confirmed).state);
            assertNotEquals(confirmed,f.receipt().id);
        }
    }
    @Test public void anEditWhichReusesAChangedQueueRowSurvivesTheOlderSnapshotAck() throws Exception {
        try(Fixture f=new Fixture()) {
            long id=f.insert("initial");ContentValues before=new ContentValues();before.put("name","submitted");
            assertEquals(1,f.layer.updateAddChanges(before,id));
            f.layer.duringPost=()->{
                f.coalescedUpdate(id,"later");
            };
            assertFalse("The later edit still needs a PUT",f.layer.sendLocalChanges(new SyncResult()));
            assertEquals(1,f.pending());assertEquals("later",f.feature(42).getFieldValue("name"));
            assertEquals(UploadOperationJournal.CONFIRMED,f.receipt().state);
            assertTrue(f.receipt().payload.contains("submitted"));assertFalse(f.receipt().payload.contains("later"));
        }
    }
    @Test public void aRetiredUncertainOperationCannotAcknowledgeAnotherGenerationOfTheSameRowIds() throws Exception {
        try(Fixture f=new Fixture()) {
            long id=f.insert("old");f.layer.loseResponse=true;assertFalse(f.layer.sendLocalChanges(new SyncResult()));
            UploadOperationJournal.Entry old=f.receipt();f.layer.candidate=f.feature(id);f.layer.candidate.setId(91);
            f.map.getDatabase(false).delete("points","_id=?",new String[]{Long.toString(id)});
            FeatureChanges.delete(f.map.getDatabase(false),f.layer.getChangeTableName());
            FeatureChanges.initialize(f.map.getDatabase(false),f.layer.getChangeTableName());
            ContentValues feature=new ContentValues();feature.put("_id",id);feature.put("name","new");
            feature.put(Constants.FIELD_GEOM,new GeoPoint(1,2).toBlob());
            f.map.getDatabase(false).insertOrThrow("points",null,feature);
            FeatureChanges.add(f.map.getDatabase(false),f.layer.getChangeTableName(),id,Constants.CHANGE_OPERATION_NEW);
            assertFalse(f.layer.reconcileCreatedFeature(old.id,91));assertFalse(f.layer.authorizeCreationRetry(old.id));
            assertEquals("new",f.feature(id).getFieldValue("name"));assertNull(f.feature(91));assertEquals(1,f.pending());
            f.layer.loseResponse=false;f.layer.reply="{\"id\":43}";
            assertTrue(f.layer.sendLocalChanges(new SyncResult()));assertEquals("new",f.feature(43).getFieldValue("name"));
            assertEquals(UploadOperationJournal.UNCERTAIN,UploadOperationJournal.find(f.map.getDatabase(false),"points",old.id).state);
        }
    }
    @Test public void successfulPutAcknowledgesItsExactSnapshotAndKeepsALaterCoalescedEditPending() throws Exception {
        try(Fixture f=new Fixture()) {
            f.insert("initial");assertTrue(f.layer.sendLocalChanges(new SyncResult()));
            ContentValues submitted=new ContentValues();submitted.put("name","submitted");assertEquals(1,f.layer.updateAddChanges(submitted,42));
            f.layer.allowUpdates=true;f.layer.duringUpdate=()->{
                f.coalescedUpdate(42,"later");
            };
            assertFalse("A partial pass must not report complete delivery",f.layer.sendLocalChanges(new SyncResult()));
            assertEquals(1,f.pending());assertTrue(f.layer.updatedPayload.contains("submitted"));assertFalse(f.layer.updatedPayload.contains("later"));
            assertEquals("later",f.feature(42).getFieldValue("name"));
            assertTrue(f.layer.sendLocalChanges(new SyncResult()));assertEquals(0,f.pending());assertTrue(f.layer.updatedPayload.contains("later"));
            int confirmed=0;
            for(UploadOperationJournal.Entry entry:UploadOperationJournal.page(f.map.getDatabase(false),"points",100,0))
                if("update".equals(entry.operation)&&UploadOperationJournal.CONFIRMED.equals(entry.state))confirmed++;
            assertEquals(2,confirmed);
        }
    }
    @Test public void inFlightIsLiveEvidenceAndCannotSurviveTheFinishedRequest() throws Exception {
        try(Fixture f=new Fixture()) {
            f.insert("initial");String[] identity={null};
            f.layer.duringPost=()->{
                identity[0]=f.receipt().id;
                assertTrue(UploadOperationJournal.isInFlight(f.map.getDatabase(false),"points",identity[0]));
            };
            assertTrue(f.layer.sendLocalChanges(new SyncResult()));
            assertFalse(UploadOperationJournal.isInFlight(f.map.getDatabase(false),"points",identity[0]));
        }
    }
    @Test public void anAppendedLaterEditCanFinishInTheSamePassWithTwoSeparateConfirmedSnapshots() throws Exception {
        try(Fixture f=new Fixture()) {
            f.insert("initial");assertTrue(f.layer.sendLocalChanges(new SyncResult()));
            ContentValues submitted=new ContentValues();submitted.put("name","submitted");f.layer.updateAddChanges(submitted,42);
            f.layer.allowUpdates=true;f.layer.duringUpdate=()->{
                ContentValues next=new ContentValues();next.put("name","later");assertEquals(1,f.layer.updateAddChanges(next,42));
            };
            assertTrue(f.layer.sendLocalChanges(new SyncResult()));assertEquals(0,f.pending());
            assertEquals(2,f.layer.updatePayloads.size());assertTrue(f.layer.updatePayloads.get(0).contains("submitted"));
            assertTrue(f.layer.updatePayloads.get(1).contains("later"));assertEquals("later",f.feature(42).getFieldValue("name"));
        }
    }
    @Test public void explicitReviewReservesWithoutPostingAndRefusesStaleOrFormOwnedRows() throws Exception {
        try(Fixture f=new Fixture()) {
            long id=f.insert("неотправленная точка");
            long change=FeatureChanges.getLastChangeRecordId(f.map.getDatabase(false),f.layer.getChangeTableName());
            String identity=FeatureChanges.operationId(f.map.getDatabase(false),f.layer.getChangeTableName(),change);
            FeatureSaveJournal.holdUpload(f.map.getDatabase(false),"points",id,"form");
            try {f.layer.prepareChangeReview(change,identity,id,-1,"create");fail("Unfinished form reviewed");}
            catch(android.database.sqlite.SQLiteException expected) { }
            assertEquals(0,f.layer.posts);assertTrue(UploadOperationJournal.entries(f.map.getDatabase(false),"points").isEmpty());
            FeatureSaveJournal.releaseUpload(f.map.getDatabase(false),"points","form");
            String reserved=f.layer.prepareChangeReview(change,identity,id,-1,"create");assertEquals(identity,reserved);
            assertEquals(UploadOperationJournal.UNCERTAIN,f.receipt().state);assertEquals(0,f.layer.posts);
            assertFalse(f.layer.sendLocalChanges(new SyncResult()));assertEquals(0,f.layer.posts);
            f.layer.candidate=f.feature(id);f.layer.candidate.setId(91);
            assertTrue(f.layer.reconcileUpload(reserved,91));assertEquals(0,f.pending());assertEquals(0,f.layer.posts);
            try {f.layer.prepareChangeReview(change,identity,id,-1,"create");fail("Retired row reviewed");}
            catch(android.database.sqlite.SQLiteException expected) { }
        }
    }
}

package com.nextgis.maplib.util;

import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;
import org.json.JSONException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, manifest=Config.NONE)
public class UploadOperationJournalTest {
    private static void queue(SQLiteDatabase db,String layer,long id,long feature) {
        String table=layer+"_changes";FeatureChanges.initialize(db,table);
        android.content.ContentValues row=new android.content.ContentValues();row.put(Constants.FIELD_ID,id);
        row.put(Constants.FIELD_FEATURE_ID,feature);row.put(Constants.FIELD_OPERATION,Constants.CHANGE_OPERATION_NEW);
        row.put(Constants.FIELD_ATTACH_ID,-1);row.put(Constants.FIELD_ATTACH_OPERATION,0);
        FeatureChanges.insert(db,table,row);
    }
    private static UploadOperationJournal.Entry begin(SQLiteDatabase db,String layer,long id,long feature,String target,String payload,long boundary) throws Exception {
        return UploadOperationJournal.begin(db,layer,layer+"_changes",id,feature,-1,"create",target,()->payload,boundary);
    }
    @Test public void onlyTheAttemptWhichReservedTheReceiptMaySend() throws Exception {
        try(SQLiteDatabase db=SQLiteDatabase.create(null)) {
            queue(db,"p",1,4);
            UploadOperationJournal.Entry first=begin(db,"p",1,4,"A","{}",1);
            UploadOperationJournal.Entry second=begin(db,"p",1,4,"A","{}",1);
            assertTrue(first.wasStarted());assertFalse(second.wasStarted());assertEquals(first.id,second.id);
        }
    }
    @Test public void onlyTheOwningFormCanReleaseItsHold() {
        try(SQLiteDatabase db=SQLiteDatabase.create(null)) {
            FeatureSaveJournal.holdUpload(db,"A",42,"one");
            FeatureSaveJournal.holdUpload(db,"A",42,"two");
            FeatureSaveJournal.holdUpload(db,"B",42,"one");
            FeatureSaveJournal.releaseUpload(db,"A","one");
            assertTrue(FeatureSaveJournal.isUploadHeld(db,"A",42));
            FeatureSaveJournal.releaseUpload(db,"A","two");
            assertFalse(FeatureSaveJournal.isUploadHeld(db,"A",42));
            assertTrue(FeatureSaveJournal.isUploadHeld(db,"B",42));
        }
    }
    @Test public void failedFeatureTransactionDoesNotLeaveACommittedSaveHold() {
        try(SQLiteDatabase db=SQLiteDatabase.create(null)) {
            try {
                LayerDatabaseTransaction.run(db,()->{
                    FeatureSaveJournal.holdUpload(db,"A",42,"one");throw new IllegalStateException("synthetic rollback");
                }); fail();
            } catch(IllegalStateException expected) { }
            assertFalse(FeatureSaveJournal.isUploadHeld(db,"A",42));
        }
    }
    @Test public void identifiersAreStrictIntegralNonnegativeAndNeverCoerced() throws Exception {
        for (String json : new String[]{"{}", "{\"id\":null}", "{\"id\":true}", "{\"id\":\"42\"}",
                "{\"id\":-1}", "{\"id\":1.5}", "{\"id\":1e2}", "{\"id\":9223372036854775808}"}) {
            try { UploadOperationJournal.serverId(new JSONObject(json)); fail(json); }
            catch (JSONException expected) { }
        }
        assertEquals(0, UploadOperationJournal.serverId(new JSONObject("{\"id\":0}")));
        assertEquals(Long.MAX_VALUE, UploadOperationJournal.serverId(new JSONObject("{\"id\":9223372036854775807}")));
    }
    @Test public void sendingReceiptAndAcceptedIdentifierSurviveReopeningTheDatabase() throws Exception {
        java.io.File file = java.io.File.createTempFile("receipt-", ".db");
        try {
            String id;
            try (SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(file,null)) {
                queue(db,"points",7,10000000);
                UploadOperationJournal.Entry e=begin(db,"points",7,10000000,"A","{}",11);
                id=e.id; assertTrue(e.needsReconciliation());
            }
            try (SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(file,null)) {
                UploadOperationJournal.Entry e=UploadOperationJournal.queued(db,"points","points_changes",7);
                assertEquals(id,e.id); assertEquals(11,e.boundary);
                assertTrue(UploadOperationJournal.blocksFeature(db,"points","points_changes",10000000));
                UploadOperationJournal.state(db,e,UploadOperationJournal.ACCEPTED,42,"server_id_received");
            }
            try (SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(file,null)) {
                assertEquals(42,UploadOperationJournal.find(db,"points",id).remoteId);
                assertNull(UploadOperationJournal.find(db,"other",id));
            }
        } finally { assertTrue(file.delete()); }
    }
    @Test public void rejectedRequestCanRetryButServerFailureRemainsUncertain() throws Exception {
        try (SQLiteDatabase db=SQLiteDatabase.create(null)) {
            queue(db,"p",1,4);
            UploadOperationJournal.Entry e=begin(db,"p",1,4,"A","{}",1);
            UploadOperationJournal.httpFailure(db,e,403);
            assertFalse(UploadOperationJournal.queued(db,"p","p_changes",1).needsReconciliation());
            e=begin(db,"p",1,4,"A","{}",2);
            UploadOperationJournal.httpFailure(db,e,503);
            assertEquals(UploadOperationJournal.UNCERTAIN,UploadOperationJournal.queued(db,"p","p_changes",1).state);
            try {begin(db,"p",1,4,"B","changed",3);fail("A changed target must not retarget a pending operation");}
            catch(android.database.sqlite.SQLiteException expected) { }
            assertEquals(2,UploadOperationJournal.find(db,"p",e.id).boundary);
        }
    }
    @Test public void timeoutAndConflictResponsesCannotAuthorizeAnAutomaticDuplicateCreation() throws Exception {
        for(int status:new int[]{408,409,425,500,503})try(SQLiteDatabase db=SQLiteDatabase.create(null)) {
            queue(db,"p",1,4);UploadOperationJournal.Entry receipt=begin(db,"p",1,4,"A","{}",1);
            UploadOperationJournal.httpFailure(db,receipt,status);
            assertEquals(UploadOperationJournal.UNCERTAIN,UploadOperationJournal.find(db,"p",receipt.id).state);
            assertFalse(begin(db,"p",1,4,"A","{}",1).wasStarted());
        }
    }
    @Test public void anUnreadableSnapshotIsRecordedWithoutEverStartingHttpAndMayBeReadAgain() throws Exception {
        try(SQLiteDatabase db=SQLiteDatabase.create(null)) {
            queue(db,"p",1,4);
            UploadOperationJournal.Entry failed=UploadOperationJournal.begin(db,"p","p_changes",1,4,-1,"create","A",
                    ()->{throw new java.io.IOException("synthetic unavailable source");},1);
            assertFalse(failed.wasStarted());assertEquals(UploadOperationJournal.READ_FAILED,failed.state);
            assertFalse(failed.needsReconciliation());assertTrue(begin(db,"p",1,4,"A","{}",1).wasStarted());
        }
    }
}

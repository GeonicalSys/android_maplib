package com.nextgis.maplib.util;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE)
public class FeatureChangesOperationIdentityTest {
    private static ContentValues row(long id,int operation) {
        ContentValues value=new ContentValues();value.put(Constants.FIELD_ID,id);
        value.put(Constants.FIELD_FEATURE_ID,10000000);value.put(Constants.FIELD_OPERATION,operation);
        value.put(Constants.FIELD_ATTACH_ID,-1);value.put(Constants.FIELD_ATTACH_OPERATION,0);return value;
    }
    private static UploadOperationJournal.Entry begin(SQLiteDatabase db,String layer,String payload) throws Exception {
        return UploadOperationJournal.begin(db,layer,layer+"_changes",1,10000000,-1,"create","target",()->payload,1);
    }
    @Test public void newCreationKeepsItsIdentityButACoalescedEditGetsANewOne() {
        try(SQLiteDatabase db=SQLiteDatabase.create(null)) {
            FeatureChanges.initialize(db,"p_changes");
            FeatureChanges.replace(db,"p_changes",row(1,Constants.CHANGE_OPERATION_NEW));
            String creation=FeatureChanges.operationId(db,"p_changes",1);
            FeatureChanges.replace(db,"p_changes",row(1,Constants.CHANGE_OPERATION_NEW));
            assertEquals(creation,FeatureChanges.operationId(db,"p_changes",1));
            FeatureChanges.replace(db,"p_changes",row(2,Constants.CHANGE_OPERATION_CHANGED));
            String edit=FeatureChanges.operationId(db,"p_changes",2);
            FeatureChanges.replace(db,"p_changes",row(2,Constants.CHANGE_OPERATION_CHANGED));
            assertNotEquals(edit,FeatureChanges.operationId(db,"p_changes",2));
        }
    }
    @Test public void anOldQueueWithoutAttemptIdentityCannotAutomaticallyPostACreation() throws Exception {
        try(SQLiteDatabase db=SQLiteDatabase.create(null)) {
            db.execSQL("CREATE TABLE p_changes(_id INTEGER PRIMARY KEY AUTOINCREMENT,feature_id INTEGER,operation INTEGER,attach_id INTEGER,attach_operation INTEGER)");
            // Use the historic five-column schema and an independent SQL insert.
            ContentValues legacy=row(1,Constants.CHANGE_OPERATION_NEW);
            db.insertOrThrow("p_changes",null,legacy);
            UploadOperationJournal.Entry receipt=begin(db,"p","{}");
            assertFalse(receipt.wasStarted());assertEquals(UploadOperationJournal.UNCERTAIN,receipt.state);
            assertEquals("legacy_creation_outcome_unknown",receipt.reason);
            assertEquals(receipt.id,FeatureChanges.operationId(db,"p_changes",1));
            assertFalse(begin(db,"p","{}").wasStarted());
        }
    }
    @Test public void migratingNumericUniquenessPreservesHistoryAndDoesNotAliasANewQueue() throws Exception {
        try(SQLiteDatabase db=SQLiteDatabase.create(null)) {
            db.execSQL("CREATE TABLE ngm_upload_receipts(operation_id TEXT PRIMARY KEY,layer TEXT NOT NULL,change_id INTEGER NOT NULL,feature_id INTEGER NOT NULL,attach_id INTEGER NOT NULL,operation TEXT NOT NULL,target TEXT NOT NULL,payload TEXT NOT NULL,boundary INTEGER NOT NULL,state TEXT NOT NULL,remote_id INTEGER NOT NULL,reason TEXT NOT NULL,updated INTEGER NOT NULL,UNIQUE(layer,change_id))");
            db.execSQL("INSERT INTO ngm_upload_receipts VALUES('historic','p',1,10000000,-1,'create','target','{\"old\":true}',1,'confirmed',42,'server_confirmed',1)");
            FeatureChanges.initialize(db,"p_changes");FeatureChanges.insert(db,"p_changes",row(1,Constants.CHANGE_OPERATION_NEW));
            UploadOperationJournal.Entry next=begin(db,"p","{\"new\":true}");
            assertTrue(next.wasStarted());assertNotEquals("historic",next.id);
            UploadOperationJournal.Entry historic=UploadOperationJournal.find(db,"p","historic");
            assertEquals(42,historic.remoteId);assertEquals("{\"old\":true}",historic.payload);
            assertEquals(UploadOperationJournal.CONFIRMED,historic.state);assertTrue(historic.acknowledgedChanges.isEmpty());
            assertEquals(2,UploadOperationJournal.entries(db,"p").size());
        }
    }
    @Test public void aDuplicateUuidCannotOverwriteAnotherLayersReceipt() throws Exception {
        try(SQLiteDatabase db=SQLiteDatabase.create(null)) {
            FeatureChanges.initialize(db,"p_changes");FeatureChanges.insert(db,"p_changes",row(1,Constants.CHANGE_OPERATION_NEW));
            UploadOperationJournal.Entry first=begin(db,"p","{\"owner\":\"p\"}");
            FeatureChanges.initialize(db,"q_changes");ContentValues conflicting=row(1,Constants.CHANGE_OPERATION_NEW);
            conflicting.put(FeatureChanges.OPERATION_UUID,first.id);FeatureChanges.insert(db,"q_changes",conflicting);
            try {begin(db,"q","{}");fail("A UUID collision must fail closed");}catch(SQLiteException expected) { }
            assertEquals("{\"owner\":\"p\"}",UploadOperationJournal.find(db,"p",first.id).payload);
            assertNull(UploadOperationJournal.find(db,"q",first.id));
        }
    }
    @Test public void aReceiptLargerThanACursorWindowKeepsItsExactUnicodeSnapshot() throws Exception {
        try(SQLiteDatabase db=SQLiteDatabase.create(null)) {
            FeatureChanges.initialize(db,"p_changes");FeatureChanges.insert(db,"p_changes",row(1,Constants.CHANGE_OPERATION_NEW));
            StringBuilder json=new StringBuilder("{\"text\":\"");
            for(int i=0;i<900000;i++)json.append("а😀");json.append("\"}");String payload=json.toString();
            UploadOperationJournal.Entry receipt=begin(db,"p",payload);
            assertEquals(payload,UploadOperationJournal.find(db,"p",receipt.id).payload);
            assertNull("History pages do not allocate the submitted payload",UploadOperationJournal.page(db,"p",10,0).get(0).payload);
        }
    }
    @Test public void onlyTheFormsSaveUuidMayResolveARemappedSelection() {
        try(SQLiteDatabase db=SQLiteDatabase.create(null)) {
            db.execSQL("CREATE TABLE p(_id INTEGER PRIMARY KEY,name TEXT)");db.execSQL("INSERT INTO p VALUES(42,'kept')");
            FeatureSaveJournal.record(db,"p","form-a",42);
            assertEquals(42,UploadOperationJournal.resolveCreatedId(db,"p",10000000,"form-a"));
            assertEquals(Constants.NOT_FOUND,UploadOperationJournal.resolveCreatedId(db,"p",10000000,"form-b"));
            try(Cursor c=db.rawQuery("SELECT count(*) FROM p",null)) {assertTrue(c.moveToFirst());assertEquals(1,c.getInt(0));}
        }
    }
}

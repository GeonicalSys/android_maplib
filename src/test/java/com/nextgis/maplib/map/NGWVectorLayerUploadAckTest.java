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
import org.json.JSONArray;
import org.json.JSONObject;
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
        int posts, status=201, historyMetadataReads, serverFeatureReads, candidateReads;
        boolean loseResponse, nullCursor, allowUpdates, transactionFixture;
        Runnable duringPost, duringUpdate;
        String updatedPayload;
        final List<String> postedPayloads=new ArrayList<>();
        final List<String> updatePayloads=new ArrayList<>();
        final List<String> transactionRequests=new ArrayList<>();
        Feature candidate;
        final java.util.Map<Long,Feature> serverFeatures=new HashMap<>();
        JSONObject historyMetadata;
        JSONArray reviewCandidates=new JSONArray();
        Layer(Context context,File path) {
            super(context,path); mNGWLayerType=Connection.NGWResourceTypePostgisLayer;mNgwVersionMajor=4;
            mNet=new NetworkUtil(context) { @Override public boolean isNetworkAvailable() { return true; } };
        }
        @Override protected HttpResponse addFeatureOnServer(String payload,AccountUtil.AccountData account) throws IOException {
            posts++;postedPayloads.add(payload);
            if(duringPost!=null) { Runnable action=duringPost;duringPost=null;action.run(); }
            if(loseResponse)throw new IOException("Synthetic response loss after acceptance");
            HttpResponse response=new HttpResponse(status,"synthetic",reply);response.setOk(status>=200&&status<300);return response;
        }
        @Override protected Feature getFeatureFromServer(long id,AccountUtil.AccountData account) {
            serverFeatureReads++;return serverFeatures.containsKey(id)?serverFeatures.get(id):candidate;
        }
        @Override protected JSONObject readResourceMetadataForHistory(AccountUtil.AccountData account) {
            historyMetadataReads++;return historyMetadata;
        }
        @Override protected JSONArray readCreationCandidatesForReview(JSONObject payload,AccountUtil.AccountData account,int sourceSrs) {
            candidateReads++;return reviewCandidates;
        }
        @Override protected int reviewedServerMajor(AccountUtil.AccountData account) { return mNgwVersionMajor; }
        @Override protected HttpResponse requestFeatureTransaction(String method,String url,String body,AccountUtil.AccountData account)
                throws IOException {
            if(!transactionFixture)throw new IOException("Unexpected transaction request in plain fixture");
            String path=java.net.URI.create(url).getPath();transactionRequests.add(method+" "+path);
            String reply="GET".equals(method)?"[[0,{\"action\":\"feature.create\",\"fid\":42}]]"
                    :"POST".equals(method)&&path.endsWith("/transaction/")?"{\"id\":501}":"{}";
            HttpResponse response=new HttpResponse(200,"synthetic transaction",reply);response.setOk(true);return response;
        }
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
            this(Collections.singletonList(new Field(GeoConstants.FTString,"name","Name")));
        }
        Fixture(List<Field> fields) throws Exception {
            assertTrue(root.mkdirs());map=new Map(app,new File(root,"map.ngm"));
            layer=new Layer(app,new File(root,"points"));map.addLayer(layer);
            layer.beginBulkImport();layer.create(GeoConstants.GTPoint,fields);
            layer.endBulkImport();layer.setSyncType(Constants.SYNC_ALL);layer.setSyncDirection(3);
            layer.setAccountName("synthetic");layer.setRemoteId(77);
            session=new SyncWorkspaceSession(map);scope=session.enter();
        }
        long insert(String name) throws Exception {
            ContentValues v=new ContentValues();v.put("name",name);v.put(Constants.FIELD_GEOM,new GeoPoint(1,2).toBlob());
            return layer.insertAddChanges(v);
        }
        long insertRaw226Creation(String name) throws Exception {
            android.database.sqlite.SQLiteDatabase db=map.getDatabase(false);
            String changes=layer.getChangeTableName();
            assertEquals(0,FeatureChanges.getChangeCount(db,changes));
            db.execSQL("DROP TABLE \""+changes+"\"");
            db.execSQL("CREATE TABLE \""+changes+"\"(_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    +"feature_id INTEGER,operation INTEGER,attach_id INTEGER,attach_operation INTEGER)");
            long id=10000001;
            ContentValues feature=new ContentValues();feature.put("_id",id);feature.put("name",name);
            feature.put("created_at",1700000000000L);feature.put("created_by","old-client");
            feature.put(Constants.FIELD_GEOM,new GeoPoint(1,2).toBlob());
            assertEquals(id,db.insertOrThrow("points",null,feature));
            raw226Change(id,Constants.CHANGE_OPERATION_NEW,-1,0);
            assertFalse("Real 226 seed must precede operation UUID migration",FeatureChanges.hasOperationIdentity(db,changes));
            assertTrue("No request receipt existed in the old queue",UploadOperationJournal.entries(db,"points").isEmpty());
            layer.mNgwVersionMajor=4;
            return id;
        }
        long raw226Change(long feature,int operation,long attachment,int attachmentOperation) {
            ContentValues change=new ContentValues();change.put(Constants.FIELD_FEATURE_ID,feature);
            change.put(Constants.FIELD_OPERATION,operation);change.put(Constants.FIELD_ATTACH_ID,attachment);
            change.put(Constants.FIELD_ATTACH_OPERATION,attachmentOperation);
            return map.getDatabase(false).insertOrThrow(layer.getChangeTableName(),null,change);
        }
        String reviewCreation(long feature) {
            UploadOperationJournal.Entry receipt=receipt();
            return layer.prepareChangeReview(receipt.changeId,receipt.id,feature,-1,"create");
        }
        long pending() { return FeatureChanges.getChangeCount(map.getDatabase(false),layer.getChangeTableName()); }
        Set<String> columns() {
            Set<String> names=new HashSet<>();
            try(Cursor cursor=map.getDatabase(false).rawQuery("PRAGMA table_info(points)",null)) {
                while(cursor.moveToNext())names.add(cursor.getString(cursor.getColumnIndexOrThrow("name")));
            }
            return names;
        }
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
    private static final String[] HISTORY_FIELDS={"created_at","updated_at","deleted_at",
            "created_by","updated_by","deleted_by","deletion_operation_id"};
    private static List<Field> historyFields() {
        List<Field> fields=new ArrayList<>();fields.add(new Field(GeoConstants.FTString,"name","Name"));
        for(String key:new String[]{"created_at","updated_at","deleted_at"})
            fields.add(new Field(GeoConstants.FTDateTime,key,key));
        for(String key:new String[]{"created_by","updated_by","deleted_by"})
            fields.add(new Field(GeoConstants.FTString,key,key));
        fields.add(new Field(GeoConstants.FTLong,"deletion_operation_id","Deletion operation"));
        fields.add(new Field(GeoConstants.FTInteger,"nullable_count","Nullable count"));
        fields.add(new Field(GeoConstants.FTReal,"nullable_measure","Nullable measure"));
        fields.add(new Field(GeoConstants.FTLong,"nullable_identifier","Nullable identifier"));
        return fields;
    }
    private static List<Field> legacyHistoryFields() {
        List<Field> fields=historyFields();
        fields.removeIf(field->field.getName().equals("deleted_at")||field.getName().equals("deleted_by")
                ||field.getName().equals("deletion_operation_id"));
        return fields;
    }
    private static JSONObject historyMetadata(String table) throws Exception {
        JSONArray fields=new JSONArray();
        fields.put(new JSONObject().put("keyname","name").put("datatype","STRING"));
        for(String key:HISTORY_FIELDS) {
            String type=key.endsWith("_at")?"DATETIME":key.equals("deletion_operation_id")?"BIGINT":"STRING";
            fields.put(new JSONObject().put("keyname",key).put("datatype",type));
        }
        fields.put(new JSONObject().put("keyname","nullable_count").put("datatype","INTEGER"));
        fields.put(new JSONObject().put("keyname","nullable_measure").put("datatype","REAL"));
        fields.put(new JSONObject().put("keyname","nullable_identifier").put("datatype","BIGINT"));
        return new JSONObject().put("resource",new JSONObject().put("id",77).put("cls","postgis_layer"))
                .put("postgis_layer",new JSONObject().put("schema","dynamic").put("table",table))
                .put("feature_layer",new JSONObject().put("fields",fields));
    }
    private static JSONObject historyMetadata(String table,boolean legacyServer) throws Exception {
        JSONObject metadata=historyMetadata(table);
        if(legacyServer) {
            JSONArray fields=metadata.getJSONObject("feature_layer").getJSONArray("fields");
            for(int i=fields.length()-1;i>=0;i--) {
                String key=fields.getJSONObject(i).getString("keyname");
                if(key.equals("deleted_at")||key.equals("deleted_by")||key.equals("deletion_operation_id"))fields.remove(i);
            }
        }
        return metadata;
    }
    private static ContentValues serverHistoryValues() {
        ContentValues values=new ContentValues();
        values.put("created_at",1700000000000L);values.put("updated_at",1700000001000L);
        values.put("deleted_at",1700000002000L);values.put("created_by","server-creator");
        values.put("updated_by","server-editor");values.put("deleted_by","server-deleter");
        values.put("deletion_operation_id",9007199254740993L);return values;
    }
    private static void assertHistoryOmitted(String payload) throws Exception {
        JSONObject fields=new JSONObject(payload).getJSONObject("fields");
        for(String key:HISTORY_FIELDS)assertFalse("Server-owned field submitted: "+key,fields.has(key));
        assertEquals(0,fields.getInt("nullable_count"));
        assertEquals(0.0,fields.getDouble("nullable_measure"),0.0);
        assertEquals(0L,fields.getLong("nullable_identifier"));
    }
    @Test public void unboundLayerKeepsItsPendingEdits() throws Exception {
        try (Fixture fixture = new Fixture()) {
            long id = fixture.insert("pending point");
            boolean editable = fixture.layer.isEditingAllowed();
            for (String account : new String[]{null, ""}) {
                fixture.layer.setAccountName(account);
                SyncResult result = new SyncResult();
                assertTrue(fixture.layer.isRemoteReadOnly());
                assertEquals(editable, fixture.layer.isEditingAllowed());
                assertTrue(fixture.layer.sendLocalChanges(result));
                fixture.layer.sync("test.reliability", null, result);
                assertFalse(result.hasError());
                assertEquals(0, fixture.layer.historyMetadataReads);
                assertEquals(0, fixture.layer.posts);
                assertEquals(1, fixture.pending());
                assertNotNull(fixture.feature(id));
            }
        }
    }

    @Test public void actual226QueueRemainsUnknownUntilAnExplicitReviewAuthorizesItsFirstNewRequest() throws Exception {
        try(Fixture f=new Fixture(legacyHistoryFields())) {
            long id=f.insertRaw226Creation("226 offline point");Set<String> schema=f.columns();
            f.layer.historyMetadata=historyMetadata("field_points");
            assertFalse(f.layer.sendLocalChanges(new SyncResult()));
            UploadOperationJournal.Entry unknown=f.receipt();
            assertEquals(UploadOperationJournal.UNCERTAIN,unknown.state);
            assertEquals("legacy_creation_outcome_unknown",unknown.reason);
            assertEquals(0,f.layer.posts);assertEquals(1,f.pending());assertNotNull(f.feature(id));
            assertHistoryOmitted(unknown.payload);
            assertFalse(f.layer.sendLocalChanges(new SyncResult()));
            assertEquals(unknown.payload,f.receipt().payload);assertEquals(0,f.layer.posts);
            assertEquals(unknown.id,f.reviewCreation(id));
            assertTrue(f.layer.authorizeCreationRetry(unknown.id));
            assertTrue(f.layer.sendLocalChanges(new SyncResult()));
            assertEquals(1,f.layer.posts);assertEquals(0,f.pending());
            assertEquals("226 offline point",f.feature(42).getFieldValue("name"));
            assertNull("ACK remaps the same row to the server ID",f.feature(id));
            assertEquals(schema,f.columns());assertHistoryOmitted(f.layer.postedPayloads.get(0));
            assertEquals(UploadOperationJournal.CONFIRMED,f.receipt().state);
        }
    }
    @Test public void acceptedPreUuidPostCanBeAcknowledgedByOperatorChosenServerIdWithoutAnotherPost() throws Exception {
        try(Fixture f=new Fixture(legacyHistoryFields())) {
            long id=f.insertRaw226Creation("accepted by old APK before response loss");
            Feature accepted=f.feature(id);accepted.setId(91);f.layer.serverFeatures.put(91L,accepted);
            f.layer.historyMetadata=historyMetadata("field_points");
            assertFalse(f.layer.sendLocalChanges(new SyncResult()));
            String reviewed=f.reviewCreation(id),frozen=f.receipt().payload;
            assertTrue(f.layer.reconcileCreatedFeature(reviewed,91));
            assertEquals(0,f.layer.posts);assertEquals(0,f.pending());assertNull(f.feature(id));
            assertEquals("accepted by old APK before response loss",f.feature(91).getFieldValue("name"));
            assertEquals(frozen,f.receipt().payload);assertEquals(UploadOperationJournal.CONFIRMED,f.receipt().state);
            assertEquals(1,f.layer.serverFeatures.size());
        }
    }
    @Test public void twoIdenticalPreexistingServerObjectsCannotAutomaticallyResolveAPreUuidCreation() throws Exception {
        try(Fixture f=new Fixture(legacyHistoryFields())) {
            long id=f.insertRaw226Creation("identical is not identity");
            Feature first=f.feature(id);first.setId(91);f.layer.serverFeatures.put(91L,first);
            Feature second=f.feature(id);second.setId(92);f.layer.serverFeatures.put(92L,second);
            f.layer.historyMetadata=historyMetadata("field_points");
            assertFalse(f.layer.sendLocalChanges(new SyncResult()));
            assertFalse(f.layer.sendLocalChanges(new SyncResult()));
            assertEquals(0,f.layer.posts);assertEquals(0,f.layer.serverFeatureReads);
            assertEquals(1,f.pending());assertNotNull(f.feature(id));assertNull(f.feature(91));assertNull(f.feature(92));
            assertEquals(2,f.layer.serverFeatures.size());assertEquals(UploadOperationJournal.UNCERTAIN,f.receipt().state);
        }
    }
    @Test public void candidateLookupReportsBothExact226MatchesWithoutChoosingEitherOne() throws Exception {
        try(Fixture f=new Fixture(legacyHistoryFields())) {
            long id=f.insertRaw226Creation("two independent matching objects");
            f.layer.historyMetadata=historyMetadata("field_points");
            assertFalse(f.layer.sendLocalChanges(new SyncResult()));
            String reviewed=f.reviewCreation(id),frozen=f.receipt().payload;
            f.layer.reviewCandidates.put(new JSONObject(frozen).put("id",91));
            f.layer.reviewCandidates.put(new JSONObject(frozen).put("id",92));
            var candidates=f.layer.findCreationCandidates(reviewed);
            assertEquals(new HashSet<>(Arrays.asList(91L,92L)),new HashSet<>(candidates.serverIds));
            assertFalse(candidates.truncated);assertEquals(1,f.layer.candidateReads);
            assertEquals(0,f.layer.posts);assertEquals(1,f.pending());assertNotNull(f.feature(id));
            assertNull(f.feature(91));assertNull(f.feature(92));assertEquals(frozen,f.receipt().payload);
            assertEquals(UploadOperationJournal.UNCERTAIN,f.receipt().state);
        }
    }
    @Test public void candidateLookupRequiresBothFullGeometryAndSubmittedBusinessValuesAndNeverAutoAcksOne() throws Exception {
        try(Fixture f=new Fixture(legacyHistoryFields())) {
            long id=f.insertRaw226Creation("submitted business values");
            f.layer.historyMetadata=historyMetadata("field_points");
            assertFalse(f.layer.sendLocalChanges(new SyncResult()));
            String reviewed=f.reviewCreation(id),frozen=f.receipt().payload;
            JSONObject differentValue=new JSONObject(frozen).put("id",90);
            differentValue.getJSONObject("fields").put("name","another object");
            f.layer.reviewCandidates.put(differentValue);
            f.layer.reviewCandidates.put(new JSONObject(frozen).put("id",91).put("geom","POINT (8 9)"));
            f.layer.reviewCandidates.put(new JSONObject(frozen).put("id",92));
            var candidates=f.layer.findCreationCandidates(reviewed);
            assertEquals(Collections.singletonList(92L),candidates.serverIds);assertFalse(candidates.truncated);
            assertEquals(0,f.layer.posts);assertEquals(1,f.pending());assertNotNull(f.feature(id));
            assertNull(f.feature(92));assertEquals(frozen,f.receipt().payload);
            assertEquals(UploadOperationJournal.UNCERTAIN,f.receipt().state);
        }
    }
    @Test public void reconcilingAFrozen226ReviewRemapsButCannotAcknowledgeALaterLocalEdit() throws Exception {
        try(Fixture f=new Fixture(legacyHistoryFields())) {
            long id=f.insertRaw226Creation("submitted before upgrade");
            Feature accepted=f.feature(id);accepted.setId(91);f.layer.serverFeatures.put(91L,accepted);
            f.layer.historyMetadata=historyMetadata("field_points");
            assertFalse(f.layer.sendLocalChanges(new SyncResult()));
            String reviewed=f.reviewCreation(id),frozen=f.receipt().payload;
            ContentValues later=new ContentValues();later.put("name","edited after review");
            assertEquals(1,f.layer.updateAddChanges(later,id));assertEquals(2,f.pending());
            assertTrue(f.layer.reconcileCreatedFeature(reviewed,91));
            assertEquals(frozen,f.receipt().payload);assertEquals(1,f.pending());assertEquals(0,f.layer.posts);
            assertEquals("edited after review",f.feature(91).getFieldValue("name"));
            f.layer.serverFeatures.clear();f.layer.allowUpdates=true;
            assertTrue(f.layer.sendLocalChanges(new SyncResult()));
            assertEquals(0,f.layer.posts);assertEquals(0,f.pending());assertEquals(1,f.layer.updatePayloads.size());
            assertEquals("edited after review",new JSONObject(f.layer.updatedPayload).getJSONObject("fields").getString("name"));
            assertEquals("edited after review",f.feature(91).getFieldValue("name"));
        }
    }
    @Test public void explicitlyRetried226CreationRetainsItsPhotoQueueAndBytesThroughFidRemap() throws Exception {
        try(Fixture f=new Fixture(legacyHistoryFields())) {
            long id=f.insertRaw226Creation("226 point with photo");
            File photos=new File(f.layer.getPath(),Long.toString(id));assertTrue(photos.mkdirs());
            byte[] original={7,2,6,2,1,0};Files.write(new File(photos,"10").toPath(),original);
            f.raw226Change(id,Constants.CHANGE_OPERATION_ATTACH,10,Constants.CHANGE_OPERATION_NEW);
            assertFalse(FeatureChanges.hasOperationIdentity(f.map.getDatabase(false),f.layer.getChangeTableName()));
            f.layer.historyMetadata=historyMetadata("field_points");
            assertFalse(f.layer.sendLocalChanges(new SyncResult()));
            assertEquals(2,f.pending());assertEquals(0,f.layer.posts);
            String reviewed=f.reviewCreation(id);assertTrue(f.layer.authorizeCreationRetry(reviewed));
            assertFalse("The unsent photo keeps the pass incomplete",f.layer.sendLocalChanges(new SyncResult()));
            assertEquals(1,f.layer.posts);assertEquals(1,f.pending());assertNotNull(f.feature(42));assertNull(f.feature(id));
            assertArrayEquals(original,Files.readAllBytes(new File(f.layer.getPath(),"42/10").toPath()));
            try(Cursor queued=f.map.getDatabase(false).query(f.layer.getChangeTableName(),null,null,null,null,null,null)) {
                assertTrue(queued.moveToFirst());assertEquals(42,queued.getLong(queued.getColumnIndexOrThrow(Constants.FIELD_FEATURE_ID)));
                assertEquals(10,queued.getLong(queued.getColumnIndexOrThrow(Constants.FIELD_ATTACH_ID)));assertFalse(queued.moveToNext());
            }
            assertEquals(UploadOperationJournal.CONFIRMED,f.receipt().state);
        }
    }
    @Test public void legacyLinkKeepsEditsAndPhotos() throws Exception {
        try(Fixture f=new Fixture(legacyHistoryFields())) {
            long id=f.insertRaw226Creation("local offline value");
            File photos=new File(f.layer.getPath(),Long.toString(id));assertTrue(photos.mkdirs());
            byte[] original={2,2,6,9,1};Files.write(new File(photos,"10").toPath(),original);
            f.raw226Change(id,Constants.CHANGE_OPERATION_ATTACH,10,Constants.CHANGE_OPERATION_NEW);
            Feature remote=f.feature(id);remote.setId(91);assertTrue(remote.setFieldValue("name","older server value"));
            f.layer.serverFeatures.put(91L,remote);f.layer.historyMetadata=historyMetadata("field_points");
            assertFalse(f.layer.sendLocalChanges(new SyncResult()));String reviewed=f.reviewCreation(id);
            assertTrue(f.layer.linkReviewedCreation(reviewed,91));
            assertEquals(0,f.layer.posts);assertEquals(2,f.pending());assertNull(f.feature(id));
            assertEquals("local offline value",f.feature(91).getFieldValue("name"));
            assertTrue(f.feature(91).getGeometry().equals(new GeoPoint(1,2)));
            assertArrayEquals(original,Files.readAllBytes(new File(f.layer.getPath(),"91/10").toPath()));
            try(Cursor count=f.map.getDatabase(false).rawQuery("SELECT count(*) FROM points",null)) {
                assertTrue(count.moveToFirst());assertEquals(1,count.getInt(0));
            }
            int updates=0;
            try(Cursor changes=f.map.getDatabase(false).query(f.layer.getChangeTableName(),null,null,null,null,null,null)) {
                while(changes.moveToNext())if(changes.getInt(changes.getColumnIndexOrThrow(Constants.FIELD_OPERATION))
                        ==Constants.CHANGE_OPERATION_CHANGED) {
                    updates++;assertEquals(91,changes.getLong(changes.getColumnIndexOrThrow(Constants.FIELD_FEATURE_ID)));
                    assertNotNull(changes.getString(changes.getColumnIndexOrThrow(FeatureChanges.OPERATION_UUID)));
                }
            }
            assertEquals(1,updates);f.layer.serverFeatures.clear();f.layer.allowUpdates=true;
            assertFalse("The photo remains pending after the one PUT",f.layer.sendLocalChanges(new SyncResult()));
            assertEquals(0,f.layer.posts);assertEquals(1,f.layer.updatePayloads.size());assertEquals(1,f.pending());
            assertEquals("local offline value",new JSONObject(f.layer.updatedPayload).getJSONObject("fields").getString("name"));
            assertArrayEquals(original,Files.readAllBytes(new File(f.layer.getPath(),"91/10").toPath()));
        }
    }
    @Test public void explicitlyLinkingA226CreationCannotOverwriteAnExistingLocalTargetRow() throws Exception {
        try(Fixture f=new Fixture(legacyHistoryFields())) {
            long id=f.insertRaw226Creation("local offline value");
            ContentValues existing=new ContentValues();existing.put("_id",91);existing.put("name","existing local row");
            existing.put(Constants.FIELD_GEOM,new GeoPoint(8,9).toBlob());
            f.map.getDatabase(false).insertOrThrow("points",null,existing);
            Feature remote=f.feature(id);remote.setId(91);f.layer.serverFeatures.put(91L,remote);
            f.layer.historyMetadata=historyMetadata("field_points");
            assertFalse(f.layer.sendLocalChanges(new SyncResult()));String reviewed=f.reviewCreation(id),frozen=f.receipt().payload;
            assertFalse(f.layer.linkReviewedCreation(reviewed,91));
            assertEquals(0,f.layer.posts);assertEquals(1,f.pending());assertEquals(frozen,f.receipt().payload);
            assertEquals("local offline value",f.feature(id).getFieldValue("name"));
            assertEquals("existing local row",f.feature(91).getFieldValue("name"));
            assertTrue(f.feature(91).getGeometry().equals(new GeoPoint(8,9)));
            assertEquals(UploadOperationJournal.UNCERTAIN,f.receipt().state);
            try(Cursor count=f.map.getDatabase(false).rawQuery("SELECT count(*) FROM points",null)) {
                assertTrue(count.moveToFirst());assertEquals(2,count.getInt(0));
            }
        }
    }
    @Test public void committedTransactionIdentityCannotBeReplacedByAnotherIdenticalServerObjectBeforeLocalAck() throws Exception {
        try(Fixture f=new Fixture(legacyHistoryFields())) {
            long id=f.insertRaw226Creation("known transaction identity");
            f.layer.historyMetadata=historyMetadata("field_points");
            f.layer.historyMetadata.getJSONObject("postgis_layer").put("srs",new JSONObject().put("id",3857));
            assertFalse(f.layer.sendLocalChanges(new SyncResult()));String reviewed=f.reviewCreation(id);
            f.layer.mNgwVersionMajor=5;f.layer.mCRS=GeoConstants.CRS_WEB_MERCATOR;f.layer.transactionFixture=true;
            assertTrue(f.layer.authorizeCreationRetry(reviewed));
            // Fail after the transaction result is durable but before the feature receipt/local ACK.
            f.map.getDatabase(false).execSQL("CREATE TRIGGER abort_reviewed_accept BEFORE UPDATE OF state ON ngm_upload_receipts "
                    +"WHEN NEW.state='accepted' BEGIN SELECT RAISE(ABORT,'simulated local ACK failure'); END");
            try {assertFalse(f.layer.sendLocalChanges(new SyncResult()));}
            catch(android.database.sqlite.SQLiteException expected) {
                assertTrue(expected.getMessage(),expected.getMessage().contains("simulated local ACK failure"));
            } finally {f.map.getDatabase(false).execSQL("DROP TRIGGER abort_reviewed_accept");}
            UploadTransactionJournal.Entry transaction=UploadTransactionJournal.find(f.map.getDatabase(false),"points",reviewed);
            assertNotNull(transaction);assertEquals(UploadTransactionJournal.COMMITTED,transaction.phase);
            assertEquals(42,transaction.remoteId);assertTrue(f.receipt().remoteId<0);
            Feature accepted=f.feature(id);accepted.setId(42);f.layer.serverFeatures.put(42L,accepted);
            Feature identical=f.feature(id);identical.setId(43);f.layer.serverFeatures.put(43L,identical);
            assertFalse(f.layer.reconcileCreatedFeature(reviewed,43));
            assertFalse(f.layer.linkReviewedCreation(reviewed,43));
            assertEquals(1,f.pending());assertNotNull(f.feature(id));assertNull(f.feature(43));assertEquals(0,f.layer.posts);
            assertTrue(f.layer.reconcileCreatedFeature(reviewed,42));
            assertEquals(0,f.pending());assertNull(f.feature(id));assertNotNull(f.feature(42));assertNull(f.feature(43));
            assertEquals(0,f.layer.posts);assertEquals(4,f.layer.transactionRequests.size());
        }
    }
    @Test public void legacyPendingCreateUsesFreshProofWithoutReloadingItsSchema() throws Exception {
        for(boolean legacyServer:new boolean[]{true,false})try(Fixture f=new Fixture(legacyHistoryFields())) {
            long id=f.insert("collected before APK update");Set<String> columns=f.columns();
            assertFalse(columns.contains("deletion_operation_id"));
            assertEquals(1,f.pending());assertEquals(0,f.layer.posts);
            assertTrue(UploadOperationJournal.entries(f.map.getDatabase(false),"points").isEmpty());

            f.layer.historyMetadata=historyMetadata("field_points",legacyServer);
            assertTrue(f.layer.sendLocalChanges(new SyncResult()));
            assertEquals(1,f.layer.historyMetadataReads);assertEquals(1,f.layer.posts);assertEquals(0,f.pending());
            assertHistoryOmitted(f.layer.postedPayloads.get(0));
            assertEquals(f.receipt().payload,f.layer.postedPayloads.get(0));
            assertEquals("collected before APK update",f.feature(42).getFieldValue("name"));
            assertNull(f.feature(id));assertEquals(columns,f.columns());
        }
    }
    @Test public void legacyRejectedCreateResnapshotsAfterUpgrade() throws Exception {
        for(boolean legacyServer:new boolean[]{true,false})try(Fixture f=new Fixture(legacyHistoryFields())) {
            f.insert("rejected before APK update");Set<String> columns=f.columns();
            f.layer.status=403;assertFalse(f.layer.sendLocalChanges(new SyncResult()));
            UploadOperationJournal.Entry before=f.receipt();
            assertEquals(UploadOperationJournal.REJECTED,before.state);
            assertTrue(new JSONObject(before.payload).getJSONObject("fields").has("created_at"));

            f.layer.historyMetadata=historyMetadata("field_points",legacyServer);f.layer.status=201;
            assertTrue(f.layer.sendLocalChanges(new SyncResult()));
            UploadOperationJournal.Entry after=f.receipt();
            assertEquals(before.id,after.id);assertNotEquals(before.payload,after.payload);
            assertHistoryOmitted(after.payload);assertEquals(after.payload,f.layer.postedPayloads.get(1));
            assertEquals(before.payload,f.layer.postedPayloads.get(0));
            assertEquals(UploadOperationJournal.CONFIRMED,after.state);assertEquals(2,f.layer.posts);assertEquals(0,f.pending());
            assertEquals(columns,f.columns());
        }
    }
    @Test public void legacyPendingUpdateUploadsWithoutReloadingItsSchema() throws Exception {
        for(boolean legacyServer:new boolean[]{true,false})try(Fixture f=new Fixture(legacyHistoryFields())) {
            ContentValues saved=new ContentValues();saved.put("_id",42);saved.put("name","previously synchronized");
            saved.put("created_at",1700000000000L);saved.put("created_by","server-creator");
            saved.put(Constants.FIELD_GEOM,new GeoPoint(1,2).toBlob());
            f.map.getDatabase(false).insertOrThrow("points",null,saved);
            ContentValues edit=new ContentValues();edit.put("name","edited before APK update");
            assertEquals(1,f.layer.updateAddChanges(edit,42));Set<String> columns=f.columns();
            assertEquals(1,f.pending());assertEquals(0,f.layer.posts);assertTrue(f.layer.updatePayloads.isEmpty());

            f.layer.historyMetadata=historyMetadata("field_points",legacyServer);f.layer.allowUpdates=true;
            assertTrue(f.layer.sendLocalChanges(new SyncResult()));
            assertEquals(1,f.layer.historyMetadataReads);assertEquals(0,f.layer.posts);assertEquals(1,f.layer.updatePayloads.size());
            assertHistoryOmitted(f.layer.updatedPayload);assertEquals(0,f.pending());
            List<UploadOperationJournal.Entry> receipts=UploadOperationJournal.entries(f.map.getDatabase(false),"points");
            assertEquals(1,receipts.size());assertEquals("update",receipts.get(0).operation);
            assertEquals(UploadOperationJournal.CONFIRMED,receipts.get(0).state);
            assertEquals(receipts.get(0).payload,f.layer.updatedPayload);
            assertEquals("edited before APK update",f.feature(42).getFieldValue("name"));
            assertEquals(1700000000000L,((Number)f.feature(42).getFieldValue("created_at")).longValue());
            assertEquals("server-creator",f.feature(42).getFieldValue("created_by"));assertEquals(columns,f.columns());
        }
    }
    @Test public void legacy503ReceiptStaysFrozenAfterHistoryProofRefresh() throws Exception {
        try(Fixture f=new Fixture(legacyHistoryFields())) {
            long id=f.insert("outcome unknown before APK update");f.layer.status=503;
            assertFalse(f.layer.sendLocalChanges(new SyncResult()));UploadOperationJournal.Entry before=f.receipt();
            assertEquals(UploadOperationJournal.UNCERTAIN,before.state);assertEquals("http_503",before.reason);
            assertTrue(new JSONObject(before.payload).getJSONObject("fields").has("created_at"));

            f.layer.historyMetadata=historyMetadata("field_points");f.layer.status=201;
            assertFalse(f.layer.sendLocalChanges(new SyncResult()));UploadOperationJournal.Entry after=f.receipt();
            assertEquals(2,f.layer.historyMetadataReads);assertEquals(1,f.layer.posts);assertEquals(1,f.pending());
            assertEquals(before.id,after.id);assertEquals(before.payload,after.payload);
            assertEquals(before.boundary,after.boundary);assertEquals(before.acknowledgedChanges,after.acknowledgedChanges);
            assertEquals(UploadOperationJournal.UNCERTAIN,after.state);assertEquals("http_503",after.reason);
            assertNotNull(f.feature(id));assertNull(f.feature(42));
        }
    }
    @Test public void verifiedHistoryIsOmittedFromCreateAndUpdateWithoutChangingBusinessNumbersOrLocalMetadata() throws Exception {
        try(Fixture f=new Fixture(historyFields())) {
            // LocalChangesWorker calls sendLocalChanges directly, without a preceding schema sync.
            f.layer.historyMetadata=historyMetadata("field_points");long id=f.insert("original");
            assertEquals(1,f.map.getDatabase(false).update("points",serverHistoryValues(),"_id=?",new String[]{Long.toString(id)}));
            assertTrue(f.layer.sendLocalChanges(new SyncResult()));assertEquals(0,f.pending());
            assertEquals(1,f.layer.historyMetadataReads);
            assertHistoryOmitted(f.layer.postedPayloads.get(0));
            assertEquals(f.receipt().payload,f.layer.postedPayloads.get(0));
            assertEquals("original",new JSONObject(f.layer.postedPayloads.get(0)).getJSONObject("fields").getString("name"));
            assertEquals(9007199254740993L,((Number)f.feature(42).getFieldValue("deletion_operation_id")).longValue());

            ContentValues edited=serverHistoryValues();edited.put("name","edited");
            assertEquals(1,f.layer.updateAddChanges(edited,42));f.layer.allowUpdates=true;
            assertTrue(f.layer.sendLocalChanges(new SyncResult()));assertEquals(0,f.pending());
            assertEquals("Each direct upload pass obtains fresh metadata",2,f.layer.historyMetadataReads);
            assertHistoryOmitted(f.layer.updatedPayload);
            assertEquals("edited",new JSONObject(f.layer.updatedPayload).getJSONObject("fields").getString("name"));
            UploadOperationJournal.Entry update=null;
            for(UploadOperationJournal.Entry entry:UploadOperationJournal.entries(f.map.getDatabase(false),"points"))
                if("update".equals(entry.operation))update=entry;
            assertNotNull(update);assertEquals(UploadOperationJournal.CONFIRMED,update.state);
            assertEquals(update.payload,f.layer.updatedPayload);
            assertEquals(9007199254740993L,((Number)f.feature(42).getFieldValue("deletion_operation_id")).longValue());
        }
    }
    @Test public void newHistoryProofDoesNotRewriteOrResendAnUncertainLegacyCreation() throws Exception {
        try(Fixture f=new Fixture(historyFields())) {
            long id=f.insert("legacy");f.layer.loseResponse=true;assertFalse(f.layer.sendLocalChanges(new SyncResult()));
            UploadOperationJournal.Entry before=f.receipt();
            assertEquals(0L,new JSONObject(before.payload).getJSONObject("fields").getLong("deletion_operation_id"));
            assertEquals(UploadOperationJournal.UNCERTAIN,before.state);
            f.layer.historyMetadata=historyMetadata("field_points");assertFalse(f.layer.sendLocalChanges(new SyncResult()));
            UploadOperationJournal.Entry after=f.receipt();
            assertEquals(before.id,after.id);assertEquals(before.payload,after.payload);
            assertEquals(UploadOperationJournal.UNCERTAIN,after.state);assertEquals(1,f.layer.posts);assertEquals(1,f.pending());
            assertEquals(2,f.layer.historyMetadataReads);
            assertNull(f.feature(id).getFieldValue("deletion_operation_id"));
        }
    }
    @Test public void explicitlyAuthorizedLegacyRetryPersistsExactlyTheNewSanitizedPayloadThatIsSent() throws Exception {
        try(Fixture f=new Fixture(historyFields())) {
            f.insert("legacy");f.layer.loseResponse=true;assertFalse(f.layer.sendLocalChanges(new SyncResult()));
            UploadOperationJournal.Entry before=f.receipt();f.layer.historyMetadata=historyMetadata("field_points");
            assertTrue(f.layer.authorizeCreationRetry(before.id));
            assertEquals(before.payload,f.receipt().payload);assertEquals(1,f.layer.posts);
            f.layer.loseResponse=false;assertTrue(f.layer.sendLocalChanges(new SyncResult()));
            UploadOperationJournal.Entry after=f.receipt();
            assertEquals(before.id,after.id);assertNotEquals(before.payload,after.payload);
            assertHistoryOmitted(after.payload);assertEquals(after.payload,f.layer.postedPayloads.get(1));
            assertEquals(before.payload,f.layer.postedPayloads.get(0));
            assertEquals(UploadOperationJournal.CONFIRMED,after.state);assertEquals(2,f.layer.posts);assertEquals(0,f.pending());
            assertEquals(2,f.layer.historyMetadataReads);
        }
    }
    @Test public void legacyHistoryReviewStillChecksBusinessDataAndGeometry() throws Exception {
        try(Fixture f=new Fixture(historyFields())) {
            long id=f.insert("submitted");f.layer.loseResponse=true;assertFalse(f.layer.sendLocalChanges(new SyncResult()));
            UploadOperationJournal.Entry original=f.receipt();
            f.layer.historyMetadata=historyMetadata("field_points");
            Feature remote=f.feature(id);remote.setId(91);
            ContentValues history=serverHistoryValues();
            for(String key:HISTORY_FIELDS)assertTrue(remote.setFieldValue(key,history.get(key)));
            assertTrue(remote.setFieldValue("name","different"));f.layer.candidate=remote;
            assertFalse(f.layer.reconcileCreatedFeature(original.id,91));
            assertEquals(original.payload,f.receipt().payload);assertEquals(1,f.pending());
            assertTrue(remote.setFieldValue("name","submitted"));remote.setGeometry(new GeoPoint(8,9));
            assertFalse(f.layer.reconcileCreatedFeature(original.id,91));
            assertEquals(original.payload,f.receipt().payload);assertEquals(1,f.pending());
            remote.setGeometry(new GeoPoint(1,2));
            assertTrue(f.layer.reconcileCreatedFeature(original.id,91));
            assertEquals(original.payload,f.receipt().payload);assertEquals(UploadOperationJournal.CONFIRMED,f.receipt().state);
            assertEquals(4,f.layer.historyMetadataReads);assertEquals(1,f.layer.posts);assertEquals(0,f.pending());
            assertNotNull(f.feature(91));
        }
    }
    @Test public void anUnrelatedPostgisTableKeepsSameNamedFieldsAndCannotNormalizeAReviewMismatch() throws Exception {
        try(Fixture f=new Fixture(historyFields())) {
            f.layer.historyMetadata=historyMetadata("user_observations");long id=f.insert("ordinary");
            f.layer.loseResponse=true;assertFalse(f.layer.sendLocalChanges(new SyncResult()));
            UploadOperationJournal.Entry receipt=f.receipt();
            JSONObject fields=new JSONObject(receipt.payload).getJSONObject("fields");
            assertTrue(fields.has("created_at"));assertEquals(0L,fields.getLong("deletion_operation_id"));
            Feature remote=f.feature(id);remote.setId(91);assertTrue(remote.setFieldValue("deletion_operation_id",5L));
            f.layer.candidate=remote;assertFalse(f.layer.reconcileCreatedFeature(receipt.id,91));
            assertEquals(receipt.payload,f.receipt().payload);assertEquals(1,f.layer.posts);assertEquals(1,f.pending());
        }
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
            assertNotNull(f.feature(91));assertEquals(1,f.layer.posts);assertEquals(0,f.layer.historyMetadataReads);
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

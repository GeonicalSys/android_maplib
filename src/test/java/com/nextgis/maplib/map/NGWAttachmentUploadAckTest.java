package com.nextgis.maplib.map;

import android.content.ContentValues;
import android.content.Context;
import android.content.SyncResult;
import android.database.Cursor;
import com.nextgis.maplib.datasource.*;
import com.nextgis.maplib.datasource.ngw.Connection;
import com.nextgis.maplib.util.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=NGWVectorLayerUploadAckTest.App.class)
public class NGWAttachmentUploadAckTest {
    private static final long LOCAL=10000000;
    private static final byte[] PHOTO={1,9,3,7};
    static class Layer extends NGWVectorLayer {
        int uploads,associations,metadataUpdates;
        String reply="{\"id\":91}",lastAssociation,lastUpdate;
        boolean loseResponse,allowUpdates;
        Runnable duringAssociation;
        byte[] transferred;
        String reviewFeature;
        boolean reviewedBytesMatch;
        Layer(Context app,File directory) {
            super(app,directory);mNGWLayerType=Connection.NGWResourceTypePostgisLayer;
            mNet=new NetworkUtil(app) {@Override public boolean isNetworkAvailable() {return true;}};
        }
        private HttpResponse response(int code,String body) {
            HttpResponse result=new HttpResponse(code,"synthetic",body);result.setOk(code>=200&&code<300);return result;
        }
        @Override protected HttpResponse sendAttachOnServerViaTus(long feature,AttachItem item) throws IOException {
            uploads++;
            try {
                UploadOperationJournal.Entry receipt=UploadOperationJournal.entries(DatabaseContext.getDatabaseForLayer(this,false),getPath().getName()).get(0);
                transferred=Files.readAllBytes(UploadAttachmentRemap.payloadFile(getPath(),receipt).toPath());
            }catch(JSONException error) {throw new IOException(error);}
            return response(200,"{\"id\":\"temporary-upload\",\"size\":4,\"mime_type\":\"image/jpeg\"}");
        }
        @Override protected HttpResponse sendFeatureAttachOnServer(JSONObject upload,long feature,AttachItem item) throws JSONException,IOException {
            associations++;lastAssociation=item.toJSON().toString();
            if(duringAssociation!=null) {Runnable action=duringAssociation;duringAssociation=null;action.run();}
            if(loseResponse)throw new IOException("Synthetic loss after the association was accepted");
            return response(201,reply);
        }
        @Override protected HttpResponse changeAttachOnServer(long feature,long attachment,String payload) {
            metadataUpdates++;lastUpdate=payload;return response(allowUpdates?200:503,"{}");
        }
        @Override protected HttpResponse readFeatureForReview(long feature,AccountUtil.AccountData account) {return response(200,reviewFeature);}
        @Override protected boolean attachmentBytesMatch(UploadOperationJournal.Entry receipt,long id,AccountUtil.AccountData account) {return reviewedBytesMatch;}
        void metadata(long id,String description) {
            java.util.Map<String,AttachItem> entries=getAttachMap("42");if(entries==null)entries=new HashMap<>();
            entries.put(Long.toString(id),new AttachItem(Long.toString(id),"photo.jpg","image/jpeg",description,PHOTO.length));
            saveAttach("42",entries);
        }
    }
    static class Fixture implements AutoCloseable {
        final NGWVectorLayerUploadAckTest.App app=(NGWVectorLayerUploadAckTest.App)RuntimeEnvironment.getApplication();
        final File root=new File(app.getCacheDir(),"attachment-ack-"+UUID.randomUUID());
        final NGWVectorLayerUploadAckTest.Map map;
        final Layer layer;
        final SyncWorkspaceSession session;
        final SyncWorkspaceSession.Scope scope;
        Fixture() throws Exception {
            assertTrue(root.mkdirs());map=new NGWVectorLayerUploadAckTest.Map(app,new File(root,"map.ngm"));
            layer=new Layer(app,new File(root,"photos"));map.addLayer(layer);layer.beginBulkImport();
            layer.create(GeoConstants.GTPoint,Collections.singletonList(new Field(GeoConstants.FTString,"name","Name")));layer.endBulkImport();
            layer.setSyncType(Constants.SYNC_ALL);layer.setSyncDirection(3);layer.setAccountName("synthetic");layer.setRemoteId(77);
            session=new SyncWorkspaceSession(map);scope=session.enter();
            ContentValues parent=new ContentValues();parent.put("_id",42);parent.put("name","parent");parent.put(Constants.FIELD_GEOM,new GeoPoint(1,2).toBlob());
            map.getDatabase(false).insertOrThrow("photos",null,parent);
            assertTrue(new File(layer.getPath(),"42").mkdirs());
            Files.write(file(LOCAL).toPath(),PHOTO);layer.metadata(LOCAL,"submitted");
            FeatureChanges.add(map.getDatabase(false),layer.getChangeTableName(),42,LOCAL,Constants.CHANGE_OPERATION_NEW);
        }
        File file(long id) {return new File(layer.getPath(),"42/"+id);}
        long pending() {return FeatureChanges.getChangeCount(map.getDatabase(false),layer.getChangeTableName());}
        UploadOperationJournal.Entry receipt() {
            for(UploadOperationJournal.Entry entry:UploadOperationJournal.entries(map.getDatabase(false),"photos"))
                if("attachment_create".equals(entry.operation))return entry;
            throw new AssertionError("Attachment receipt is missing");
        }
        boolean send() {return layer.sendLocalChanges(new SyncResult());}
        @Override public void close() {
            scope.close();session.close();map.closeSyncWorkspace();FileUtil.deleteRecursive(root);assertFalse(root.exists());
        }
    }
    @Test public void acceptedIdentifierMovesExactBytesMetadataAndSaveIdentityBeforeAcknowledgement() throws Exception {
        try(Fixture f=new Fixture()) {
            FeatureSaveJournal.record(f.map.getDatabase(false),"photos/42/attach","photo-save",LOCAL);
            assertTrue(f.send());assertEquals(0,f.pending());assertEquals(1,f.layer.associations);
            assertArrayEquals(PHOTO,f.layer.transferred);assertArrayEquals(PHOTO,Files.readAllBytes(f.file(91).toPath()));assertFalse(f.file(LOCAL).exists());
            assertEquals(UploadOperationJournal.CONFIRMED,f.receipt().state);assertEquals(91,f.receipt().remoteId);
            assertEquals(91,FeatureSaveJournal.find(f.map.getDatabase(false),"photos/42/attach","photo-save"));
            assertTrue(f.layer.getAttachMap("42").containsKey("91"));assertFalse(f.layer.getAttachMap("42").containsKey(Long.toString(LOCAL)));
            assertEquals(0,f.layer.getPath().listFiles(file->file.getName().startsWith(".upload-attachment-")).length);
        }
    }
    @Test public void reconciliationNeedsMatchingServerMetadataAndBytesAndNeverRepeatsAssociation() throws Exception {
        try(Fixture f=new Fixture()) {
            f.layer.loseResponse=true;assertFalse(f.send());assertEquals(1,f.layer.associations);
            f.layer.reviewFeature="{\"id\":42,\"extensions\":{\"attachment\":[{\"id\":91,\"name\":\"other.jpg\",\"description\":\"submitted\"}]}}";
            f.layer.reviewedBytesMatch=true;assertFalse(f.layer.reconcileUpload(f.receipt().id,91));assertEquals(1,f.pending());
            f.layer.reviewFeature=f.layer.reviewFeature.replace("other.jpg","photo.jpg");f.layer.reviewedBytesMatch=false;
            assertFalse(f.layer.reconcileUpload(f.receipt().id,91));assertEquals(1,f.pending());
            f.layer.reviewedBytesMatch=true;assertTrue(f.layer.reconcileUpload(f.receipt().id,91));
            assertEquals(0,f.pending());assertEquals(1,f.layer.associations);assertArrayEquals(PHOTO,Files.readAllBytes(f.file(91).toPath()));
        }
    }
    @Test public void missingInvalidAndCoercedIdsCannotConsumeThePhotoOrRepeatItsAssociation() throws Exception {
        for(String body:new String[]{"{}","{\"id\":null}","{\"id\":\"91\"}","{\"id\":true}","{\"id\":-1}","{\"id\":91.5}"})try(Fixture f=new Fixture()) {
            f.layer.reply=body;assertFalse(f.send());assertEquals(1,f.pending());assertEquals(UploadOperationJournal.UNCERTAIN,f.receipt().state);
            assertArrayEquals(PHOTO,Files.readAllBytes(f.file(LOCAL).toPath()));assertFalse(f.send());assertEquals(1,f.layer.associations);
        }
    }
    @Test public void lostAssociationResponseRetainsItsImmutableInputAndNeverBlindlyPostsAgain() throws Exception {
        try(Fixture f=new Fixture()) {
            f.layer.loseResponse=true;assertFalse(f.send());assertEquals(1,f.pending());
            assertEquals(UploadOperationJournal.UNCERTAIN,f.receipt().state);
            f.layer.loseResponse=false;assertFalse(f.send());assertEquals(1,f.layer.uploads);assertEquals(1,f.layer.associations);
            assertArrayEquals(PHOTO,Files.readAllBytes(f.file(LOCAL).toPath()));
        }
    }
    @Test public void missingAndCorruptLocalMetadataRemainVisibleWithoutAnyHttp() throws Exception {
        try(Fixture f=new Fixture()) {
            File meta=new File(f.layer.getPath(),"42/meta.json");assertTrue(meta.delete());
            assertFalse(f.send());assertEquals(1,f.pending());assertEquals(0,f.layer.uploads);assertEquals(UploadOperationJournal.LOCAL_MISSING,f.receipt().state);
        }
        try(Fixture f=new Fixture()) {
            File meta=new File(f.layer.getPath(),"42/meta.json");byte[] corrupt="{broken".getBytes(java.nio.charset.StandardCharsets.UTF_8);Files.write(meta.toPath(),corrupt);
            assertFalse(f.send());assertEquals(1,f.pending());assertEquals(0,f.layer.associations);assertEquals(UploadOperationJournal.READ_FAILED,f.receipt().state);
            assertArrayEquals(corrupt,Files.readAllBytes(meta.toPath()));
        }
    }
    @Test public void acceptedResponseCanFinishAfterLocalFailureWithoutAnotherUpload() throws Exception {
        try(Fixture f=new Fixture()) {
            Files.write(f.file(91).toPath(),new byte[]{8,8});assertFalse(f.send());
            assertEquals(UploadOperationJournal.ACCEPTED,f.receipt().state);assertEquals(1,f.pending());assertEquals(1,f.layer.associations);
            assertTrue(f.file(91).delete());assertTrue(f.send());assertEquals(0,f.pending());assertEquals(1,f.layer.uploads);assertEquals(1,f.layer.associations);
            assertArrayEquals(PHOTO,Files.readAllBytes(f.file(91).toPath()));
        }
    }
    @Test public void metadataEditedDuringAssociationGetsItsOwnPutAndIsNotConsumedByTheOlderAck() throws Exception {
        try(Fixture f=new Fixture()) {
            f.layer.duringAssociation=()->LayerDatabaseTransaction.run(f.map.getDatabase(false),()->{
                f.layer.metadata(LOCAL,"later");FeatureChanges.add(f.map.getDatabase(false),f.layer.getChangeTableName(),42,LOCAL,Constants.CHANGE_OPERATION_CHANGED);return null;
            });
            assertFalse(f.send());assertEquals(1,f.pending());assertEquals("later",f.layer.getAttachMap("42").get("91").getDescription());
            assertTrue(f.layer.lastAssociation.contains("submitted"));assertFalse(f.layer.lastAssociation.contains("later"));
            assertTrue(f.layer.lastUpdate.contains("later"));f.layer.allowUpdates=true;assertTrue(f.send());assertEquals(0,f.pending());assertEquals(1,f.layer.associations);
        }
    }
    @Test public void anUnboundLegacyPhotoRequiresReviewBeforeANewAssociation() throws Exception {
        try(Fixture f=new Fixture()) {
            f.map.getDatabase(false).execSQL("UPDATE photos_changes SET _operation_uuid=NULL");
            assertFalse(f.send());assertEquals(UploadOperationJournal.UNCERTAIN,f.receipt().state);assertEquals(0,f.layer.uploads);assertEquals(1,f.pending());
        }
    }
}

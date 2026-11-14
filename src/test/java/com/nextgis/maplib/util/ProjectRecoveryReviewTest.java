package com.nextgis.maplib.util;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE)
public class ProjectRecoveryReviewTest {
    private static void features(android.database.sqlite.SQLiteDatabase db) {
        db.execSQL("CREATE TABLE points(_id INTEGER PRIMARY KEY,name TEXT)");db.execSQL("INSERT INTO points VALUES(7,'one'),(8,'two')");
        FeatureChanges.initialize(db,"points_changes");FeatureChanges.add(db,"points_changes",7,Constants.CHANGE_OPERATION_NEW);
        FeatureChanges.add(db,"points_changes",8,Constants.CHANGE_OPERATION_NEW);
    }
    private static String hash(String text) {return UnderlayFiles.hex(UnderlayFiles.digest().digest(text.getBytes(StandardCharsets.UTF_8)));}
    @Test public void archivedCanonicalIdentitySurvivesAMoveButCannotBeRetargeted() throws Exception {
        File root=new File(RuntimeEnvironment.getApplication().getCacheDir(),"review-"+UUID.randomUUID());
        assertTrue(root.mkdirs());
        try {
            File layer=new File(root,"points");assertTrue(layer.mkdirs());
            UnderlayFiles.writeJson(new File(root,"map.ngm"),new JSONObject("{\"layers\":[{\"path\":\"points\"}]}"));
            UnderlayFiles.writeJson(new File(layer,"config.json"),new JSONObject().put("type",Constants.LAYERTYPE_NGW_VECTOR)
                    .put("account","A").put("id",77).put("ngw_layer_type",9));
            JSONObject source=new JSONObject().put("map_name","map").put("workspace_path","/old/device/project")
                    .put("accounts",new JSONArray().put(new JSONObject().put("name","A")
                            .put("server_sha256",hash("https://example.invalid")).put("login_sha256",hash("user"))));
            ProjectRecoveryGuard.mark(root,source);
            assertEquals(UploadOperationJournal.target("/old/device/project","points","A","https://example.invalid","user",77),
                    ProjectRecoveryGuard.originalTarget(root,layer,"A","https://example.invalid","user",77,9));
            for(String[] changed:new String[][]{{"A","https://other.invalid","user","77"},{"A","https://example.invalid","other","77"},
                    {"B","https://example.invalid","user","77"},{"A","https://example.invalid","user","78"}}) {
                try {ProjectRecoveryGuard.originalTarget(root,layer,changed[0],changed[1],changed[2],Long.parseLong(changed[3]),9);fail("Retarget accepted");}
                catch(IOException expected) { }
            }
            JSONObject identity=ProjectRecoveryGuard.heldIdentity(root);ProjectRecoveryGuard.release(root,identity);
            assertFalse(ProjectRecoveryGuard.isHeld(root));assertTrue(ProjectRecoveryGuard.isRecoveryCopy(root));
        }finally {FileUtil.deleteRecursive(root);assertFalse(root.exists());}
    }
    @Test public void mismatchedHoldCannotBeReleasedAndOriginIsPreserved() throws Exception {
        File root=new File(RuntimeEnvironment.getApplication().getCacheDir(),"review-"+UUID.randomUUID());assertTrue(root.mkdirs());
        try {
            ProjectRecoveryGuard.mark(root,new JSONObject().put("map_name","map"));
            byte[] origin=Files.readAllBytes(new File(root,ProjectRecoveryGuard.ORIGIN).toPath());
            JSONObject expected=ProjectRecoveryGuard.heldIdentity(root);
            UnderlayFiles.writeJson(new File(root,ProjectRecoveryGuard.HOLD),new JSONObject().put("different",true));
            try {ProjectRecoveryGuard.release(root,expected);fail("Changed hold released");}catch(IOException correct) { }
            assertTrue(ProjectRecoveryGuard.isHeld(root));assertArrayEquals(origin,Files.readAllBytes(new File(root,ProjectRecoveryGuard.ORIGIN).toPath()));
        }finally {FileUtil.deleteRecursive(root);assertFalse(root.exists());}
    }
    @Test public void deliveryOwnershipSurvivesRestartAndNewRestoreKeepsAncestryWithoutChangingItsSource() throws Exception {
        File root=new File(RuntimeEnvironment.getApplication().getCacheDir(),"ownership-"+UUID.randomUUID());assertTrue(root.mkdirs());
        File source=new File(root,"source"),staging=new File(root,"staging");assertTrue(source.mkdir());assertTrue(staging.mkdir());
        try {
            ProjectRecoveryGuard.mark(source,new JSONObject().put("uid","local:original").put("map_name","map"));
            ProjectRecoveryGuard.release(source,ProjectRecoveryGuard.heldIdentity(source));
            String operation=UUID.randomUUID().toString();
            ProjectRecoveryGuard.blockDelivery(source,"local:first-copy","local:chosen-copy",operation);
            assertTrue(ProjectRecoveryGuard.isSyncBlocked(source));assertFalse(ProjectRecoveryGuard.isHeld(source));
            byte[] before=Files.readAllBytes(new File(source,ProjectRecoveryGuard.DELIVERY_OWNER).toPath());
            Files.copy(new File(source,ProjectRecoveryGuard.ORIGIN).toPath(),new File(staging,ProjectRecoveryGuard.ORIGIN).toPath());
            Files.copy(new File(source,ProjectRecoveryGuard.DELIVERY_OWNER).toPath(),new File(staging,ProjectRecoveryGuard.DELIVERY_OWNER).toPath());
            ProjectRecoveryGuard.mark(staging,new JSONObject().put("uid","local:first-copy").put("map_name","map"));
            assertTrue(ProjectRecoveryGuard.isHeld(staging));assertFalse(ProjectRecoveryGuard.isDeliveryBlocked(staging));
            assertTrue(ProjectRecoveryGuard.family(staging,"local:new-copy").containsAll(java.util.Arrays.asList("local:original","local:first-copy","local:new-copy")));
            assertEquals("local:chosen-copy",ProjectRecoveryGuard.heldIdentity(staging).getJSONObject("prior_delivery_owner").getString("destination_uid"));
            assertArrayEquals(before,Files.readAllBytes(new File(source,ProjectRecoveryGuard.DELIVERY_OWNER).toPath()));
        }finally {FileUtil.deleteRecursive(root);assertFalse(root.exists());}
    }
    @Test public void unknownDeliveryMarkerBlocksSyncAndCannotBeOverwrittenOrDiscardedDuringRestore() throws Exception {
        File root=new File(RuntimeEnvironment.getApplication().getCacheDir(),"ownership-"+UUID.randomUUID());assertTrue(root.mkdirs());
        try {
            byte[] bytes="{\"foreign\":true}".getBytes(StandardCharsets.UTF_8);
            Files.write(new File(root,ProjectRecoveryGuard.DELIVERY_OWNER).toPath(),bytes);
            assertTrue(ProjectRecoveryGuard.isSyncBlocked(root));
            try {ProjectRecoveryGuard.blockDelivery(root,"local:source","local:copy",UUID.randomUUID().toString());fail("Foreign marker replaced");}
            catch(IOException expected) { }
            try {ProjectRecoveryGuard.mark(root,new JSONObject().put("uid","local:source").put("map_name","map"));fail("Foreign marker discarded");}
            catch(IOException expected) { }
            assertArrayEquals(bytes,Files.readAllBytes(new File(root,ProjectRecoveryGuard.DELIVERY_OWNER).toPath()));
            assertFalse(ProjectRecoveryGuard.isRecoveryCopy(root));
        }finally {FileUtil.deleteRecursive(root);assertFalse(root.exists());}
    }
    @Test public void onlyANewCompleteSaveOfTheExactQueueCanReleaseArchivedHoldsWithoutTouchingCurrentOwners() throws Exception {
        try(android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.create(null)) {
            features(db);FeatureSaveJournal.holdUpload(db,"points",7,"archived");FeatureSaveJournal.holdUpload(db,"points",8,"other-feature");
            LayerDatabaseTransaction.run(db,()->{FeatureSaveJournal.completeForm(db,"points","old-full-save",7);return null;});
            JSONObject baseline=FeatureSaveJournal.recoveryBaseline(db);
            assertFalse(LayerDatabaseTransaction.run(db,()->{
                try {return FeatureSaveJournal.releaseRecoveredHolds(db,baseline,"points",7);}catch(JSONException e){throw new AssertionError(e);}
            }));
            FeatureSaveJournal.record(db,"points","feature-only",7);FeatureSaveJournal.holdUpload(db,"points",7,"current-form");
            assertFalse(FeatureSaveJournal.isFormComplete(db,"points","feature-only",7));
            LayerDatabaseTransaction.run(db,()->{
                FeatureSaveJournal.completeForm(db,"points","current-form",7);
                try {assertTrue(FeatureSaveJournal.releaseRecoveredHolds(db,baseline,"points",7));}catch(JSONException e){throw new AssertionError(e);}
                return null;
            });
            assertTrue("The current form still owns its checkpoint",FeatureSaveJournal.isUploadHeld(db,"points",7));
            FeatureSaveJournal.releaseUpload(db,"points","current-form");assertFalse(FeatureSaveJournal.isUploadHeld(db,"points",7));
            assertTrue(FeatureSaveJournal.isUploadHeld(db,"points",8));assertEquals(2,android.database.DatabaseUtils.queryNumEntries(db,"points_changes"));
        }
    }
    @Test public void laterPartialMutationCannotReuseAnEarlierFullCompletionAndRetiredIdsAreNotNewEvidence() throws Exception {
        try(android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.create(null)) {
            features(db);FeatureSaveJournal.holdUpload(db,"points",7,"archived");
            LayerDatabaseTransaction.run(db,()->{FeatureSaveJournal.completeForm(db,"points","retired",7);return null;});
            FeatureSaveJournal.forget(db,"points","retired");JSONObject baseline=FeatureSaveJournal.recoveryBaseline(db);
            assertTrue(baseline.getLong("completion_watermark")>0);
            LayerDatabaseTransaction.run(db,()->{FeatureSaveJournal.completeForm(db,"points","first-complete-copy-save",7);return null;});
            FeatureChanges.add(db,"points_changes",7,Constants.CHANGE_OPERATION_CHANGED);
            assertFalse(LayerDatabaseTransaction.run(db,()->{
                try {return FeatureSaveJournal.releaseRecoveredHolds(db,baseline,"points",7);}catch(JSONException e){throw new AssertionError(e);}
            }));
            assertTrue(FeatureSaveJournal.isUploadHeld(db,"points",7));
            LayerDatabaseTransaction.run(db,()->{
                FeatureSaveJournal.completeForm(db,"points","second-complete-copy-save",7);
                try {assertTrue(FeatureSaveJournal.releaseRecoveredHolds(db,baseline,"points",7));}catch(JSONException e){throw new AssertionError(e);}
                return null;
            });
            assertFalse(FeatureSaveJournal.isUploadHeld(db,"points",7));
        }
    }
}

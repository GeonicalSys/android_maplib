package com.nextgis.maplib.util;

import com.nextgis.maplib.api.ILayer;
import com.nextgis.maplib.map.MapBase;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.LinkedHashSet;
import java.util.Set;

/** A restored copy can be inspected offline before any server mutation or pull is permitted. */
public final class ProjectRecoveryGuard {
    public static final String ORIGIN="recovery-origin.json", HOLD="recovery-hold.json";
    public static final String DELIVERY_OWNER="recovery-delivery-owner.json";
    private ProjectRecoveryGuard() { }
    public static boolean isRecoveryCopy(File directory) {return new File(directory,ORIGIN).exists();}
    public static boolean isHeld(File directory) {return new File(directory,HOLD).exists();}
    public static boolean isHeld(ILayer layer) {
        com.nextgis.maplib.map.MapContentProviderHelper map=mapForLayer(layer);
        return map!=null&&isHeld(map.getPath());
    }
    /** Existence is deliberately fail-closed, including an unreadable control file. */
    public static boolean isDeliveryBlocked(File directory) {return new File(directory,DELIVERY_OWNER).exists();}
    public static boolean isSyncBlocked(File directory) {return isHeld(directory)||isDeliveryBlocked(directory);}
    public static boolean isSyncBlocked(ILayer layer) {
        com.nextgis.maplib.map.MapContentProviderHelper map=mapForLayer(layer);
        return map!=null&&isSyncBlocked(map.getPath());
    }
    /** A layer with no project has no recovery directory. Do not require the process map singleton. */
    private static com.nextgis.maplib.map.MapContentProviderHelper mapForLayer(ILayer layer) {
        ILayer current=layer;
        while(current!=null) {
            if(current instanceof com.nextgis.maplib.map.MapContentProviderHelper)
                return (com.nextgis.maplib.map.MapContentProviderHelper)current;
            current=current.getParent();
        }
        com.nextgis.maplib.map.MapBase scoped=SyncWorkspaceSession.currentMap();
        if(scoped instanceof com.nextgis.maplib.map.MapContentProviderHelper)
            return (com.nextgis.maplib.map.MapContentProviderHelper)scoped;
        com.nextgis.maplib.map.MapBase active=com.nextgis.maplib.map.MapBase.getActiveInstance();
        if(active instanceof com.nextgis.maplib.map.MapContentProviderHelper)
            return (com.nextgis.maplib.map.MapContentProviderHelper)active;
        return null;
    }
    private static String identity(String value) throws IOException {
        if(value==null||value.isEmpty()||value.length()>512||value.indexOf('\n')>=0||value.indexOf('\r')>=0)
            throw new IOException("Invalid recovery project identity");
        return value;
    }
    private static void addFamily(Set<String> family,String value) throws IOException {
        family.add(identity(value));if(family.size()>64)throw new IOException("Recovery family is too large");
    }
    /** Only registry identities and the app's archived ancestry participate; paths are never taken from it. */
    public static Set<String> family(File directory,String uid) throws IOException {
        Set<String> result=new LinkedHashSet<>();addFamily(result,uid);
        if(isRecoveryCopy(directory))try {
            JSONObject origin=UnderlayFiles.readJson(new File(directory,ORIGIN));
            String source=origin.getJSONObject("source").optString("uid","");
            if(!source.isEmpty())addFamily(result,source);
            JSONArray ancestors=origin.optJSONArray("family");
            if(ancestors!=null)for(int i=0;i<ancestors.length();i++)addFamily(result,ancestors.getString(i));
        }catch(org.json.JSONException error) {throw new IOException("Invalid recovery ancestry",error);}
        return result;
    }
    private static JSONObject deliveryOwner(File directory,String uid) throws IOException {
        try {
            if(Files.isSymbolicLink(new File(directory,DELIVERY_OWNER).toPath()))throw new IOException("Linked delivery owner marker");
            JSONObject value=UnderlayFiles.readJson(new File(directory,DELIVERY_OWNER));
            if(value.length()!=5||value.getInt("schema")!=1||!"recovery_delivery_owner".equals(value.getString("kind"))
                    ||!identity(uid).equals(value.getString("source_uid")))throw new IOException("Unexpected delivery owner marker");
            identity(value.getString("destination_uid"));UUID.fromString(value.getString("recovery_operation"));
            return value;
        }catch(org.json.JSONException|IllegalArgumentException error) {throw new IOException("Invalid delivery owner marker",error);}
    }
    /** Caller owns the exclusive project operation and has explicitly confirmed the choice of copy. */
    public static void blockDelivery(File directory,String uid,String chosenUid,String operation) throws IOException {
        if(identity(uid).equals(identity(chosenUid)))throw new IOException("Cannot suppress the chosen copy");
        UUID.fromString(operation);
        if(isDeliveryBlocked(directory))deliveryOwner(directory,uid);
        try {
            write(new File(directory,DELIVERY_OWNER),new JSONObject().put("schema",1).put("kind","recovery_delivery_owner")
                    .put("source_uid",uid).put("destination_uid",chosenUid).put("recovery_operation",operation).toString());
        }catch(org.json.JSONException error) {throw new IOException("Invalid delivery owner identity",error);}
    }
    public static void mark(File directory,JSONObject source) throws IOException {
        try {
            String sourceUid=source.optString("uid","");
            Set<String> ancestors=sourceUid.isEmpty()?new LinkedHashSet<>():family(directory,sourceUid);
            JSONObject inherited=isDeliveryBlocked(directory)?deliveryOwner(directory,source.getString("uid")):null;
            JSONObject bindings=new JSONObject();
            for(UnderlayWorkspaceIndex.Link layer:UnderlayWorkspaceIndex.layers(new File(directory,
                    source.getString("map_name")+Constants.MAP_EXT))) {
                if(layer.config.optInt("type")!=Constants.LAYERTYPE_NGW_VECTOR)continue;
                String path=directory.toPath().relativize(layer.directory.toPath()).toString().replace(File.separatorChar,'/');
                bindings.put(path,new JSONObject().put("account",layer.config.getString("account"))
                        .put("resource",layer.config.getLong("id")).put("type",layer.config.getInt("ngw_layer_type")));
            }
            JSONObject origin=new JSONObject().put("schema",1).put("operation",UUID.randomUUID().toString())
                    .put("restored_at",System.currentTimeMillis()).put("source",source).put("layers",bindings)
                    .put("family",new JSONArray(ancestors));
            if(inherited!=null)origin.put("prior_delivery_owner",inherited);
            File database=new File(directory,"layers.db");
            if(database.isFile())try(android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.openDatabase(
                    database.getPath(),null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY)) {
                origin.put("form_state",FeatureSaveJournal.recoveryBaseline(db));
            }
            write(new File(directory,ORIGIN),origin.toString());
            write(new File(directory,HOLD),origin.toString());
            // This is the NEW restore staging directory. The source marker is never removed.
            if(inherited!=null&&!new File(directory,DELIVERY_OWNER).delete())throw new IOException("Cannot stage delivery ownership");
        } catch(org.json.JSONException error){throw new IOException("Invalid recovery identity",error);}
    }
    public static JSONObject heldIdentity(File directory) throws IOException {
        JSONObject hold=UnderlayFiles.readJson(new File(directory,HOLD)),origin=UnderlayFiles.readJson(new File(directory,ORIGIN));
        if(!hold.toString().equals(origin.toString()))throw new IOException("Recovery identity differs");
        return hold;
    }
    public static void releaseCompletedFormHolds(File workspace,android.database.sqlite.SQLiteDatabase db,String layer,long row) throws IOException {
        if(!isHeld(workspace))return;
        try {
            JSONObject baseline=heldIdentity(workspace).optJSONObject("form_state");
            if(baseline!=null)FeatureSaveJournal.releaseRecoveredHolds(db,baseline,layer,row);
        }catch(org.json.JSONException error) {throw new IOException("Invalid recovery form baseline",error);}
    }
    private static String hash(String value) {
        return UnderlayFiles.hex(UnderlayFiles.digest().digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    /** A recovered receipt may move only within its original layer/account/server binding. */
    public static String originalTarget(File workspace,File layer,String account,String url,String login,long resource,int type)
            throws IOException {
        try {
            UnderlayFiles.requireChild(workspace,layer);JSONObject identity=heldIdentity(workspace);
            String path=workspace.toPath().relativize(layer.toPath()).toString().replace(File.separatorChar,'/');
            JSONObject binding=identity.getJSONObject("layers").getJSONObject(path);
            if(!account.equals(binding.getString("account"))||resource!=binding.getLong("resource")||type!=binding.getInt("type"))
                throw new IOException("Restored layer destination changed");
            JSONObject source=identity.getJSONObject("source");JSONArray accounts=source.getJSONArray("accounts");
            boolean matched=false;
            for(int i=0;i<accounts.length();i++) {
                JSONObject item=accounts.getJSONObject(i);
                if(account.equals(item.getString("name"))&&hash(url).equals(item.getString("server_sha256"))
                        &&hash(login).equals(item.getString("login_sha256")))matched=true;
            }
            if(!matched)throw new IOException("Restored account destination changed");
            return UploadOperationJournal.target(source.getString("workspace_path"),layer.getName(),account,url,login,resource);
        }catch(org.json.JSONException error){throw new IOException("Invalid restored layer binding",error);}
    }
    /** The caller holds the project lease and the database transaction after checking every pending row. */
    public static void release(File directory,JSONObject expected) throws IOException {
        if(!heldIdentity(directory).toString().equals(expected.toString()))throw new IOException("Recovery identity changed");
        if(!new File(directory,HOLD).delete())throw new IOException("Cannot release recovery hold");
        // ORIGIN is permanent: a released copy must never import global legacy track history.
    }
    private static void write(File destination,String value) throws IOException {
        File temporary=new File(destination.getParentFile(),".recovery-"+UUID.randomUUID());
        try {
            try(FileOutputStream out=new FileOutputStream(temporary)) {
                out.write(value.getBytes(StandardCharsets.UTF_8));out.getFD().sync();
            }
            Files.move(temporary.toPath(),destination.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {if(temporary.exists()&&!temporary.delete())throw new IOException("Recovery staging cleanup failed");}
    }
}

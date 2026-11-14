package com.nextgis.maplib.util;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.UUID;

/** An accepted attachment is completed locally without posting it a second time. */
public final class UploadAttachmentRemap {
    private static final int MAX_META=4*1024*1024;
    private UploadAttachmentRemap() { }
    private static void cancelled() throws IOException {
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Attachment upload cancelled");
    }
    private static File child(File root,String name) throws IOException {
        File file=new File(root,name);UnderlayFiles.requireChild(root,file);
        if(Files.isSymbolicLink(file.toPath()))throw new IOException("Attachment path is a link");return file;
    }
    private static File folder(File layer,long feature) throws IOException {
        if(feature<0)throw new IOException("Invalid feature identifier");return child(layer,Long.toString(feature));
    }
    private static JSONArray metadata(File folder) throws IOException,JSONException {
        File meta=child(folder,"meta.json");
        try(InputStream input=new FileInputStream(meta)) {
            return new JSONArray(new String(UnderlayFiles.readBounded(input,MAX_META),StandardCharsets.UTF_8));
        }
    }
    public static String sha256(File file) throws IOException {
        MessageDigest digest=UnderlayFiles.digest();byte[] bytes=new byte[65536];
        try(InputStream input=new FileInputStream(file)) {
            int count;while((count=input.read(bytes))!=-1) {cancelled();digest.update(bytes,0,count);}
        }
        return UnderlayFiles.hex(digest.digest());
    }
    public static String snapshot(File layer,long feature,long attachment,boolean includeFile) throws IOException,JSONException {
        if(attachment<0)throw new IOException("Invalid attachment identifier");
        File directory=folder(layer,feature);if(!child(directory,"meta.json").isFile())return null;
        JSONObject found=null;JSONArray items=metadata(directory);
        for(int i=0;i<items.length();i++) {
            JSONObject item=items.getJSONObject(i);
            if(Long.toString(attachment).equals(item.getString("id"))) {
                if(found!=null)throw new IOException("Duplicate attachment metadata");found=item;
            }
        }
        if(found==null)return null;
        AttachItem checked=new AttachItem();checked.fromJSON(found);
        JSONObject snapshot=new JSONObject().put("metadata",found);
        if(includeFile) {
            File file=child(directory,Long.toString(attachment));if(!file.isFile())return null;
            long length=file.length();String hash=sha256(file);
            if(file.length()!=length)throw new IOException("Attachment changed while taking its snapshot");
            snapshot.put("sha256",hash).put("bytes",length);
        }
        return snapshot.toString();
    }
    private static File stage(File layer,UploadOperationJournal.Entry receipt) throws IOException {
        if(!receipt.id.matches("[a-f0-9-]{36}"))throw new IOException("Invalid attachment operation identity");
        return child(layer,".upload-attachment-"+receipt.id);
    }
    private static void verifyOwner(File stage,UploadOperationJournal.Entry receipt) throws IOException,JSONException {
        JSONObject owner=UnderlayFiles.readJson(child(stage,"owner.json"));
        if(!receipt.id.equals(owner.getString("operation"))||owner.getLong("feature")!=receipt.featureId
                ||owner.getLong("attachment")!=receipt.attachId)throw new IOException("Upload stage belongs to another operation");
    }
    private static void atomic(File destination,byte[] bytes) throws IOException {
        File temporary=child(destination.getParentFile(),".writing-"+UUID.randomUUID());
        try {
            try(FileOutputStream output=new FileOutputStream(temporary)) {output.write(bytes);output.getFD().sync();}
            Files.move(temporary.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally {if(temporary.exists()&&!temporary.delete())throw new IOException("Cannot remove upload staging file");}
    }
    /** Immutable transfer input. The source photograph is never replaced by a retry. */
    public static File payloadFile(File layer,UploadOperationJournal.Entry receipt) throws IOException,JSONException {
        JSONObject snapshot=new JSONObject(receipt.payload);String expected=snapshot.getString("sha256");long length=snapshot.getLong("bytes");
        File stage=stage(layer,receipt);UnderlayFiles.directory(stage);
        File owner=child(stage,"owner.json");
        if(owner.exists())verifyOwner(stage,receipt);
        else UnderlayFiles.writeJson(owner,new JSONObject().put("operation",receipt.id).put("feature",receipt.featureId).put("attachment",receipt.attachId));
        File payload=child(stage,"payload.bin");
        if(payload.isFile()&&payload.length()==length&&expected.equals(sha256(payload)))return payload;
        if(stage.getUsableSpace()<length+16L*1024*1024)throw new IOException("Not enough space for an immutable upload snapshot");
        File original=child(folder(layer,receipt.featureId),Long.toString(receipt.attachId));
        File temporary=child(stage,".payload-"+UUID.randomUUID());
        try {
            long copied=0;MessageDigest digest=UnderlayFiles.digest();byte[] bytes=new byte[65536];
            try(InputStream input=new FileInputStream(original);FileOutputStream output=new FileOutputStream(temporary)) {
                int count;while((count=input.read(bytes))!=-1) {cancelled();copied+=count;
                    if(copied>length)throw new IOException("Attachment changed before upload");
                    output.write(bytes,0,count);digest.update(bytes,0,count);
                }
                output.getFD().sync();
            }
            if(copied!=length||!expected.equals(UnderlayFiles.hex(digest.digest())))throw new IOException("Attachment changed before upload");
            Files.move(temporary.toPath(),payload.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            return payload;
        }finally {if(temporary.exists()&&!temporary.delete())throw new IOException("Cannot remove upload snapshot staging file");}
    }
    public static AttachItem submitted(UploadOperationJournal.Entry receipt) throws JSONException {
        AttachItem item=new AttachItem();item.fromJSON(new JSONObject(receipt.payload).getJSONObject("metadata"));return item;
    }

    /** Files are forward-recoverable if the process dies before SQLite commits its ACK. */
    public static void finish(SQLiteDatabase db,File layer,String changes,String attachments,UploadOperationJournal.Entry receipt)
            throws IOException,JSONException {
        if(!db.inTransaction()||!UploadOperationJournal.ACCEPTED.equals(receipt.state)||receipt.remoteId<0)
            throw new SQLiteException("Attachment completion needs an accepted receipt and a transaction");
        JSONObject snapshot=new JSONObject(receipt.payload);String expected=snapshot.getString("sha256");
        File directory=folder(layer,receipt.featureId),stage=stage(layer,receipt);verifyOwner(stage,receipt);
        File original=child(directory,Long.toString(receipt.attachId)),target=child(directory,Long.toString(receipt.remoteId));
        JSONArray current=metadata(directory),updated=new JSONArray();JSONObject before=null,after=null;
        for(int i=0;i<current.length();i++) {
            JSONObject item=current.getJSONObject(i);String id=item.getString("id");
            if(id.equals(Long.toString(receipt.attachId))) {if(before!=null)throw new IOException("Duplicate local attachment");before=item;}
            else if(id.equals(Long.toString(receipt.remoteId))) {if(after!=null)throw new IOException("Duplicate server attachment");after=item;}
            else updated.put(item);
        }
        if(before!=null&&after!=null)throw new IOException("Attachment destination already has metadata");
        if(before==null&&after==null)throw new IOException("Attachment metadata is missing");
        if(original.exists()&&!expected.equals(sha256(original)))throw new IOException("Local attachment changed after upload");
        if(target.exists()) {
            if(!target.isFile()||!expected.equals(sha256(target)))throw new IOException("Attachment destination differs from the accepted upload");
        } else {
            File immutable=child(stage,"payload.bin");
            if(!immutable.isFile()||!expected.equals(sha256(immutable)))throw new IOException("Accepted upload snapshot is unavailable");
            Files.copy(immutable.toPath(),target.toPath());
            try(RandomAccessFile output=new RandomAccessFile(target,"rw")) {output.getFD().sync();}
            if(!expected.equals(sha256(target)))throw new IOException("Attachment copy differs from the accepted upload");
        }
        JSONObject retained=new JSONObject((before!=null?before:after).toString()).put("id",Long.toString(receipt.remoteId));updated.put(retained);
        byte[] encoded=updated.toString().getBytes(StandardCharsets.UTF_8);if(encoded.length>MAX_META)throw new IOException("Attachment metadata is too large");
        atomic(child(directory,"meta.json"),encoded);
        ContentValues row=new ContentValues();row.put(Constants.FIELD_ATTACH_ID,receipt.remoteId);
        db.update(changes,row,Constants.FIELD_FEATURE_ID+"=? AND "+Constants.FIELD_ATTACH_ID+"=? AND ("+Constants.FIELD_OPERATION+" & "+Constants.CHANGE_OPERATION_ATTACH+")!=0",
                new String[]{Long.toString(receipt.featureId),Long.toString(receipt.attachId)});
        FeatureAttachments.initialize(db,attachments);
        db.delete(attachments,Constants.FIELD_FEATURE_ID+"=? AND "+Constants.FIELD_ATTACH_ID+"=?",new String[]{Long.toString(receipt.featureId),Long.toString(receipt.remoteId)});
        AttachItem sent=submitted(receipt);
        if(FeatureAttachments.add(db,attachments,receipt.featureId,receipt.remoteId,sent.getDescription(),sent.getDisplayName(),sent.getMimetype())<0)
            throw new SQLiteException("Cannot persist accepted attachment metadata");
        // Attachment form saves use the same row journal in their own namespace.
        FeatureSaveJournal.changeFeatureId(db,layer.getName()+"/"+receipt.featureId+"/attach",receipt.attachId,receipt.remoteId);
        UploadOperationJournal.acknowledge(db,changes,receipt,receipt.featureId,receipt.remoteId);
    }
    public static void complete(SQLiteDatabase db,File layer,UploadOperationJournal.Entry receipt) throws IOException {
        File stage=stage(layer,receipt);if(!stage.exists())return;
        try {
            verifyOwner(stage,receipt);
            LayerDatabaseTransaction.run(db,()->{
                UploadOperationJournal.Entry current=UploadOperationJournal.find(db,layer.getName(),receipt.id);
                if(current==null||!UploadOperationJournal.CONFIRMED.equals(current.state)||current.remoteId!=receipt.remoteId)
                    throw new SQLiteException("Upload cleanup needs a committed acknowledgement");
                try {
                    if(receipt.attachId!=receipt.remoteId) {
                        File directory=folder(layer,receipt.featureId),original=child(directory,Long.toString(receipt.attachId));
                        boolean referenced=false;
                        if(child(directory,"meta.json").isFile()) {
                            JSONArray items=metadata(directory);
                            for(int i=0;i<items.length();i++)if(Long.toString(receipt.attachId).equals(items.getJSONObject(i).getString("id")))referenced=true;
                        }
                        if(!referenced&&original.isFile()&&new JSONObject(receipt.payload).getString("sha256").equals(sha256(original))
                                &&!original.delete())throw new IOException("Cannot remove acknowledged local upload copy");
                    }
                }catch(IOException|JSONException error) {throw new SQLiteException("Upload cleanup deferred",error);}
                return null;
            });
        }catch(JSONException error) {throw new IOException("Invalid completed upload stage",error);}
        UnderlayFiles.deleteChildTree(layer,stage);
    }
}

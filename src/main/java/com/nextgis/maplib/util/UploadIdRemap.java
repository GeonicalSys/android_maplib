package com.nextgis.maplib.util;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Recoverable filesystem half of an acknowledged feature-ID remap. */
public final class UploadIdRemap {
    private UploadIdRemap() { }
    private static File marker(File folder, String operation) {
        if (!operation.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid operation");
        return new File(folder, ".ngw-remap-" + operation);
    }
    private static boolean owns(File folder, String operation) throws IOException {
        File marker = marker(folder, operation);
        return marker.isFile() && operation.equals(new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8));
    }
    public static void files(File layer, long oldId, long newId, String operation) throws IOException {
        if (oldId == newId) return;
        File before = new File(layer, Long.toString(oldId)), after = new File(layer, Long.toString(newId));
        if (before.exists()) {
            if (!before.isDirectory() || after.exists()) throw new IOException("Attachment destination already exists");
            try (FileOutputStream out = new FileOutputStream(marker(before, operation))) {
                out.write(operation.getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
            }
            if (!before.renameTo(after)) throw new IOException("Cannot move feature attachments");
        } else if (after.exists() && !owns(after, operation)) {
            throw new IOException("Attachment destination belongs to another operation");
        }
    }
    public static void rollbackFiles(File layer, long oldId, long newId, String operation) {
        if (oldId == newId) return;
        File before = new File(layer, Long.toString(oldId)), after = new File(layer, Long.toString(newId));
        try {
            if (!before.exists() && after.isDirectory() && owns(after, operation)) after.renameTo(before);
        } catch (IOException ignored) { /* The accepted receipt retains the exact recovery operation. */ }
    }
    public static void completeFiles(File layer, long newId, String operation) {
        // A leftover marker is harmless and is included in a complete project archive.
        marker(new File(layer, Long.toString(newId)), operation).delete();
    }
    public static void rows(SQLiteDatabase db, String layer, String changes, String attachments,
            long oldId, long newId) {
        ContentValues row = new ContentValues(); row.put(Constants.FIELD_ID, newId);
        if (db.update(layer, row, Constants.FIELD_ID + "=?", new String[]{Long.toString(oldId)}) != 1)
            throw new SQLiteException("The local feature is missing during ID remap");
        row.clear(); row.put(Constants.FIELD_FEATURE_ID, newId);
        db.update(changes, row, Constants.FIELD_FEATURE_ID + "=?", new String[]{Long.toString(oldId)});
        try (Cursor tables = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                new String[]{attachments})) {
            if (tables.moveToFirst()) db.update(attachments, row, Constants.FIELD_FEATURE_ID + "=?",
                    new String[]{Long.toString(oldId)});
        }
        FeatureSaveJournal.changeFeatureId(db, layer, oldId, newId);
    }
}

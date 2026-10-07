package com.nextgis.maplib.scripts;

import android.util.AtomicFile;
import com.nextgis.maplib.map.LayerGroup;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import org.json.JSONException;

/** Immutable content-addressed cache; old packages remain available to restored form drafts. */
public final class ScriptPackageStore {
    private ScriptPackageStore() { }
    private static File file(LayerGroup group, String hash) throws IOException {
        if (!hash.matches("[a-f0-9]{64}")) throw new IOException("Invalid cache identity");
        return new File(new File(group.getPath(), "project_scripts"), hash + ".zip");
    }
    public static ScriptPackage load(LayerGroup group, ScriptReference reference) throws IOException, JSONException {
        byte[] bytes;
        try (InputStream in = new AtomicFile(file(group, reference.hash)).openRead()) {
            bytes = boundedRead(in);
        }
        ScriptPackage pack = ScriptPackage.read(bytes, reference.hash);
        if (!pack.version.equals(reference.version)) throw new IOException("Package version mismatch");
        for (String alias : pack.aliases()) reference.layerId(alias);
        return pack;
    }
    public static void install(LayerGroup group, ScriptReference ref, byte[] bytes) throws IOException, JSONException {
        ScriptPackage pack = ScriptPackage.read(bytes, ref.hash);
        if (!pack.version.equals(ref.version)) throw new IOException("Package version mismatch");
        for (String alias : pack.aliases()) ref.layerId(alias);
        File path = file(group, ref.hash);
        if (!path.getParentFile().isDirectory() && !path.getParentFile().mkdirs()) throw new IOException("Cache directory");
        AtomicFile atomic = new AtomicFile(path);
        FileOutputStream out = null;
        try {
            out = atomic.startWrite();
            out.write(bytes);
            atomic.finishWrite(out);
        } catch (IOException error) { if (out != null) atomic.failWrite(out); throw error; }
    }
    public static byte[] boundedRead(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) != -1) {
            if (out.size() + count > ScriptPackage.MAX_ARCHIVE) throw new IOException("Package download too large");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }
}

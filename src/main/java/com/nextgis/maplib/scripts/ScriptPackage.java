package com.nextgis.maplib.scripts;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Bounded, source-only package. ZIP entries are never extracted to arbitrary paths. */
public final class ScriptPackage {
    public static final int MAX_ARCHIVE = 2 * 1024 * 1024;
    public static final int MAX_ENTRY = 512 * 1024;
    private final JSONObject manifest;
    private final Map<String, String> files;
    public final String hash, id, version;

    private ScriptPackage(JSONObject manifest, Map<String,String> files, String hash) throws JSONException {
        this.manifest = manifest;
        this.files = Collections.unmodifiableMap(new HashMap<>(files));
        this.hash = hash;
        id = manifest.getString("id");
        version = manifest.getString("version");
        validate();
    }
    public static ScriptPackage read(byte[] archive, String expectedHash) throws IOException, JSONException {
        if (archive.length > MAX_ARCHIVE || archive.length < 4) throw new IOException("Archive size");
        String hash = sha256(archive);
        if (!hash.equals(expectedHash)) throw new IOException("Package checksum mismatch");
        Map<String,String> files = new HashMap<>();
        Set<String> names = new HashSet<>();
        int total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || !name.matches("manifest\\.json|scripts/[A-Za-z0-9_-]+\\.js|tables/[A-Za-z0-9_-]+\\.json")
                        || !names.add(name.toLowerCase(Locale.ROOT)) || names.size() > 32)
                    throw new IOException("Unsafe or duplicate ZIP entry");
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    total += count;
                    if (data.size() + count > MAX_ENTRY || total > 4 * 1024 * 1024)
                        throw new IOException("Expanded package size");
                    data.write(buffer, 0, count);
                }
                long compressed = entry.getCompressedSize();
                if (compressed > 0 && data.size() > compressed * 100) throw new IOException("ZIP expansion ratio");
                files.put(name, utf8(data.toByteArray()));
                zip.closeEntry();
            }
        }
        if (!files.containsKey("manifest.json")) throw new IOException("Manifest missing");
        return new ScriptPackage(new JSONObject(files.get("manifest.json")), files, hash);
    }
    public static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest) out.append(String.format(Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    public static String utf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    private void validate() throws JSONException {
        if (manifest.getInt("schema_version") != 1 || manifest.getInt("api_version") != 1
                || !id.matches("[a-z][a-z0-9_-]{0,63}") || !version.matches("[A-Za-z0-9._-]{1,64}"))
            throw new JSONException("Unsupported package identity or API");
        JSONArray caps = manifest.getJSONArray("capabilities");
        for (int i=0; i<caps.length(); i++)
            if (!Arrays.asList("gis.query", "time.monthWindow").contains(caps.getString(i)))
                throw new JSONException("Unsupported capability");
        JSONObject layers = manifest.getJSONObject("layers");
        if (layers.length() == 0 || layers.length() > 32) throw new JSONException("Layer count");
        for (String alias : aliases()) {
            if (!alias.matches("[a-z][a-z0-9_]{0,63}")) throw new JSONException("Layer alias");
            JSONArray fields = layers.getJSONObject(alias).getJSONArray("read_fields");
            if (fields.length() == 0 || fields.length() > 32) throw new JSONException("Field count");
            for (int i=0; i<fields.length(); i++)
                if (!fields.getString(i).matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) throw new JSONException("Field name");
        }
        JSONArray hooks = manifest.getJSONArray("hooks");
        if (hooks.length() == 0 || hooks.length() > 32) throw new JSONException("Hook count");
        Set<String> ids = new HashSet<>();
        for (int i=0; i<hooks.length(); i++) {
            JSONObject hook = hooks.getJSONObject(i);
            if (!hook.getString("id").matches("[a-z][a-z0-9_-]{0,63}") || !ids.add(hook.getString("id"))
                    || !layers.has(hook.getString("layer"))
                    || !hook.getString("entry").matches("scripts/[A-Za-z0-9_-]+\\.js")
                    || !files.containsKey(hook.getString("entry"))) throw new JSONException("Invalid hook");
            JSONArray events = hook.getJSONArray("events");
            if (events.length() == 0 || events.length() > 3) throw new JSONException("Event count");
            for (int j=0; j<events.length(); j++)
                if (!Arrays.asList("on_open", "on_field_change", "before_save").contains(events.getString(j)))
                    throw new JSONException("Unsupported event");
            JSONArray fields = hook.getJSONArray("fields");
            for (int j=0; j<fields.length(); j++)
                if (!canRead(hook.getString("layer"), fields.getString(j))) throw new JSONException("Unbound hook field");
        }
    }
    public boolean hasCapability(String capability) { return contains(manifest.optJSONArray("capabilities"), capability); }
    public Set<String> aliases() throws JSONException {
        Set<String> aliases = new HashSet<>();
        var keys = manifest.getJSONObject("layers").keys();
        while (keys.hasNext()) aliases.add(keys.next());
        return Collections.unmodifiableSet(aliases);
    }
    public boolean canRead(String layer, String field) {
        JSONObject config = manifest.optJSONObject("layers").optJSONObject(layer);
        return config != null && contains(config.optJSONArray("read_fields"), field);
    }
    public List<JSONObject> hooks(ScriptReference reference, long remoteId, String event, String field) throws JSONException {
        List<JSONObject> result = new ArrayList<>();
        JSONArray hooks = manifest.getJSONArray("hooks");
        for (int i=0; i<hooks.length(); i++) {
            JSONObject hook = hooks.getJSONObject(i);
            if (reference.layerId(hook.getString("layer")) == remoteId
                    && contains(hook.getJSONArray("events"), event)
                    && (!event.equals("on_field_change") || contains(hook.getJSONArray("fields"), field)))
                result.add(new JSONObject(hook.toString()));
        }
        return result;
    }
    public String source(JSONObject hook) throws JSONException { return files.get(hook.getString("entry")); }
    private static boolean contains(JSONArray array, String value) {
        if (array == null) return false;
        for (int i=0; i<array.length(); i++) if (value.equals(array.optString(i))) return true;
        return false;
    }
}

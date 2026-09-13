package com.nextgis.maplib.util;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** App-private assets; project layers are references, never payload owners. */
public final class SharedUnderlayCatalog {
    public static final String LAYER_KEY = "shared_underlay_id";
    public static final String MBTILES = "mbtiles", LEGACY_TILES = "ngrc_tiles";
    public static final String READY = "ready", MOVING = "moving", DELETING = "deleting", GARBAGE = "garbage";
    private static final Object LOCK = new Object();
    private final File root;
    private final Checkpoint checkpoint;
    public interface Checkpoint { void reached(String step) throws IOException; }

    public SharedUnderlayCatalog(File root) { this(root, step -> { }); }
    SharedUnderlayCatalog(File root, Checkpoint checkpoint) { this.root = root; this.checkpoint = checkpoint; }
    public File root() { return root; }

    public static final class Asset {
        private final JSONObject json;
        public final String id, name, kind, sha256, state;
        public final long size, createdAt;
        Asset(JSONObject json) throws JSONException {
            this.json = new JSONObject(json.toString());
            id = json.getString("id"); name = json.getString("name"); kind = json.getString("kind");
            sha256 = json.optString("content_sha256", ""); state = json.optString("state", READY);
            size = json.optLong("size", 0); createdAt = json.optLong("created_at", 0);
            if (!validId(id) || (!MBTILES.equals(kind) && !LEGACY_TILES.equals(kind)))
                throw new JSONException("Invalid underlay identity");
        }
        public JSONObject layerConfig() throws IOException {
            try { return new JSONObject(json.getJSONObject("layer_config").toString()); }
            catch (JSONException e) { throw new IOException("Missing underlay configuration", e); }
        }
        public boolean isReady() { return READY.equals(state); }
        public boolean hasSource(String source) {
            JSONArray sources = json.optJSONArray("source_keys");
            if (source != null && sources != null)
                for (int i = 0; i < sources.length(); i++) if (source.equals(sources.optString(i))) return true;
            return false;
        }
    }

    public List<Asset> list() throws IOException {
        synchronized (LOCK) {
            ArrayList<Asset> result = new ArrayList<>();
            JSONArray rows = index().optJSONArray("assets");
            try {
                if (rows == null) throw new JSONException("Missing underlay list");
                for (int i = 0; i < rows.length(); i++) result.add(new Asset(rows.getJSONObject(i)));
            } catch (JSONException e) { throw new IOException("Invalid underlay catalog", e); }
            return result;
        }
    }

    public Asset get(String id) throws IOException {
        if (!validId(id)) return null;
        for (Asset asset : list()) if (asset.id.equals(id)) return asset;
        return null;
    }

    public Asset find(String hash, String sourceKey) throws IOException {
        for (Asset asset : list()) if (asset.isReady() && payload(asset.id).isDirectory()
                && ((hash != null && !hash.isEmpty() && hash.equals(asset.sha256)) || asset.hasSource(sourceKey)))
            return asset;
        return null;
    }

    public File payload(String id) throws IOException {
        if (!validId(id)) throw new IOException("Invalid underlay id");
        File directory = new File(new File(root, id), "payload");
        UnderlayFiles.requireChild(root, directory);
        return directory;
    }

    /** Only this importer owns and may discard its unpublished stage. */
    public File createStage() throws IOException {
        synchronized (LOCK) {
            File stage = new File(root, UUID.randomUUID() + ".partial");
            UnderlayFiles.directory(new File(stage, "payload"));
            return stage;
        }
    }

    public void discardStage(File stage) throws IOException {
        if (!stage.getName().endsWith(".partial") || !stage.getParentFile().getCanonicalFile().equals(root.getCanonicalFile()))
            throw new IOException("Not an underlay import stage");
        UnderlayFiles.deleteChildTree(root, stage);
    }

    public Asset publish(File stage, String name, String kind, String hash, long size,
                         JSONObject config, String sourceKey) throws IOException {
        synchronized (LOCK) {
            UnderlayFiles.requireChild(root, stage);
            Asset existing = find(hash, sourceKey);
            if (existing != null) { addSource(existing.id, sourceKey); discardStage(stage); return get(existing.id); }
            String id = UUID.randomUUID().toString();
            JSONObject row = row(id, name, kind, hash, size, config, sourceKey);
            File destination = new File(root, id);
            UnderlayFiles.writeJson(new File(stage, "manifest.json"), row);
            if (!stage.renameTo(destination)) throw new IOException("Cannot publish underlay payload");
            checkpoint.reached("payload-published");
            put(row);
            return get(id);
        }
    }

    /** A bounded rename transaction; hashing and tile enumeration happen before entering here. */
    public Asset migrateLayer(File source, JSONObject config, String kind, String hash, long size) throws IOException {
        synchronized (LOCK) {
            if (source.getCanonicalFile().equals(root.getCanonicalFile())
                    || source.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)
                    || !new File(source, "config.json").isFile())
                throw new IOException("Cannot migrate the catalog into itself");
            String already = config.optString(LAYER_KEY, "");
            if (!already.isEmpty()) {
                Asset asset = get(already);
                if (asset != null && asset.isReady()) return asset;
                throw new IOException("Underlay reference is unavailable");
            }
            String id = UUID.randomUUID().toString();
            Asset existing = find(hash, null);
            JSONObject row = row(id, config.optString("name", "Underlay"), kind, hash, size, config, null);
            try {
                row.put("state", MOVING).put("source_layer", source.getCanonicalPath())
                        .put("target_id", existing == null ? id : existing.id);
            } catch (JSONException e) { throw new IOException(e); }
            UnderlayFiles.directory(new File(root, id));
            put(row);
            checkpoint.reached("migration-journal");
            finishMigration(new AssetUnchecked(row).asset);
            return get(existing == null ? id : existing.id);
        }
    }

    public void recover() throws IOException {
        synchronized (LOCK) {
            // A validated payload may have been renamed immediately before the index commit.
            File[] directories = root.listFiles();
            if (directories != null) for (File directory : directories) {
                if (!validId(directory.getName()) || get(directory.getName()) != null) continue;
                File manifest = new File(directory, "manifest.json");
                if (manifest.isFile()) {
                    JSONObject row = UnderlayFiles.readJson(manifest);
                    if (MOVING.equals(row.optString("state")) || (READY.equals(row.optString("state", READY))
                            && payload(directory.getName()).isDirectory())) put(row);
                }
            }
            for (Asset asset : list()) if (MOVING.equals(asset.state)) finishMigration(asset);
        }
    }

    private void finishMigration(Asset asset) throws IOException {
        try {
            File source = new File(asset.json.getString("source_layer"));
            if (!source.isAbsolute() || source.getCanonicalFile().equals(root.getCanonicalFile())
                    || source.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator))
                throw new IOException("Invalid migration source");
            File payload = payload(asset.id);
            String targetId = asset.json.getString("target_id");
            if (!payload.exists()) {
                if (!source.isDirectory() || !source.renameTo(payload))
                    throw new IOException("Cannot move the existing underlay on this storage");
                checkpoint.reached("migration-moved");
            }
            JSONObject thin = asset.layerConfig().put(LAYER_KEY, targetId);
            try { UnderlayFiles.writeJson(new File(source, "config.json"), thin); }
            catch (IOException failure) {
                // A failed thin-config creation must leave a loadable original at the map path.
                File config = new File(source, "config.json");
                if (!config.exists()) {
                    File pending = new File(source, "config.json.pending");
                    if (pending.exists() && !pending.delete()) throw failure;
                    if (source.exists() && !source.delete()) throw failure;
                    if (!payload.renameTo(source)) throw failure;
                }
                throw failure;
            }
            checkpoint.reached("migration-linked");
            if (targetId.equals(asset.id)) {
                JSONObject ready = asset.json.put("state", READY);
                ready.remove("source_layer"); ready.remove("target_id");
                put(ready);
            } else {
                Asset target = get(targetId);
                if (target == null || !target.isReady() || !payload(targetId).isDirectory())
                    throw new IOException("Duplicate underlay target disappeared");
                // The complete original is retained until the replacement link is durable.
                put(asset.json.put("state", GARBAGE));
            }
        } catch (JSONException e) { throw new IOException("Invalid underlay migration journal", e); }
    }

    /** Expensive removal of verified duplicate trees runs off the UI/startup thread. */
    public void cleanMigratedDuplicates() throws IOException {
        synchronized (LOCK) {
            for (Asset asset : list()) if (GARBAGE.equals(asset.state)) deleteUnlinked(asset.id);
        }
    }

    public void rename(String id, String name) throws IOException {
        synchronized (LOCK) {
            Asset asset = get(id);
            if (asset == null || !asset.isReady() || name == null || name.trim().isEmpty())
                throw new IOException("Underlay cannot be renamed");
            try { put(asset.json.put("name", name.trim())); }
            catch (JSONException e) { throw new IOException(e); }
        }
    }

    public void addSource(String id, String source) throws IOException {
        if (source == null || source.isEmpty()) return;
        synchronized (LOCK) {
            Asset asset = get(id);
            if (asset == null || asset.hasSource(source)) return;
            try {
                JSONArray keys = asset.json.optJSONArray("source_keys");
                if (keys == null) keys = new JSONArray();
                put(asset.json.put("source_keys", keys.put(source)));
            } catch (JSONException e) { throw new IOException(e); }
        }
    }

    /** Lazy inventory never delays a map opening by walking millions of tile files. */
    public void inventory() throws IOException {
        for (Asset asset : list()) {
            if (!asset.isReady() || asset.size >= 0) continue;
            File payload = payload(asset.id);
            long size = UnderlayFiles.size(payload);
            String hash = asset.sha256;
            if (hash.isEmpty() && MBTILES.equals(asset.kind)) {
                try (java.io.InputStream input = new java.io.FileInputStream(new File(payload, MbTilesInfo.MBTILES_FILENAME))) {
                    hash = UnderlayFiles.sha256(input);
                }
            }
            synchronized (LOCK) {
                Asset current = get(asset.id);
                if (current == null || !current.isReady()) continue;
                try { put(current.json.put("size", size).put("content_sha256", hash)); }
                catch (JSONException e) { throw new IOException(e); }
            }
        }
    }

    /** After lazy hashing, merge old identical files with a durable redirect journal. */
    public void mergeDuplicates(List<File> maps) throws IOException {
        synchronized (LOCK) {
            java.util.Map<String, Asset> hashes = new java.util.LinkedHashMap<>();
            for (Asset asset : list()) {
                if ("redirecting".equals(asset.state)) { finishRedirect(asset, maps); continue; }
                if (!asset.isReady() || asset.sha256.isEmpty()) continue;
                Asset first = hashes.get(asset.sha256);
                if (first == null) { hashes.put(asset.sha256, asset); continue; }
                try { put(asset.json.put("state", "redirecting").put("target_id", first.id)); }
                catch (JSONException e) { throw new IOException(e); }
                finishRedirect(get(asset.id), maps);
            }
        }
    }
    private void finishRedirect(Asset asset, List<File> maps) throws IOException {
        String target = asset.json.optString("target_id");
        Asset canonical = get(target);
        if (canonical == null || !canonical.isReady() || !payload(target).isDirectory())
            throw new IOException("Duplicate target unavailable");
        for (File map : maps) for (UnderlayWorkspaceIndex.Link layer : UnderlayWorkspaceIndex.layers(map)) {
            if (!asset.id.equals(layer.assetId())) continue;
            try { UnderlayFiles.writeJson(new File(layer.directory, "config.json"), layer.config.put(LAYER_KEY, target)); }
            catch (JSONException e) { throw new IOException(e); }
        }
        JSONArray sources = asset.json.optJSONArray("source_keys");
        if (sources != null) for (int i = 0; i < sources.length(); i++) addSource(target, sources.optString(i));
        try { put(asset.json.put("state", GARBAGE)); }
        catch (JSONException e) { throw new IOException(e); }
    }

    /** Only call at process startup, before an importer can own an unpublished stage. */
    public void cleanAbandonedStages() throws IOException {
        File[] stages = root.listFiles((dir, name) -> name.endsWith(".partial"));
        if (stages != null) for (File stage : stages) discardStage(stage);
    }

    /** The caller must unlink and durably save every project before deleting the payload. */
    public void markDeleting(String id) throws IOException {
        synchronized (LOCK) {
            Asset asset = get(id);
            if (asset == null || MOVING.equals(asset.state)) throw new IOException("Underlay is not ready");
            try { put(asset.json.put("state", DELETING)); }
            catch (JSONException e) { throw new IOException(e); }
        }
    }

    public void deleteUnlinked(String id) throws IOException {
        synchronized (LOCK) {
            Asset asset = get(id);
            if (asset == null) return;
            UnderlayFiles.deleteChildTree(root, new File(root, id));
            JSONArray kept = new JSONArray();
            for (Asset other : list()) if (!other.id.equals(id)) kept.put(other.json);
            writeIndex(kept);
        }
    }

    private JSONObject row(String id, String name, String kind, String hash, long size,
                           JSONObject config, String sourceKey) throws IOException {
        try {
            JSONObject row = new JSONObject().put("id", id).put("name", name == null ? "Underlay" : name)
                    .put("kind", kind).put("content_sha256", hash == null ? "" : hash).put("size", size)
                    .put("state", READY).put("created_at", System.currentTimeMillis())
                    .put("layer_config", new JSONObject(config.toString()));
            if (sourceKey != null) row.put("source_keys", new JSONArray().put(sourceKey));
            return row;
        } catch (JSONException e) { throw new IOException(e); }
    }

    private void put(JSONObject row) throws IOException {
        Asset asset = new AssetUnchecked(row).asset;
        JSONArray rows = new JSONArray(); boolean replaced = false;
        for (Asset existing : list()) {
            if (asset.id.equals(existing.id)) { rows.put(row); replaced = true; }
            else rows.put(existing.json);
        }
        if (!replaced) rows.put(row);
        UnderlayFiles.writeJson(new File(new File(root, asset.id), "manifest.json"), row);
        writeIndex(rows);
    }

    private JSONObject index() throws IOException {
        File file = new File(root, "catalog.json");
        if (!file.exists()) {
            try { return new JSONObject().put("schema", 1).put("assets", new JSONArray()); }
            catch (JSONException e) { throw new IOException(e); }
        }
        JSONObject index = UnderlayFiles.readJson(file);
        if (index.optInt("schema", -1) != 1) throw new IOException("Unsupported underlay catalog");
        return index;
    }

    private void writeIndex(JSONArray rows) throws IOException {
        try { UnderlayFiles.writeJson(new File(root, "catalog.json"), new JSONObject().put("schema", 1).put("assets", rows)); }
        catch (JSONException e) { throw new IOException(e); }
    }

    private static boolean validId(String id) {
        return id != null && id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
    private static final class AssetUnchecked {
        final Asset asset;
        AssetUnchecked(JSONObject json) throws IOException {
            try { asset = new Asset(json); } catch (JSONException e) { throw new IOException(e); }
        }
    }
}

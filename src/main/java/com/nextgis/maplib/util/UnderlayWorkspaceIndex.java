package com.nextgis.maplib.util;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Reads closed workspaces without constructing a MapBase or changing its global singleton. */
public final class UnderlayWorkspaceIndex {
    public static final class Link {
        public final File directory, parentConfig;
        public final JSONObject config;
        Link(File directory, File parentConfig, JSONObject config) {
            this.directory = directory; this.parentConfig = parentConfig; this.config = config;
        }
        public String assetId() { return config.optString(SharedUnderlayCatalog.LAYER_KEY, ""); }
    }
    private UnderlayWorkspaceIndex() { }
    public static List<Link> layers(File mapFile) throws IOException {
        List<Link> result = new ArrayList<>();
        if (mapFile.isFile()) scan(mapFile, 0, result);
        return result;
    }
    private static void scan(File configFile, int depth, List<Link> result) throws IOException {
        if (depth > 32) throw new IOException("Workspace nesting exceeds limit");
        JSONObject config = UnderlayFiles.readJson(configFile);
        JSONArray children = config.optJSONArray("layers");
        if (children == null) return;
        try {
            for (int i = 0; i < children.length(); i++) {
                String name = children.getJSONObject(i).getString("path");
                if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains(":")
                        || name.equals(".") || name.equals("..")) throw new IOException("Invalid layer path");
                File dir = new File(configFile.getParentFile(), name);
                UnderlayFiles.requireChild(configFile.getParentFile(), dir);
                File child = new File(dir, "config.json");
                // Missing layers are retained in the parent; scanning must never rewrite them away.
                if (!child.isFile()) continue;
                JSONObject layer = UnderlayFiles.readJson(child);
                result.add(new Link(dir, configFile, layer));
                if (layer.has("layers")) scan(child, depth + 1, result);
            }
        } catch (JSONException e) { throw new IOException("Invalid workspace layer list", e); }
    }
    public static boolean uses(File mapFile, String id) throws IOException {
        for (Link layer : layers(mapFile)) if (id.equals(layer.assetId())) return true;
        return false;
    }
    public static void unlink(File mapFile, String id) throws IOException {
        for (Link layer : layers(mapFile)) {
            if (!id.equals(layer.assetId())) continue;
            JSONObject parent = UnderlayFiles.readJson(layer.parentConfig);
            JSONArray kept = new JSONArray(), children = parent.optJSONArray("layers");
            try {
                for (int i = 0; i < children.length(); i++) {
                    JSONObject row = children.getJSONObject(i);
                    if (!layer.directory.getName().equals(row.optString("path"))) kept.put(row);
                }
                parent.put("layers", kept);
                UnderlayFiles.writeJson(layer.parentConfig, parent);
            } catch (JSONException e) { throw new IOException(e); }
            // Leave the tiny detached config until normal workspace cleanup. It owns no payload.
        }
    }
}

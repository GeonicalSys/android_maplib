/*
 * Disk cache for MapLibre-bound GeoJSON features to avoid full SQLite scans on cold start.
 * Primary path: {@link #tryLoadFeatures} parses cached GeoJSON into Java {@link Feature}s for GeoJsonSource.
 * Optional: {@link #tryLoadAsUri} for native mbgl file loading (experimental).
 * On cache miss, the caller builds features from SQLite and calls save() for next time.
 *
 * <p>All entry points no-op when {@link com.nextgis.maplib.util.Constants#MAP_STARTUP_OPTIMIZATIONS_ENABLED}
 * is false — keep this class for when that flag is turned back on.</p>
 */

package com.nextgis.maplib.map;

import android.content.Context;
import android.net.Uri;
import java.net.URI;
import android.text.TextUtils;
import android.util.Log;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.nextgis.maplib.api.IJSONStore;
import com.nextgis.maplib.api.IRenderer;
import com.nextgis.maplib.util.Constants;
import com.nextgis.maplib.util.FileUtil;
import com.nextgis.maplib.util.SettingsConstants;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;
import org.maplibre.geojson.Feature;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class VectorLayerRenderCache {

    /**
     * When true, {@link #tryLoadAsUri} returns a file URL so MapLibre parses GeoJSON in native code.
     * This is much faster but has caused layers to render empty on some builds (source stays empty).
     * Keep false until a reliable file-URL strategy is verified on device.
     */
    public static volatile boolean USE_MAPLIBRE_NATIVE_GEOJSON_URI = false;

    private static final String TAG = "VectorLayerRenderCache";
    private static final int META_SCHEMA = 2;
    private static final String CACHE_SUBDIR = "maplibre_vector_render";
    private static final String FILE_FEATURES = "features.geojson";
    private static final String FILE_META = "meta.json";

    private static final AtomicLong sLastLoadNanos = new AtomicLong(0);

    private VectorLayerRenderCache() {
    }

    /**
     * Any persisted {@link VectorLayer} with a context may use the render cache.
     * {@link NGWVectorLayer} invalidates on sync/import in layer code; local layers via
     * {@link VectorLayer} notifications and GeoJSON import.
     */
    public static boolean isEligible(VectorLayer layer) {
        return layer != null && layer.getContext() != null;
    }

    public static long getLastLoadNanos() {
        return sLastLoadNanos.get();
    }

    /**
     * Bump render-cache generation and delete on-disk files so the next map load rebuilds from DB.
     */
    public static void invalidateOnDataChange(VectorLayer layer) {
        if (!Constants.MAP_STARTUP_OPTIMIZATIONS_ENABLED) return;
        if (layer == null || layer.getContext() == null) return;
        long next = layer.getPreferences().getLong(SettingsConstants.KEY_PREF_RENDER_CACHE_GENERATION, 0L) + 1L;
        layer.getPreferences().edit().putLong(SettingsConstants.KEY_PREF_RENDER_CACHE_GENERATION, next).apply();
        deleteCacheFiles(layer.getContext(), layer);
        Log.d(TAG, "invalidateOnDataChange layer=" + layer.getName() + " gen=" + next);
    }

    /**
     * Check if a valid cache file exists for this layer. Returns the file URI for MapLibre
     * GeoJsonSource, or null if cache miss. Does NOT read the GeoJSON file into Java memory.
     */
    public static URI tryLoadAsUri(VectorLayer layer) {
        if (!Constants.MAP_STARTUP_OPTIMIZATIONS_ENABLED || !USE_MAPLIBRE_NATIVE_GEOJSON_URI) return null;
        if (!isEligible(layer)) return null;
        Context ctx = layer.getContext();
        File dir = cacheDir(ctx, layer);
        File metaFile = new File(dir, FILE_META);
        File geoFile = new File(dir, FILE_FEATURES);
        if (!metaFile.exists() || !geoFile.exists()) return null;

        long gen = layer.getPreferences().getLong(SettingsConstants.KEY_PREF_RENDER_CACHE_GENERATION, 0L);
        String styleFp = styleFingerprint(layer);
        try {
            String metaJson = new String(Files.readAllBytes(metaFile.toPath()), StandardCharsets.UTF_8);
            JSONObject meta = new JSONObject(metaJson);
            if (meta.optInt("schema", 0) != META_SCHEMA) return null;
            if (meta.optLong("generation", -1) != gen) return null;
            if (!TextUtils.equals(meta.optString("styleFp", ""), styleFp)) return null;

            long t0 = System.nanoTime();
            sLastLoadNanos.set(System.nanoTime() - t0);
            Log.d(TAG, "cache HIT (file URI) layer=" + layer.getName()
                    + " features=" + meta.optInt("featureCount", -1)
                    + " file=" + geoFile.length() + "b");
            // Prefer Android file URL; java.io.File#toURI() shape differs and mbgl can fail to open.
            return URI.create(Uri.fromFile(geoFile).toString());
        } catch (Exception e) {
            Log.d(TAG, "cache meta read fail layer=" + layer.getName() + " " + e.getMessage());
            return null;
        }
    }

    /**
     * Load cached GeoJSON into memory for {@link org.maplibre.android.style.sources.GeoJsonSource}.
     * Does not require {@link #USE_MAPLIBRE_NATIVE_GEOJSON_URI}. Geometry validity uses generation + schema
     * only (not style fingerprint) so unstable renderer JSON does not force endless cache misses.
     */
    @Nullable
    public static List<Feature> tryLoadFeatures(VectorLayer layer) {
        if (!Constants.MAP_STARTUP_OPTIMIZATIONS_ENABLED) {
            return null;
        }
        if (!isEligible(layer)) {
            return null;
        }
        Context ctx = layer.getContext();
        File dir = cacheDir(ctx, layer);
        File metaFile = new File(dir, FILE_META);
        File geoFile = new File(dir, FILE_FEATURES);
        if (!metaFile.exists() || !geoFile.exists()) {
            return null;
        }

        long gen = layer.getPreferences().getLong(SettingsConstants.KEY_PREF_RENDER_CACHE_GENERATION, 0L);
        JSONObject meta;
        try {
            String metaJson = new String(Files.readAllBytes(metaFile.toPath()), StandardCharsets.UTF_8);
            meta = new JSONObject(metaJson);
            if (meta.optInt("schema", 0) != META_SCHEMA) {
                return null;
            }
            if (meta.optLong("generation", -1) != gen) {
                return null;
            }
            String metaFp = meta.optString("styleFp", "");
            String liveFp = styleFingerprint(layer);
            if (!TextUtils.equals(metaFp, liveFp)) {
                if (Constants.DEBUG_MODE) {
                    Log.d(TAG, "cache styleFp differs from meta (ignored for tryLoadFeatures) layer="
                            + layer.getName());
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "tryLoadFeatures meta fail layer=" + layer.getName() + " " + e.getMessage());
            return null;
        }

        int expected = meta.optInt("featureCount", 0);
        ArrayList<Feature> out = new ArrayList<>(expected > 0 ? expected : 1024);
        long t0 = System.nanoTime();
        try (JsonReader jr = new JsonReader(new InputStreamReader(
                new FileInputStream(geoFile), StandardCharsets.UTF_8))) {
            jr.setLenient(true);
            jr.beginObject();
            boolean foundFeatures = false;
            while (jr.hasNext()) {
                String name = jr.nextName();
                if ("features".equals(name)) {
                    foundFeatures = true;
                    jr.beginArray();
                    while (jr.hasNext()) {
                        JsonElement el = JsonParser.parseReader(jr);
                        out.add(Feature.fromJson(el.toString()));
                    }
                    jr.endArray();
                } else {
                    jr.skipValue();
                }
            }
            jr.endObject();
            if (!foundFeatures) {
                return null;
            }
        } catch (Exception e) {
            Log.w(TAG, "tryLoadFeatures parse fail layer=" + layer.getName(), e);
            deleteCacheFiles(ctx, layer);
            return null;
        }

        if (out.isEmpty()) {
            long elapsedEmpty = System.nanoTime() - t0;
            sLastLoadNanos.set(elapsedEmpty);
            if (Constants.DEBUG_MODE) {
                Log.d(TAG, "cache HIT (features) layer=" + layer.getName() + " n=0 parseMs="
                        + (elapsedEmpty / 1_000_000));
            }
            return new ArrayList<>();
        }

        long elapsed = System.nanoTime() - t0;
        sLastLoadNanos.set(elapsed);
        if (Constants.DEBUG_MODE) {
            Log.d(TAG, "cache HIT (features) layer=" + layer.getName()
                    + " n=" + out.size()
                    + " parseMs=" + (elapsed / 1_000_000));
        }
        return out;
    }

    /**
     * Write features to disk using streaming (BufferedWriter) to avoid OOM on large layers.
     */
    public static void save(VectorLayer layer, List<Feature> features) {
        if (!Constants.MAP_STARTUP_OPTIMIZATIONS_ENABLED) return;
        if (!isEligible(layer) || features == null) return;
        Context ctx = layer.getContext();
        File dir = cacheDir(ctx, layer);
        if (!dir.exists() && !dir.mkdirs()) return;

        long gen = layer.getPreferences().getLong(SettingsConstants.KEY_PREF_RENDER_CACHE_GENERATION, 0L);
        String styleFp = styleFingerprint(layer);
        File tmpGeo = new File(dir, FILE_FEATURES + ".tmp");
        File tmpMeta = new File(dir, FILE_META + ".tmp");
        try {
            long t0 = System.nanoTime();
            writeGeoJsonStreaming(tmpGeo, features);
            long writeMs = (System.nanoTime() - t0) / 1_000_000;

            JSONObject meta = new JSONObject();
            meta.put("schema", META_SCHEMA);
            meta.put("generation", gen);
            meta.put("styleFp", styleFp);
            meta.put("featureCount", features.size());
            meta.put("fileSizeBytes", tmpGeo.length());
            FileUtil.writeToFile(tmpMeta, meta.toString(), false);

            File metaF = new File(dir, FILE_META);
            File geoF = new File(dir, FILE_FEATURES);
            if (!tmpMeta.renameTo(metaF)) {
                Files.move(tmpMeta.toPath(), metaF.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            if (!tmpGeo.renameTo(geoF)) {
                Files.move(tmpGeo.toPath(), geoF.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            Log.d(TAG, "cache WRITE layer=" + layer.getName()
                    + " features=" + features.size()
                    + " size=" + geoF.length() + "b"
                    + " writeMs=" + writeMs
                    + " gen=" + gen);
        } catch (Exception e) {
            Log.w(TAG, "cache write failed " + layer.getName(), e);
            deleteCacheFiles(ctx, layer);
        } finally {
            if (tmpGeo.exists()) tmpGeo.delete();
            if (tmpMeta.exists()) tmpMeta.delete();
        }
    }

    /**
     * Stream GeoJSON FeatureCollection to file without building full string in memory.
     */
    private static void writeGeoJsonStreaming(File file, List<Feature> features) throws Exception {
        try (BufferedWriter w = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8),
                65536)) {
            w.write("{\"type\":\"FeatureCollection\",\"features\":[");
            for (int i = 0; i < features.size(); i++) {
                if (i > 0) w.write(',');
                w.write(features.get(i).toJson());
            }
            w.write("]}");
        }
    }

    static String styleFingerprint(VectorLayer layer) {
        try {
            IRenderer ir = layer.getRenderer();
            if (ir instanceof IJSONStore) {
                return ((IJSONStore) ir).toJSON().toString();
            }
        } catch (JSONException ignored) {
        }
        return "";
    }

    private static void deleteCacheFiles(Context ctx, VectorLayer layer) {
        File dir = cacheDir(ctx, layer);
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            dir.delete();
        }
    }

    private static File cacheDir(Context ctx, VectorLayer layer) {
        String id = String.format("%s_%08x", layer.getPath().getName(),
                layer.getPath().getAbsolutePath().hashCode());
        return new File(ctx.getCacheDir(), CACHE_SUBDIR + File.separator + "v2" + File.separator + id);
    }
}

/*
 * Disk cache for MapLibre-bound GeoJSON features to avoid full SQLite scans on cold start.
 *
 * <p>Schema 3 splits geometry and style:
 * <ul>
 *   <li>{@link #FILE_GEOM} — geometry + stable ids (featureid, layerid, order)</li>
 *   <li>Style props applied in memory via {@link MPLFeaturesUtils#refreshMaplibreStyleOnFeatures}</li>
 * </ul>
 *
 * <p>Schema 2 (legacy combined) is purged on load; only schema 3 is used.</p>
 *
 * <p>All entry points no-op when {@link com.nextgis.maplib.util.Constants#VECTOR_RENDER_DISK_CACHE_ENABLED}
 * is false.</p>
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
    /** Legacy combined cache (geometry + style props). */
    private static final int META_SCHEMA_COMBINED = 2;
    /** Geometry-only cache; style applied on load. */
    private static final int META_SCHEMA_GEOM = 3;
    private static final String CACHE_SUBDIR = "maplibre_vector_render";
    private static final String FILE_FEATURES = "features.geojson";
    private static final String FILE_GEOM = "features-geom.geojson";
    /** Full geometry + style props for {@link #tryLoadAsUri} (native MapLibre parse). */
    private static final String FILE_STYLED = "features-styled.geojson";
    private static final String FILE_META = "meta.json";

    private static final AtomicLong sLastLoadNanos = new AtomicLong(0);

    private VectorLayerRenderCache() {
    }

    public static boolean isEligible(VectorLayer layer) {
        return layer != null && layer.getContext() != null;
    }

    public static long getLastLoadNanos() {
        return sLastLoadNanos.get();
    }

    /**
     * Whether on-disk schema-3 geometry cache matches current {@code geom_cache_generation}
     * (meta check only — no GeoJSON parse).
     */
    public static boolean hasValidCache(VectorLayer layer) {
        if (!Constants.VECTOR_RENDER_DISK_CACHE_ENABLED || !isEligible(layer)) {
            return false;
        }
        Context ctx = layer.getContext();
        File dir = cacheDir(ctx, layer);
        File metaFile = new File(dir, FILE_META);
        File geoFile = new File(dir, FILE_GEOM);
        if (!metaFile.exists() || !geoFile.exists() || geoFile.length() == 0L) {
            return false;
        }
        long geomGen = layer.getPreferences().getLong(SettingsConstants.KEY_PREF_GEOM_CACHE_GENERATION, 0L);
        try {
            JSONObject meta = readMeta(metaFile);
            if (meta.optInt("schema", 0) != META_SCHEMA_GEOM) {
                return false;
            }
            return meta.optLong("geomGeneration", -1) == geomGen;
        } catch (Exception e) {
            return false;
        }
    }

    /** Bump geometry generation and delete on-disk cache (data edit / import / sync). */
    public static void invalidateOnDataChange(VectorLayer layer) {
        if (!Constants.VECTOR_RENDER_DISK_CACHE_ENABLED) {
            return;
        }
        if (layer == null || layer.getContext() == null) {
            return;
        }
        long next = layer.getPreferences().getLong(SettingsConstants.KEY_PREF_GEOM_CACHE_GENERATION, 0L) + 1L;
        layer.getPreferences().edit()
                .putLong(SettingsConstants.KEY_PREF_GEOM_CACHE_GENERATION, next)
                .apply();
        deleteCacheFiles(layer.getContext(), layer);
        Log.d(TAG, "invalidateOnDataChange layer=" + layer.getName() + " geomGen=" + next);
    }

    /**
     * Style/renderer changed — geometry cache stays valid; styled native file is dropped so
     * {@link #tryLoadAsUri} falls back to geom + in-memory style apply until next save.
     */
    public static void invalidateOnStyleChange(VectorLayer layer) {
        if (!Constants.VECTOR_RENDER_DISK_CACHE_ENABLED || layer == null) {
            return;
        }
        if (layer.getContext() != null) {
            File styled = new File(cacheDir(layer.getContext(), layer), FILE_STYLED);
            if (styled.exists()) {
                styled.delete();
            }
        }
        Log.d(TAG, "invalidateOnStyleChange (geom cache retained) layer=" + layer.getName());
    }

    public static URI tryLoadAsUri(VectorLayer layer) {
        if (!Constants.VECTOR_RENDER_DISK_CACHE_ENABLED || !USE_MAPLIBRE_NATIVE_GEOJSON_URI) {
            return null;
        }
        if (!isEligible(layer) || !layer.isEditingAllowed()) {
            return null;
        }
        Context ctx = layer.getContext();
        File dir = cacheDir(ctx, layer);
        File metaFile = new File(dir, FILE_META);
        File geoFile = geomCacheFile(dir, metaFile);
        if (!metaFile.exists() || geoFile == null || !geoFile.exists()) {
            return null;
        }

        long geomGen = layer.getPreferences().getLong(SettingsConstants.KEY_PREF_GEOM_CACHE_GENERATION, 0L);
        try {
            JSONObject meta = readMeta(metaFile);
            int schema = meta.optInt("schema", 0);
            if (schema == META_SCHEMA_GEOM) {
                if (meta.optLong("geomGeneration", -1) != geomGen) {
                    return null;
                }
                String fp = styleFingerprint(layer);
                if (!TextUtils.equals(meta.optString("styleFp", ""), fp)) {
                    return null;
                }
                File styled = new File(dir, FILE_STYLED);
                if (!styled.exists() || styled.length() == 0L) {
                    return null;
                }
                geoFile = styled;
            } else {
                return null;
            }

            long t0 = System.nanoTime();
            sLastLoadNanos.set(System.nanoTime() - t0);
            Log.d(TAG, "cache HIT (file URI) layer=" + layer.getName()
                    + " features=" + meta.optInt("featureCount", -1)
                    + " file=" + geoFile.length() + "b");
            return URI.create(Uri.fromFile(geoFile).toString());
        } catch (Exception e) {
            Log.d(TAG, "cache meta read fail layer=" + layer.getName() + " " + e.getMessage());
            return null;
        }
    }

    /**
     * Load cached features for {@link org.maplibre.android.style.sources.GeoJsonSource}.
     * Schema 3: geometry from disk + style applied in memory (no styleFp gate on geom).
     */
    @Nullable
    public static List<Feature> tryLoadFeatures(VectorLayer layer) {
        if (!Constants.VECTOR_RENDER_DISK_CACHE_ENABLED || !isEligible(layer)) {
            return null;
        }
        purgeInvalidOrLegacyCache(layer);
        List<Feature> geom = tryLoadGeometryFeatures(layer);
        if (geom != null) {
            long t0 = System.nanoTime();
            MPLFeaturesUtils.refreshMaplibreStyleOnFeatures(layer, geom);
            long elapsed = System.nanoTime() - t0;
            sLastLoadNanos.set(elapsed);
            if (Constants.DEBUG_MODE) {
                Log.d(TAG, "cache HIT (geom+style) layer=" + layer.getName()
                        + " n=" + geom.size()
                        + " styleApplyMs=" + (elapsed / 1_000_000));
            }
            return geom;
        }
        if (Constants.DEBUG_MODE) {
            Log.d(TAG, "cache MISS layer=" + layer.getName()
                    + " hasValidCache=" + hasValidCache(layer));
        }
        return null;
    }

    /** Schema 2 (legacy combined) and unknown schemas are purged so the next load writes schema 3. */
    private static void purgeInvalidOrLegacyCache(VectorLayer layer) {
        Context ctx = layer.getContext();
        if (ctx == null) {
            return;
        }
        File dir = cacheDir(ctx, layer);
        File metaFile = new File(dir, FILE_META);
        if (!metaFile.exists()) {
            return;
        }
        try {
            JSONObject meta = readMeta(metaFile);
            int schema = meta.optInt("schema", 0);
            if (schema == META_SCHEMA_GEOM) {
                return;
            }
            deleteCacheFiles(ctx, layer);
            Log.d(TAG, "purged stale cache schema=" + schema + " layer=" + layer.getName());
        } catch (Exception e) {
            deleteCacheFiles(ctx, layer);
            Log.d(TAG, "purged unreadable cache layer=" + layer.getName() + " " + e.getMessage());
        }
    }

    @Nullable
    private static List<Feature> tryLoadGeometryFeatures(VectorLayer layer) {
        Context ctx = layer.getContext();
        File dir = cacheDir(ctx, layer);
        File metaFile = new File(dir, FILE_META);
        File geoFile = new File(dir, FILE_GEOM);
        if (!metaFile.exists() || !geoFile.exists()) {
            return null;
        }
        long geomGen = layer.getPreferences().getLong(SettingsConstants.KEY_PREF_GEOM_CACHE_GENERATION, 0L);
        try {
            JSONObject meta = readMeta(metaFile);
            if (meta.optInt("schema", 0) != META_SCHEMA_GEOM) {
                return null;
            }
            if (meta.optLong("geomGeneration", -1) != geomGen) {
                return null;
            }
            int expected = meta.optInt("featureCount", 0);
            return parseFeatureArray(geoFile, layer.getName(), expected);
        } catch (Exception e) {
            Log.d(TAG, "tryLoadGeometry meta fail layer=" + layer.getName() + " " + e.getMessage());
            return null;
        }
    }

    /**
     * Persist geometry shells (schema 3). Caller may pass fully styled features — they are stripped.
     */
    public static void save(VectorLayer layer, List<Feature> features) {
        if (!Constants.VECTOR_RENDER_DISK_CACHE_ENABLED || !isEligible(layer) || features == null) {
            return;
        }
        Context ctx = layer.getContext();
        File dir = cacheDir(ctx, layer);
        if (!dir.exists() && !dir.mkdirs()) {
            return;
        }

        long geomGen = layer.getPreferences().getLong(SettingsConstants.KEY_PREF_GEOM_CACHE_GENERATION, 0L);
        String styleFp = styleFingerprint(layer);
        List<Feature> geomShells = MPLFeaturesUtils.toGeometryShells(features, layer.getGeometryType());

        File tmpGeo = new File(dir, FILE_GEOM + ".tmp");
        File tmpStyled = new File(dir, FILE_STYLED + ".tmp");
        File tmpMeta = new File(dir, FILE_META + ".tmp");
        try {
            long t0 = System.nanoTime();
            writeGeoJsonStreaming(tmpGeo, geomShells);
            writeGeoJsonStreaming(tmpStyled, features);
            long writeMs = (System.nanoTime() - t0) / 1_000_000;

            JSONObject meta = new JSONObject();
            meta.put("schema", META_SCHEMA_GEOM);
            meta.put("geomGeneration", geomGen);
            meta.put("styleFp", styleFp);
            meta.put("featureCount", geomShells.size());
            meta.put("fileSizeBytes", tmpGeo.length());
            meta.put("styledFileSizeBytes", tmpStyled.length());
            FileUtil.writeToFile(tmpMeta, meta.toString(), false);

            File metaF = new File(dir, FILE_META);
            File geoF = new File(dir, FILE_GEOM);
            File styledF = new File(dir, FILE_STYLED);
            commitAtomic(tmpMeta, metaF);
            commitAtomic(tmpGeo, geoF);
            commitAtomic(tmpStyled, styledF);

            File legacyCombined = new File(dir, FILE_FEATURES);
            if (legacyCombined.exists()) {
                legacyCombined.delete();
            }

            Log.d(TAG, "cache WRITE geom layer=" + layer.getName()
                    + " features=" + geomShells.size()
                    + " geom=" + geoF.length() + "b"
                    + " styled=" + styledF.length() + "b"
                    + " writeMs=" + writeMs
                    + " geomGen=" + geomGen);
        } catch (Exception e) {
            Log.w(TAG, "cache write failed " + layer.getName(), e);
            deleteCacheFiles(ctx, layer);
        } finally {
            if (tmpGeo.exists()) {
                tmpGeo.delete();
            }
            if (tmpStyled.exists()) {
                tmpStyled.delete();
            }
            if (tmpMeta.exists()) {
                tmpMeta.delete();
            }
        }
    }

    private static void writeGeoJsonStreaming(File file, List<Feature> features) throws Exception {
        try (BufferedWriter w = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8),
                65536)) {
            w.write("{\"type\":\"FeatureCollection\",\"features\":[");
            for (int i = 0; i < features.size(); i++) {
                if (i > 0) {
                    w.write(',');
                }
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

    @Nullable
    private static File geomCacheFile(File dir, File metaFile) {
        if (!metaFile.exists()) {
            return null;
        }
        try {
            JSONObject meta = readMeta(metaFile);
            int schema = meta.optInt("schema", 0);
            if (schema == META_SCHEMA_GEOM) {
                File styled = new File(dir, FILE_STYLED);
                if (styled.exists()) {
                    return styled;
                }
                File geom = new File(dir, FILE_GEOM);
                return geom.exists() ? geom : null;
            }
            if (schema == META_SCHEMA_COMBINED) {
                File combined = new File(dir, FILE_FEATURES);
                return combined.exists() ? combined : null;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Nullable
    private static List<Feature> parseFeatureArray(File geoFile, String layerName, int expected) {
        ArrayList<Feature> out = new ArrayList<>(expected > 0 ? expected : 1024);
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
            Log.w(TAG, "parseFeatureArray fail layer=" + layerName, e);
            return null;
        }
        return out;
    }

    private static JSONObject readMeta(File metaFile) throws Exception {
        String metaJson = new String(Files.readAllBytes(metaFile.toPath()), StandardCharsets.UTF_8);
        return new JSONObject(metaJson);
    }

    private static void commitAtomic(File tmp, File dest) throws Exception {
        if (!tmp.renameTo(dest)) {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
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

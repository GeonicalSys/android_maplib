/*
 * Disk cache for MapLibre-bound GeoJSON features to avoid full SQLite scans on cold start
 * for NGW vector layers synced from server only (DIRECTION_FROM).
 */

package com.nextgis.maplib.map;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.nextgis.maplib.api.IJSONStore;
import com.nextgis.maplib.api.IRenderer;
import com.nextgis.maplib.util.Constants;
import com.nextgis.maplib.util.FileUtil;
import com.nextgis.maplib.util.SettingsConstants;

import org.json.JSONException;
import org.json.JSONObject;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class VectorLayerRenderCache {

    /**
     * Master switch: when {@code false}, layers always load from SQLite / {@code createFeatureListFromLayer}
     * (original behavior). Disk cache read/write/invalidate are skipped.
     */
    public static volatile boolean ENABLED = false;

    private static final String TAG = "VectorLayerRenderCache";
    private static final int META_SCHEMA = 1;
    private static final String CACHE_SUBDIR = "maplibre_vector_render";
    private static final String FILE_FEATURES = "features.geojson";
    private static final String FILE_META = "meta.json";

    /** Monotonic counter for debug timing (nanos). */
    private static final AtomicLong sLastLoadNanos = new AtomicLong(0);

    private VectorLayerRenderCache() {
    }

    /**
     * Server-pull-only NGW layers: local edits are not expected; cache is safe and most valuable.
     */
    public static boolean isEligible(VectorLayer layer) {
        return layer instanceof NGWVectorLayer
                && ((NGWVectorLayer) layer).getSyncDirection() == 2; // NGWVectorLayer.DIRECTION_FROM
    }

    public static long getLastLoadNanos() {
        return sLastLoadNanos.get();
    }

    /**
     * Bump render-cache generation and delete on-disk files so the next map load rebuilds from DB.
     */
    public static void invalidateOnDataChange(VectorLayer layer) {
        if (!ENABLED) {
            return;
        }
        if (layer == null || layer.getContext() == null) {
            return;
        }
        long next = layer.getPreferences().getLong(SettingsConstants.KEY_PREF_RENDER_CACHE_GENERATION, 0L) + 1L;
        layer.getPreferences().edit().putLong(SettingsConstants.KEY_PREF_RENDER_CACHE_GENERATION, next).apply();
        deleteCacheFiles(layer.getContext(), layer);
        if (Constants.DEBUG_MODE) {
            Log.d(TAG, "invalidateOnDataChange layer=" + layer.getName() + " gen=" + next);
        }
    }

    public static List<Feature> tryLoad(VectorLayer layer) {
        if (!ENABLED) {
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
        String styleFp = styleFingerprint(layer);
        try {
            String metaJson = new String(Files.readAllBytes(metaFile.toPath()), StandardCharsets.UTF_8);
            JSONObject meta = new JSONObject(metaJson);
            if (meta.optInt("schema", 0) != META_SCHEMA) {
                return null;
            }
            if (meta.optLong("generation", -1) != gen) {
                return null;
            }
            if (!TextUtils.equals(meta.optString("styleFp", ""), styleFp)) {
                return null;
            }
            long t0 = System.nanoTime();
            String geoJson = new String(Files.readAllBytes(geoFile.toPath()), StandardCharsets.UTF_8);
            FeatureCollection fc = FeatureCollection.fromJson(geoJson);
            List<Feature> out = fc != null && fc.features() != null
                    ? new ArrayList<>(fc.features())
                    : new ArrayList<>();
            sLastLoadNanos.set(System.nanoTime() - t0);
            if (Constants.DEBUG_MODE) {
                Log.d(TAG, "cache HIT layer=" + layer.getName() + " features=" + out.size()
                        + " loadNs=" + sLastLoadNanos.get());
            }
            return out;
        } catch (Exception e) {
            if (Constants.DEBUG_MODE) {
                Log.d(TAG, "cache read miss layer=" + layer.getName() + " " + e.getMessage());
            }
            return null;
        }
    }

    public static void save(VectorLayer layer, List<Feature> features) {
        if (!ENABLED) {
            return;
        }
        if (!isEligible(layer) || features == null) {
            return;
        }
        Context ctx = layer.getContext();
        File dir = cacheDir(ctx, layer);
        if (!dir.exists() && !dir.mkdirs()) {
            return;
        }
        long gen = layer.getPreferences().getLong(SettingsConstants.KEY_PREF_RENDER_CACHE_GENERATION, 0L);
        String styleFp = styleFingerprint(layer);
        try {
            String geoJson = featureCollectionToJson(features);
            File tmpGeo = new File(dir, FILE_FEATURES + ".tmp");
            File tmpMeta = new File(dir, FILE_META + ".tmp");
            FileUtil.writeToFile(tmpGeo, geoJson, false);
            JSONObject meta = new JSONObject();
            meta.put("schema", META_SCHEMA);
            meta.put("generation", gen);
            meta.put("styleFp", styleFp);
            meta.put("featureCount", features.size());
            FileUtil.writeToFile(tmpMeta, meta.toString(), false);
            File metaF = new File(dir, FILE_META);
            File geoF = new File(dir, FILE_FEATURES);
            if (!tmpMeta.renameTo(metaF)) {
                Files.move(tmpMeta.toPath(), metaF.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            if (!tmpGeo.renameTo(geoF)) {
                Files.move(tmpGeo.toPath(), geoF.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            if (Constants.DEBUG_MODE) {
                Log.d(TAG, "cache WRITE layer=" + layer.getName() + " features=" + features.size() + " gen=" + gen);
            }
        } catch (Exception e) {
            Log.w(TAG, "cache write failed " + layer.getName(), e);
            deleteCacheFiles(ctx, layer);
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
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
            //noinspection ResultOfMethodCallIgnored
            dir.delete();
        }
    }

    private static File cacheDir(Context ctx, VectorLayer layer) {
        String id = String.format("%s_%08x", layer.getPath().getName(),
                layer.getPath().getAbsolutePath().hashCode());
        return new File(ctx.getCacheDir(), CACHE_SUBDIR + File.separator + "v1" + File.separator + id);
    }

    /** GeoJSON serialization without relying on FeatureCollection.toJson (SDK differences). */
    private static String featureCollectionToJson(List<Feature> features) {
        StringBuilder sb = new StringBuilder(Math.min(features.size() * 256, 1_048_576));
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[");
        for (int i = 0; i < features.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(features.get(i).toJson());
        }
        sb.append("]}");
        return sb.toString();
    }
}

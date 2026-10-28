package com.nextgis.maplib.map;

import android.os.Looper;
import android.os.Handler;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.FillLayer;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.android.style.sources.Source;
import org.maplibre.geojson.FeatureCollection;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import static com.nextgis.maplib.util.GeoConstants.*;

/** UI-owned native sources; preparation/readiness never materializes an entire vector layer. */
final class SqliteVectorLayerController {
    private static final Map<MapLibreMap, WeakReference<SqliteVectorLayerController>> OWNERS = new WeakHashMap<>();
    private static final Map<Style, WeakReference<SqliteVectorLayerController>> STYLE_OWNERS = new WeakHashMap<>();
    private static final AtomicLong NEXT_SOURCE_EPOCH = new AtomicLong();
    private final Map<Integer, AtomicLong> epochs = new ConcurrentHashMap<>();
    private final Map<Integer, Entry> entries = new ConcurrentHashMap<>();
    private final Map<Integer, Long> excluded = new HashMap<>();
    private final Map<Integer, java.util.Set<Long>> hidden = new HashMap<>();
    private final SqliteSourceRetirement retirement = new SqliteSourceRetirement();

    boolean isRetired() { return retirement.isRetired(); }

    /** Any thread, terminal and nonblocking: no SDK calls or application monitor acquisition. */
    void retire() {
        try {
            retirement.retire();
        } finally {
            for (AtomicLong epoch : epochs.values()) epoch.set(Long.MIN_VALUE);
            for (Entry entry : entries.values()) entry.provider.close();
            // Cancellation already happened. Only release UI-owned wrapper/filter references here.
            new Handler(Looper.getMainLooper()).post(this::reset);
        }
    }

    static void closeBeforeStyleChange(MapLibreMap map) {
        ui();
        WeakReference<SqliteVectorLayerController> reference = OWNERS.remove(map);
        SqliteVectorLayerController owner = reference == null ? null : reference.get();
        if (owner != null) owner.closeAll();
    }

    SqliteVectorTileProvider provider(int layerId) {
        // Reading a provider reference is pure Java and may be used by viewport IO snapshots.
        Entry entry = entries.get(layerId); return entry == null ? null : entry.provider;
    }

    Map<String, Object> diagnostics(int layerId) {
        Entry entry = entries.get(layerId);
        Map<String, Object> state = new java.util.LinkedHashMap<>();
        state.put("source_registered", entry != null);
        if (entry != null) state.putAll(entry.bridge.getDiagnostics());
        return state;
    }

    String nativeSourceId(int layerId) {
        Entry entry = entries.get(layerId); return entry == null ? null : entry.nativeId;
    }

    private static Entry find(Style style, String logicalPath) {
        WeakReference<SqliteVectorLayerController> reference = STYLE_OWNERS.get(style);
        SqliteVectorLayerController owner = reference == null ? null : reference.get();
        if (owner == null) return null;
        for (Entry entry : owner.entries.values())
            if (entry.style == style && entry.layer.getPath().toString().equals(logicalPath)) return entry;
        return null;
    }

    static String nativeSourceId(Style style, String logicalPath) {
        ui(); Entry entry = find(style, logicalPath); return entry == null ? logicalPath : entry.nativeId;
    }

    static boolean isStreaming(Style style, String logicalPath) {
        ui(); return find(style, logicalPath) != null;
    }

    /** An explicit classic bind must retire any prior streaming source before replacing children. */
    static void detachForClassic(Style style, String logicalPath) {
        ui(); Entry entry = find(style, logicalPath); if (entry == null) return;
        WeakReference<SqliteVectorLayerController> reference = STYLE_OWNERS.get(style);
        SqliteVectorLayerController owner = reference == null ? null : reference.get();
        if (owner != null) owner.remove(entry.layer.getId(), style, entry.overlays);
    }

    boolean bind(MapLibreMap map, Style style, VectorLayer layer,
                 Map<String, GeoJsonSource> overlaySources) throws IOException {
        ui();
        if (retirement.isRetired() || !SqliteVectorTileProvider.canServe(layer)) return false;
        WeakReference<SqliteVectorLayerController> previousOwner = OWNERS.get(map);
        SqliteVectorLayerController otherOwner = previousOwner == null ? null : previousOwner.get();
        if (otherOwner != null && otherOwner != this) otherOwner.closeAll();
        Entry old = entries.get(layer.getId());
        String path = layer.getPath().toString();
        String fingerprint = VectorLayerRenderCache.styleFingerprint(layer);
        if (old != null && old.style == style && old.layer == layer && old.provider.isCurrent()
                && old.styleFingerprint.equals(fingerprint)
                && style.getSource(old.nativeId) == old.source) return true;
        remove(layer.getId(), style, overlaySources);
        removeSources(style, path, path, overlaySources);
        AtomicLong epoch = epochs.computeIfAbsent(layer.getId(), ignored -> new AtomicLong());
        long generation = NEXT_SOURCE_EPOCH.incrementAndGet();
        epoch.set(generation);
        // SDK13.7.1 coalesces same-ID replacements and compares loader presence, not identity.
        // A distinct renderer identity forces the old tile pyramid/loader to be retired.
        String nativeId = path + "#sqlite-native-" + generation;
        // Editing suppresses the old feature through native filters, without rebuilding all tiles.
        SqliteVectorTileProvider provider = new SqliteVectorTileProvider(layer, generation,
                () -> retirement.isRetired() ? Long.MIN_VALUE : epoch.get(), null);
        SqliteVectorSourceBridge bridge = null;
        SqliteSourceRetirement.Registration registration = null;
        try {
            bridge = new SqliteVectorSourceBridge(nativeId, provider,
                    () -> !retirement.isRetired() && epoch.get() == generation, 0, 18, layer.getId());
            registration = retirement.register(bridge::cancelPendingWork);
            if (retirement.isRetired()) return false;
            Source source = bridge.getSource();
            source.setPrefetchZoomDelta(0);
            source.setVolatile(true);
            style.addSource(source);
            if (layer.getGeometryType() == GTLineString || layer.getGeometryType() == GTMultiLineString) {
                String labels = path + MPLFeaturesUtils.source_polygon_text;
                GeoJsonSource points = new GeoJsonSource(labels, FeatureCollection.fromFeatures(Collections.emptyList()));
                style.addSource(points); overlaySources.put(labels, points);
            }
            entries.put(layer.getId(), new Entry(layer, style, provider, bridge, source, fingerprint,
                    registration, nativeId, overlaySources));
            OWNERS.put(map, new WeakReference<>(this));
            STYLE_OWNERS.put(style, new WeakReference<>(this));
            return true;
        } catch (RuntimeException | Error failure) {
            epoch.set(Long.MIN_VALUE);
            if (registration != null) registration.close();
            if (bridge != null) bridge.closeBeforeDetach(); else provider.close();
            removeSources(style, path, nativeId, overlaySources);
            throw failure;
        }
    }

    void remove(int layerId, Style style, Map<String, GeoJsonSource> overlaySources) {
        ui();
        Entry entry = entries.remove(layerId);
        AtomicLong epoch = epochs.get(layerId); if (epoch != null) epoch.set(Long.MIN_VALUE);
        if (entry == null) return;
        entry.registration.close();
        entry.bridge.closeBeforeDetach();
        if (entry.style == style) removeSources(style, entry.layer.getPath().toString(), entry.nativeId, overlaySources);
    }

    void closeAll() {
        ui();
        for (Map.Entry<Integer, Entry> item : entries.entrySet()) {
            epochs.get(item.getKey()).set(Long.MIN_VALUE);
            item.getValue().registration.close();
            item.getValue().bridge.closeBeforeDetach();
            STYLE_OWNERS.remove(item.getValue().style);
        }
        entries.clear();
    }

    void reset() { closeAll(); excluded.clear(); hidden.clear(); }

    void forget(int layerId, Style style, Map<String, GeoJsonSource> overlaySources) {
        remove(layerId, style, overlaySources); excluded.remove(layerId); hidden.remove(layerId);
    }

    void hide(Style style, int layerId, long featureId, boolean hide) {
        ui();
        if (hide) hidden.computeIfAbsent(layerId, ignored -> new HashSet<>()).add(featureId);
        else {
            java.util.Set<Long> ids = hidden.get(layerId);
            if (ids != null) { ids.remove(featureId); if (ids.isEmpty()) hidden.remove(layerId); }
        }
        layersCreated(style, layerId);
    }

    void exclude(Style style, int layerId, Long featureId) {
        ui();
        if (featureId == null) excluded.remove(layerId); else excluded.put(layerId, featureId);
        layersCreated(style, layerId);
    }

    void layersCreated(Style style, int layerId) {
        ui();
        Entry entry = entries.get(layerId); if (entry == null || entry.style != style) return;
        String path = entry.layer.getPath().toString();
        Long hidden = excluded.get(layerId);
        for (Layer discovered : style.getLayers()) {
            // getLayers() returns new JNI wrappers even for an unchanged core layer. Java-added
            // factories are cached by getLayer(id), so identity here tracks actual replacement,
            // not a fresh enumeration wrapper capturing our already-derived exclusion as base.
            Layer layer = style.getLayer(discovered.getId());
            if (layer == null) continue;
            String source = sourceId(layer);
            if (!entry.nativeId.equals(source) && !(path + MPLFeaturesUtils.source_polygon_text).equals(source)) continue;
            Filter original = entry.filters.get(layer.getId());
            if (original == null || original.layer != layer) {
                original = new Filter(layer, filter(layer)); entry.filters.put(layer.getId(), original);
            }
            Expression active = original.expression;
            if (hidden != null) {
                Expression include = Expression.neq(Expression.toString(Expression.get(MPLFeaturesUtils.prop_featureid)),
                        Expression.literal(hidden.toString()));
                active = active == null ? include : Expression.all(active, include);
            }
            java.util.Set<Long> hiddenIds = this.hidden.get(layerId);
            if (hiddenIds != null) for (Long id : hiddenIds) {
                Expression include = Expression.neq(Expression.toString(Expression.get(MPLFeaturesUtils.prop_featureid)),
                        Expression.literal(id.toString()));
                active = active == null ? include : Expression.all(active, include);
            }
            filter(layer, active);
        }
    }

    static String sourceId(Layer layer) {
        if (layer instanceof FillLayer) return ((FillLayer) layer).getSourceId();
        if (layer instanceof LineLayer) return ((LineLayer) layer).getSourceId();
        if (layer instanceof CircleLayer) return ((CircleLayer) layer).getSourceId();
        if (layer instanceof SymbolLayer) return ((SymbolLayer) layer).getSourceId();
        return null;
    }

    private static Expression filter(Layer layer) {
        if (layer instanceof FillLayer) return ((FillLayer) layer).getFilter();
        if (layer instanceof LineLayer) return ((LineLayer) layer).getFilter();
        if (layer instanceof CircleLayer) return ((CircleLayer) layer).getFilter();
        if (layer instanceof SymbolLayer) return ((SymbolLayer) layer).getFilter();
        return null;
    }

    private static void filter(Layer layer, Expression expression) {
        // Removing a transient exclusion restores the exact original expression, including no filter.
        Expression actual = expression == null ? Expression.literal(true) : expression;
        if (layer instanceof FillLayer) ((FillLayer) layer).setFilter(actual);
        else if (layer instanceof LineLayer) ((LineLayer) layer).setFilter(actual);
        else if (layer instanceof CircleLayer) ((CircleLayer) layer).setFilter(actual);
        else if (layer instanceof SymbolLayer) ((SymbolLayer) layer).setFilter(actual);
    }

    private static void removeSources(Style style, String path, String nativeId, Map<String, GeoJsonSource> overlaySources) {
        for (Layer layer : new ArrayList<>(style.getLayers())) {
            String source = sourceId(layer);
            if (nativeId.equals(source) || path.equals(source)
                    || (path + MPLFeaturesUtils.source_polygon_text).equals(source)) style.removeLayer(layer);
        }
        if (!nativeId.equals(path) && style.getSource(nativeId) != null) style.removeSource(nativeId);
        if (style.getSource(path) != null) style.removeSource(path);
        String labels = path + MPLFeaturesUtils.source_polygon_text;
        if (style.getSource(labels) != null) style.removeSource(labels);
        overlaySources.remove(path); overlaySources.remove(labels);
    }

    private static void ui() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("SQLite source lifecycle requires UI thread");
    }

    private static final class Filter {
        final Layer layer; final Expression expression;
        Filter(Layer layer, Expression expression) { this.layer = layer; this.expression = expression; }
    }
    private static final class Entry {
        final VectorLayer layer; final Style style; final SqliteVectorTileProvider provider;
        final SqliteVectorSourceBridge bridge; final Source source;
        final String styleFingerprint;
        final String nativeId;
        final Map<String, GeoJsonSource> overlays;
        final SqliteSourceRetirement.Registration registration;
        final Map<String, Filter> filters = new HashMap<>();
        Entry(VectorLayer layer, Style style, SqliteVectorTileProvider provider, SqliteVectorSourceBridge bridge, Source source,
              String styleFingerprint, SqliteSourceRetirement.Registration registration,
              String nativeId, Map<String, GeoJsonSource> overlays) {
            this.layer = layer; this.style = style; this.provider = provider; this.bridge = bridge; this.source = source;
            this.styleFingerprint = styleFingerprint;
            this.registration = registration;
            this.nativeId = nativeId; this.overlays = overlays;
        }
    }
}

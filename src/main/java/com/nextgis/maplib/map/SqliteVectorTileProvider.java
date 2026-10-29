package com.nextgis.maplib.map;

import com.nextgis.maplib.api.ITextStyle;
import com.nextgis.maplib.datasource.GeoEnvelope;
import com.nextgis.maplib.display.LabelAttributes;
import com.nextgis.maplib.display.LabelTemplate;
import com.nextgis.maplib.display.RuleFeatureRenderer;
import com.nextgis.maplib.display.FieldStyleRule;
import com.nextgis.maplib.display.SimpleFeatureRenderer;
import com.nextgis.maplib.display.SimpleMarkerStyle;
import com.nextgis.maplib.display.SimpleLineStyle;
import com.nextgis.maplib.display.SimplePolygonStyle;
import com.nextgis.maplib.util.DatabaseContext;
import com.nextgis.maplib.util.MapRuntimeDiagnostics;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.Point;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Consumer;
import static com.nextgis.maplib.util.GeoConstants.*;
import static com.nextgis.maplib.util.MapRuntimeDiagnostics.SqlitePhase.*;
import static com.nextgis.maplib.util.MapRuntimeDiagnostics.SqliteCount.*;

/** One immutable source generation. SQLite/RTree reads and transient feature conversion are IO-only. */
public final class SqliteVectorTileProvider implements SqliteVectorTileBackend {
    private static final String SESSION = UUID.randomUUID().toString();
    private final Access access;
    private final long sourceEpoch, contentVersion;
    private final String ownerIdentity, styleFingerprint;
    private final LongSupplier currentEpoch;
    private final Long excludedEditedId;
    private final LocalVectorTileDiskCache disk;
    private final Object layerLock;
    private volatile boolean closed;

    /** Metadata only; custom renderers and unknown styles retain the host's explicit fallback. */
    public static boolean canServe(VectorLayer layer) {
        if (layer == null || layer.getRenderer() == null) return false;
        Class<?> renderer = layer.getRenderer().getClass();
        if (renderer != SimpleFeatureRenderer.class && renderer != RuleFeatureRenderer.class) return false;
        int type = layer.getGeometryType();
        com.nextgis.maplib.display.Style fallback = layer.getDefaultStyleNoExcept();
        if (!knownStyle(fallback, type)) return false;
        if (renderer == RuleFeatureRenderer.class) {
            RuleFeatureRenderer rules = (RuleFeatureRenderer) layer.getRenderer();
            if (!(rules.getStyleRule() instanceof FieldStyleRule)) return false;
            FieldStyleRule field = (FieldStyleRule) rules.getStyleRule();
            if (!knownStyle(field.resolveOtherStyle(fallback), type)) return false;
            for (String category : field.getStyleRules().keySet())
                if (!knownStyle(field.resolveEffectiveStyle(category, fallback), type)) return false;
        }
        return true;
    }
    private static boolean knownStyle(com.nextgis.maplib.display.Style style, int type) {
        if (style == null) return false;
        if (type == GTPoint || type == GTMultiPoint) return style.getClass() == SimpleMarkerStyle.class;
        if (type == GTLineString || type == GTMultiLineString) return style.getClass() == SimpleLineStyle.class;
        if (type == GTPolygon || type == GTMultiPolygon) return style.getClass() == SimplePolygonStyle.class;
        return false;
    }

    public SqliteVectorTileProvider(VectorLayer layer, long sourceEpoch, LongSupplier currentEpoch,
            Long excludedEditedId) throws IOException {
        this(new VectorAccess(layer), sourceEpoch, currentEpoch, excludedEditedId);
    }
    SqliteVectorTileProvider(Access access, long sourceEpoch, LongSupplier currentEpoch, Long excludedEditedId) {
        this.access = access; this.sourceEpoch = sourceEpoch; this.currentEpoch = currentEpoch;
        this.excludedEditedId = excludedEditedId; ownerIdentity = access.ownerIdentity();
        contentVersion = access.contentVersion(); styleFingerprint = access.styleFingerprint();
        File cache = access.cacheDirectory(); disk = cache == null ? null : new LocalVectorTileDiskCache(cache);
        layerLock = RenderTileWorkLimiter.layerLock(ownerIdentity);
    }

    @Override public SqliteVectorTileRequest snapshot(int z, int x, int y) {
        requireCurrent(() -> true);
        return new SqliteVectorTileRequest(ownerIdentity, sourceEpoch, contentVersion,
                styleFingerprint, excludedEditedId, z, x, y);
    }
    public boolean isCurrent() {
        try {
            return !closed && currentEpoch.getAsLong() == sourceEpoch && access.ownerCurrent()
                    && contentVersion == access.contentVersion();
        } catch (RuntimeException unavailable) { return false; }
    }
    @Override public boolean isCurrent(SqliteVectorTileRequest request) {
        return request != null && ownerIdentity.equals(request.getOwnerIdentity())
                && sourceEpoch == request.getSourceEpoch() && contentVersion == request.getContentVersion()
                && styleFingerprint.equals(request.getStyleFingerprint())
                && Objects.equals(excludedEditedId, request.getExcludedEditedId()) && isCurrent();
    }
    @Override public void close() { closed = true; } // terminal, idempotent, no joins/DB ownership

    @Override public byte[] load(SqliteVectorTileRequest request, BooleanSupplier current) throws Exception {
        return load(request, current, null);
    }
    @Override public byte[] load(SqliteVectorTileRequest request, BooleanSupplier current,
                               MapRuntimeDiagnostics.SqliteScope diagnostics) throws Exception {
        MapRuntimeDiagnostics.SqliteScope scope = diagnostics != null ? diagnostics : MapRuntimeDiagnostics.sqliteTile(-1, 0);
        scope.identity(request.getSourceEpoch(), request.getContentVersion()); scope.phase(STYLE);
        BooleanSupplier valid = () -> isCurrent(request) && current.getAsBoolean();
        requireCurrent(valid); requireStyleCurrent();
        RenderMemoryGuard memory = access.memory();
        long cacheBytes = memory == null ? 128 * RenderMemoryPolicy.MIB : memory.limits().diskCacheBytes;
        // SESSION makes cache entries derived from this process only, including source epochs.
        String key = request.cacheKey(SESSION);
        scope.phase(LAYER_LOCK_WAIT);
        synchronized (layerLock) {
            requireCurrent(valid);
            checkMemory(memory);
            scope.phase(CACHE_READ);
            if (disk != null) {
                byte[] cached = disk.read(key, 4 * RenderMemoryPolicy.MIB);
                requireCurrent(valid);
                if (cached != null) {
                    scope.count(CACHE_HITS, 1); scope.count(PBF_BYTES, cached.length);
                    scope.phase(STYLE); requireStyleCurrent(); return cached;
                }
            }
            scope.count(CACHE_MISSES, 1);
            checkMemory(memory);
            boolean constrained = memory != null && memory.shouldSerializeTileWork();
            if (constrained) memory.noteSerializedTiles();
            byte[] bytes;
            scope.phase(GATE_WAIT);
            try (RenderTileWorkGate.Lease lease = RenderTileWorkLimiter.processShared().acquire(constrained, valid)) {
                requireCurrent(valid); bytes = build(request, valid, memory, scope);
            }
            scope.phase(STYLE);
            requireCurrent(valid); requireStyleCurrent();
            scope.phase(CACHE_WRITE);
            if (disk != null) { scope.count(CACHE_WRITE_CALLS, 1); disk.put(key, bytes, cacheBytes, valid); }
            requireCurrent(valid);
            return bytes;
        }
    }

    private byte[] build(SqliteVectorTileRequest request, BooleanSupplier current, RenderMemoryGuard memory,
                         MapRuntimeDiagnostics.SqliteScope scope)
            throws Exception {
        NativeVectorTileEncoder.Bounds bounds = NativeVectorTileEncoder.Bounds.tile(request);
        GeoEnvelope envelope = new GeoEnvelope(bounds.queryMinX(), bounds.queryMaxX(), bounds.queryMinY(), bounds.queryMaxY());
        scope.phase(INDEX_QUERY);
        List<Long> candidates = access.query(envelope);
        scope.count(CANDIDATES, candidates.size());
        NativeVectorTileEncoder encoder = new NativeVectorTileEncoder(bounds);
            access.visitOriginals(eligibleIds(new LinkedHashSet<>(candidates),current),scope,current,new SqliteVectorFeatureReader.Visitor() {
                int visited;
                public void beforeRead() throws Exception {
                    requireCurrent(current);if(visited++%32==0)checkMemory(memory);
                }
                public void accept(com.nextgis.maplib.datasource.Feature original) throws Exception {
                requireCurrent(current);
                long id=original.getId();
                scope.count(FEATURES_READ, 1); scope.phase(STYLE);
                Feature styled = access.styled(original, false);
                List<Feature> singleton = Collections.singletonList(styled);
                Feature geometry = NativeFeaturePayload.geometry(singleton, access.geometryType()).get(0);
                Map<String, Object> props = NativeVectorTilePropertyCodec.encode(geometry.properties());
                scope.phase(CLIP);
                org.locationtech.jts.geom.Geometry clipped = NativeVectorTileClipper.clip(original.getGeometry(), bounds);
                scope.phase(PBF_ENCODE); encoder.add(id, props, clipped);
                if ((access.geometryType() == GTPolygon || access.geometryType() == GTMultiPolygon)
                        && NativeFeaturePayload.labelCount(singleton) != 0) {
                    scope.phase(POLYGON_LABEL);
                    // Use exactly the classic4326 interior anchor before any3857 tile clipping.
                    Feature full = Feature.fromGeometry(MPLFeaturesUtils.getFeatureFromNGFeature(original.getGeometry()).geometry(),
                            styled.properties());
                    for (Feature label : MPLFeaturesUtils.nativePolygonLabels(Collections.singletonList(full))) {
                        Point point = (Point) label.geometry();
                        double[] xy = MPLFeaturesUtils.convert4326To3857(point.longitude(), point.latitude());
                        if (!bounds.owns(xy[0], xy[1])) continue;
                        Map<String, Object> labelProps = NativeVectorTilePropertyCodec.encode(label.properties());
                        labelProps.put(NativeVectorTileEncoder.LABEL_ANCHOR, true);
                        scope.count(LABELS, 1); scope.phase(PBF_ENCODE);
                        encoder.add(id, labelProps, bounds.anchor(xy[0], xy[1]));
                        scope.phase(POLYGON_LABEL);
                    }
                }
                }
            });
            requireCurrent(current); checkMemory(memory); scope.phase(PBF_FINISH);
            byte[] bytes = encoder.finish(); scope.count(PBF_BYTES, bytes.length); return bytes;
    }

    /** Full editable DB geometry/attributes, never the clipped or quantized native feature. */
    public com.nextgis.maplib.datasource.Feature loadOriginalFeature(long featureId) throws Exception {
        requireCurrent(() -> true); requireStyleCurrent();
        checkMemory(access.memory());
        com.nextgis.maplib.datasource.Feature feature = access.read(featureId);
        requireCurrent(() -> true); requireStyleCurrent(); return feature;
    }

    /** Full rich styled original for selection/edit overlays, with the same stableFID sort key. */
    public Feature readOriginalFeature(long featureId) throws Exception {
        com.nextgis.maplib.datasource.Feature original = loadOriginalFeature(featureId);
        if (original == null) return null;
        if (original.getGeometry() == null) throw new UnsupportedTileException("Missing editable geometry");
        Feature result = access.styled(original, true); requireCurrent(() -> true); requireStyleCurrent(); return result;
    }

    /** Compatibility collector; production label layout uses visitVisibleLines without retaining full geometry. */
    public List<Feature> readVisibleLines(List<GeoEnvelope> envelopes, BooleanSupplier current) throws Exception {
        List<Feature> result = new ArrayList<>();
        visitVisibleLines(envelopes, current, result::add);
        return result;
    }

    /** Visits one complete styled DB original at a time; the visitor must not retain its line geometry. */
    public void visitVisibleLines(List<GeoEnvelope> envelopes, BooleanSupplier current,
                                 Consumer<Feature> visitor) throws Exception {
        Objects.requireNonNull(visitor, "visitor");
        MapRuntimeDiagnostics.SqliteScope scope = MapRuntimeDiagnostics.sqliteLabels(access.layerId());
        MapRuntimeDiagnostics.SqliteOutcome outcome = MapRuntimeDiagnostics.SqliteOutcome.ABANDONED;
        try {
        requireCurrent(current); requireStyleCurrent();
        if (access.geometryType() != GTLineString && access.geometryType() != GTMultiLineString)
            throw new UnsupportedTileException("Visible line labels require line geometry");
        RenderMemoryGuard memory = access.memory(); checkMemory(memory);
        boolean constrained = memory != null && memory.shouldSerializeTileWork();
        if (constrained) memory.noteSerializedTiles();
        scope.phase(GATE_WAIT);
        try (RenderTileWorkGate.Lease lease = RenderTileWorkLimiter.processShared().acquire(constrained,
                () -> isCurrent() && current.getAsBoolean())) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        scope.phase(INDEX_QUERY);
        for (GeoEnvelope envelope : envelopes) { requireCurrent(current); ids.addAll(access.query(envelope)); }
        scope.count(CANDIDATES, ids.size());
        access.visitOriginals(eligibleIds(ids,current),scope,()->isCurrent() && current.getAsBoolean(),new SqliteVectorFeatureReader.Visitor() {
            int visited;
            public void beforeRead() throws Exception {
                requireCurrent(current);if(visited++%32==0)checkMemory(memory);
            }
            public void accept(com.nextgis.maplib.datasource.Feature feature) throws Exception {
            requireCurrent(current);
            scope.count(FEATURES_READ, 1); scope.phase(STYLE);
            Feature styled = access.styled(feature, true);
            requireCurrent(current);
            scope.phase(LINE_LABEL);
            visitor.accept(styled);
            }
        });
        requireCurrent(current); requireStyleCurrent();
        }
        outcome = MapRuntimeDiagnostics.SqliteOutcome.PROVIDER_RETURNED;
        } catch (CancellationException cancelled) {
            outcome = MapRuntimeDiagnostics.SqliteOutcome.CANCELLED; throw cancelled;
        } catch (Exception failed) {
            outcome = MapRuntimeDiagnostics.SqliteOutcome.FAILED; throw failed;
        } finally { scope.finish(outcome); }
    }

    private void requireCurrent(BooleanSupplier current) {
        if (Thread.currentThread().isInterrupted() || !isCurrent() || !current.getAsBoolean())
            throw new CancellationException("Obsolete SQLite native source request");
    }
    /** Lazy ID-only filtering: preserve candidate order, never fetch hidden/excluded original rows. */
    private Iterator<Long> eligibleIds(Iterable<Long> candidates,BooleanSupplier current) {
        Iterator<Long> source=candidates.iterator();
        return new Iterator<Long>() {
            Long next;boolean prepared;
            private void prepare() {
                if(prepared)return;requireCurrent(current);
                while(source.hasNext()) {
                    requireCurrent(current);Long id=source.next();
                    if(id!=null && !Objects.equals(id,excludedEditedId) && !access.hidden(id)) {
                        next=id;prepared=true;return;
                    }
                }
            }
            public boolean hasNext(){prepare();return prepared;}
            public Long next(){prepare();if(!prepared)throw new NoSuchElementException();prepared=false;return next;}
        };
    }
    private void requireStyleCurrent() {
        if (!styleFingerprint.equals(access.styleFingerprint()))
            throw new CancellationException("Obsolete SQLite native source style");
    }
    private static void checkMemory(RenderMemoryGuard memory) throws RetryableTileException {
        if (memory != null && !memory.canBuildTile()) {
            memory.noteReducedCache(); throw new RetryableTileException("render_memory_limited", 250);
        }
    }

    /** Storage seam for deterministic technical tests; the production implementation never loads all rows. */
    interface Access {
        String ownerIdentity(); boolean ownerCurrent(); long contentVersion(); String styleFingerprint();
        int geometryType(); int layerId(); List<Long> query(GeoEnvelope envelope) throws Exception;
        com.nextgis.maplib.datasource.Feature read(long id) throws Exception;
        default com.nextgis.maplib.datasource.Feature read(long id, MapRuntimeDiagnostics.SqliteScope diagnostics) throws Exception {
            return read(id);
        }
        default void visitOriginals(Iterator<Long> ids,MapRuntimeDiagnostics.SqliteScope scope,
                BooleanSupplier current,SqliteVectorFeatureReader.Visitor visitor) throws Exception {
            while(ids.hasNext()) {
                long id=ids.next();visitor.beforeRead();scope.phase(SQL_ATTRIBUTES);com.nextgis.maplib.datasource.Feature feature=read(id,scope);
                if(feature==null || feature.getId()!=id || feature.getGeometry()==null)
                    throw new UnsupportedTileException("Indexed native tile feature could not be read");
                visitor.accept(feature);
            }
        }
        boolean hidden(long id); Feature styled(com.nextgis.maplib.datasource.Feature feature, boolean fullGeometry) throws Exception;
        RenderMemoryGuard memory(); File cacheDirectory();
    }
    private static final class VectorAccess implements Access {
        final VectorLayer layer; final MapContentProviderHelper owner; final String identity;
        final long dataGeneration;
        VectorAccess(VectorLayer layer) throws IOException {
            this.layer = Objects.requireNonNull(layer); owner = DatabaseContext.getMapForLayer(layer);
            identity = layer.getPath().getCanonicalPath();
            dataGeneration = layer.getDataGeneration();
            if (owner == null || !canServe(layer))
                throw new UnsupportedTileException("Unsupported SQLite native renderer or owner");
            int type = layer.getGeometryType();
            if (type != GTPoint && type != GTMultiPoint && type != GTLineString && type != GTMultiLineString
                    && type != GTPolygon && type != GTMultiPolygon)
                throw new UnsupportedTileException("Unsupported SQLite native layer geometry");
        }
        public String ownerIdentity() { return identity; }
        public boolean ownerCurrent() {
            return DatabaseContext.getMapForLayer(layer) == owner && dataGeneration == layer.getDataGeneration();
        }
        public long contentVersion() { return layer.renderContentVersion(); }
        public String styleFingerprint() { return VectorLayerRenderCache.styleFingerprint(layer); }
        public int geometryType() { return layer.getGeometryType(); }
        public int layerId() { return layer.getId(); }
        public List<Long> query(GeoEnvelope envelope) throws RetryableTileException {
            return layer.queryForNativeRender(envelope);
        }
        public com.nextgis.maplib.datasource.Feature read(long id) { return layer.getFeature(id); }
        public com.nextgis.maplib.datasource.Feature read(long id, MapRuntimeDiagnostics.SqliteScope diagnostics) {
            return layer.getFeatureForNativeRender(id, diagnostics);
        }
        public void visitOriginals(Iterator<Long> ids,MapRuntimeDiagnostics.SqliteScope scope,
                BooleanSupplier current,SqliteVectorFeatureReader.Visitor visitor) throws Exception {
            layer.visitOriginalsForNativeRender(ids,scope,current,visitor);
        }
        public boolean hidden(long id) { return layer.isFeatureHidden(id); }
        public RenderMemoryGuard memory() { return RenderMemoryGuard.forLayer(layer); }
        public File cacheDirectory() { return layer.getContext().getCacheDir(); }
        public Feature styled(com.nextgis.maplib.datasource.Feature original, boolean fullGeometry) {
            Feature feature = fullGeometry ? MPLFeaturesUtils.getFeatureFromNGFeature(original.getGeometry())
                    : Feature.fromGeometry(Point.fromLngLat(0, 0)); // shell never leaves the property mapper
            feature.addStringProperty(MplFeatureStyleProps.FEATURE_ID, Long.toString(original.getId()));
            feature.addStringProperty(MplFeatureStyleProps.LAYER_ID, Integer.toString(layer.getId()));
            feature.addNumberProperty(MplFeatureStyleProps.ORDER, original.getId()); // stable across tile borders
            if (layer.getGeometryType() == GTLineString || layer.getGeometryType() == GTMultiLineString)
                feature.addStringProperty(MPLFeaturesUtils.prop_color, MPLFeaturesUtils.colorBlue);
            com.nextgis.maplib.display.Style style = layer.getDefaultStyleNoExcept();
            ITextStyle text = (ITextStyle) style;
            boolean rules = layer.getRenderer() instanceof RuleFeatureRenderer;
            boolean labels = rules || text.getField() != null && !text.getField().isEmpty()
                    || text.getText() != null && !text.getText().isEmpty()
                    || LabelTemplate.hasTemplate(LabelAttributes.fromStyle(style).getLabelTemplate());
            MPLFeaturesUtils.applyTextAndStyle(layer, original, feature, layer.getGeometryType(), rules,
                    labels, MPLFeaturesUtils.getLayerSignatureField(layer), text.getText());
            return feature;
        }
    }
}

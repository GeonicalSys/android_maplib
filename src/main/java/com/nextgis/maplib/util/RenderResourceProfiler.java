package com.nextgis.maplib.util;

import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Opt-in, local numeric work accounting. Never owns files, SDK objects or render admission. */
public final class RenderResourceProfiler {
    public static final int LAYER_LIMIT = 256;
    public static final int DETAILED_ACTIVE_LIMIT = 512;
    public static final int SUMMARY_DETAIL_LIMIT = 32;
    private static final int HISTOGRAM_BUCKETS = 32;
    private static final MapRuntimeDiagnostics.SqlitePhase[] PHASES = MapRuntimeDiagnostics.SqlitePhase.values();
    private static final MapRuntimeDiagnostics.SqliteCount[] COUNTS = MapRuntimeDiagnostics.SqliteCount.values();
    private static final MapRuntimeDiagnostics.SqliteOutcome[] OUTCOMES = MapRuntimeDiagnostics.SqliteOutcome.values();
    private static volatile Engine active;

    private RenderResourceProfiler() { }

    /** Must copy/accept without blocking. False means the raw record was not retained. */
    public interface Sink { boolean offer(Map<String, Object> record); }

    interface Clock {
        long nanos();
        long cpuNanos();
        long tid();
    }

    private static final Clock SYSTEM = new Clock() {
        public long nanos() { return SystemClock.elapsedRealtimeNanos(); }
        public long cpuNanos() { return Debug.threadCpuTimeNanos(); }
        public long tid() { return Process.myTid(); }
    };

    public static void start(String sessionId, String version, int build, String revision, Sink sink) {
        startWithClock(sessionId, version, build, revision, sink, SYSTEM);
    }

    static synchronized void startWithClock(String sessionId, String version, int build, String revision,
                                             Sink sink, Clock clock) {
        stop("replaced");
        if (sink == null || clock == null || sessionId == null
                || !sessionId.matches("[A-Za-z0-9_.-]{1,96}")) return;
        try {
            Engine engine = new Engine(sessionId, safeIdentity(version), build, safeIdentity(revision), sink, clock);
            active = engine;
            engine.emit("resource_session", engine.identity());
        } catch (Throwable unavailable) { active = null; }
    }

    public static boolean isActive() { return active != null; }

    /** Stop observation only; never closes or cancels the renderer. Caller persists this independently of raw queue. */
    public static synchronized Map<String, Object> stop(String reason) {
        Engine engine = active;
        active = null;
        if (engine == null) return Collections.emptyMap();
        try {
            synchronized (engine) { engine.stopped = true; }
            Map<String, Object> summary = engine.summary(safeReason(reason));
            engine.emit("resource_end", summary);
            // Include rejection of the best-effort final raw record in the authoritative return value.
            return engine.summary(safeReason(reason));
        } catch (Throwable unavailable) { return Collections.emptyMap(); }
    }

    /** Bounded background checkpoint. It is an observation, not a memory ownership attribution. */
    public static Map<String, Object> snapshot() {
        Engine engine = active;
        if (engine == null) return Collections.emptyMap();
        try { return engine.summary("checkpoint"); }
        catch (Throwable unavailable) { return Collections.emptyMap(); }
    }

    public static void event(String kind, Map<String, Object> numericFields) {
        Engine engine = active;
        if (engine == null) return;
        try {
            if (kind == null || !kind.matches("[a-z][a-z0-9_]{0,47}")) return;
            Map<String, Object> fields = new LinkedHashMap<>();
            if (numericFields != null) for (Map.Entry<String, Object> entry : numericFields.entrySet()) {
                String key = entry.getKey(); Object value = entry.getValue();
                if (fields.size() == 64) { engine.invalidField(); break; }
                if (key == null || !key.matches("[a-z][a-z0-9_]{0,47}")
                        || key.matches(".*(?:^|_)(?:latitude|longitude|lat|lon|x|y|uri|path|coordinate|coordinates|feature_id|object_id|row_id)(?:_|$).*")
                        || !numeric(value)) {
                    engine.invalidField(); continue;
                }
                if (value instanceof Float || value instanceof Double)
                    fields.put(key, Double.valueOf(((Number)value).doubleValue()));
                else if (value instanceof Number) fields.put(key, Long.valueOf(((Number)value).longValue()));
                else fields.put(key, value);
            }
            Map<String, Object> data = new LinkedHashMap<>(); data.put("metrics", fields);
            engine.emit(kind, data);
        } catch (Throwable unavailable) { engine.sinkFailure(); }
    }

    public static Token beginSqlite(int layerId, int zoom, String sourceType) {
        Engine engine = active;
        if (engine == null) return null;
        try { return engine.begin(layerId, zoom, sourceType); }
        catch (Throwable unavailable) { engine.clockFailure(); return null; }
    }

    private static boolean numeric(Object value) {
        if (value == null || value instanceof Boolean) return true;
        if (!(value instanceof Number)) return false;
        double n = ((Number) value).doubleValue();
        return !Double.isInfinite(n) && !Double.isNaN(n);
    }

    private static String safeIdentity(String text) {
        return text != null && text.matches("[A-Za-z0-9_.+-]{1,128}") ? text : "unknown";
    }
    private static String safeReason(String text) {
        return text != null && text.matches("[a-z][a-z0-9_]{0,47}") ? text : "unspecified";
    }
    private static long add(long a, long b) { return b > 0 && Long.MAX_VALUE - a < b ? Long.MAX_VALUE : a + b; }
    private static int bucket(long nanos) {
        long micros = nanos / 1000;
        return micros <= 1 ? 0 : Math.min(HISTOGRAM_BUCKETS - 1, 64 - Long.numberOfLeadingZeros(micros - 1));
    }
    private static List<String> phaseNames() {
        List<String> names = new ArrayList<>();
        for (MapRuntimeDiagnostics.SqlitePhase p : PHASES) names.add(p.name().toLowerCase(java.util.Locale.ROOT));
        return names;
    }

    /** A fixed-size detailed token, or a smaller overflow token which still accounts aggregate work. */
    public static final class Token {
        private final Engine engine;
        private final Stats layer;
        private final long id;
        private final int layerId, zoom;
        private final String sourceType;
        private final boolean detailed;
        private final long[] wall, cpu, unknown, counts;
        private long started, phaseStarted, phaseCpu, phaseTid, epoch, content;
        private boolean closed, identified, timingAvailable = true;
        private MapRuntimeDiagnostics.SqlitePhase phase = MapRuntimeDiagnostics.SqlitePhase.IO_QUEUE;

        Token(Engine engine, Stats layer, long id, int layerId, int zoom, String sourceType, boolean detailed) {
            this.engine = engine; this.layer = layer; this.id = id; this.layerId = layerId;
            this.zoom = zoom; this.sourceType = sourceType; this.detailed = detailed;
            wall = detailed ? new long[PHASES.length] : null;
            cpu = detailed ? new long[PHASES.length] : null;
            unknown = detailed ? new long[PHASES.length] : null;
            counts = detailed ? new long[COUNTS.length] : null;
            try {
                started = phaseStarted = engine.clock.nanos(); phaseTid = engine.clock.tid();
                phaseCpu = engine.clock.cpuNanos();
            } catch (Throwable unavailable) { timingAvailable = false; engine.clockFailure(); }
        }

        public synchronized void identity(long sourceEpoch, long contentVersion) {
            if (closed || engine.stopped) return;
            epoch = sourceEpoch; content = contentVersion; identified = true;
        }

        public synchronized void phase(MapRuntimeDiagnostics.SqlitePhase next) {
            if (closed || engine.stopped || next == null) return;
            try { account(); phase = next; }
            catch (Throwable unavailable) { timingAvailable = false; engine.clockFailure(); phase = next; }
        }

        public synchronized void count(MapRuntimeDiagnostics.SqliteCount key, long value) {
            if (closed || engine.stopped || key == null || value < 0) return;
            try {
                if (detailed) counts[key.ordinal()] = add(counts[key.ordinal()], value);
                engine.count(layer, key.ordinal(), value);
            } catch (Throwable unavailable) { engine.clockFailure(); }
        }

        public synchronized void finish(MapRuntimeDiagnostics.SqliteOutcome outcome) {
            if (closed || outcome == null || outcome == MapRuntimeDiagnostics.SqliteOutcome.ACTIVE) return;
            closed = true;
            if (engine.stopped) return;
            long elapsed = -1;
            try {
                account();
                if (timingAvailable) elapsed = Math.max(0, phaseStarted - started);
            } catch (Throwable unavailable) { timingAvailable = false; engine.clockFailure(); }
            try {
                Map<String, Object> record = null;
                if (detailed) {
                    record = new LinkedHashMap<>();
                    record.put("request_id", id); record.put("layer_id", layerId); record.put("zoom", zoom);
                    record.put("source_type", sourceType); record.put("outcome", outcome.name().toLowerCase(java.util.Locale.ROOT));
                    record.put("started_ns", started); record.put("elapsed_ns", elapsed < 0 ? null : elapsed);
                    record.put("timing_available", timingAvailable);
                    if (identified) { record.put("source_epoch", epoch); record.put("content_version", content); }
                    record.put("phase_wall_ns", wall.clone()); record.put("phase_thread_cpu_known_ns", cpu.clone());
                    record.put("phase_cpu_unknown_segments", unknown.clone()); record.put("counts", counts.clone());
                }
                engine.finish(this, layer, outcome, elapsed, record);
            } catch (Throwable unavailable) { engine.sinkFailure(); }
        }

        private void account() {
            if (!timingAvailable) return;
            long now = engine.clock.nanos(), tid = engine.clock.tid(), threadCpu = engine.clock.cpuNanos();
            long wallNs = Math.max(0, now - phaseStarted);
            boolean cpuKnown = tid == phaseTid && tid > 0 && threadCpu >= phaseCpu && phaseCpu >= 0;
            long cpuNs = cpuKnown ? threadCpu - phaseCpu : 0;
            if (now < phaseStarted) engine.clockFailure();
            int p = phase.ordinal();
            if (detailed) {
                wall[p] = add(wall[p], wallNs); cpu[p] = add(cpu[p], cpuNs);
                if (!cpuKnown) unknown[p] = add(unknown[p], 1);
            }
            engine.phase(layer, p, wallNs, cpuNs, cpuKnown);
            phaseStarted = now; phaseTid = tid; phaseCpu = threadCpu;
        }
    }

    private static final class Stats {
        final int layerId;
        long requests, active, unobserved, elapsed, cpuTotal;
        final long[] outcomes = new long[OUTCOMES.length], counts = new long[COUNTS.length];
        final long[] wall = new long[PHASES.length], cpu = new long[PHASES.length], unknown = new long[PHASES.length];
        Stats(int id) { layerId = id; }
        Stats copy() {
            Stats out = new Stats(layerId);
            out.requests=requests; out.active=active; out.unobserved=unobserved; out.elapsed=elapsed; out.cpuTotal=cpuTotal;
            System.arraycopy(outcomes,0,out.outcomes,0,outcomes.length); System.arraycopy(counts,0,out.counts,0,counts.length);
            System.arraycopy(wall,0,out.wall,0,wall.length); System.arraycopy(cpu,0,out.cpu,0,cpu.length);
            System.arraycopy(unknown,0,out.unknown,0,unknown.length); return out;
        }
        long[] row() {
            long[] row = new long[6 + OUTCOMES.length + COUNTS.length];
            row[0]=layerId; row[1]=requests; row[2]=active; row[3]=unobserved; row[4]=elapsed; row[5]=cpuTotal;
            System.arraycopy(outcomes,0,row,6,outcomes.length); System.arraycopy(counts,0,row,6+outcomes.length,counts.length);
            return row;
        }
    }

    private static final class Engine {
        final String session, version, revision;
        final int build;
        final Sink sink;
        final Clock clock;
        final Stats global = new Stats(-1), overflow = new Stats(-1);
        final Map<Integer,Stats> layers = new LinkedHashMap<>();
        final Map<Long,Token> details = new LinkedHashMap<>();
        final long[][] phaseHist = new long[PHASES.length][HISTOGRAM_BUCKETS];
        final long[] elapsedHist = new long[HISTOGRAM_BUCKETS];
        volatile boolean stopped;
        long nextRequest, sequence, rawAccepted, rawRejected, sinkFailures, clockFailures, invalidFields, layerOverflow;

        Engine(String session, String version, int build, String revision, Sink sink, Clock clock) {
            this.session=session;this.version=version;this.build=build;this.revision=revision;this.sink=sink;this.clock=clock;
        }
        Map<String,Object> identity() {
            Map<String,Object> m=new LinkedHashMap<>(); m.put("version",version); m.put("build",build); m.put("revision",revision);
            m.put("phase_columns",phaseNames());
            List<String> counts=new ArrayList<>(); for(MapRuntimeDiagnostics.SqliteCount c:COUNTS) counts.add(c.name().toLowerCase(java.util.Locale.ROOT));
            m.put("count_columns",counts); m.put("layer_limit",LAYER_LIMIT);m.put("detailed_active_limit",DETAILED_ACTIVE_LIMIT);
            m.put("clock","elapsed_realtime_ns_and_same_tid_thread_cpu_ns");
            m.put("diagnostic_overhead","Clock/accounting overhead is included; not calibrated and not a performance acceptance run."); return m;
        }
        synchronized Token begin(int layerId,int zoom,String kind) {
            if(stopped) return null;
            Stats layer=layers.get(layerId);
            if(layer==null) {
                if(layerId < 0 || layers.size() >= LAYER_LIMIT) { layer=overflow; layerOverflow=add(layerOverflow,1); }
                else { layer=new Stats(layerId); layers.put(layerId,layer); }
            }
            boolean detailed=details.size()<DETAILED_ACTIVE_LIMIT;
            Token token=new Token(this,layer,++nextRequest,layerId,zoom,
                    "sqlite_line_labels".equals(kind)?"sqlite_line_labels":"sqlite_native",detailed);
            global.requests=add(global.requests,1);layer.requests=add(layer.requests,1);
            global.active=add(global.active,1);layer.active=add(layer.active,1);
            if(detailed) details.put(token.id,token);
            else { global.unobserved=add(global.unobserved,1);layer.unobserved=add(layer.unobserved,1); }
            return token;
        }
        synchronized void count(Stats layer,int c,long n) {
            if(stopped) return;
            global.counts[c]=add(global.counts[c],n);layer.counts[c]=add(layer.counts[c],n);
        }
        synchronized void phase(Stats layer,int p,long wall,long cpu,boolean known) {
            if(stopped) return;
            global.wall[p]=add(global.wall[p],wall);layer.wall[p]=add(layer.wall[p],wall);
            global.cpu[p]=add(global.cpu[p],cpu);layer.cpu[p]=add(layer.cpu[p],cpu);
            global.cpuTotal=add(global.cpuTotal,cpu);layer.cpuTotal=add(layer.cpuTotal,cpu);
            if(!known) {global.unknown[p]=add(global.unknown[p],1);layer.unknown[p]=add(layer.unknown[p],1);}
            int b=bucket(wall);phaseHist[p][b]=add(phaseHist[p][b],1);
        }
        synchronized void finish(Token token,Stats layer,MapRuntimeDiagnostics.SqliteOutcome outcome,long elapsed,
                                 Map<String,Object> record) {
            if(stopped) return;
            details.remove(token.id);global.active--;layer.active--;
            int o=outcome.ordinal();global.outcomes[o]=add(global.outcomes[o],1);layer.outcomes[o]=add(layer.outcomes[o],1);
            if(elapsed>=0) {global.elapsed=add(global.elapsed,elapsed);layer.elapsed=add(layer.elapsed,elapsed);int b=bucket(elapsed);elapsedHist[b]=add(elapsedHist[b],1);}
            // Acceptance and aggregate completion share the stop boundary. Sink must only enqueue.
            if(record!=null) emit("sqlite_request",record);
        }
        synchronized void invalidField() { invalidFields=add(invalidFields,1); }
        synchronized void clockFailure() { clockFailures=add(clockFailures,1); }
        synchronized void sinkFailure() { sinkFailures=add(sinkFailures,1); }

        synchronized void emit(String kind,Map<String,Object> fields) {
            if(stopped && !"resource_end".equals(kind)) return;
            try {
                Map<String,Object> record=new LinkedHashMap<>();record.put("schema",1);record.put("session",session);
                synchronized(this) {record.put("sequence",++sequence);}
                record.put("event",kind);
                try {record.put("ts_ns",clock.nanos());} catch(Throwable unavailable) {record.put("ts_ns",null);clockFailure();}
                record.putAll(fields);
                boolean accepted=sink.offer(Collections.unmodifiableMap(record));
                synchronized(this) {if(accepted)rawAccepted=add(rawAccepted,1);else rawRejected=add(rawRejected,1);}
            } catch(Throwable unavailable) {sinkFailure();}
        }

        Map<String,Object> summary(String reason) {
            Stats g,o;List<Stats> all=new ArrayList<>();long[][] hist;long[] elapsed;
            long accepted,rejected,failures,clocks,invalid,overflowRequests;boolean ended;
            synchronized(this) {
                g=global.copy();o=overflow.copy();for(Stats s:layers.values())all.add(s.copy());
                hist=new long[PHASES.length][];for(int i=0;i<hist.length;i++)hist[i]=phaseHist[i].clone();
                elapsed=elapsedHist.clone();accepted=rawAccepted;rejected=rawRejected;failures=sinkFailures;
                clocks=clockFailures;invalid=invalidFields;overflowRequests=layerOverflow;ended=stopped;
            }
            Map<String,Object> out=identity();out.put("schema",1);out.put("session",session);out.put("reason",reason);out.put("stopped",ended);
            out.put("aggregate_scope","All accepted requests, including detailed-active overflow and rejected raw records; phase totals are completed segments before stop. Elapsed sums overlap across requests and are not scene latency.");
            out.put("cpu_scope","Known same-TID segments only; cross-thread CPU is unknown, never subtracted across threads.");
            List<String> columns=new ArrayList<>();Collections.addAll(columns,"layer_id","requests","active_at_snapshot","raw_unobserved","request_elapsed_ns_sum","thread_cpu_known_ns_sum");
            for(MapRuntimeDiagnostics.SqliteOutcome result:OUTCOMES)columns.add("outcome_"+result.name().toLowerCase(java.util.Locale.ROOT));
            for(MapRuntimeDiagnostics.SqliteCount c:COUNTS)columns.add(c.name().toLowerCase(java.util.Locale.ROOT));
            out.put("layer_columns",columns);out.put("global",g.row());out.put("layer_overflow",o.row());
            List<long[]> layerRows=new ArrayList<>();for(Stats s:all)layerRows.add(s.row());out.put("layers",layerRows);
            out.put("global_phase_wall_ns",g.wall);out.put("global_phase_thread_cpu_known_ns",g.cpu);out.put("global_phase_cpu_unknown_segments",g.unknown);
            out.put("phase_segment_wall_histogram",hist);out.put("request_elapsed_histogram",elapsed);
            out.put("histogram_unit","ceil_log2_microseconds; bucket0<=1us, bucket31>=2^30us");
            List<Stats> byCpu=new ArrayList<>(all),byElapsed=new ArrayList<>(all);
            byCpu.sort(Comparator.comparingLong((Stats s)->s.cpuTotal).reversed());
            byElapsed.sort(Comparator.comparingLong((Stats s)->s.elapsed).reversed());
            LinkedHashSet<Stats> top=new LinkedHashSet<>();
            for(int i=0;i<16;i++){if(i<byCpu.size())top.add(byCpu.get(i));if(i<byElapsed.size())top.add(byElapsed.get(i));}
            List<Map<String,Object>> phaseDetails=new ArrayList<>();
            for(Stats s:top){Map<String,Object> m=new LinkedHashMap<>();m.put("layer_id",s.layerId);m.put("wall_ns",s.wall);m.put("thread_cpu_known_ns",s.cpu);m.put("cpu_unknown_segments",s.unknown);phaseDetails.add(m);}
            out.put("layer_phase_details",phaseDetails);out.put("phase_detail_omitted_layers",all.size()-top.size());
            out.put("raw_accepted",accepted);out.put("raw_rejected",rejected);out.put("sink_failures",failures);out.put("clock_failures",clocks);
            out.put("invalid_event_fields",invalid);out.put("untracked_layer_requests",overflowRequests);
            out.put("detailed_complete",g.unobserved==0&&rejected==0&&failures==0&&g.active==0&&overflowRequests==0&&clocks==0);
            long unknownSegments=0;for(long n:g.unknown)unknownSegments=add(unknownSegments,n);
            out.put("cpu_fully_attributed",unknownSegments==0&&clocks==0&&g.active==0);
            out.put("native_memory_ownership","unattributed; process/SDK observations are not per-layer retained allocations");
            return Collections.unmodifiableMap(out);
        }
    }
}

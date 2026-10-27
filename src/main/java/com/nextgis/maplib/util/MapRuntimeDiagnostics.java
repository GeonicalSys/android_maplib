package com.nextgis.maplib.util;

import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import com.hypertrack.hyperlog.HyperLog;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded, error-only map diagnostics. Never calls MapLibre or reads business storage. */
public final class MapRuntimeDiagnostics {
    public static final String REASON_HEADER = "X-Lisa-Tile-Reason";
    public static final String REQUEST_HEADER = "X-Lisa-Tile-Request";
    static final long SLOW_MS = 500, WATCH_MS = 1000, REPEAT_MS = 10000, EVENT_GRACE_MS = 15000;
    static final int MAX_JOURNAL_BYTES = 16384;
    private static final Engine ENGINE = new Engine(new AndroidAdapter());
    private MapRuntimeDiagnostics() {}

    public static void initialize(Context context, String version, int build, String revision) {
        ((AndroidAdapter) ENGINE.adapter).journal = new File(context.getFilesDir(), "map-runtime-diagnostics.json");
        ENGINE.configure(version, build, revision);
    }
    /** Observe startup on the existing independent watcher, without moving GIS work or reading it. */
    public static Trace beginStartup() { return ENGINE.begin("startup", now()); }
    public static long now() { return ENGINE.adapter.now(); }
    public static long nextRequestId() { return ENGINE.nextId.incrementAndGet(); }
    public static Trace beginQuery(String target, Integer mode) {
        Trace trace = ENGINE.begin("render_query", now());
        trace.put("target", target); trace.put("mode", mode);
        trace.phase("native_query");
        return trace;
    }
    public static Trace beginTile(long requestId, long acceptedAt) {
        Trace trace = ENGINE.begin("tile", acceptedAt, requestId);
        trace.put("queue_ms", Math.max(0, now() - acceptedAt));
        return trace;
    }
    public static void tileRejected(long requestId, String reason, int active, int queued, Trace trace) {
        ENGINE.reject(requestId, reason, active, queued, trace);
    }
    public static void tileLoad(int active, int queued) { ENGINE.activeWorkers = active; ENGINE.queued = queued; }
    public static void frameReceived() { ENGINE.lastFrame = now(); }
    public static void mapState(String state) { ENGINE.mapState = state; }
    public static void sceneState(String state) { ENGINE.sceneState = state; }
    /** Includes a prior-process journal only when the event timestamp belongs to its recorded operation. */
    public static Map<String, Object> forEvent(long timestamp) { return ENGINE.forEvent(timestamp); }

    interface Cancel { void cancel(); }
    interface Adapter {
        long now();
        long wall();
        int pid();
        Cancel watch(Runnable task);
        void execute(Runnable task);
        void log(String text);
        void write(Map<String, Object> snapshot);
        Map<String, Object> read();
        Map<String, Object> memory();
    }

    public static final class Trace {
        private final Engine engine;
        private final Thread thread;
        private final long id, start, wall;
        private final String kind;
        private final Map<String, Object> details = new LinkedHashMap<>();
        private final Map<String, Long> timings = new LinkedHashMap<>();
        private String phase = "request";
        private long phaseStart;
        private boolean finished;
        private int readCount;
        private Cancel watch;

        Trace(Engine engine, String kind, long start, long id) {
            this.engine = engine; this.kind = kind; this.start = start; this.id = id;
            wall = engine.adapter.wall() - Math.max(0, engine.adapter.now() - start);
            phaseStart = engine.adapter.now(); thread = Thread.currentThread();
        }
        public long id() { return id; }
        public synchronized void put(String key, Object value) {
            if (details.size() < 16 && (value == null || value instanceof Number || value instanceof Boolean || value instanceof String))
                details.put(key, value);
        }
        public synchronized void phase(String next) {
            long now = engine.adapter.now();
            timings.put(phase, timings.getOrDefault(phase, 0L) + Math.max(0, now - phaseStart));
            phase = next; phaseStart = now;
        }
        public synchronized void featureRead() { readCount++; }
        public void finish(int count, String error) {
            synchronized (this) {
                if (finished) return;
                phase(error == null ? "finished" : "failed");
                finished = true;
                details.put("result_count", count);
                if (error != null && !details.containsKey("error_type")) details.put("error_type", error);
            }
            if (watch != null) watch.cancel();
            engine.active.remove(id);
            if (engine.adapter.now() - start >= SLOW_MS || error != null)
                engine.adapter.execute(() -> engine.record(this, error == null ? "slow_finished" : "failed"));
        }
        synchronized Map<String, Object> snapshot() {
            long now = engine.adapter.now();
            Map<String, Object> result = new LinkedHashMap<>(details);
            result.put("id", id); result.put("kind", kind); result.put("phase", phase);
            result.put("started_at_ms", wall); result.put("observed_at_ms", engine.adapter.wall());
            result.put("elapsed_ms", Math.max(0, now - start));
            result.put("finished", finished); result.put("features_read", readCount);
            Map<String, Long> stages = new LinkedHashMap<>(timings);
            stages.put(phase, stages.getOrDefault(phase, 0L) + Math.max(0, now - phaseStart));
            result.put("stage_ms", stages);
            return result;
        }
        void check() {
            synchronized (this) { if (finished) return; }
            engine.record(this, "stalled");
        }
    }

    static final class Engine {
        final Adapter adapter;
        final AtomicLong nextId = new AtomicLong();
        final Map<Long, Trace> active = new ConcurrentHashMap<>();
        private final Map<String, Counter> limits = new LinkedHashMap<>();
        private final Map<String, Object> identity = new LinkedHashMap<>();
        private volatile Map<String, Object> lastQuery, lastTile, lastStartup, previousProcess;
        private boolean previousReadScheduled;
        volatile long lastFrame;
        volatile String mapState = "unknown";
        volatile String sceneState = "unknown";
        volatile int activeWorkers, queued;

        Engine(Adapter adapter) {
            this.adapter = adapter;
            identity.put("session", UUID.randomUUID().toString());
            identity.put("pid", adapter.pid());
        }
        synchronized void configure(String version, int build, String revision) {
            identity.put("app_version", version); identity.put("app_build", build); identity.put("source_revision", revision);
            if (!previousReadScheduled) {
                previousReadScheduled = true;
                // This is queued before new startup writes. A delayed SDK ANR must still find
                // the old process after the current startup replaces the journal on disk.
                adapter.execute(() -> previousProcess = adapter.read());
            }
        }
        Trace begin(String kind, long start) { return begin(kind, start, nextId.incrementAndGet()); }
        Trace begin(String kind, long start, long id) {
            Trace trace = new Trace(this, kind, start, id);
            active.put(id, trace);
            trace.watch = adapter.watch(trace::check);
            return trace;
        }
        void reject(long id, String reason, int workers, int queue, Trace trace) {
            activeWorkers = workers; queued = queue;
            Map<String, Object> data = trace == null ? new LinkedHashMap<>() : trace.snapshot();
            long wall = adapter.wall();
            data.put("id", id); data.put("kind", "tile"); data.put("reason", reason);
            data.put("started_at_ms", wall); data.put("observed_at_ms", wall); data.put("finished", true);
            data.put("active_workers", workers); data.put("queued", queue); data.put("heap", adapter.memory());
            lastTile = data;
            emit("tile_rejected:" + reason, data);
        }
        void record(Trace trace, String event) {
            Map<String, Object> data = trace.snapshot();
            if ("stalled".equals(event) && Boolean.TRUE.equals(data.get("finished"))) return;
            data.put("event", event); data.put("map_state", mapState);
            data.put("scene_state", sceneState);
            data.put("last_frame_age_ms", lastFrame == 0 ? -1L : Math.max(0, adapter.now() - lastFrame));
            data.put("active_workers", activeWorkers); data.put("queued", queued);
            data.put("heap", adapter.memory());
            if ("render_query".equals(trace.kind) || "startup".equals(trace.kind)) {
                List<String> stack = new ArrayList<>();
                if ("stalled".equals(event)) {
                    for (StackTraceElement frame : trace.thread.getStackTrace()) {
                        if (stack.size() == 24) break;
                        String text = frame.toString();
                        stack.add(text.length() <= 200 ? text : text.substring(0, 200));
                    }
                }
                data.put("thread_stack", stack);
                List<Map<String, Object>> tiles = new ArrayList<>();
                for (Trace activeTrace : active.values()) {
                    if ("tile".equals(activeTrace.kind) && tiles.size() < 2) tiles.add(activeTrace.snapshot());
                }
                if ("startup".equals(trace.kind)) lastStartup = data;
                else {
                    data.put("active_tiles", tiles);
                    lastQuery = data;
                }
            } else lastTile = data;
            emit(trace.kind + ":" + event, data);
        }
        private void emit(String key, Map<String, Object> data) {
            long suppressed;
            synchronized (this) {
                Counter counter = limits.get(key);
                if (counter == null) {
                    if (limits.size() >= 32) limits.remove(limits.keySet().iterator().next());
                    counter = new Counter(); limits.put(key, counter);
                }
                if (counter.logged && adapter.now() - counter.last < REPEAT_MS) {
                    counter.suppressed++;
                    // A later startup/query may be the one that crashes. Throttle the text log,
                    // but do not leave the previous operation's identity/stack on disk.
                    if ("startup".equals(data.get("kind")) || "render_query".equals(data.get("kind")))
                        adapter.execute(() -> adapter.write(journal()));
                    return;
                }
                suppressed = counter.suppressed; counter.suppressed = 0;
                counter.logged = true; counter.last = adapter.now();
            }
            Map<String, Object> logged = new LinkedHashMap<>(data);
            logged.put("suppressed", suppressed);
            adapter.execute(() -> {
                adapter.write(journal());
                adapter.log("MapRuntime " + new JSONObject(logged));
            });
        }
        private synchronized Map<String, Object> journal() {
            Map<String, Object> result = new LinkedHashMap<>(identity);
            if (lastQuery != null) result.put("render_query", lastQuery);
            if (lastTile != null) result.put("local_tile", lastTile);
            if (lastStartup != null) result.put("startup", lastStartup);
            // Keep the latest observations inside the existing on-disk bound.
            while (new JSONObject(result).toString().getBytes(StandardCharsets.UTF_8).length > MAX_JOURNAL_BYTES) {
                String oldest = null; long observed = Long.MAX_VALUE;
                for (String key : new String[]{"startup", "render_query", "local_tile"}) {
                    Object entry = result.get(key);
                    if (!(entry instanceof Map)) continue;
                    Object at = ((Map<?, ?>) entry).get("observed_at_ms");
                    long time = at instanceof Number ? ((Number) at).longValue() : 0;
                    if (oldest == null || time < observed) { oldest = key; observed = time; }
                }
                if (oldest == null) break;
                result.remove(oldest);
            }
            return result;
        }
        Map<String, Object> forEvent(long timestamp) {
            Map<String, Object> result = matching(journal(), timestamp);
            if (result.isEmpty()) result = matching(previousProcess, timestamp);
            return result.isEmpty() ? matching(adapter.read(), timestamp) : result;
        }
        private Map<String, Object> matching(Map<String, Object> snapshot, long timestamp) {
            Map<String, Object> result = new LinkedHashMap<>();
            if (snapshot == null) return result;
            for (String key : new String[]{"render_query", "local_tile", "startup"}) {
                Object value = snapshot.get(key);
                if (!(value instanceof Map)) continue;
                Map<?, ?> record = (Map<?, ?>) value;
                Object first = record.get("started_at_ms"), last = record.get("observed_at_ms");
                if (!(first instanceof Number) || !(last instanceof Number)) continue;
                if (timestamp >= ((Number) first).longValue() - 250
                        && timestamp <= ((Number) last).longValue() + EVENT_GRACE_MS) {
                    if (result.isEmpty()) {
                        for (String meta : new String[]{"session", "pid", "app_version", "app_build", "source_revision"})
                            if (snapshot.containsKey(meta)) result.put(meta, snapshot.get(meta));
                    }
                    result.put(key, value);
                }
            }
            return result;
        }
    }
    private static final class Counter { long last, suppressed; boolean logged; }

    private static final class AndroidAdapter implements Adapter {
        volatile File journal;
        private ScheduledThreadPoolExecutor executor;
        private java.util.concurrent.ThreadPoolExecutor writer;
        private final AtomicLong dropped = new AtomicLong();
        private synchronized ScheduledThreadPoolExecutor executor() {
            if (executor == null) {
                executor = new ScheduledThreadPoolExecutor(1, action -> {
                    Thread worker = new Thread(action, "MapRuntimeDiagnostics"); worker.setDaemon(true); return worker;
                });
                executor.setRemoveOnCancelPolicy(true);
            }
            return executor;
        }
        public long now() { return SystemClock.elapsedRealtime(); }
        public long wall() { return System.currentTimeMillis(); }
        public int pid() { try { return Process.myPid(); } catch (RuntimeException unavailable) { return -1; } }
        public Cancel watch(Runnable task) {
            java.util.concurrent.ScheduledFuture<?> future = executor().scheduleWithFixedDelay(
                    () -> { try { task.run(); } catch (RuntimeException ignored) {} }, WATCH_MS, REPEAT_MS, TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }
        public void execute(Runnable action) {
            synchronized (this) {
                if (writer == null) writer = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                        new java.util.concurrent.ArrayBlockingQueue<>(16), task -> {
                    Thread worker = new Thread(task, "MapDiagnosticWriter"); worker.setDaemon(true); return worker;
                });
            }
            try { writer.execute(() -> { try { action.run(); } catch (RuntimeException ignored) {} }); }
            catch (java.util.concurrent.RejectedExecutionException overloaded) { dropped.incrementAndGet(); }
        }
        public void log(String text) { HyperLog.w(Constants.TAG, text); }
        public Map<String, Object> memory() {
            Runtime memory = Runtime.getRuntime();
            Map<String, Object> data = new LinkedHashMap<>();
            long used = memory.totalMemory() - memory.freeMemory();
            data.put("max_bytes", memory.maxMemory()); data.put("used_bytes", used);
            data.put("available_bytes", Math.max(0, memory.maxMemory() - used));
            data.put("dropped_diagnostic_tasks", dropped.get());
            return data;
        }
        public void write(Map<String, Object> snapshot) {
            if (journal == null) return;
            writeJournal(journal, snapshot);
        }
        public Map<String, Object> read() { return journal == null ? null : readJournal(journal); }
    }
    static void writeJournal(File target, Map<String, Object> snapshot) {
        try {
            byte[] bytes = new JSONObject(snapshot).toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_JOURNAL_BYTES) return;
            File staging = new File(target.getParentFile(), target.getName() + ".tmp");
            Files.write(staging.toPath(), bytes);
            Files.move(staging.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) { /* Diagnostics must not alter map behavior. */ }
    }
    static Map<String, Object> readJournal(File target) {
        try {
            if (!target.isFile() || target.length() > MAX_JOURNAL_BYTES) return null;
            return jsonMap(new JSONObject(new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8)));
        } catch (Exception ignored) { return null; }
    }
    private static Map<String, Object> jsonMap(JSONObject object) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        for (java.util.Iterator<String> keys = object.keys(); keys.hasNext();) {
            String key = keys.next(); Object value = object.get(key);
            if (value == JSONObject.NULL) value = null;
            if (value instanceof JSONObject) value = jsonMap((JSONObject) value);
            else if (value instanceof org.json.JSONArray) value = jsonList((org.json.JSONArray) value);
            result.put(key, value);
        }
        return result;
    }
    private static List<Object> jsonList(org.json.JSONArray array) throws Exception {
        List<Object> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Object value = array.get(i);
            if (value instanceof JSONObject) value = jsonMap((JSONObject) value);
            else if (value instanceof org.json.JSONArray) value = jsonList((org.json.JSONArray) value);
            result.add(value);
        }
        return result;
    }
}

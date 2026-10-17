package com.nextgis.maplib.util;

import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class MapRuntimeDiagnosticsTest {
    static class Fake implements MapRuntimeDiagnostics.Adapter {
        long now = 100, wall = 100000;
        final List<Runnable> checks = new ArrayList<>();
        final List<String> logs = new ArrayList<>();
        Map<String, Object> saved;
        int cancelled;
        void advance(long delta) { now += delta; wall += delta; }
        public long now() { return now; }
        public long wall() { return wall; }
        public int pid() { return 42; }
        public MapRuntimeDiagnostics.Cancel watch(Runnable task) { checks.add(task); return () -> cancelled++; }
        public void execute(Runnable task) { task.run(); }
        public void log(String value) { logs.add(value); }
        public void write(Map<String, Object> data) { saved = data; }
        public Map<String, Object> read() { return saved; }
        public Map<String, Object> memory() { return Map.of("available_bytes", 1024L); }
    }

    @Test public void fastQueriesHaveNoLogsOrDiskWritesAndCancelTheWatch() {
        Fake fake = new Fake(); MapRuntimeDiagnostics.Engine engine = new MapRuntimeDiagnostics.Engine(fake);
        MapRuntimeDiagnostics.Trace trace = engine.begin("render_query", fake.now);
        fake.advance(30); trace.finish(2, null);
        fake.checks.get(0).run(); // A queued late callback must not report a finished query.
        assertEquals(1, fake.cancelled);
        assertTrue(fake.logs.isEmpty()); assertNull(fake.saved); assertTrue(engine.active.isEmpty());
    }

    @Test public void independentObserverRecordsAQueryThatNeverReturns() {
        Fake fake = new Fake(); MapRuntimeDiagnostics.Engine engine = new MapRuntimeDiagnostics.Engine(fake);
        engine.configure("1.2", 23, "revision");
        engine.lastFrame = fake.now; engine.mapState = "resumed";
        MapRuntimeDiagnostics.Trace tile = engine.begin("tile", fake.now);
        tile.put("layer_id", 5); tile.phase("query_ids");
        MapRuntimeDiagnostics.Trace query = engine.begin("render_query", fake.now);
        query.put("target", "marker-layer"); query.put("mode", 0); query.phase("native_query");
        fake.advance(1200); query.check();
        Map<String, Object> context = engine.forEvent(fake.wall);
        assertEquals("revision", context.get("source_revision"));
        Map<?, ?> state = (Map<?, ?>) context.get("render_query");
        assertEquals("native_query", state.get("phase")); assertEquals(false, state.get("finished"));
        assertEquals(1200L, state.get("elapsed_ms")); assertEquals(1200L, state.get("last_frame_age_ms"));
        assertEquals(1, ((List<?>) state.get("active_tiles")).size());
        assertNotNull(fake.saved); assertFalse(((List<?>) state.get("thread_stack")).isEmpty());
    }

    @Test public void reasonsDoNotSuppressEachOtherAndRepeatedWarningsCountTheSuppressedEvents() {
        Fake fake = new Fake(); MapRuntimeDiagnostics.Engine engine = new MapRuntimeDiagnostics.Engine(fake);
        engine.reject(1, "queue_full", 2, 16, null);
        engine.reject(2, "heap_headroom", 2, 15, null);
        for (int i = 0; i < 4; i++) engine.reject(10 + i, "queue_full", 2, 16, null);
        assertEquals(2, fake.logs.size());
        fake.advance(MapRuntimeDiagnostics.REPEAT_MS);
        engine.reject(20, "queue_full", 2, 16, null);
        assertEquals(3, fake.logs.size());
        assertTrue(fake.logs.get(2).contains("\"suppressed\":4"));
        assertTrue(fake.logs.get(2).contains("\"queued\":16"));
    }

    @Test public void tileStagesSeparateQueueWaitLockWaitReadsAndEncoding() {
        Fake fake = new Fake(); MapRuntimeDiagnostics.Engine engine = new MapRuntimeDiagnostics.Engine(fake);
        MapRuntimeDiagnostics.Trace trace = engine.begin("tile", fake.now - 40);
        trace.put("queue_ms", 40L);
        trace.phase("wait_layer_lock"); fake.advance(200);
        trace.phase("query_ids"); fake.advance(100);
        trace.phase("feature_read_clip"); trace.featureRead(); trace.featureRead(); fake.advance(250);
        trace.phase("mvt_encode"); fake.advance(100); trace.finish(50, null);
        Map<?, ?> tile = (Map<?, ?>) engine.forEvent(fake.wall).get("local_tile");
        Map<?, ?> timings = (Map<?, ?>) tile.get("stage_ms");
        assertEquals(200L, timings.get("wait_layer_lock")); assertEquals(100L, timings.get("query_ids"));
        assertEquals(250L, timings.get("feature_read_clip")); assertEquals(100L, timings.get("mvt_encode"));
        assertEquals(2, tile.get("features_read")); assertEquals(40L, tile.get("queue_ms"));
    }

    @Test public void historicalEventUsesTheSavedProcessAndRejectsUnrelatedTimes() {
        Fake old = new Fake(); MapRuntimeDiagnostics.Engine previous = new MapRuntimeDiagnostics.Engine(old);
        previous.configure("old", 12, "old-revision");
        MapRuntimeDiagnostics.Trace query = previous.begin("render_query", old.now);
        old.advance(1000); query.check();
        Fake fresh = new Fake(); fresh.saved = old.saved; fresh.wall = old.wall + 60000;
        MapRuntimeDiagnostics.Engine restarted = new MapRuntimeDiagnostics.Engine(fresh);
        restarted.configure("new", 13, "new-revision");
        assertEquals("old-revision", restarted.forEvent(old.wall + 2000).get("source_revision"));
        assertTrue(restarted.forEvent(fresh.wall).isEmpty());
        assertTrue(restarted.forEvent(old.wall - 5000).isEmpty());
    }

    @Test public void corruptOrOversizedJournalCannotBreakEventProcessing() throws Exception {
        File file = Files.createTempFile("map-diagnostics-test", ".json").toFile();
        try {
            Files.write(file.toPath(), new byte[MapRuntimeDiagnostics.MAX_JOURNAL_BYTES + 1]);
            assertNull(MapRuntimeDiagnostics.readJournal(file));
            Files.write(file.toPath(), "{broken".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertNull(MapRuntimeDiagnostics.readJournal(file));
        } finally { Files.deleteIfExists(file.toPath()); }
    }

    @Test public void startupObserverKeepsTheBlockedPhaseAndMainThreadStack() {
        Fake fake = new Fake(); MapRuntimeDiagnostics.Engine engine = new MapRuntimeDiagnostics.Engine(fake);
        engine.configure("1.2", 23, "startup-revision");
        MapRuntimeDiagnostics.Trace startup = engine.begin("startup", fake.now);
        startup.phase("gis_application_load"); fake.advance(1200); startup.check();
        Map<String, Object> context = engine.forEvent(fake.wall);
        Map<?, ?> state = (Map<?, ?>) context.get("startup");
        assertEquals("gis_application_load", state.get("phase"));
        assertEquals(false, state.get("finished"));
        assertFalse(((List<?>) state.get("thread_stack")).isEmpty());
        assertEquals("startup-revision", context.get("source_revision"));
        startup.finish(0, null);
        assertTrue(engine.active.isEmpty()); assertEquals(1, fake.cancelled);
    }

    @Test public void currentStartupCannotEraseThePreviousProcessBeforeItsDelayedAnrArrives() {
        Fake old = new Fake(); MapRuntimeDiagnostics.Engine previous = new MapRuntimeDiagnostics.Engine(old);
        previous.configure("old", 12, "old-revision");
        MapRuntimeDiagnostics.Trace blocked = previous.begin("startup", old.now);
        blocked.phase("preferences_and_local_log"); old.advance(1200); blocked.check();
        Fake fresh = new Fake(); fresh.saved = old.saved; fresh.wall = old.wall + 60000;
        MapRuntimeDiagnostics.Engine restarted = new MapRuntimeDiagnostics.Engine(fresh);
        restarted.configure("new", 13, "new-revision");
        MapRuntimeDiagnostics.Trace current = restarted.begin("startup", fresh.now);
        fresh.advance(1200); current.check();
        assertEquals("new-revision", fresh.saved.get("source_revision"));
        assertEquals("old-revision", restarted.forEvent(old.wall + 2000).get("source_revision"));
        assertEquals("preferences_and_local_log", ((Map<?, ?>) restarted.forEvent(old.wall + 2000).get("startup")).get("phase"));
    }

    @Test public void journalDropsTheOldestObservationWhenTheirCombinedSizeExceedsTheDiskBound() {
        Fake fake = new Fake(); MapRuntimeDiagnostics.Engine engine = new MapRuntimeDiagnostics.Engine(fake);
        engine.configure("1.2", 23, "revision");
        MapRuntimeDiagnostics.Trace query = engine.begin("render_query", fake.now);
        query.put("target", "q".repeat(9500)); fake.advance(1200); query.check();
        MapRuntimeDiagnostics.Trace startup = engine.begin("startup", fake.now);
        startup.put("synthetic_detail", "s".repeat(9500)); fake.advance(1200); startup.check();
        assertTrue(fake.saved.containsKey("startup"));
        assertFalse(fake.saved.containsKey("render_query"));
        assertTrue(new org.json.JSONObject(fake.saved).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                <= MapRuntimeDiagnostics.MAX_JOURNAL_BYTES);
    }

    @Test public void boundedJournalRoundTripsAtomicallyAndOversizedReplacementRetainsPreviousData() throws Exception {
        File directory = Files.createTempDirectory("map-diagnostics-test").toFile();
        File file = new File(directory, "journal.json");
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("source_revision", "test"); data.put("render_query", Map.of("thread_stack", List.of("main")));
            MapRuntimeDiagnostics.writeJournal(file, data);
            assertEquals(data, MapRuntimeDiagnostics.readJournal(file));
            MapRuntimeDiagnostics.writeJournal(file, Map.of("large", "x".repeat(MapRuntimeDiagnostics.MAX_JOURNAL_BYTES)));
            assertEquals(data, MapRuntimeDiagnostics.readJournal(file));
            assertFalse(new File(directory, "journal.json.tmp").exists());
        } finally { Files.deleteIfExists(file.toPath()); Files.deleteIfExists(directory.toPath()); }
    }
}

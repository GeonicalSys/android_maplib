package com.nextgis.maplib.util;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;
import static com.nextgis.maplib.util.MapRuntimeDiagnostics.SqliteCount.*;
import static com.nextgis.maplib.util.MapRuntimeDiagnostics.SqlitePhase.*;
import static com.nextgis.maplib.util.MapRuntimeDiagnostics.SqliteOutcome.*;

public class RenderResourceProfilerTest {
    private static final class Clock implements RenderResourceProfiler.Clock {
        long now, cpu, tid = 7;
        boolean fail;
        public long nanos() { if (fail) throw new IllegalStateException("clock unavailable"); return now; }
        public long cpuNanos() { return cpu; }
        public long tid() { return tid; }
        void advance(long wall, long work) { now += wall; cpu += work; }
    }
    private static final class Sink implements RenderResourceProfiler.Sink {
        final List<Map<String,Object>> records = new ArrayList<>();
        boolean accept = true, fail;
        public boolean offer(Map<String,Object> record) {
            if (fail) throw new AssertionError("writer failure must not escape");
            if (accept) records.add(record);
            return accept;
        }
    }
    @After public void stop() { RenderResourceProfiler.stop("test_cleanup"); }
    private static void start(Clock clock, Sink sink) {
        RenderResourceProfiler.startWithClock("session-1", "3.1.2.30", 224, "abc+dirty", sink, clock);
    }
    private static long[] global(Map<String,Object> summary) { return (long[]) summary.get("global"); }
    private static long outcome(Map<String,Object> summary, MapRuntimeDiagnostics.SqliteOutcome result) {
        return global(summary)[6 + result.ordinal()];
    }
    private static long count(Map<String,Object> summary, MapRuntimeDiagnostics.SqliteCount key) {
        return global(summary)[6 + MapRuntimeDiagnostics.SqliteOutcome.values().length + key.ordinal()];
    }
    private static long[] array(Map<String,Object> summary, String name) { return (long[]) summary.get(name); }

    @Test public void disabledHasNoSinkOrTokenAndDoesNotTouchAndroidClock() {
        RenderResourceProfiler.stop("before");
        assertNull(RenderResourceProfiler.beginSqlite(1, 12, "sqlite_native"));
        RenderResourceProfiler.event("frame", null);
        assertTrue(RenderResourceProfiler.snapshot().isEmpty());
        assertTrue(RenderResourceProfiler.stop("again").isEmpty());
    }

    @Test public void sameThreadCpuAndWallAreIndependentExactCounters() {
        Clock clock=new Clock(); Sink sink=new Sink(); start(clock,sink);
        RenderResourceProfiler.Token token=RenderResourceProfiler.beginSqlite(18,12,"sqlite_native");
        token.identity(7,9);
        clock.advance(10_000,3_000); token.phase(STYLE);
        clock.advance(20_000,5_000); token.phase(BLOB_READ);
        token.count(FEATURES_READ,4);token.count(BLOB_BYTES,900);
        clock.advance(40_000,7_000);token.finish(RETURNED_TO_SDK);
        Map<String,Object> summary=RenderResourceProfiler.stop("operator_stop");
        assertEquals(1,global(summary)[1]);assertEquals(0,global(summary)[2]);
        assertEquals(70_000,global(summary)[4]);assertEquals(15_000,global(summary)[5]);
        assertEquals(1,outcome(summary,RETURNED_TO_SDK));assertEquals(4,count(summary,FEATURES_READ));
        assertEquals(10_000,array(summary,"global_phase_wall_ns")[IO_QUEUE.ordinal()]);
        assertEquals(5_000,array(summary,"global_phase_thread_cpu_known_ns")[STYLE.ordinal()]);
        assertEquals(7_000,array(summary,"global_phase_thread_cpu_known_ns")[BLOB_READ.ordinal()]);
        assertEquals(true,summary.get("detailed_complete"));
        Map<String,Object> raw=sink.records.get(1);
        assertEquals("sqlite_request",raw.get("event")); assertEquals(7L,raw.get("source_epoch"));
        assertEquals(9L,raw.get("content_version"));assertEquals(900,((long[])raw.get("counts"))[BLOB_BYTES.ordinal()]);
    }

    @Test public void handoffNeverSubtractsCpuClocksAcrossThreads() {
        Clock clock=new Clock(); Sink sink=new Sink();start(clock,sink);
        RenderResourceProfiler.Token token=RenderResourceProfiler.beginSqlite(1,12,"sqlite_native");
        clock.advance(1000,10_000_000);clock.tid=8;token.phase(STYLE);
        clock.advance(1000,50);token.phase(STYLE);
        clock.advance(1000,900_000_000);clock.tid=7;token.finish(RETURNED_TO_SDK);
        Map<String,Object> summary=RenderResourceProfiler.stop("stop");
        assertEquals(50,global(summary)[5]);
        assertEquals(1,array(summary,"global_phase_cpu_unknown_segments")[IO_QUEUE.ordinal()]);
        assertEquals(1,array(summary,"global_phase_cpu_unknown_segments")[STYLE.ordinal()]);
    }

    @Test public void rejectedRawStillPreservesAllAggregateCountsAndFinalSummary() {
        Clock clock=new Clock(); Sink sink=new Sink();sink.accept=false;start(clock,sink);
        RenderResourceProfiler.Token token=RenderResourceProfiler.beginSqlite(8,12,"sqlite_native");
        token.count(FEATURES_READ,101);token.count(LABELS,77);clock.advance(20,5);token.finish(CANCELLED);
        Map<String,Object> summary=RenderResourceProfiler.stop("stop");
        assertEquals(101,count(summary,FEATURES_READ));assertEquals(77,count(summary,LABELS));
        assertEquals(1,outcome(summary,CANCELLED));assertEquals(3L,summary.get("raw_rejected"));
        assertEquals(false,summary.get("detailed_complete"));assertTrue(sink.records.isEmpty());
    }

    @Test public void sinkErrorsNeverChangeDeliveryOrPreventAccounting() {
        Clock clock=new Clock();Sink sink=new Sink();sink.fail=true;start(clock,sink);
        RenderResourceProfiler.Token token=RenderResourceProfiler.beginSqlite(2,12,"sqlite_native");
        token.count(INLINE_BLOBS,3);token.finish(RETURNED_TO_SDK);
        Map<String,Object> summary=RenderResourceProfiler.stop("stop");
        assertEquals(1,outcome(summary,RETURNED_TO_SDK));assertEquals(3,count(summary,INLINE_BLOBS));
        assertEquals(3L,summary.get("sink_failures"));assertEquals(false,summary.get("detailed_complete"));
    }

    @Test public void doubleFinishAndLaterCountsCannotDuplicateOrRewriteTheRawRecord() {
        Clock clock=new Clock();Sink sink=new Sink();start(clock,sink);
        RenderResourceProfiler.Token token=RenderResourceProfiler.beginSqlite(5,12,"sqlite_native");
        token.count(PBF_BYTES,17);token.finish(CANCELLED);
        Map<String,Object> raw=sink.records.get(1);long[] counts=(long[])raw.get("counts");
        token.count(PBF_BYTES,100);token.finish(RETURNED_TO_SDK);token.phase(STYLE);
        Map<String,Object> summary=RenderResourceProfiler.stop("stop");
        assertEquals(17,counts[PBF_BYTES.ordinal()]);assertEquals(1,outcome(summary,CANCELLED));
        assertEquals(0,outcome(summary,RETURNED_TO_SDK));assertEquals(17,count(summary,PBF_BYTES));
    }

    @Test public void activeOverflowStillCountsItsPhasesWorkAndCompletion() {
        Clock clock=new Clock();Sink sink=new Sink();start(clock,sink);
        List<RenderResourceProfiler.Token> tokens=new ArrayList<>();
        for(int i=0;i<513;i++)tokens.add(RenderResourceProfiler.beginSqlite(4,12,"sqlite_native"));
        for(RenderResourceProfiler.Token token:tokens){clock.advance(1000,1);token.phase(STYLE);token.count(FEATURES_READ,1);token.finish(RETURNED_TO_SDK);}
        Map<String,Object> summary=RenderResourceProfiler.stop("stop");
        assertEquals(513,global(summary)[1]);assertEquals(1,global(summary)[3]);assertEquals(0,global(summary)[2]);
        assertEquals(513,count(summary,FEATURES_READ));assertEquals(513,outcome(summary,RETURNED_TO_SDK));
        assertTrue(array(summary,"global_phase_wall_ns")[IO_QUEUE.ordinal()]>0);
        assertEquals(false,summary.get("detailed_complete"));
    }

    @Test public void layerOverflowIsExplicitAndPreservesTheGlobalDenominator() {
        Clock clock=new Clock();Sink sink=new Sink();start(clock,sink);
        for(int i=0;i<257;i++){RenderResourceProfiler.Token t=RenderResourceProfiler.beginSqlite(i,12,"sqlite_native");t.count(FEATURES_READ,1);t.finish(RETURNED_TO_SDK);}
        Map<String,Object> summary=RenderResourceProfiler.stop("stop");
        assertEquals(256,((List<?>)summary.get("layers")).size());assertEquals(257,global(summary)[1]);
        assertEquals(257,count(summary,FEATURES_READ));assertEquals(1L,summary.get("untracked_layer_requests"));
        assertEquals(1,((long[])summary.get("layer_overflow"))[1]);
        assertTrue(((Number)summary.get("phase_detail_omitted_layers")).intValue()>0);
    }

    @Test public void stopLeavesRenderingScopesUnfinishedAndDoesNotInventCancellation() {
        Clock clock=new Clock();Sink sink=new Sink();start(clock,sink);
        RenderResourceProfiler.Token token=RenderResourceProfiler.beginSqlite(3,12,"sqlite_native");
        token.count(FEATURES_READ,4);
        Map<String,Object> summary=RenderResourceProfiler.stop("operator_stop");
        assertEquals(1,global(summary)[2]);assertEquals(0,outcome(summary,CANCELLED));assertEquals(0,outcome(summary,ABANDONED));
        int before=sink.records.size();token.finish(RETURNED_TO_SDK);token.count(FEATURES_READ,100);
        assertEquals(before,sink.records.size());assertEquals(4,count(summary,FEATURES_READ));
    }

    @Test public void clockFailureStillReleasesAndCountsAnOutcome() {
        Clock clock=new Clock();Sink sink=new Sink();start(clock,sink);
        RenderResourceProfiler.Token token=RenderResourceProfiler.beginSqlite(1,12,"sqlite_native");
        clock.fail=true;token.phase(BLOB_READ);token.count(PFD_BLOBS,2);token.finish(FAILED);
        Map<String,Object> summary=RenderResourceProfiler.stop("stop");
        assertEquals(0,global(summary)[2]);assertEquals(1,outcome(summary,FAILED));assertEquals(2,count(summary,PFD_BLOBS));
        assertTrue(((Number)summary.get("clock_failures")).longValue()>0);
        assertEquals(false,summary.get("detailed_complete"));
    }

    @Test public void eventDoesNotRetainMutableNumbersOrPrivateCoordinatesAndStrings() {
        Clock clock=new Clock();Sink sink=new Sink();start(clock,sink);
        Map<String,Object> input=new LinkedHashMap<>();AtomicLong mutable=new AtomicLong(7);
        input.put("frame_count",mutable);input.put("fully",true);input.put("longitude",50.2);
        input.put("uri","private");input.put("bad",Double.NaN);RenderResourceProfiler.event("frame",input);
        input.clear();mutable.set(999);
        Map<?,?> fields=(Map<?,?>)sink.records.get(1).get("metrics");
        assertEquals(7L,fields.get("frame_count"));assertEquals(true,fields.get("fully"));
        assertEquals(2,fields.size());assertEquals(3L,RenderResourceProfiler.snapshot().get("invalid_event_fields"));
    }

    @Test public void factoryScopesRemainDetailedWhenLegacyObserverCannotObserveThem() {
        Clock clock=new Clock();Sink sink=new Sink();start(clock,sink);
        MapRuntimeDiagnostics.Engine legacy=new MapRuntimeDiagnostics.Engine(new MapRuntimeDiagnostics.Adapter(){
            public long now(){return clock.now/1_000_000;}
            public long nanos(){return clock.now;}
            public long wall(){return 100_000+now();}
            public int pid(){return 7;}
            public MapRuntimeDiagnostics.Cancel watch(Runnable ignored){return ()->{};}
            public void execute(Runnable ignored){}
            public void log(String ignored){}
            public void write(Map<String,Object> ignored){}
            public Map<String,Object> read(){return null;}
            public Map<String,Object> memory(){throw new AssertionError("No additional memory reads");}
        });
        List<MapRuntimeDiagnostics.SqliteScope> scopes=new ArrayList<>();
        for(int i=0;i<96;i++)scopes.add(MapRuntimeDiagnostics.sqliteObserved(legacy,12345,12,"sqlite_native"));
        assertEquals(16,legacy.sqliteActive.size());
        for(MapRuntimeDiagnostics.SqliteScope scope:scopes){scope.count(FEATURES_READ,2);scope.finish(RETURNED_TO_SDK);}
        Map<String,Object> summary=RenderResourceProfiler.stop("stop");
        assertEquals(96,global(summary)[1]);assertEquals(192,count(summary,FEATURES_READ));
        assertEquals(96,outcome(summary,RETURNED_TO_SDK));assertEquals(0,global(summary)[3]);
        assertTrue(legacy.sqliteActive.isEmpty());
    }

    @Test public void fullCardinalityFooterHasBoundedSizeEvenWithLargeSaturatingCounters() {
        Clock clock=new Clock();Sink sink=new Sink();start(clock,sink);
        for(int i=0;i<256;i++){
            RenderResourceProfiler.Token token=RenderResourceProfiler.beginSqlite(Integer.MAX_VALUE-i,12,"sqlite_native");
            for(MapRuntimeDiagnostics.SqliteCount c:MapRuntimeDiagnostics.SqliteCount.values()){token.count(c,Long.MAX_VALUE);token.count(c,1);}
            for(MapRuntimeDiagnostics.SqlitePhase p:MapRuntimeDiagnostics.SqlitePhase.values()){
                clock.advance((512L-i)*10_000_000_000L,(i+1L)*1_000_000_000L);token.phase(p);
            }
            token.finish(RETURNED_TO_SDK);
        }
        Map<String,Object> summary=RenderResourceProfiler.stop("stop");
        assertEquals(Long.MAX_VALUE,count(summary,BLOB_BYTES));
        assertEquals(32,((List<?>)summary.get("layer_phase_details")).size());
        assertTrue(new JSONObject(summary).toString().getBytes(StandardCharsets.UTF_8).length<192*1024);
    }
}

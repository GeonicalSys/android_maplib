package com.nextgis.maplib.map;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;
import android.os.SystemClock;
import com.nextgis.maplib.util.DatabaseContext;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Cache economy and short-lived work control; never omits, simplifies or suspends layer data. */
public final class RenderMemoryGuard {
    public interface Listener {
        void restricted(int ignoredLayerId);
        default void workDelayed() { restricted(-1); }
    }
    private final Context context;
    private final Listener listener;
    private String globalEconomy;
    private Sample lastSample;
    private long sampledAt, lastPressureTrimAt;
    private boolean pressureTrimObserved;

    public RenderMemoryGuard(Context context, Listener listener) {
        this.context = context.getApplicationContext(); this.listener = listener;
    }

    static RenderMemoryGuard forLayer(VectorLayer layer) {
        if (layer == null) return null;
        MapContentProviderHelper owner = DatabaseContext.getMapForLayer(layer);
        return owner instanceof MapDrawable ? ((MapDrawable) owner).getRenderMemoryGuard() : null;
    }

    public synchronized RenderMemoryPolicy.Limits limits() { return sample().limits; }
    /** There are no suppressed layers in the cache-only strategy. */
    public Map<Integer, String> restrictions() { return Collections.emptyMap(); }
    public synchronized boolean hasResourceNotice() { return globalEconomy != null; }
    public synchronized boolean shouldReduceCache() {
        Sample memory = sample();
        return constrained(memory) || underPressure(memory);
    }
    public synchronized boolean shouldSerializeTileWork() { return constrained(sample()); }
    public synchronized boolean canBuildTile() {
        Sample memory = sample();
        return RenderMemoryPolicy.canStartTile(memory.limits, memory.availableRam, memory.systemThreshold,
                memory.javaHeadroom, memory.systemLow);
    }
    public synchronized void noteReducedCache() { noteEconomy("reduced_tile_cache"); }
    public synchronized void noteSerializedTiles() { noteEconomy("serialized_tile_builds"); }
    public synchronized void noteDelayedWork() {
        // Queue saturation alone says nothing about free memory. Keep the local 503/trace,
        // but do not claim a memory-saving mode while both system and Java headroom are ample.
        if (globalEconomy == null && shouldReduceCache()) {
            globalEconomy = "tile_work_delayed";
            listener.workDelayed();
        }
    }
    private void noteEconomy(String reason) {
        if (globalEconomy == null || "tile_work_delayed".equals(globalEconomy)) {
            globalEconomy = reason;
            listener.restricted(-1);
        }
    }
    public synchronized void retry() {
        sampledAt = 0;
        if (!shouldReduceCache()) globalEconomy = null;
        pressureTrimObserved = false;
    }

    /** The listener posts an owner-fenced native cache trim; persistent sources remain untouched. */
    public synchronized void checkPressure() {
        Sample memory = sample();
        if (!constrained(memory) && !underPressure(memory)) return;
        boolean first = globalEconomy == null || "tile_work_delayed".equals(globalEconomy);
        if (first) globalEconomy = "reduced_tile_cache";
        long now = SystemClock.elapsedRealtime();
        if (first || !pressureTrimObserved || now - lastPressureTrimAt >= 10000) {
            lastPressureTrimAt = now; pressureTrimObserved = true;
            listener.restricted(-1);
        }
    }
    private static boolean constrained(Sample memory) {
        return RenderMemoryPolicy.serializeTileWork(memory.limits, memory.availableRam, memory.systemThreshold,
                memory.javaHeadroom, memory.systemLow);
    }
    private static boolean underPressure(Sample memory) {
        return RenderMemoryPolicy.freeMemoryUnderPressure(memory.limits, memory.availableRam,
                memory.systemThreshold, memory.javaHeadroom, memory.systemLow);
    }

    public synchronized Map<String, Object> snapshot() {
        Sample memory = sample(); Map<String, Object> out = new LinkedHashMap<>();
        out.put("total_ram_bytes", memory.totalRam); out.put("available_ram_bytes", memory.availableRam);
        out.put("process_rss_bytes", memory.processBytes); out.put("native_heap_bytes", memory.nativeBytes);
        out.put("java_used_bytes", memory.javaUsed);
        out.put("java_headroom_bytes", memory.javaHeadroom);
        out.put("system_threshold_bytes", memory.systemThreshold);
        out.put("system_low_memory", memory.systemLow);
        out.put("free_memory_reserve_bytes", memory.limits.reserveBytes);
        out.put("tile_work_limit", constrained(memory) ? 1 : 2);
        out.put("global_economy", globalEconomy == null ? "none" : globalEconomy);
        out.put("limited_layer_count", 0); out.put("limited_layer_ids", Collections.emptyList());
        return out;
    }

    private Sample sample() {
        long now = SystemClock.elapsedRealtime();
        if (lastSample != null && now - sampledAt < 500) return lastSample;
        lastSample = new Sample(context); sampledAt = now; return lastSample;
    }

    private static final class Sample {
        long totalRam, availableRam = -1, systemThreshold, processBytes = -1, nativeBytes, javaUsed, javaHeadroom;
        boolean systemLow;
        RenderMemoryPolicy.Limits limits;
        Sample(Context context) {
            boolean lowRam = true;
            try {
                ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
                if (manager != null) {
                    manager.getMemoryInfo(memory); totalRam = memory.totalMem;
                    availableRam = memory.totalMem > 0 ? memory.availMem : -1; systemThreshold = memory.threshold;
                    systemLow = memory.lowMemory; lowRam = manager.isLowRamDevice();
                }
            } catch (RuntimeException unavailable) { /* Conservative unknown-device cache policy. */ }
            Runtime runtime = Runtime.getRuntime(); javaUsed = runtime.totalMemory() - runtime.freeMemory();
            javaHeadroom = Math.max(0, runtime.maxMemory() - javaUsed);
            nativeBytes = Debug.getNativeHeapAllocatedSize();
            try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/status"), 2048)) {
                for (int i = 0; i < 96; i++) {
                    String line = reader.readLine(); if (line == null) break;
                    if (line.startsWith("VmRSS:")) {
                        processBytes = RenderMemoryPolicy.times(Long.parseLong(line.substring(6).trim().split("\\s+")[0]), 1024);
                        break;
                    }
                }
            } catch (Exception unavailable) { /* Optional resident-memory measurement. */ }
            if (processBytes < 0) processBytes = RenderMemoryPolicy.plus(RenderMemoryPolicy.plus(javaUsed, nativeBytes), 64 * RenderMemoryPolicy.MIB);
            limits = RenderMemoryPolicy.limits(totalRam, lowRam);
        }
    }
}

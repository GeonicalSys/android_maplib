package com.nextgis.maplib.util;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Self-process counters only; no feature data, paths, other processes or memory dumps. */
final class RenderProcessMemory {
    private RenderProcessMemory() {}

    static Map<String, Object> sample(Context context) {
        Map<String, Object> result = new LinkedHashMap<>();
        try { result.put("native_allocated_bytes", Debug.getNativeHeapAllocatedSize()); }
        catch (RuntimeException unavailable) { /* Optional counter. */ }
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/status"), 2048)) {
            long rss = readRss(reader);
            if (rss >= 0) result.put("process_rss_bytes", rss);
        } catch (IOException | RuntimeException unavailable) { /* Not a zero measurement. */ }
        if (context != null) try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager != null) {
                ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo(); manager.getMemoryInfo(memory);
                result.put("system_available_bytes", memory.availMem);
                result.put("system_total_bytes", memory.totalMem);
                result.put("system_threshold_bytes", memory.threshold);
                result.put("system_low_memory", memory.lowMemory);
            }
        } catch (RuntimeException unavailable) { /* Diagnostics must not stop rendering. */ }
        return result;
    }

    static long readRss(BufferedReader reader) throws IOException {
        for (int i = 0; i < 96; i++) {
            String line = reader.readLine(); if (line == null) break;
            if (!line.startsWith("VmRSS:")) continue;
            String[] parts = line.substring(6).trim().split("\\s+");
            if (parts.length != 2 || !"kB".equals(parts[1])) return -1;
            try {
                long value = Long.parseLong(parts[0]);
                return value >= 0 && value <= Long.MAX_VALUE / 1024 ? value * 1024 : -1;
            } catch (NumberFormatException malformed) { return -1; }
        }
        return -1;
    }
}

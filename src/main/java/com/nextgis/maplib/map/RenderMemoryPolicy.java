package com.nextgis.maplib.map;

/** Free-memory admission and cache thresholds; used memory is diagnostic only. */
public final class RenderMemoryPolicy {
    public static final long MIB = 1024L * 1024L;
    public static final long GIB = 1024L * MIB;
    private RenderMemoryPolicy() { }

    public static final class Limits {
        public final long diskCacheBytes, reserveBytes;
        public final boolean lowMemoryDevice;
        private Limits(boolean lowMemoryDevice) {
            this.lowMemoryDevice = lowMemoryDevice;
            diskCacheBytes = (lowMemoryDevice ? 32 : 128) * MIB;
            reserveBytes = (lowMemoryDevice ? 32 : 64) * MIB;
        }
    }

    public static Limits limits(long totalRam, boolean androidLowRam) {
        long ram = totalRam > 0 ? totalRam : 2 * GIB;
        boolean low = androidLowRam || ram <= 2304 * MIB;
        // Device class sizes the ordinary disk cache and allocation reserve only.
        // It does not by itself request a warning, slower work or native cache eviction.
        return new Limits(low);
    }

    public static boolean freeMemoryUnderPressure(Limits limits, long availableRam, long systemThreshold,
                                                   long javaHeadroom, boolean systemLowMemory) {
        return systemLowMemory
                || (availableRam >= 0 && availableRam <= plus(systemThreshold, limits.reserveBytes))
                || (javaHeadroom >= 0 && javaHeadroom < limits.reserveBytes);
    }

    /** Serialize complete tile work only when the measured free remainder is small. */
    public static boolean serializeTileWork(Limits limits, long availableRam, long systemThreshold,
                                             long javaHeadroom, boolean systemLowMemory) {
        return systemLowMemory
                || (availableRam >= 0 && availableRam <= Math.max(512 * MIB, plus(systemThreshold, limits.reserveBytes)))
                || (javaHeadroom >= 0 && javaHeadroom < 2 * limits.reserveBytes);
    }

    /** Retryable pressure only: no arbitrary per-layer/feature size or successful partial result. */
    public static boolean canStartTile(Limits limits, long availableRam, long systemThreshold,
                                        long javaHeadroom, boolean systemLowMemory) {
        return !systemLowMemory
                && (availableRam < 0 || availableRam > plus(systemThreshold, limits.reserveBytes))
                && (javaHeadroom < 0 || javaHeadroom >= limits.reserveBytes);
    }

    public static long plus(long left, long right) {
        if (left < 0 || right < 0 || left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }
    public static long times(long left, long right) {
        if (left < 0 || right < 0 || (right != 0 && left > Long.MAX_VALUE / right)) return Long.MAX_VALUE;
        return left * right;
    }
}

/*
 * Project: NextGIS Mobile
 * Purpose: Bound local vector-tile work to the Android heap budget.
 */

package com.nextgis.maplib.map;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class LocalVectorTileRequestPolicy {
    static final int MAX_CONCURRENT_REQUESTS = 2;
    static final int MAX_QUEUED_REQUESTS = 16;
    static final long MIN_HEAP_HEADROOM_BYTES = 64L * 1024L * 1024L;

    private LocalVectorTileRequestPolicy() {
    }

    static ThreadPoolExecutor newExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                MAX_CONCURRENT_REQUESTS,
                MAX_CONCURRENT_REQUESTS,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MAX_QUEUED_REQUESTS),
                new TileThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }

    static boolean hasHeapHeadroom(long maxMemory, long totalMemory, long freeMemory) {
        if (maxMemory <= 0L || totalMemory < 0L || freeMemory < 0L) {
            return false;
        }
        long usedMemory = Math.max(0L, totalMemory - freeMemory);
        return maxMemory - usedMemory >= MIN_HEAP_HEADROOM_BYTES;
    }

    private static final class TileThreadFactory implements ThreadFactory {
        private final AtomicInteger mNextId = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(
                    runnable,
                    "LocalVectorTileWorker-" + mNextId.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}

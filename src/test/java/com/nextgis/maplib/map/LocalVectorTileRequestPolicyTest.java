package com.nextgis.maplib.map;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class LocalVectorTileRequestPolicyTest {
    @Test
    public void executorBoundsConcurrentAndQueuedTileRequests() throws Exception {
        ThreadPoolExecutor executor = LocalVectorTileRequestPolicy.newExecutor();
        CountDownLatch workersStarted = new CountDownLatch(
                LocalVectorTileRequestPolicy.MAX_CONCURRENT_REQUESTS);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        try {
            for (int i = 0; i < LocalVectorTileRequestPolicy.MAX_CONCURRENT_REQUESTS; i++) {
                executor.execute(() -> {
                    workersStarted.countDown();
                    try {
                        releaseWorkers.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertTrue(workersStarted.await(2, TimeUnit.SECONDS));

            for (int i = 0; i < LocalVectorTileRequestPolicy.MAX_QUEUED_REQUESTS; i++) {
                executor.execute(() -> { });
            }

            try {
                executor.execute(() -> { });
                fail("executor accepted work beyond its bounded queue");
            } catch (RejectedExecutionException expected) {
                // Expected overload behavior: the HTTP server returns 503 for this request.
            }
        } finally {
            releaseWorkers.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void heapHeadroomRejectsWorkNearGrowthLimit() {
        long mib = 1024L * 1024L;
        assertTrue(LocalVectorTileRequestPolicy.hasHeapHeadroom(
                512L * mib, 256L * mib, 128L * mib));
        assertFalse(LocalVectorTileRequestPolicy.hasHeapHeadroom(
                512L * mib, 512L * mib, 5L * mib));
    }
}

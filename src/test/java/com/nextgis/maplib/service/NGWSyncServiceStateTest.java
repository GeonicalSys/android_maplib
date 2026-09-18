package com.nextgis.maplib.service;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class NGWSyncServiceStateTest {
    @Test(timeout = 10000) public void rejectedParallelAttemptCannotFinishAnotherWorker() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            NGWSyncService.markSyncStarted();
            started.countDown();
            try { finish.await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { NGWSyncService.markSyncFinished(); }
        });
        worker.start();
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS));
            assertTrue(NGWSyncService.isSyncStarted());
            NGWSyncService.markSyncFinished();
            assertTrue(NGWSyncService.isSyncStarted());
            NGWSyncService.markSyncStarted();
            NGWSyncService.markSyncFinished();
            assertTrue(NGWSyncService.isSyncStarted());
        } finally { finish.countDown(); worker.join(3000); NGWSyncService.markSyncFinished(); }
        assertFalse(NGWSyncService.isSyncStarted());
    }
}

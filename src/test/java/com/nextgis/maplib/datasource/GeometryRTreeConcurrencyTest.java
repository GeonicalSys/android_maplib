package com.nextgis.maplib.datasource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class GeometryRTreeConcurrencyTest {

    @Test(timeout = 15000)
    public void concurrentAddsAndSearches_keepTreeCompleteAndInitialized() throws Exception {
        GeometryRTree tree = new GeometryRTree();
        int writerCount = 4;
        int entriesPerWriter = 500;
        int readerCount = 2;
        ExecutorService pool = Executors.newFixedThreadPool(writerCount + readerCount);
        CountDownLatch ready = new CountDownLatch(writerCount + readerCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();

        for (int writer = 0; writer < writerCount; writer++) {
            final int writerIndex = writer;
            futures.add(pool.submit((Callable<Void>) () -> {
                ready.countDown();
                start.await();
                long firstId = (long) writerIndex * entriesPerWriter;
                for (int i = 0; i < entriesPerWriter; i++) {
                    double coordinate = firstId + i;
                    tree.addItem(firstId + i,
                            new GeoEnvelope(coordinate, coordinate + 1.0,
                                    coordinate, coordinate + 1.0));
                }
                return null;
            }));
        }

        GeoEnvelope world = new GeoEnvelope(-1.0, 10000.0, -1.0, 10000.0);
        for (int reader = 0; reader < readerCount; reader++) {
            futures.add(pool.submit((Callable<Void>) () -> {
                ready.countDown();
                start.await();
                for (int i = 0; i < 500; i++) {
                    tree.search(world);
                }
                return null;
            }));
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        for (Future<Void> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        int expected = writerCount * entriesPerWriter;
        assertEquals(expected, tree.size());
        assertEquals(expected, tree.getAll().size());
        assertTrue(tree.getRoot().mCoords.isInit());
    }
}

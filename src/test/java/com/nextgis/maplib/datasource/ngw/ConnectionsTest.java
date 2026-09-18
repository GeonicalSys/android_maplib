package com.nextgis.maplib.datasource.ngw;

import org.junit.Test;
import java.util.Set;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class ConnectionsTest {
    @Test public void concurrentResourceLoadsAllocateDistinctProcessIds() throws Exception {
        Set<Integer> ids = ConcurrentHashMap.newKeySet();
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            java.util.List<Future<?>> tasks = new java.util.ArrayList<>();
            for (int thread = 0; thread < 4; thread++) {
                tasks.add(workers.submit(() -> {
                    for (int i = 0; i < 10000; i++) ids.add(Connections.getNewId());
                }));
            }
            for (Future<?> task : tasks) task.get(5, TimeUnit.SECONDS);
            assertEquals(40000, ids.size());
        } finally { workers.shutdownNow(); }
    }
}

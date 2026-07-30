package com.nextgis.maplib.map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LayerStorageAllocatorTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void reservesDistinctDirectoriesAcrossConcurrentBatch() throws Exception {
        File parent = temporaryFolder.newFolder("map");
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<File>> calls = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                calls.add(() -> LayerStorageAllocator.reserve(parent, "layer_"));
            }

            List<Future<File>> futures = executor.invokeAll(calls);
            Set<String> names = new HashSet<>();
            for (Future<File> future : futures) {
                File storage = future.get();
                assertTrue(storage.isDirectory());
                assertTrue(names.add(storage.getName()));
            }
            assertEquals(200, names.size());
        } finally {
            executor.shutdownNow();
        }
    }
}

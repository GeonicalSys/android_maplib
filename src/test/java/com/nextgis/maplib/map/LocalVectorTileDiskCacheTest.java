package com.nextgis.maplib.map;

import java.io.File;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class LocalVectorTileDiskCacheTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private String key(long generation) {
        return LocalVectorTileDiskCache.key("/project/layer", generation, "style", "[]", 12, 2, 3);
    }
    @Test public void aSeparateCacheInstanceCanReadTheSameContentKeyWithoutAnInMemoryCopy() {
        byte[] bytes = new byte[]{1, 4, 6, 9};
        new LocalVectorTileDiskCache(folder.getRoot()).put(key(1), bytes, 1024);
        assertArrayEquals(bytes, new LocalVectorTileDiskCache(folder.getRoot()).read(key(1), 64));
        assertNull(new LocalVectorTileDiskCache(folder.getRoot()).read(key(2), 64));
    }
    @Test public void geometryStyleAndEditorVisibilityHaveDifferentKeys() {
        assertNotEquals(key(1), key(2));
        assertNotEquals(key(1), LocalVectorTileDiskCache.key("/project/layer", 1, "new-style", "[]", 12, 2, 3));
        assertNotEquals(key(1), LocalVectorTileDiskCache.key("/project/layer", 1, "style", "[5]", 12, 2, 3));
        assertNotEquals(key(1), LocalVectorTileDiskCache.key("/other/layer", 1, "style", "[]", 12, 2, 3));
    }
    @Test public void cachedEntriesCannotExceedTheReadOrDiskBudget() {
        LocalVectorTileDiskCache cache = new LocalVectorTileDiskCache(folder.getRoot());
        cache.put(key(1), new byte[64], 1024);
        assertNull(cache.read(key(1), 32));
        cache.put(key(2), new byte[65], 1024);
        assertNull(cache.read(key(2), 1024));
        for (int i = 3; i < 40; i++) cache.put(key(i), new byte[64], 1024);
        long bytes = 0;
        for (File file : new File(folder.getRoot(), "local-vector-tiles-v1").listFiles()) {
            assertTrue(file.getName().endsWith(".pbf")); bytes += file.length();
        }
        assertTrue(bytes <= 1024);
    }
    @Test public void aCacheKeyCannotBecomeAFileSystemPath() {
        try {
            new LocalVectorTileDiskCache(folder.getRoot()).read("../source", 1024); fail();
        } catch (IllegalArgumentException expected) { }
    }
}

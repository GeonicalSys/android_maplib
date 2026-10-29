package com.nextgis.maplib.map;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class LocalVectorTileDiskCacheTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder(new File(
            System.getenv("TMPDIR") == null ? System.getProperty("java.io.tmpdir") : System.getenv("TMPDIR")));
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

    @Test public void hotWritesAndNewProvidersDoNotRepeatTheInventoryOrStatOldEntries() throws Exception {
        File app = folder.newFolder(); File root = root(app); assertTrue(root.mkdir());
        for (int i = 0; i < 128; i++) Files.write(tile(root, i).toPath(), new byte[4]);
        CountingFiles io = new CountingFiles();
        new LocalVectorTileDiskCache(app, io).put(rawKey(1000), new byte[4], 65536);
        assertEquals(1, io.inventories);
        int metadataAfterInventory = io.metadata;
        for (int i = 1001; i <= 1200; i++)
            new LocalVectorTileDiskCache(new File(app, "."), io).put(rawKey(i), new byte[4], 65536);
        assertEquals(1, io.inventories);
        assertEquals("One target check per new write, no old-file metadata walk", 200, io.metadata - metadataAfterInventory);
        assertBudget(root, 65536, 1024);
    }

    @Test public void theFileLimitStillAppliesWhenTheByteBudgetHasPlentyOfRoom() throws Exception {
        File app = folder.newFolder(); File root = root(app); assertTrue(root.mkdir());
        for (int i = 0; i < 1100; i++) {
            File file = tile(root, i); Files.write(file.toPath(), new byte[]{1});
            assertTrue(file.setLastModified(1000 + i));
        }
        LocalVectorTileDiskCache cache = new LocalVectorTileDiskCache(app);
        cache.put(rawKey(2000), new byte[]{9}, 65536);
        assertEquals(1024, count(root));
        assertArrayEquals(new byte[]{9}, cache.read(rawKey(2000), 64));
        assertFalse(tile(root, 0).exists());
        assertBudget(root, 65536, 1024);
    }

    @Test public void separateAndAliasedProvidersShareOneByteBudget() throws Exception {
        File app = folder.newFolder();
        for (int i = 0; i < 60; i++) {
            File alias = i % 2 == 0 ? app : new File(app, ".");
            new LocalVectorTileDiskCache(alias).put(rawKey(i), new byte[64], 1024);
            assertBudget(root(app), 1024, 1024);
        }
        assertEquals(16, count(root(app)));
    }

    @Test public void anOldProviderRebuildsAfterItsRootLedgerWasEvicted() throws Exception {
        File app = folder.newFolder(); CountingFiles io = new CountingFiles();
        LocalVectorTileDiskCache old = new LocalVectorTileDiskCache(app, io);
        for (int i = 0; i < 16; i++) old.put(rawKey(i), new byte[64], 1024);
        int previousInventories = io.inventories;
        for (int i = 0; i < 8; i++)
            new LocalVectorTileDiskCache(folder.newFolder()).put(rawKey(0), new byte[64], 1024);
        old.put(rawKey(100), new byte[64], 1024);
        assertEquals(previousInventories + 1, io.inventories);
        new LocalVectorTileDiskCache(app, io).put(rawKey(101), new byte[64], 1024);
        assertEquals(previousInventories + 1, io.inventories);
        assertBudget(root(app), 1024, 1024);
        assertEquals(16, count(root(app)));
    }

    @Test public void aReducedDeviceBudgetIsEnforcedByTheNextPut() throws Exception {
        File app = folder.newFolder(); LocalVectorTileDiskCache cache = new LocalVectorTileDiskCache(app);
        byte[] oneMiB = new byte[(int) RenderMemoryPolicy.MIB];
        for (int i = 0; i < 48; i++) cache.put(rawKey(i), oneMiB, 128 * RenderMemoryPolicy.MIB);
        assertEquals(48, count(root(app)));
        cache.put(rawKey(100), oneMiB, 32 * RenderMemoryPolicy.MIB);
        assertBudget(root(app), 32 * RenderMemoryPolicy.MIB, 1024);
        assertEquals(32, count(root(app)));
    }

    @Test public void duplicatePutsNeverChargeTheSameFileTwice() throws Exception {
        File app = folder.newFolder(); LocalVectorTileDiskCache cache = new LocalVectorTileDiskCache(app);
        for (int i = 0; i < 20; i++) cache.put(rawKey(0), new byte[64], 1024);
        for (int i = 1; i < 16; i++) cache.put(rawKey(i), new byte[64], 1024);
        assertEquals(16, count(root(app)));
        for (int i = 0; i < 16; i++) assertNotNull(cache.read(rawKey(i), 64));
        assertBudget(root(app), 1024, 1024);
    }

    @Test public void readTouchesRemainOrderedWithinOneFilesystemTimestamp() throws Exception {
        File app = folder.newFolder(); CountingFiles io = new CountingFiles(); io.clock = 1000;
        LocalVectorTileDiskCache cache = new LocalVectorTileDiskCache(app, io);
        for (int i = 0; i < 16; i++) cache.put(rawKey(i), new byte[]{(byte)i}, 16);
        assertNotNull(cache.read(rawKey(0), 1));
        cache.put(rawKey(100), new byte[]{99}, 16);
        assertArrayEquals(new byte[]{0}, cache.read(rawKey(0), 1));
        assertNull(cache.read(rawKey(1), 1));
        assertBudget(root(app), 16, 1024);
    }

    @Test public void externalDeletionWithAnUnchangedDirectoryTimestampCannotUndercount() throws Exception {
        File app = folder.newFolder(); LocalVectorTileDiskCache cache = new LocalVectorTileDiskCache(app);
        for (int i = 0; i < 16; i++) cache.put(rawKey(i), new byte[64], 1024);
        File root = root(app); long stamp = root.lastModified();
        for (int i = 0; i < 14; i++) assertTrue(tile(root, i).delete());
        assertTrue(root.setLastModified(stamp));
        for (int i = 100; i < 140; i++) {
            cache.put(rawKey(i), new byte[64], 1024);
            assertBudget(root, 1024, 1024);
        }
        assertEquals(16, count(root));
        assertNotNull(cache.read(rawKey(139), 64));
    }

    @Test public void removalAndRecreationOfTheWholeCacheDirectoryIsSafe() throws Exception {
        File app = folder.newFolder(); LocalVectorTileDiskCache cache = new LocalVectorTileDiskCache(app);
        for (int i = 0; i < 16; i++) cache.put(rawKey(i), new byte[64], 1024);
        File root = root(app); long stamp = root.lastModified();
        for (File file : root.listFiles()) assertTrue(file.delete());
        assertTrue(root.delete()); assertTrue(root.mkdir()); assertTrue(root.setLastModified(stamp));
        for (int i = 100; i < 132; i++) cache.put(rawKey(i), new byte[64], 1024);
        assertBudget(root, 1024, 1024);
        assertEquals(16, count(root));
    }

    @Test public void changedDirectoryContentsAreReconciledAndUnknownFilesArePreserved() throws Exception {
        File app = folder.newFolder(); CountingFiles io = new CountingFiles();
        LocalVectorTileDiskCache cache = new LocalVectorTileDiskCache(app, io);
        for (int i = 0; i < 16; i++) cache.put(rawKey(i), new byte[64], 1024);
        File root = root(app); long stamp = root.lastModified();
        File unrelated = new File(root, "source.sqlite"); Files.write(unrelated.toPath(), new byte[]{6, 7});
        Files.write(tile(root, 500).toPath(), new byte[64]);
        assertTrue(root.setLastModified(stamp + 2000));
        cache.put(rawKey(501), new byte[64], 1024);
        assertEquals(1, io.inventories);
        assertArrayEquals(new byte[]{6, 7}, Files.readAllBytes(unrelated.toPath()));
        assertBudget(root, 1024, 1024);
    }

    @Test public void failedPublicationAndPartialWritesDoNotChargeOrLeaveFinalFiles() throws Exception {
        File app = folder.newFolder(); CountingFiles io = new CountingFiles();
        LocalVectorTileDiskCache cache = new LocalVectorTileDiskCache(app, io);
        io.failPublish = true;
        cache.put(rawKey(100), new byte[64], 1024);
        assertEquals(0, root(app).listFiles().length);
        io.failPublish = false; io.failWrite = true;
        cache.put(rawKey(101), new byte[64], 1024);
        assertEquals(0, root(app).listFiles().length);
        io.failWrite = false;
        for (int i = 0; i < 16; i++) cache.put(rawKey(i), new byte[64], 1024);
        assertEquals(16, count(root(app)));
        assertBudget(root(app), 1024, 1024);
    }

    @Test public void failedEvictionSkipsPublicationAndCanBeRetriedWithoutLosingAccounting() throws Exception {
        File app = folder.newFolder(); CountingFiles io = new CountingFiles(); io.clock = 1000;
        LocalVectorTileDiskCache cache = new LocalVectorTileDiskCache(app, io);
        for (int i = 0; i < 16; i++) cache.put(rawKey(i), new byte[64], 1024);
        io.undeletable = rawKey(0) + ".pbf";
        cache.put(rawKey(100), new byte[64], 1024);
        assertFalse(tile(root(app), 100).exists()); assertEquals(16, count(root(app)));
        io.undeletable = null;
        cache.put(rawKey(100), new byte[64], 1024);
        assertNotNull(cache.read(rawKey(100), 64));
        assertBudget(root(app), 1024, 1024);
    }

    @Test public void anIterationIoFailureIsAnOptionalCacheMissAndClosesTheStream() throws Exception {
        File app = folder.newFolder(); assertTrue(root(app).mkdir());
        AtomicBoolean fail = new AtomicBoolean(true); AtomicBoolean closed = new AtomicBoolean();
        CountingFiles io = new CountingFiles() {
            @Override DirectoryStream<Path> inventory(File directory) throws IOException {
                DirectoryStream<Path> real = super.inventory(directory);
                if (!fail.get()) return real;
                return new DirectoryStream<Path>() {
                    public Iterator<Path> iterator() { return new Iterator<Path>() {
                        public boolean hasNext() { throw new DirectoryIteratorException(new IOException("evicted")); }
                        public Path next() { throw new AssertionError(); }
                    }; }
                    public void close() throws IOException { closed.set(true); real.close(); }
                };
            }
        };
        LocalVectorTileDiskCache cache = new LocalVectorTileDiskCache(app, io);
        cache.put(rawKey(0), new byte[64], 1024);
        assertTrue(closed.get()); assertEquals(0, count(root(app)));
        fail.set(false); cache.put(rawKey(0), new byte[64], 1024);
        assertArrayEquals(new byte[64], cache.read(rawKey(0), 64));
    }

    @Test public void cancellationAfterWritingTheTemporaryFileNeverPublishesIt() throws Exception {
        File app = folder.newFolder(); AtomicBoolean current = new AtomicBoolean(true);
        CountingFiles io = new CountingFiles() {
            @Override void write(File file, byte[] bytes) throws IOException {
                super.write(file, bytes); current.set(false);
            }
        };
        new LocalVectorTileDiskCache(app, io).put(rawKey(0), new byte[64], 1024, current::get);
        assertEquals(0, root(app).listFiles().length);
        assertEquals(0, io.publications);
    }

    @Test public void anAlreadyCancelledPutDoesNotCreateTheCacheDirectory() throws Exception {
        File app = folder.newFolder(); CountingFiles io = new CountingFiles();
        new LocalVectorTileDiskCache(app, io).put(rawKey(0), new byte[64], 1024, () -> false);
        assertFalse(root(app).exists()); assertEquals(0, io.metadata); assertEquals(0, io.inventories);
    }

    @Test public void anOversizedReplacementCannotOverflowOrEvadeTheLedger() throws Exception {
        File app = folder.newFolder(); LocalVectorTileDiskCache cache = new LocalVectorTileDiskCache(app);
        cache.put(rawKey(0), new byte[64], 1024);
        try (RandomAccessFile changed = new RandomAccessFile(tile(root(app), 0), "rw")) {
            changed.setLength(4 * RenderMemoryPolicy.MIB + 1);
        }
        cache.put(rawKey(0), new byte[64], 1024);
        assertArrayEquals(new byte[64], cache.read(rawKey(0), 64));
        assertBudget(root(app), 1024, 1024);
    }

    @Test public void aShortReadInvalidatesAccountingBeforeAnotherPublication() throws Exception {
        File app = folder.newFolder(); AtomicBoolean shortRead = new AtomicBoolean();
        CountingFiles io = new CountingFiles() {
            @Override BasicFileAttributes attributes(File file) throws IOException {
                BasicFileAttributes real = super.attributes(file);
                if (!shortRead.get()) return real;
                return new BasicFileAttributes() {
                    public FileTime lastModifiedTime() { return real.lastModifiedTime(); }
                    public FileTime lastAccessTime() { return real.lastAccessTime(); }
                    public FileTime creationTime() { return real.creationTime(); }
                    public boolean isRegularFile() { return real.isRegularFile(); }
                    public boolean isDirectory() { return real.isDirectory(); }
                    public boolean isSymbolicLink() { return real.isSymbolicLink(); }
                    public boolean isOther() { return real.isOther(); }
                    public long size() { return real.size() + 8; }
                    public Object fileKey() { return real.fileKey(); }
                };
            }
        };
        LocalVectorTileDiskCache cache = new LocalVectorTileDiskCache(app, io);
        cache.put(rawKey(0), new byte[64], 1024);
        shortRead.set(true); assertNull(cache.read(rawKey(0), 1024));
        shortRead.set(false); cache.put(rawKey(1), new byte[64], 1024);
        assertEquals(1, io.inventories);
        assertBudget(root(app), 1024, 1024);
    }

    @Test public void cancellationBetweenEvictionsStopsBeforeNewPublication() throws Exception {
        File app = folder.newFolder(); AtomicBoolean current = new AtomicBoolean(true);
        AtomicBoolean cancelOnDelete = new AtomicBoolean(false);
        CountingFiles io = new CountingFiles() {
            @Override boolean delete(File file) {
                boolean deleted = super.delete(file);
                if (cancelOnDelete.get() && file.getName().endsWith(".pbf")) current.set(false);
                return deleted;
            }
        };
        LocalVectorTileDiskCache cache = new LocalVectorTileDiskCache(app, io);
        for (int i = 0; i < 16; i++) cache.put(rawKey(i), new byte[64], 1024);
        cancelOnDelete.set(true);
        cache.put(rawKey(100), new byte[16], 256, current::get);
        assertEquals(15, count(root(app))); assertFalse(tile(root(app), 100).exists());
        cancelOnDelete.set(false); cache.put(rawKey(100), new byte[16], 256);
        assertBudget(root(app), 256, 1024);
    }

    @Test public void aThrowingPostPublicationGuardRemovesItsOwnedFinalFile() throws Exception {
        File app = folder.newFolder(); AtomicBoolean current = new AtomicBoolean(true);
        CountingFiles io = new CountingFiles() {
            @Override boolean publish(File temporary, File target) {
                boolean published = super.publish(temporary, target); current.set(false); return published;
            }
        };
        try {
            new LocalVectorTileDiskCache(app, io).put(rawKey(0), new byte[64], 1024, () -> {
                if (!current.get()) throw new CancellationException("obsolete");
                return true;
            });
            fail();
        } catch (CancellationException expected) { }
        assertEquals(0, root(app).listFiles().length);
    }

    @Test public void aGuardThatBecomesFalseWhileWaitingForTheSharedLockDoesNoIo() throws Exception {
        File app = folder.newFolder(); CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1); CountDownLatch waiting = new CountDownLatch(1);
        AtomicBoolean current = new AtomicBoolean(true);
        CountingFiles blocking = new CountingFiles() {
            @Override void write(File file, byte[] bytes) throws IOException {
                writing.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test timeout"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
                super.write(file, bytes);
            }
        };
        CountingFiles stale = new CountingFiles(); ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> new LocalVectorTileDiskCache(app, blocking).put(rawKey(0), new byte[64], 1024));
            assertTrue(writing.await(5, TimeUnit.SECONDS));
            Future<?> second = pool.submit(() -> new LocalVectorTileDiskCache(app, stale).put(rawKey(1), new byte[64], 1024, () -> {
                boolean valid = current.get(); waiting.countDown(); return valid;
            }));
            assertTrue(waiting.await(5, TimeUnit.SECONDS)); current.set(false); release.countDown();
            first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
            assertEquals(0, stale.metadata); assertEquals(0, stale.publications);
            assertTrue(tile(root(app), 0).isFile()); assertFalse(tile(root(app), 1).exists());
        } finally {
            release.countDown(); pool.shutdownNow();
            assertTrue("Owned writer threads stopped before temporary-folder cleanup", pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void concurrentProvidersNeverPublishBeyondTheSharedPhysicalBudget() throws Exception {
        File app = folder.newFolder(); ExecutorService pool = Executors.newFixedThreadPool(4);
        CountingFiles io = new CountingFiles() {
            @Override boolean publish(File temporary, File target) {
                assertTrue(bytes(root(app)) + temporary.length() <= 1024);
                return super.publish(temporary, target);
            }
        };
        try {
            Future<?>[] jobs = new Future<?>[4];
            for (int worker = 0; worker < jobs.length; worker++) {
                final int id = worker;
                jobs[worker] = pool.submit(() -> {
                    LocalVectorTileDiskCache cache = new LocalVectorTileDiskCache(new File(app, "."), io);
                    for (int i = 0; i < 30; i++) cache.put(rawKey(id * 100 + i), new byte[64], 1024);
                });
            }
            for (Future<?> job : jobs) job.get(10, TimeUnit.SECONDS);
            assertEquals(16, count(root(app))); assertBudget(root(app), 1024, 1024);
        } finally {
            pool.shutdownNow();
            assertTrue("Owned provider threads stopped before temporary-folder cleanup", pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static File root(File app) { return new File(app, "local-vector-tiles-v1"); }
    private static File tile(File root, int id) { return new File(root, rawKey(id) + ".pbf"); }
    private static String rawKey(int id) {
        char[] out = new char[64]; Arrays.fill(out, '0'); String suffix = Integer.toHexString(id);
        suffix.getChars(0, suffix.length(), out, 64 - suffix.length()); return new String(out);
    }
    private static int count(File root) {
        File[] files = root.listFiles(file -> file.isFile() && file.getName().endsWith(".pbf"));
        return files == null ? 0 : files.length;
    }
    private static long bytes(File root) {
        long total = 0; File[] files = root.listFiles();
        if (files != null) for (File file : files) if (file.isFile() && file.getName().endsWith(".pbf")) total += file.length();
        return total;
    }
    private static void assertBudget(File root, long maximumBytes, int maximumFiles) {
        assertTrue("Physical complete-PBF byte budget", bytes(root) <= maximumBytes);
        assertTrue("Physical complete-PBF file budget", count(root) <= maximumFiles);
    }

    private static class CountingFiles extends LocalVectorTileDiskCache.FileAccess {
        int inventories, metadata, publications;
        long clock = -1;
        boolean failPublish, failWrite;
        String undeletable;
        @Override DirectoryStream<Path> inventory(File directory) throws IOException {
            inventories++; return super.inventory(directory);
        }
        @Override BasicFileAttributes attributes(File file) throws IOException {
            metadata++; return super.attributes(file);
        }
        @Override void write(File file, byte[] bytes) throws IOException {
            if (failWrite) { super.write(file, new byte[]{1}); throw new IOException("disk full"); }
            super.write(file, bytes);
        }
        @Override boolean publish(File temporary, File target) {
            publications++;
            if (failPublish) return false;
            boolean published = super.publish(temporary, target);
            if (published && clock >= 0) assertTrue(target.setLastModified(clock));
            return published;
        }
        @Override boolean delete(File file) {
            return !file.getName().equals(undeletable) && super.delete(file);
        }
        @Override long nowMillis() { return clock >= 0 ? clock : super.nowMillis(); }
    }
}

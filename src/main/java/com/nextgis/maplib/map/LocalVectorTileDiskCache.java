package com.nextgis.maplib.map;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;

/** Bounded derived tiles. SQLite remains authoritative; cache loss never removes project data. */
final class LocalVectorTileDiskCache {
    private static final Object DISK_LOCK = new Object();
    private static final int MAX_FILES = 1024;
    private static final int MAX_ROOTS = 8;
    private static final long MAX_ENTRY_BYTES = 4 * RenderMemoryPolicy.MIB;
    private static final BooleanSupplier ALWAYS_CURRENT = () -> true;
    // Resolve this registry on every operation: an old provider must never retain an evicted ledger.
    private static final LinkedHashMap<File, Index> INDEXES = new LinkedHashMap<>(16, .75f, true);
    private static final FileAccess FILES = new FileAccess();

    private final File root;
    private final FileAccess files;

    LocalVectorTileDiskCache(File appCache) { this(appCache, FILES); }

    LocalVectorTileDiskCache(File appCache, FileAccess files) {
        File canonical = null;
        if (appCache != null) {
            try { canonical = new File(appCache, "local-vector-tiles-v1").getCanonicalFile(); }
            catch (IOException | SecurityException unavailable) { /* Optional cache is unavailable. */ }
        }
        root = canonical;
        this.files = files;
    }

    static String key(String layerPath, long geometryGeneration, String style, String hidden, int z, int x, int y) {
        try {
            String input = layerPath + '\n' + geometryGeneration + '\n' + style + '\n' + hidden + '\n' + z + '/' + x + '/' + y;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte value : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    byte[] read(String key, long maxEntryBytes) {
        requireKey(key);
        if (root == null) return null;
        synchronized (DISK_LOCK) {
            Index index = INDEXES.get(root);
            String name = key + ".pbf";
            File file = new File(root, name);
            try {
                BasicFileAttributes attributes = files.attributes(file);
                long length = attributes.size();
                if (index != null && index.initialized) {
                    Entry known = index.entries.get(name);
                    if (known == null || !attributes.isRegularFile() || known.bytes != length)
                        index.initialized = false;
                }
                if (!attributes.isRegularFile() || length <= 0
                        || length > Math.min(maxEntryBytes, MAX_ENTRY_BYTES)) return null;
                byte[] bytes = new byte[(int) length];
                try (FileInputStream input = new FileInputStream(file)) {
                    int used = 0;
                    while (used < bytes.length) {
                        int count = input.read(bytes, used, bytes.length - used);
                        if (count < 0) {
                            if (index != null) index.initialized = false;
                            return null;
                        }
                        used += count;
                    }
                }
                long now = files.nowMillis();
                boolean touched = file.setLastModified(now);
                if (index != null && index.initialized) {
                    Entry known = index.entries.get(name);
                    if (known == null || known.bytes != length) index.initialized = false;
                    else if (touched) index.add(new Entry(file, length, now, ++index.touchSequence));
                }
                return bytes;
            } catch (NoSuchFileException missing) {
                if (index != null) index.remove(name);
                return null;
            } catch (IOException | SecurityException unavailable) {
                if (index != null) index.initialized = false;
                return null;
            }
        }
    }

    void put(String key, byte[] bytes, long maxBytes) {
        put(key, bytes, maxBytes, ALWAYS_CURRENT);
    }

    void put(String key, byte[] bytes, long maxBytes, BooleanSupplier current) {
        requireKey(key);
        if (root == null || bytes.length == 0 || maxBytes <= 0
                || bytes.length > Math.min(MAX_ENTRY_BYTES, maxBytes / 16)
                || !current.getAsBoolean()) return;
        synchronized (DISK_LOCK) {
            if (!current.getAsBoolean()) return;
            Index index = index();
            File temporary = null;
            try {
                if (!root.isDirectory()) {
                    if (!root.mkdirs() && !root.isDirectory()) {
                        index.initialized = false;
                        return;
                    }
                    index.clear();
                    index.initialized = true;
                    index.directoryStamp = root.lastModified();
                }
                // The private namespace has only same-process writers. OS cache deletion can
                // overestimate a ledger until dirty/pressure reconciliation, never add bytes.
                if (!index.initialized || index.directoryStamp != root.lastModified()) {
                    if (!inventory(index, maxBytes, current)) return;
                }
                if (!trim(index, maxBytes, MAX_FILES, current)) return;
                File target = new File(root, key + ".pbf");
                BasicFileAttributes existing = null;
                try { existing = files.attributes(target); }
                catch (NoSuchFileException missing) { /* The normal new-tile path. */ }
                if (existing != null) {
                    if (!existing.isRegularFile()) return;
                    if (existing.size() <= 0 || existing.size() > MAX_ENTRY_BYTES) {
                        if (!current.getAsBoolean()) return;
                        if (!files.delete(target) && target.exists()) {
                            index.initialized = false;
                            return;
                        }
                        index.remove(target.getName());
                        index.directoryStamp = root.lastModified();
                    } else {
                        // Reconcile this one duplicate before enforcing a reduced byte budget.
                        index.add(new Entry(target, existing.size(),
                                existing.lastModifiedTime().toMillis(), ++index.touchSequence));
                        if (!trim(index, maxBytes, MAX_FILES, current)) return;
                        if (index.entries.containsKey(target.getName())) return;
                    }
                }
                if (!trim(index, maxBytes - bytes.length, MAX_FILES - 1, current)
                        || !current.getAsBoolean()) return;
                temporary = files.createTemporary(root);
                if (!current.getAsBoolean()) return;
                files.write(temporary, bytes);
                if (!current.getAsBoolean()) return;
                if (!files.publish(temporary, target)) return;
                // Charge only after complete bytes become the final PBF.
                index.add(new Entry(target, bytes.length, files.nowMillis(), ++index.touchSequence));
                boolean retain = false;
                try { retain = current.getAsBoolean(); }
                finally {
                    if (!retain) {
                        if (files.delete(target) || !target.exists()) index.remove(target.getName());
                        else index.initialized = false;
                    }
                }
                index.directoryStamp = root.lastModified();
            } catch (IOException | SecurityException | DirectoryIteratorException unavailable) {
                index.initialized = false;
                // Cache failure never removes authoritative data or rejects the built tile.
            } finally {
                if (temporary != null) {
                    try {
                        if (temporary.exists() && !files.delete(temporary))
                            android.util.Log.w("LocalVectorTiles", "Could not finalize an owned cache intermediate");
                        // Only our excluded .part file changed the directory after reconciliation.
                        if (index.initialized) index.directoryStamp = root.lastModified();
                    } catch (SecurityException unavailable) { index.initialized = false; }
                }
            }
        }
    }

    private Index index() {
        Index index = INDEXES.get(root);
        if (index == null) {
            index = new Index();
            INDEXES.put(root, index);
            if (INDEXES.size() > MAX_ROOTS) INDEXES.remove(INDEXES.keySet().iterator().next());
        }
        return index;
    }

    private boolean inventory(Index index, long maxBytes, BooleanSupplier current) throws IOException {
        index.clear();
        try (DirectoryStream<Path> paths = files.inventory(root)) {
            for (Path path : paths) {
                if (!current.getAsBoolean()) return false;
                String name = path.getFileName().toString();
                if (!isTileName(name)) continue;
                File file = path.toFile();
                BasicFileAttributes attributes;
                try { attributes = files.attributes(file); }
                catch (NoSuchFileException evicted) { continue; }
                if (!attributes.isRegularFile()) continue;
                if (attributes.size() <= 0 || attributes.size() > MAX_ENTRY_BYTES) {
                    if (!current.getAsBoolean()) return false;
                    if (!files.delete(file) && file.exists()) return false;
                    continue;
                }
                index.add(new Entry(file, attributes.size(),
                        attributes.lastModifiedTime().toMillis(), 0));
                // Streaming reconciliation retains at most 1025 metadata entries temporarily.
                if (!trim(index, maxBytes, MAX_FILES, current)) return false;
            }
        }
        index.directoryStamp = root.lastModified();
        index.initialized = true;
        return true;
    }

    private boolean trim(Index index, long allowedBytes, int allowedFiles, BooleanSupplier current) {
        while (index.bytes > allowedBytes || index.entries.size() > allowedFiles) {
            if (!current.getAsBoolean()) return false;
            if (index.oldest.isEmpty()) {
                index.initialized = false;
                return false;
            }
            Entry entry = index.oldest.first();
            if (!files.delete(entry.file) && entry.file.exists()) {
                index.initialized = false;
                return false;
            }
            index.remove(entry.file.getName());
            index.directoryStamp = root.lastModified();
        }
        return true;
    }

    private static void requireKey(String key) {
        if (!isHexKey(key)) throw new IllegalArgumentException("Invalid derived-tile cache key");
    }

    private static boolean isTileName(String name) {
        return name.length() == 68 && name.endsWith(".pbf") && isHexKey(name.substring(0, 64));
    }

    private static boolean isHexKey(String key) {
        if (key == null || key.length() != 64) return false;
        for (int i = 0; i < 64; i++) {
            char character = key.charAt(i);
            if (!(character >= 'a' && character <= 'f' || character >= '0' && character <= '9')) return false;
        }
        return true;
    }

    private static final class Entry {
        final File file;
        final long bytes, modified, order;
        Entry(File file, long bytes, long modified, long order) {
            this.file = file;
            this.bytes = bytes;
            this.modified = modified;
            this.order = order;
        }
    }

    private static final class Index {
        final Map<String, Entry> entries = new HashMap<>();
        final TreeSet<Entry> oldest = new TreeSet<>(Comparator
                .comparingLong((Entry entry) -> entry.modified)
                .thenComparingLong(entry -> entry.order)
                .thenComparing(entry -> entry.file.getName()));
        long bytes, directoryStamp, touchSequence;
        boolean initialized;

        void clear() {
            entries.clear();
            oldest.clear();
            bytes = 0;
            touchSequence = 0;
            initialized = false;
        }

        void add(Entry entry) {
            remove(entry.file.getName());
            entries.put(entry.file.getName(), entry);
            oldest.add(entry);
            bytes += entry.bytes;
        }

        void remove(String name) {
            Entry entry = entries.remove(name);
            if (entry != null) {
                oldest.remove(entry);
                bytes -= entry.bytes;
            }
        }
    }

    /** Real-filesystem defaults; package seam permits deterministic failure/budget tests. */
    static class FileAccess {
        DirectoryStream<Path> inventory(File directory) throws IOException {
            return Files.newDirectoryStream(directory.toPath());
        }
        BasicFileAttributes attributes(File file) throws IOException {
            return Files.readAttributes(file.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        }
        File createTemporary(File directory) throws IOException {
            return File.createTempFile("tile-", ".part", directory);
        }
        void write(File file, byte[] bytes) throws IOException {
            try (FileOutputStream stream = new FileOutputStream(file)) { stream.write(bytes); }
        }
        boolean publish(File temporary, File target) { return temporary.renameTo(target); }
        boolean delete(File file) { return file.delete(); }
        long nowMillis() { return System.currentTimeMillis(); }
    }
}

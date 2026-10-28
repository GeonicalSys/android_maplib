package com.nextgis.maplib.map;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;

/** Bounded derived tiles. SQLite remains authoritative; cache loss never removes project data. */
final class LocalVectorTileDiskCache {
    private static final Object DISK_LOCK = new Object();
    private final File root;
    LocalVectorTileDiskCache(File appCache) { root = new File(appCache, "local-vector-tiles-v1"); }

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
        if (!key.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid derived-tile cache key");
        synchronized (DISK_LOCK) {
            File file = new File(root, key + ".pbf");
            long length = file.length();
            if (!file.isFile() || length <= 0 || length > Math.min(maxEntryBytes, 4 * RenderMemoryPolicy.MIB)) return null;
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] bytes = new byte[(int) length]; int used = 0;
                while (used < bytes.length) {
                    int count = input.read(bytes, used, bytes.length - used);
                    if (count < 0) return null;
                    used += count;
                }
                file.setLastModified(System.currentTimeMillis());
                return bytes;
            } catch (IOException miss) { return null; }
        }
    }

    void put(String key, byte[] bytes, long maxBytes) {
        if (!key.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid derived-tile cache key");
        if (bytes.length == 0 || bytes.length > Math.min(4 * RenderMemoryPolicy.MIB, maxBytes / 16)) return;
        synchronized (DISK_LOCK) {
            File temporary = null;
            try {
                if (!root.isDirectory() && !root.mkdirs()) return;
                File target = new File(root, key + ".pbf");
                if (target.isFile()) return;
                if (!prune(maxBytes - bytes.length)) return;
                temporary = File.createTempFile("tile-", ".part", root);
                try (FileOutputStream stream = new FileOutputStream(temporary)) { stream.write(bytes); }
                if (!temporary.renameTo(target)) return;
            } catch (IOException unavailable) {
                // Disk full / Android cache eviction: the guarded tile can still be rendered.
            } finally {
                if (temporary != null && temporary.exists() && !temporary.delete())
                    android.util.Log.w("LocalVectorTiles", "Could not finalize an owned cache intermediate");
            }
        }
    }

    private boolean prune(long allowedBytes) {
        File[] files = root.listFiles(file -> file.isFile() && file.getName().matches("[a-f0-9]{64}\\.pbf"));
        if (files == null) return false;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        long size = 0;
        for (File file : files) size += file.length();
        int count = files.length;
        for (File file : files) {
            if (size <= allowedBytes && count < 1024) break;
            long bytes = file.length();
            if (file.delete()) { size -= bytes; count--; }
        }
        return size <= allowedBytes && count < 1024;
    }
}

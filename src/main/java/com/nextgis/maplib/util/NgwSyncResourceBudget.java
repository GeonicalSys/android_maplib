package com.nextgis.maplib.util;

import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Fail before consuming the reserve needed to roll back a sync or finish its backup. */
public final class NgwSyncResourceBudget {
    public static final long DISK_RESERVE_BYTES = 32L * 1024 * 1024;
    private static final int CHUNK = 64 * 1024;
    private NgwSyncResourceBudget() { }

    public static void requireSpace(File directory, long upcomingBytes) throws IOException {
        if (directory == null || upcomingBytes < 0) throw new IOException("Invalid sync storage budget");
        NgwSyncIo.checkInterrupted();
        long available = directory.getUsableSpace();
        if (available <= DISK_RESERVE_BYTES || upcomingBytes > available - DISK_RESERVE_BYTES)
            throw new LimitException("Not enough space for sync; original data is retained");
    }

    /** Leave room for WKT tokens, parsed points, attributes and the SQLite encoding together. */
    public static long featureLimit(long maximumHeap) {
        return Math.max(16 * 1024L, Math.min(8 * 1024 * 1024L, maximumHeap / 64));
    }

    public static long featureLimit() { return featureLimit(Runtime.getRuntime().maxMemory()); }

    public static void requireFeatureBytes(long bytes) throws IOException {
        if (bytes < 0 || bytes > featureLimit())
            throw new LimitException("A feature exceeds the sync memory budget; original data is retained");
    }

    public static void requireHeap() throws IOException {
        Runtime runtime = Runtime.getRuntime();
        long headroom = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
        if (headroom < 4 * featureLimit())
            throw new LimitException("Not enough memory for sync; original data is retained");
    }

    /** Checks the actual target filesystem even when Content-Length is absent or wrong. */
    public static OutputStream reserving(OutputStream output, File directory) {
        return new FilterOutputStream(output) {
            @Override public void write(int value) throws IOException {
                requireSpace(directory, 1); out.write(value);
            }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                if (offset < 0 || length < 0 || offset > bytes.length - length)
                    throw new IndexOutOfBoundsException();
                while (length > 0) {
                    int count = Math.min(CHUNK, length);
                    requireSpace(directory, count);
                    out.write(bytes, offset, count);
                    offset += count; length -= count;
                }
            }
        };
    }

    /** Error text is diagnostic classification input, never an unbounded response allocation. */
    public static String readErrorText(InputStream input) throws IOException {
        if (input == null) return null;
        try (InputStream source = input; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            while (bytes.size() < CHUNK) {
                NgwSyncIo.checkInterrupted();
                int count = source.read(buffer, 0, Math.min(buffer.length, CHUNK - bytes.size()));
                if (count == -1) break;
                if (count != 0) bytes.write(buffer, 0, count);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    public static final class LimitException extends IOException {
        public LimitException(String message) { super(message); }
    }
}

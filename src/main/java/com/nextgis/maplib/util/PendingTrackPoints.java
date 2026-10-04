package com.nextgis.maplib.util;

import android.content.ContentValues;
import android.util.AtomicFile;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Small ordered write-ahead spool, owned by one map. Failed entries stay available for retry. */
public final class PendingTrackPoints {
    public static final int CAPACITY = 2048;
    private final File directory;
    private final ArrayDeque<Entry> pending = new ArrayDeque<>();
    private final Object drainLock = new Object();
    private long sequence;

    public interface Writer {
        long insert(String operationId, ContentValues values);
        void acknowledged(String operationId);
    }

    public static final class DrainResult {
        public int count;
        public long lastRowId = Constants.NOT_FOUND;
        public ContentValues lastValues;
        public Exception failure;
    }

    private static final class Entry {
        final String operation;
        final ContentValues values;
        final File file;
        final long sequence;
        boolean durable;
        Entry(String operation, ContentValues values, File file, long sequence, boolean durable) {
            this.operation = operation;
            this.values = values;
            this.file = file;
            this.sequence = sequence;
            this.durable = durable;
        }
    }

    public PendingTrackPoints(File mapDirectory) throws IOException {
        directory = new File(mapDirectory, "pending-track-points");
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Cannot open pending track directory");
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".json")
                || name.endsWith(".json.bak") || name.endsWith(".json.new"));
        if (files == null) throw new IOException("Cannot enumerate pending track points");
        Arrays.sort(files, Comparator.comparing(File::getName));
        java.util.Set<String> loaded = new java.util.HashSet<>();
        for (File source : files) {
            String baseName = source.getName().replaceFirst("\\.(bak|new)$", "");
            if (!loaded.add(baseName)) continue;
            File file = new File(directory, baseName);
            try {
                // AtomicFile recovers .bak. A complete first-write .new can also be recovered.
                byte[] bytes = !file.exists() && source.getName().endsWith(".new")
                        && !new File(file + ".bak").exists()
                        ? java.nio.file.Files.readAllBytes(source.toPath())
                        : new AtomicFile(file).readFully();
                JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                long order = json.getLong("sequence");
                sequence = Math.max(sequence, order);
                ContentValues values = new ContentValues();
                JSONObject fields = json.getJSONObject("values");
                Iterator<String> names = fields.keys();
                while (names.hasNext()) {
                    String name = names.next();
                    Object value = fields.get(name);
                    if (value == JSONObject.NULL) values.putNull(name);
                    else if (value instanceof Float || value instanceof Double)
                        values.put(name, ((Number) value).doubleValue());
                    else if (value instanceof Number) values.put(name, ((Number) value).longValue());
                    else values.put(name, value.toString());
                }
                Entry entry = new Entry(json.getString("operation"), values, file, order,
                        file.exists());
                if (!entry.durable) write(entry);
                pending.addLast(entry);
            } catch (JSONException | RuntimeException error) {
                // Keep a corrupt journal on disk for diagnostics; never silently discard it.
                throw new IOException("Cannot decode pending track point", error);
            }
        }
        if (pending.size() > CAPACITY) throw new IOException("Pending track journal exceeds capacity");
    }

    public synchronized int size() { return pending.size(); }

    /** On a disk error the entry remains in RAM; the caller must show failure and retry. */
    public synchronized void append(ContentValues values) throws IOException {
        if (pending.size() >= CAPACITY) throw new IOException("Pending track journal is full");
        String operation = UUID.randomUUID().toString();
        long order = ++sequence;
        File file = new File(directory, String.format(java.util.Locale.ROOT,
                "%020d_%s.json", order, operation));
        Entry entry = new Entry(operation, new ContentValues(values), file, order, false);
        pending.addLast(entry);
        write(entry);
    }

    private void write(Entry entry) throws IOException {
        AtomicFile atomic = new AtomicFile(entry.file);
        FileOutputStream output = null;
        try {
            JSONObject json = new JSONObject();
            json.put("sequence", entry.sequence);
            json.put("operation", entry.operation);
            JSONObject values = new JSONObject();
            for (Map.Entry<String, Object> value : entry.values.valueSet())
                values.put(value.getKey(), value.getValue() == null ? JSONObject.NULL : value.getValue());
            json.put("values", values);
            output = atomic.startWrite();
            output.write(json.toString().getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(output);
            entry.durable = true;
        } catch (JSONException | RuntimeException | IOException error) {
            if (output != null) atomic.failWrite(output);
            throw new IOException("Cannot checkpoint pending track point", error);
        }
    }

    /** Stop at the first failed write/acknowledgement, preserving point order. */
    public DrainResult drain(Writer writer) {
        synchronized (drainLock) { return drainInOrder(writer); }
    }

    private DrainResult drainInOrder(Writer writer) {
        DrainResult result = new DrainResult();
        while (true) {
            Entry entry;
            synchronized (this) { entry = pending.peekFirst(); }
            if (entry == null) return result;
            try {
                if (!entry.durable) {
                    try { write(entry); }
                    catch (IOException ignored) {
                        // SQLite may still accept a write when the filesystem cannot grow.
                    }
                }
                long id = writer.insert(entry.operation, new ContentValues(entry.values));
                if (id < 0) throw new IOException("Track point insert was not acknowledged");
                for (String suffix : new String[]{"", ".bak", ".new"}) {
                    File file = new File(entry.file + suffix);
                    if (file.exists() && !file.delete())
                        throw new IOException("Cannot acknowledge pending track point");
                }
                synchronized (this) { pending.removeFirst(); }
                result.count++;
                result.lastRowId = id;
                result.lastValues = new ContentValues(entry.values);
            } catch (IOException | RuntimeException error) {
                result.failure = error;
                return result;
            }
            // After the file is removed, a stale SQLite idempotency row is safe to retain.
            try { writer.acknowledged(entry.operation); }
            catch (RuntimeException cleanupError) {
                android.util.Log.w(Constants.TAG, "Track journal cleanup deferred", cleanupError);
            }
        }
    }
}

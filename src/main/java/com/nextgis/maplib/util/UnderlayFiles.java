package com.nextgis.maplib.util;

import org.json.JSONException;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Small, durable metadata operations; payloads are streamed separately. */
public final class UnderlayFiles {
    private UnderlayFiles() { }

    public static JSONObject readJson(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            return new JSONObject(new String(readBounded(input, 4 * 1024 * 1024), StandardCharsets.UTF_8));
        } catch (JSONException e) { throw new IOException("Invalid underlay metadata", e); }
    }

    public static void writeJson(File file, JSONObject value) throws IOException {
        directory(file.getParentFile());
        File temporary = new File(file.getParentFile(), file.getName() + ".pending");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(value.toString().getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void directory(File file) throws IOException {
        if (!file.isDirectory() && !file.mkdirs()) throw new IOException("Cannot create underlay directory");
    }

    public static byte[] readBounded(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] bytes = new byte[32 * 1024]; int count;
        while ((count = input.read(bytes)) != -1) {
            if (out.size() > maximum - count) throw new IOException("Underlay record exceeds its limit");
            out.write(bytes, 0, count);
        }
        return out.toByteArray();
    }

    public static String sha256(InputStream input) throws IOException {
        MessageDigest digest = digest(); byte[] bytes = new byte[64 * 1024]; int count;
        while ((count = input.read(bytes)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            digest.update(bytes, 0, count);
        }
        return hex(digest.digest());
    }

    public static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }

    public static void requireChild(File parent, File child) throws IOException {
        if (!child.getCanonicalPath().startsWith(parent.getCanonicalPath() + File.separator))
            throw new IOException("Underlay path escapes its owner");
    }

    public static void deleteChildTree(File parent, File child) throws IOException {
        requireChild(parent, child);
        if (!child.exists()) return;
        Files.walkFileTree(child.toPath(), new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }

    public static long size(File directory) throws IOException {
        final long[] size = {0};
        Files.walkFileTree(directory.toPath(), new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (attributes.isSymbolicLink()) throw new IOException("Linked underlay payload is unsupported");
                size[0] = Math.addExact(size[0], attributes.size()); return FileVisitResult.CONTINUE;
            }
        });
        return size[0];
    }
}

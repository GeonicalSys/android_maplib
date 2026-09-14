package com.nextgis.maplib.util;

import org.json.JSONException;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.regex.Pattern;

/** Two bounded passes: identify the whole archive, then write tiles without an extracted tree. */
public final class NgrcArchive {
    private static final Pattern RASTER_PATH = Pattern.compile(
            "[0-9]+/[0-9]+/[0-9]+\\.(?:tile|png|jpe?g|webp)", Pattern.CASE_INSENSITIVE);
    public interface Source { InputStream open() throws IOException; }
    public interface Tiles { void add(String path, byte[] data, int scheme) throws IOException; }
    public interface Progress { void check() throws IOException; }
    public static final class Info {
        public final JSONObject config;
        public final String sha256;
        public final int scheme;
        private Info(JSONObject config, String hash) throws IOException {
            this.config = config; sha256 = hash;
            scheme = config.optInt("tms_type", -1);
            if (scheme != GeoConstants.TMSTYPE_NORMAL && scheme != GeoConstants.TMSTYPE_OSM)
                throw new IOException("NGRc must declare its TMS or OSM tile scheme");
        }
    }
    private NgrcArchive() { }

    public static Info inspect(Source source, Progress progress) throws IOException {
        final JSONObject[] config = {null};
        String hash = scan(source, progress, (path, data) -> {
            if ("config.json".equals(path)) {
                if (config[0] != null) throw new IOException("NGRc contains multiple configurations");
                try { config[0] = new JSONObject(new String(data, StandardCharsets.UTF_8)); }
                catch (JSONException e) { throw new IOException("NGRc configuration is invalid", e); }
            }
        }, false);
        if (config[0] == null) throw new IOException("NGRc configuration is missing");
        return new Info(config[0], hash);
    }

    public static void convert(Source source, Info info, Tiles tiles, Progress progress) throws IOException {
        final long[] count = {0};
        String hash = scan(source, progress, (path, data) -> {
            if (isTile(path)) { tiles.add(path, data, info.scheme); count[0]++; }
        }, true);
        if (!info.sha256.equals(hash)) throw new IOException("NGRc changed during import");
        if (count[0] == 0) throw new IOException("NGRc contains no raster tiles");
    }

    private interface Entry { void read(String path, byte[] data) throws IOException; }
    private static String scan(Source source, Progress progress, Entry consumer, boolean tiles) throws IOException {
        MessageDigest digest = UnderlayFiles.digest();
        InputStream opened = source.open();
        if (opened == null) throw new IOException("NGRc stream is unavailable");
        try (DigestInputStream input = new DigestInputStream(new BufferedInputStream(opened, 64 * 1024), digest);
             ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry; byte[] buffer = new byte[32 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                progress.check();
                String path = normalized(entry.getName());
                if (entry.isDirectory()) { zip.closeEntry(); continue; }
                boolean retained = (tiles && isTile(path)) || (!tiles && "config.json".equals(path));
                if (retained) consumer.read(path, UnderlayFiles.readBounded(zip, 16 * 1024 * 1024));
                else {
                    long size = 0; int count;
                    while ((count = zip.read(buffer)) != -1) {
                        progress.check(); size += count;
                        if (size > 16 * 1024 * 1024) throw new IOException("NGRc entry is too large");
                    }
                }
                zip.closeEntry();
            }
            // Include the central directory and ZIP comment in the identity.
            while (input.read(buffer) != -1) progress.check();
        }
        return UnderlayFiles.hex(digest.digest());
    }

    static String normalized(String name) throws IOException {
        if (name.startsWith("/") || name.contains("\\") || name.contains(":"))
            throw new IOException("Invalid NGRc path");
        for (String part : name.split("/")) if ("..".equals(part) || ".".equals(part))
            throw new IOException("Invalid NGRc path");
        String path = name.regionMatches(true, 0, "mapnik/", 0, 7) ? name.substring(7) : name;
        if ("mapnik.json".equalsIgnoreCase(path) || "config.json".equalsIgnoreCase(path))
            return "config.json";
        if (RASTER_PATH.matcher(path).matches())
            return path.substring(0, path.lastIndexOf('.')) + ".tile";
        return path;
    }
    private static boolean isTile(String path) { return path.matches("[0-9]+/[0-9]+/[0-9]+\\.tile"); }
}

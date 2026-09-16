package com.nextgis.maplib.util;

import android.content.Context;
import android.net.Uri;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Decides whether a local file is a shared-catalog underlay (NGRc or raster MBTiles)
 * without extracting an archive to a temporary tree.
 */
public enum SharedUnderlayKind {
    NGRC,
    MBTILES,
    NONE;

    public static SharedUnderlayKind classifyName(String fileName) {
        if (fileName == null) {
            return NONE;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".ngrc")) {
            return NGRC;
        }
        if (lower.endsWith(".mbtiles")) {
            return MBTILES;
        }
        return NONE;
    }

    public static boolean isZipName(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    public static SharedUnderlayKind classifyZip(InputStream input) throws IOException {
        if (input == null) {
            return NONE;
        }
        boolean hasMbtiles = false;
        boolean hasNgrcConfig = false;
        ZipInputStream zip = new ZipInputStream(input);
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            if (entry.isDirectory()) {
                zip.closeEntry();
                continue;
            }
            String name = entry.getName();
            if (name.toLowerCase(Locale.ROOT).endsWith(".mbtiles")) {
                hasMbtiles = true;
            }
            try {
                if ("config.json".equals(NgrcArchive.normalized(name))) {
                    hasNgrcConfig = true;
                }
            } catch (IOException ignored) {
                // Malicious or non-NGRc paths do not make the archive a catalog NGRc.
            }
            zip.closeEntry();
        }
        if (hasMbtiles) {
            return MBTILES;
        }
        if (hasNgrcConfig) {
            return NGRC;
        }
        return NONE;
    }

    public static SharedUnderlayKind classify(Context context, Uri uri) {
        if (context == null || uri == null) {
            return NONE;
        }
        String fileName = FileUtil.getFileNameByUri(context, uri, "");
        SharedUnderlayKind named = classifyName(fileName);
        if (named != NONE) {
            return named;
        }
        if (!isZipName(fileName)) {
            return NONE;
        }
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            return classifyZip(input);
        } catch (IOException | RuntimeException e) {
            return NONE;
        }
    }
}

/*
 * Project:  NextGIS Mobile
 * Purpose:  Mobile GIS for Android.
 * *****************************************************************************
 * Copyright (c) 2012-2026 NextGIS, info@nextgis.com
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.nextgis.maplib.util;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Lightweight validation and metadata reader for raster MBTiles databases. */
public final class MbTilesInfo {
    public static final String MBTILES_FILENAME = "map-mbtiles.mbtiles";

    public boolean valid;
    public boolean raster;
    public boolean vector;
    public String format;
    public int minZoom = -1;
    public int maxZoom = -1;
    public String name;
    public boolean boundsValid;
    public double west;
    public double south;
    public double east;
    public double north;
    public String diagnostic;

    private MbTilesInfo() {
    }

    public static MbTilesInfo inspect(File file) {
        MbTilesInfo info = new MbTilesInfo();
        if (!isSQLiteFile(file)) {
            info.diagnostic = "missing SQLite header";
            return info;
        }

        SQLiteDatabase database = null;
        try {
            database = SQLiteDatabase.openDatabase(
                    file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            if (!hasTableWithColumns(database, "tiles",
                    new String[]{"zoom_level", "tile_column", "tile_row", "tile_data"})
                    || !hasTableWithColumns(database, "metadata",
                    new String[]{"name", "value"})) {
                info.diagnostic = "required MBTiles tables or columns are missing";
                return info;
            }

            try (Cursor cursor = database.rawQuery("PRAGMA quick_check(1)", null)) {
                if (!cursor.moveToFirst() || !"ok".equalsIgnoreCase(cursor.getString(0))) {
                    info.diagnostic = "SQLite quick_check failed";
                    return info;
                }
            }

            info.format = readMetadata(database, "format");
            info.name = readMetadata(database, "name");
            info.minZoom = parseZoom(readMetadata(database, "minzoom"));
            info.maxZoom = parseZoom(readMetadata(database, "maxzoom"));
            parseBounds(readMetadata(database, "bounds"), info);

            if (info.minZoom < 0 || info.maxZoom < 0) {
                try (Cursor cursor = database.rawQuery(
                        "SELECT MIN(zoom_level), MAX(zoom_level) FROM tiles", null)) {
                    if (cursor.moveToFirst() && !cursor.isNull(0) && !cursor.isNull(1)) {
                        if (info.minZoom < 0) {
                            info.minZoom = cursor.getInt(0);
                        }
                        if (info.maxZoom < 0) {
                            info.maxZoom = cursor.getInt(1);
                        }
                    }
                }
            }

            String normalizedFormat = normalizeFormat(info.format);
            info.raster = isRasterFormat(normalizedFormat);
            info.vector = "pbf".equals(normalizedFormat) || "mvt".equals(normalizedFormat);
            info.valid = info.raster;
            if (!info.valid) {
                info.diagnostic = info.vector
                        ? "vector MBTiles are not supported"
                        : "raster format metadata is missing or unsupported";
            }
        } catch (RuntimeException e) {
            info.diagnostic = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (database != null) {
                database.close();
            }
        }
        return info;
    }

    public static boolean isReadyForMapLibre(File file) {
        if (!isSQLiteFile(file)) {
            return false;
        }
        SQLiteDatabase database = null;
        try {
            database = SQLiteDatabase.openDatabase(
                    file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            if (!hasTableWithColumns(database, "tiles",
                    new String[]{"zoom_level", "tile_column", "tile_row", "tile_data"})
                    || !hasTableWithColumns(
                    database, "metadata", new String[]{"name", "value"})) {
                return false;
            }
            return isRasterFormat(normalizeFormat(readMetadata(database, "format")));
        } catch (RuntimeException e) {
            return false;
        } finally {
            if (database != null) {
                database.close();
            }
        }
    }

    public static boolean isSQLiteFile(File file) {
        if (file == null || !file.isFile() || file.length() < 100) {
            return false;
        }
        byte[] header = new byte[16];
        try (FileInputStream input = new FileInputStream(file)) {
            if (input.read(header) != header.length) {
                return false;
            }
            return "SQLite format 3\u0000".equals(
                    new String(header, StandardCharsets.US_ASCII));
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean hasTableWithColumns(
            SQLiteDatabase database, String table, String[] requiredColumns) {
        try (Cursor cursor = database.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                new String[]{table})) {
            if (!cursor.moveToFirst()) {
                return false;
            }
        }

        Set<String> columns = new HashSet<>();
        try (Cursor cursor = database.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            int nameIndex = cursor.getColumnIndexOrThrow("name");
            while (cursor.moveToNext()) {
                columns.add(cursor.getString(nameIndex));
            }
        }
        for (String requiredColumn : requiredColumns) {
            if (!columns.contains(requiredColumn)) {
                return false;
            }
        }
        return true;
    }

    private static String readMetadata(SQLiteDatabase database, String name) {
        try (Cursor cursor = database.rawQuery(
                "SELECT value FROM metadata WHERE name=? LIMIT 1", new String[]{name})) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    private static int parseZoom(String value) {
        if (value == null) {
            return -1;
        }
        try {
            int zoom = Integer.parseInt(value.trim());
            return zoom >= 0 && zoom <= GeoConstants.DEFAULT_MAX_ZOOM ? zoom : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String normalizeFormat(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isRasterFormat(String normalizedFormat) {
        return "png".equals(normalizedFormat)
                || "jpg".equals(normalizedFormat)
                || "jpeg".equals(normalizedFormat)
                || "webp".equals(normalizedFormat);
    }

    private static void parseBounds(String value, MbTilesInfo info) {
        if (value == null) {
            return;
        }
        String[] coordinates = value.split(",");
        if (coordinates.length != 4) {
            return;
        }
        try {
            info.west = Double.parseDouble(coordinates[0].trim());
            info.south = Double.parseDouble(coordinates[1].trim());
            info.east = Double.parseDouble(coordinates[2].trim());
            info.north = Double.parseDouble(coordinates[3].trim());
            info.boundsValid = info.west >= -180 && info.west <= 180
                    && info.east >= -180 && info.east <= 180
                    && info.south >= -90 && info.south <= 90
                    && info.north >= -90 && info.north <= 90
                    && info.west <= info.east && info.south <= info.north;
        } catch (NumberFormatException ignored) {
            info.boundsValid = false;
        }
    }
}

package com.nextgis.maplib.util;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Derived MBTiles for display flags. Never rewrites the catalog original.
 */
public final class MbTilesDisplaySidecar {
    public static final String FILENAME = "map-mbtiles.display.mbtiles";
    public static final String META_FLAGS = "geonical_display_flags";
    public static final String META_SOURCE_MTIME = "geonical_source_mtime";
    public static final String META_SOURCE_SIZE = "geonical_source_size";

    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "MbTilesDisplaySidecar"));
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String, Boolean> LAST_LEVEL_BY_PATH =
            new ConcurrentHashMap<>();

    private MbTilesDisplaySidecar() {
    }

    public static File sidecarFile(File original) {
        if (original == null) {
            return null;
        }
        File parent = original.getParentFile();
        if (parent == null) {
            return new File(FILENAME);
        }
        return new File(parent, FILENAME);
    }

    public static void rememberLastLevel(File original, boolean lastLevelOverzoom) {
        if (original == null) {
            return;
        }
        LAST_LEVEL_BY_PATH.put(original.getAbsolutePath(), lastLevelOverzoom);
        File sidecar = sidecarFile(original);
        if (sidecar != null) {
            LAST_LEVEL_BY_PATH.put(sidecar.getAbsolutePath(), lastLevelOverzoom);
        }
    }

    public static boolean lastLevelOverzoomEnabled(File mbtiles) {
        if (mbtiles != null) {
            Boolean remembered = LAST_LEVEL_BY_PATH.get(mbtiles.getAbsolutePath());
            if (remembered != null) {
                return remembered;
            }
        }
        if (mbtiles == null || !FILENAME.equals(mbtiles.getName()) || !mbtiles.isFile()) {
            return false;
        }
        SQLiteDatabase database = null;
        try {
            database = SQLiteDatabase.openDatabase(
                    mbtiles.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            return UnderlayDisplaySettings.lastLevelFromFingerprint(readMeta(database, META_FLAGS));
        } catch (RuntimeException e) {
            return false;
        } finally {
            if (database != null) {
                database.close();
            }
        }
    }

    public static String urlFor(File original, UnderlayDisplaySettings settings) {
        if (original == null || !original.isFile()) {
            return null;
        }
        if (settings == null || !settings.needsSidecar()) {
            return "mbtiles://" + original.getAbsolutePath();
        }
        File sidecar = sidecarFile(original);
        if (isFresh(sidecar, original, settings)) {
            return "mbtiles://" + sidecar.getAbsolutePath();
        }
        return "mbtiles://" + original.getAbsolutePath();
    }

    public static boolean isFresh(
            File sidecar, File original, UnderlayDisplaySettings settings) {
        if (sidecar == null || original == null || !sidecar.isFile() || !original.isFile()
                || settings == null || !settings.needsSidecar()) {
            return false;
        }
        SQLiteDatabase database = null;
        try {
            database = SQLiteDatabase.openDatabase(
                    sidecar.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            String flags = readMeta(database, META_FLAGS);
            String mtime = readMeta(database, META_SOURCE_MTIME);
            String size = readMeta(database, META_SOURCE_SIZE);
            return settings.fingerprint().equals(flags)
                    && Long.toString(original.lastModified()).equals(mtime)
                    && Long.toString(original.length()).equals(size);
        } catch (RuntimeException e) {
            return false;
        } finally {
            if (database != null) {
                database.close();
            }
        }
    }

    public static void ensureAsync(
            File original, UnderlayDisplaySettings settings, Runnable onReady) {
        if (original == null || !original.isFile()) {
            return;
        }
        File sidecar = sidecarFile(original);
        if (settings == null || !settings.needsSidecar()) {
            if (sidecar != null && sidecar.isFile() && !sidecar.delete()) {
                Log.w(Constants.TAG, "Cannot delete unused underlay display sidecar");
            }
            return;
        }
        if (isFresh(sidecar, original, settings)) {
            return;
        }
        String key = original.getAbsolutePath() + "|" + settings.fingerprint();
        if (!IN_FLIGHT.add(key)) {
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                build(original, sidecar, settings);
                if (onReady != null) {
                    onReady.run();
                }
            } catch (IOException | RuntimeException e) {
                Log.w(Constants.TAG, "Underlay display sidecar failed", e);
                if (sidecar != null && sidecar.isFile() && !sidecar.delete()) {
                    Log.w(Constants.TAG, "Cannot delete incomplete underlay display sidecar");
                }
            } finally {
                IN_FLIGHT.remove(key);
            }
        });
    }

    static void build(File original, File sidecar, UnderlayDisplaySettings settings)
            throws IOException {
        File partial = new File(sidecar.getAbsolutePath() + ".partial");
        if (partial.exists() && !partial.delete()) {
            throw new IOException("Cannot replace incomplete display sidecar");
        }
        copyFile(original, partial);
        SQLiteDatabase database = SQLiteDatabase.openDatabase(
                partial.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
        try {
            database.execSQL("PRAGMA journal_mode=OFF");
            database.execSQL("PRAGMA synchronous=OFF");
            database.beginTransaction();
            try {
                if (settings.whiteAsTransparent) {
                    punchWhiteTiles(database);
                }
                if (settings.lastLevelOverzoom) {
                    insertPyramidHoles(database);
                }
                putMeta(database, META_FLAGS, settings.fingerprint());
                putMeta(database, META_SOURCE_MTIME, Long.toString(original.lastModified()));
                putMeta(database, META_SOURCE_SIZE, Long.toString(original.length()));
                database.setTransactionSuccessful();
            } finally {
                database.endTransaction();
            }
        } finally {
            database.close();
        }
        if (sidecar.exists() && !sidecar.delete()) {
            throw new IOException("Cannot replace display sidecar");
        }
        if (!partial.renameTo(sidecar)) {
            throw new IOException("Cannot publish display sidecar");
        }
    }

    private static void punchWhiteTiles(SQLiteDatabase database) {
        try (Cursor cursor = database.rawQuery(
                "SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles", null)) {
            int zoomIndex = cursor.getColumnIndexOrThrow("zoom_level");
            int columnIndex = cursor.getColumnIndexOrThrow("tile_column");
            int rowIndex = cursor.getColumnIndexOrThrow("tile_row");
            int dataIndex = cursor.getColumnIndexOrThrow("tile_data");
            SQLiteStatement update = database.compileStatement(
                    "UPDATE tiles SET tile_data=? WHERE zoom_level=? AND tile_column=? AND tile_row=?");
            try {
                while (cursor.moveToNext()) {
                    byte[] data = cursor.getBlob(dataIndex);
                    byte[] punched = punchTile(data);
                    if (punched == null) {
                        continue;
                    }
                    update.clearBindings();
                    update.bindBlob(1, punched);
                    update.bindLong(2, cursor.getLong(zoomIndex));
                    update.bindLong(3, cursor.getLong(columnIndex));
                    update.bindLong(4, cursor.getLong(rowIndex));
                    update.executeUpdateDelete();
                }
            } finally {
                update.close();
            }
        }
    }

    static byte[] punchTile(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        Bitmap decoded = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (decoded == null) {
            return null;
        }
        Bitmap bitmap = decoded.getConfig() == Bitmap.Config.ARGB_8888
                ? decoded.copy(Bitmap.Config.ARGB_8888, true)
                : decoded.copy(Bitmap.Config.ARGB_8888, true);
        if (bitmap != decoded) {
            decoded.recycle();
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        boolean changed = RasterWhiteChromaKey.punchExactWhite(pixels);
        byte[] result = null;
        if (changed) {
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                result = output.toByteArray();
            }
        }
        bitmap.recycle();
        return result;
    }

    private static void insertPyramidHoles(SQLiteDatabase database) {
        int minZoom = parseZoom(readMeta(database, "minzoom"));
        int maxZoom = parseZoom(readMeta(database, "maxzoom"));
        if (minZoom < 0 || maxZoom < 0 || maxZoom < minZoom) {
            try (Cursor cursor = database.rawQuery(
                    "SELECT MIN(zoom_level), MAX(zoom_level) FROM tiles", null)) {
                if (cursor.moveToFirst() && !cursor.isNull(0) && !cursor.isNull(1)) {
                    minZoom = cursor.getInt(0);
                    maxZoom = cursor.getInt(1);
                }
            }
        }
        if (minZoom < 0 || maxZoom < 0 || maxZoom < minZoom) {
            return;
        }
        double west;
        double south;
        double east;
        double north;
        boolean boundsValid = false;
        String bounds = readMeta(database, "bounds");
        if (bounds != null) {
            String[] parts = bounds.split(",");
            if (parts.length == 4) {
                try {
                    west = Double.parseDouble(parts[0].trim());
                    south = Double.parseDouble(parts[1].trim());
                    east = Double.parseDouble(parts[2].trim());
                    north = Double.parseDouble(parts[3].trim());
                    boundsValid = true;
                } catch (NumberFormatException ignored) {
                    west = south = east = north = 0;
                }
            } else {
                west = south = east = north = 0;
            }
        } else {
            west = south = east = north = 0;
        }
        if (!boundsValid) {
            return;
        }
        byte[] transparent = TransparentPng.tile256();
        SQLiteStatement insert = database.compileStatement(
                "INSERT OR IGNORE INTO tiles (zoom_level, tile_column, tile_row, tile_data) "
                        + "VALUES (?, ?, ?, ?)");
        try {
            for (int zoom = minZoom; zoom <= maxZoom; zoom++) {
                if (!UnderlayRasterZoomPolicy.shouldFillHole(zoom, minZoom, maxZoom)) {
                    continue;
                }
                MbTilesTileGrid grid = MbTilesTileGrid.fromWgs84(west, south, east, north, zoom);
                if (grid.tileCount() == 0 || grid.tileCount() > MbTilesTileGrid.MAX_HOLES_PER_ZOOM) {
                    Log.w(Constants.TAG, "Skip underlay pyramid holes at zoom " + zoom
                            + " count=" + grid.tileCount());
                    continue;
                }
                for (int column = grid.minColumn; column <= grid.maxColumn; column++) {
                    for (int row = grid.minRow; row <= grid.maxRow; row++) {
                        insert.clearBindings();
                        insert.bindLong(1, zoom);
                        insert.bindLong(2, column);
                        insert.bindLong(3, row);
                        insert.bindBlob(4, transparent);
                        insert.executeInsert();
                    }
                }
            }
        } finally {
            insert.close();
        }
    }

    private static void putMeta(SQLiteDatabase database, String name, String value) {
        ContentValues values = new ContentValues();
        values.put("name", name);
        values.put("value", value);
        database.insertWithOnConflict("metadata", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private static String readMeta(SQLiteDatabase database, String name) {
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
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void copyFile(File source, File destination) throws IOException {
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination);
             FileChannel inChannel = input.getChannel();
             FileChannel outChannel = output.getChannel()) {
            long size = inChannel.size();
            long position = 0;
            while (position < size) {
                position += inChannel.transferTo(position, size - position, outChannel);
            }
            output.getFD().sync();
        }
    }
}

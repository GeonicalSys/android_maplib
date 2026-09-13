package com.nextgis.maplib.util;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Locale;

/** Bounded-memory raster writer shared by NGRc import and the Debug underlay bridge. */
public final class RasterMbtilesWriter implements AutoCloseable {
    public static final int SQLITE_BATCH_SIZE = 1000;
        private final SQLiteDatabase database;
        private final SQLiteStatement insertTile;
        private final String layerName;
        private int batchCount;
        private int tileCount;
        private int minZoom = Integer.MAX_VALUE;
        private int maxZoom = Integer.MIN_VALUE;
        private double west = Double.POSITIVE_INFINITY;
        private double south = Double.POSITIVE_INFINITY;
        private double east = Double.NEGATIVE_INFINITY;
        private double north = Double.NEGATIVE_INFINITY;
        private String format;
        private boolean finished;

        public RasterMbtilesWriter(File file, String layerName) {
            this.layerName = layerName;
            database = SQLiteDatabase.openOrCreateDatabase(file, null);
            applyPragma("PRAGMA journal_mode=OFF");
            applyPragma("PRAGMA synchronous=OFF");
            database.execSQL("CREATE TABLE metadata (name TEXT PRIMARY KEY, value TEXT)");
            database.execSQL("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, "
                    + "tile_row INTEGER, tile_data BLOB, "
                    + "UNIQUE (zoom_level, tile_column, tile_row))");
            insertTile = database.compileStatement(
                    "INSERT OR REPLACE INTO tiles "
                            + "(zoom_level, tile_column, tile_row, tile_data) VALUES (?, ?, ?, ?)");
            database.beginTransaction();
        }

        private void applyPragma(String sql) {
            try (Cursor cursor = database.rawQuery(sql, null)) {
                cursor.moveToFirst();
            }
        }

        public int getTileCount() {
            return tileCount;
        }

        public void addTile(String entryName, byte[] data, int sourceTmsType) throws IOException {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Underlay conversion interrupted");
            }
            TileCoordinate tile = TileCoordinate.parse(entryName, sourceTmsType);
            String detectedFormat = LegacyTileMbtilesMath.detectRasterFormat(data);
            if (detectedFormat == null) {
                throw new IOException("Unsupported raster tile encoding");
            }
            if (format == null) {
                format = detectedFormat;
            } else if (!format.equals(detectedFormat)) {
                throw new IOException("Legacy underlay mixes raster encodings");
            }

            insertTile.clearBindings();
            insertTile.bindLong(1, tile.zoom);
            insertTile.bindLong(2, tile.x);
            insertTile.bindLong(3, tile.tmsY);
            insertTile.bindBlob(4, data);
            insertTile.executeInsert();
            tileCount++;
            batchCount++;
            updateBounds(tile);
            if (batchCount >= SQLITE_BATCH_SIZE) {
                commitBatch(true);
            }
        }

        public void finish() throws IOException {
            if (tileCount == 0 || format == null) {
                throw new IOException("Legacy underlay contains no raster tiles");
            }
            commitBatch(false);
            insertTile.close();
            database.beginTransaction();
            try {
                putMetadata("name", layerName == null || layerName.trim().isEmpty()
                        ? "Underlay" : layerName.trim());
                putMetadata("type", "baselayer");
                putMetadata("version", "1.3");
                putMetadata("format", format);
                putMetadata("minzoom", Integer.toString(minZoom));
                putMetadata("maxzoom", Integer.toString(maxZoom));
                putMetadata("bounds", String.format(Locale.US, "%.8f,%.8f,%.8f,%.8f",
                        west, south, east, north));
                database.setTransactionSuccessful();
            } finally {
                database.endTransaction();
            }
            finished = true;
        }

        private void commitBatch(boolean continueWriting) {
            database.setTransactionSuccessful();
            database.endTransaction();
            batchCount = 0;
            if (continueWriting) {
                database.beginTransaction();
            }
        }

        private void putMetadata(String name, String value) {
            ContentValues values = new ContentValues();
            values.put("name", name);
            values.put("value", value);
            database.insertOrThrow("metadata", null, values);
        }

        private void updateBounds(TileCoordinate tile) {
            int dimension = 1 << tile.zoom;
            west = Math.min(west, LegacyTileMbtilesMath.longitudeFromBoundary(tile.x, dimension));
            east = Math.max(east,
                    LegacyTileMbtilesMath.longitudeFromBoundary(tile.x + 1.0, dimension));
            south = Math.min(south,
                    LegacyTileMbtilesMath.latitudeFromTmsBoundary(tile.tmsY, dimension));
            north = Math.max(north,
                    LegacyTileMbtilesMath.latitudeFromTmsBoundary(tile.tmsY + 1.0, dimension));
            minZoom = Math.min(minZoom, tile.zoom);
            maxZoom = Math.max(maxZoom, tile.zoom);
        }

        @Override
        public void close() {
            if (!finished && database.inTransaction()) {
                database.endTransaction();
            }
            if (!finished) {
                insertTile.close();
            }
            database.close();
        }
    
    private static final class TileCoordinate {
        final int zoom;
        final int x;
        final int tmsY;

        TileCoordinate(int zoom, int x, int tmsY) {
            this.zoom = zoom;
            this.x = x;
            this.tmsY = tmsY;
        }

        static TileCoordinate parse(String entryName, int sourceTmsType) throws IOException {
            String[] parts = entryName.split("/");
            if (parts.length != 3 || !parts[2].endsWith(".tile")) {
                throw new IOException("Unexpected legacy tile path");
            }
            try {
                int zoom = Integer.parseInt(parts[0]);
                int x = Integer.parseInt(parts[1]);
                int y = Integer.parseInt(parts[2].substring(0, parts[2].length() - 5));
                if (zoom < 0 || zoom > GeoConstants.DEFAULT_MAX_ZOOM) {
                    throw new IOException("Legacy tile zoom is out of range");
                }
                int dimension = 1 << zoom;
                if (x < 0 || x >= dimension || y < 0 || y >= dimension) {
                    throw new IOException("Legacy tile coordinate is out of range");
                }
                int tmsY = LegacyTileMbtilesMath.toTmsRow(zoom, y, sourceTmsType);
                return new TileCoordinate(zoom, x, tmsY);
            } catch (NumberFormatException e) {
                throw new IOException("Legacy tile coordinate is invalid", e);
            }
        }
    }
}

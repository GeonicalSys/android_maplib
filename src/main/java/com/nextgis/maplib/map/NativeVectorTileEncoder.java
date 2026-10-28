package com.nextgis.maplib.map;

import org.locationtech.jts.geom.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Generic MVT2 output at the native SDK's8192 extent; originals never enter a global feature list. */
public final class NativeVectorTileEncoder {
    public static final int EXTENT = 8192;
    public static final String SOURCE_LAYER = "features", LABEL_ANCHOR = "_label_anchor";
    private static final double MERCATOR_MAX = 20037508.342789244;
    // Match the unchanged classic GeoJsonSource: SDK13.7.1 defaults to128px at512px/tile.
    // Both the SQLite candidate envelope and full-precision clip use these same bounds.
    private static final double CLASSIC_BUFFER_RATIO = 128.0 / 512.0;
    private final Bounds bounds;
    private final List<byte[]> features = new ArrayList<>();
    private final Map<String, Integer> keys = new LinkedHashMap<>();
    private final Map<Object, Integer> values = new LinkedHashMap<>();
    NativeVectorTileEncoder(Bounds bounds) { this.bounds = bounds; }

    void add(long id, Map<String, Object> properties, Geometry geometry) throws IOException {
        if (geometry == null || geometry.isEmpty()) return;
        int type = geometry.getDimension() + 1; // point1, line2, polygon3
        Commands commands = new Commands(bounds);
        if (type == 1) commands.points(geometry.getCoordinates());
        else commands.geometry(geometry, type);
        byte[] encoded = commands.bytes();
        if (encoded.length == 0) return;
        Proto feature = new Proto();
        if (id >= 0) feature.integer(1, id);
        Proto tags = new Proto();
        for (Map.Entry<String, Object> property : properties.entrySet()) {
            tags.varint(keys.computeIfAbsent(property.getKey(), ignored -> keys.size()));
            tags.varint(values.computeIfAbsent(property.getValue(), ignored -> values.size()));
        }
        feature.message(2, tags.bytes()); feature.integer(3, type); feature.message(4, encoded);
        features.add(feature.bytes());
    }

    byte[] finish() throws IOException {
        Proto layer = new Proto(); layer.string(1, SOURCE_LAYER);
        for (byte[] feature : features) layer.message(2, feature);
        for (String key : keys.keySet()) layer.string(3, key);
        for (Object value : values.keySet()) {
            Proto encoded = new Proto();
            if (value instanceof Boolean) encoded.integer(7, (Boolean) value ? 1 : 0);
            else if (value instanceof Long || value instanceof Integer) {
                long n = ((Number) value).longValue(); encoded.integer(6, n << 1 ^ n >> 63);
            } else if (value instanceof Number) encoded.decimal(3, ((Number) value).doubleValue());
            else encoded.string(1, (String) value);
            layer.message(4, encoded.bytes());
        }
        layer.integer(5, EXTENT); layer.integer(15, 2);
        Proto tile = new Proto(); tile.message(3, layer.bytes()); return tile.bytes();
    }

    static final class Bounds {
        final double minX, minY, maxX, maxY;
        Bounds(double minX, double minY, double maxX, double maxY) {
            if (!(minX < maxX && minY < maxY)) throw new IllegalArgumentException("Invalid tile bounds");
            this.minX = minX; this.minY = minY; this.maxX = maxX; this.maxY = maxY;
        }
        static Bounds tile(SqliteVectorTileRequest request) {
            double span = MERCATOR_MAX * 2 / (1L << request.getZ());
            double left = -MERCATOR_MAX + request.getX() * span;
            double top = MERCATOR_MAX - request.getY() * span;
            return new Bounds(left, top - span, left + span, top);
        }
        double queryMinX() { return minX - (maxX - minX) * CLASSIC_BUFFER_RATIO; }
        double queryMaxX() { return maxX + (maxX - minX) * CLASSIC_BUFFER_RATIO; }
        double queryMinY() { return minY - (maxY - minY) * CLASSIC_BUFFER_RATIO; }
        double queryMaxY() { return maxY + (maxY - minY) * CLASSIC_BUFFER_RATIO; }
        int x(double x) { return (int) Math.round((x - minX) / (maxX - minX) * EXTENT); }
        int y(double y) { return (int) Math.round((maxY - y) / (maxY - minY) * EXTENT); }
        boolean owns(double x, double y) { return x >= minX && x < maxX && y > minY && y <= maxY; }
        Point anchor(double x, double y) {
            // Keep an owned seam point inside the integer tile, as native symbol ownership requires.
            int tx = Math.min(EXTENT - 1, x(x)), ty = Math.min(EXTENT - 1, y(y));
            return NativeVectorTileClipper.FACTORY.createPoint(new Coordinate(
                    minX + tx * (maxX - minX) / EXTENT, maxY - ty * (maxY - minY) / EXTENT));
        }
    }

    private static final class Commands {
        final Bounds bounds; final Proto out = new Proto(); int cursorX, cursorY;
        Commands(Bounds bounds) { this.bounds = bounds; }
        byte[] bytes() { return out.bytes(); }
        void points(Coordinate[] points) throws IOException {
            if (points.length == 0) return;
            command(1, points.length);
            for (Coordinate point : points) point(bounds.x(point.x), bounds.y(point.y));
        }
        void geometry(Geometry geometry, int type) throws IOException {
            if (geometry instanceof GeometryCollection) {
                for (int i = 0; i < geometry.getNumGeometries(); i++) geometry(geometry.getGeometryN(i), type);
            } else if (type == 2 && geometry instanceof LineString) path(geometry.getCoordinates(), false, false);
            else if (type == 3 && geometry instanceof Polygon) {
                Polygon polygon = (Polygon) geometry;
                path(polygon.getExteriorRing().getCoordinates(), true, true);
                for (int i = 0; i < polygon.getNumInteriorRing(); i++)
                    path(polygon.getInteriorRingN(i).getCoordinates(), true, false);
            }
        }
        void path(Coordinate[] coordinates, boolean ring, boolean outer) throws IOException {
            List<int[]> points = new ArrayList<>(coordinates.length);
            for (Coordinate coordinate : coordinates) {
                int x = bounds.x(coordinate.x), y = bounds.y(coordinate.y);
                if (points.isEmpty() || points.get(points.size() - 1)[0] != x || points.get(points.size() - 1)[1] != y)
                    points.add(new int[]{x, y}); // only identical integer-grid positions coalesce
            }
            if (ring && points.size() > 1 && same(points.get(0), points.get(points.size() - 1))) points.remove(points.size() - 1);
            if (points.size() < (ring ? 3 : 2)) return;
            if (ring) {
                long area = 0;
                for (int i = 0; i < points.size(); i++) {
                    int[] a = points.get(i), b = points.get((i + 1) % points.size());
                    area += (long) a[0] * b[1] - (long) b[0] * a[1];
                }
                if (area == 0) return;
                if ((area > 0) != outer) Collections.reverse(points);
            }
            command(1, 1); point(points.get(0)[0], points.get(0)[1]); command(2, points.size() - 1);
            for (int i = 1; i < points.size(); i++) point(points.get(i)[0], points.get(i)[1]);
            if (ring) command(7, 1);
        }
        boolean same(int[] a, int[] b) { return a[0] == b[0] && a[1] == b[1]; }
        void command(int id, int count) throws IOException { out.varint((long) count << 3 | id); }
        void point(int x, int y) throws IOException {
            long dx = (long) x - cursorX, dy = (long) y - cursorY;
            out.varint(dx << 1 ^ dx >> 63); out.varint(dy << 1 ^ dy >> 63); cursorX = x; cursorY = y;
        }
    }

    private static final class Proto {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] bytes() { return output.toByteArray(); }
        void integer(int field, long value) throws IOException { varint((long) field << 3); varint(value); }
        void message(int field, byte[] bytes) throws IOException { varint((long) field << 3 | 2); varint(bytes.length); output.write(bytes); }
        void string(int field, String value) throws IOException { message(field, value.getBytes(StandardCharsets.UTF_8)); }
        void decimal(int field, double value) throws IOException {
            varint((long) field << 3 | 1); long bits = Double.doubleToLongBits(value);
            for (int i = 0; i < 8; i++) output.write((int) (bits >>> (8 * i)) & 255);
        }
        void varint(long value) throws IOException {
            while ((value & ~0x7fL) != 0) { output.write((int) value & 0x7f | 0x80); value >>>= 7; }
            output.write((int) value);
        }
    }
}

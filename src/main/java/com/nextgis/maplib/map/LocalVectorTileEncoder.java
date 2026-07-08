/*
 * Project: NextGIS Mobile
 * Purpose: Minimal MVT encoder for local vector-tile rendering.
 */

package com.nextgis.maplib.map;

import com.nextgis.maplib.datasource.Feature;
import com.nextgis.maplib.datasource.GeoGeometry;
import com.nextgis.maplib.datasource.GeoGeometryCollection;
import com.nextgis.maplib.datasource.GeoLineString;
import com.nextgis.maplib.datasource.GeoPoint;
import com.nextgis.maplib.datasource.GeoPolygon;
import com.nextgis.maplib.util.GeoConstants;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Local vector tiles foundation.
 *
 * Encodes a small subset of Mapbox Vector Tile 2.x needed for the first read-only
 * polygon/multipolygon Collector layers. This intentionally avoids a new dependency while the
 * feature is being proven on-device.
 */
final class LocalVectorTileEncoder {
    static final int EXTENT = 4096;
    static final String SOURCE_LAYER = "features";

    private static final int GEOM_TYPE_POLYGON = 3;

    private final List<byte[]> mFeatures = new ArrayList<>();
    private final Map<String, Integer> mKeys = new LinkedHashMap<>();
    private final Map<Object, Integer> mValues = new LinkedHashMap<>();

    private LocalVectorTileEncoder() {
    }

    static byte[] encodePolygonTile(
            List<Feature> features,
            TileBounds bounds,
            int layerId,
            String labelField,
            String commonLabel) throws IOException {
        LocalVectorTileEncoder encoder = new LocalVectorTileEncoder();
        int order = 0;
        for (Feature feature : features) {
            if (feature == null || feature.getGeometry() == null) {
                continue;
            }
            byte[] geometry = encoder.encodeGeometry(feature.getGeometry(), bounds);
            if (geometry.length == 0) {
                continue;
            }
            Map<String, Object> props = new LinkedHashMap<>();
            props.put(MplFeatureStyleProps.FEATURE_ID, feature.getId());
            props.put(MplFeatureStyleProps.LAYER_ID, layerId);
            props.put(MplFeatureStyleProps.ORDER, ++order);
            String label = resolveLabel(feature, labelField, commonLabel);
            if (!isEmpty(label)) {
                props.put(MplFeatureStyleProps.SIGNATURE, label);
            }
            encoder.mFeatures.add(encoder.encodeFeature(feature.getId(), props, geometry));
        }
        return encoder.encodeTile();
    }

    private static String resolveLabel(Feature feature, String labelField, String commonLabel) {
        if (!isEmpty(commonLabel)) {
            return commonLabel;
        }
        if (isEmpty(labelField)) {
            return null;
        }
        if ("_id".equals(labelField)) {
            return String.valueOf(feature.getId());
        }
        return feature.getFieldValueAsString(labelField);
    }

    private static boolean isEmpty(String value) {
        return value == null || value.length() == 0;
    }

    private byte[] encodeTile() throws IOException {
        ProtoWriter layer = new ProtoWriter();
        layer.writeString(1, SOURCE_LAYER);
        for (byte[] feature : mFeatures) {
            layer.writeMessage(2, feature);
        }
        for (String key : mKeys.keySet()) {
            layer.writeString(3, key);
        }
        for (Object value : mValues.keySet()) {
            layer.writeMessage(4, encodeValue(value));
        }
        layer.writeUInt32(5, EXTENT);
        layer.writeUInt32(15, 2);

        ProtoWriter tile = new ProtoWriter();
        tile.writeMessage(3, layer.toByteArray());
        return tile.toByteArray();
    }

    private byte[] encodeFeature(long id, Map<String, Object> props, byte[] geometry)
            throws IOException {
        ProtoWriter feature = new ProtoWriter();
        if (id >= 0) {
            feature.writeUInt64(1, id);
        }
        int[] tags = encodeTags(props);
        feature.writePackedUInt32(2, tags);
        feature.writeUInt32(3, GEOM_TYPE_POLYGON);
        feature.writePackedUInt32(4, decodeCommandStream(geometry));
        return feature.toByteArray();
    }

    private int[] encodeTags(Map<String, Object> props) {
        int[] tags = new int[props.size() * 2];
        int i = 0;
        for (Map.Entry<String, Object> entry : props.entrySet()) {
            tags[i++] = keyIndex(entry.getKey());
            tags[i++] = valueIndex(entry.getValue());
        }
        return tags;
    }

    private int keyIndex(String key) {
        Integer existing = mKeys.get(key);
        if (existing != null) {
            return existing;
        }
        int index = mKeys.size();
        mKeys.put(key, index);
        return index;
    }

    private int valueIndex(Object value) {
        Object normalized = value == null ? "" : value;
        Integer existing = mValues.get(normalized);
        if (existing != null) {
            return existing;
        }
        int index = mValues.size();
        mValues.put(normalized, index);
        return index;
    }

    private static byte[] encodeValue(Object value) throws IOException {
        ProtoWriter writer = new ProtoWriter();
        if (value instanceof Boolean) {
            writer.writeBool(7, (Boolean) value);
        } else if (value instanceof Integer || value instanceof Long) {
            writer.writeSInt64(6, ((Number) value).longValue());
        } else if (value instanceof Float || value instanceof Double) {
            writer.writeDouble(3, ((Number) value).doubleValue());
        } else {
            writer.writeString(1, String.valueOf(value));
        }
        return writer.toByteArray();
    }

    private byte[] encodeGeometry(GeoGeometry geometry, TileBounds bounds) throws IOException {
        GeometryCommandWriter writer = new GeometryCommandWriter(bounds);
        if (geometry.getType() == GeoConstants.GTPolygon) {
            writePolygonGeometry(writer, (GeoPolygon) geometry);
            return writer.toByteArray();
        }
        if (geometry.getType() == GeoConstants.GTMultiPolygon && geometry instanceof GeoGeometryCollection) {
            GeoGeometryCollection collection = (GeoGeometryCollection) geometry;
            for (int i = 0; i < collection.size(); i++) {
                GeoGeometry part = collection.get(i);
                if (part instanceof GeoPolygon) {
                    writePolygonGeometry(writer, (GeoPolygon) part);
                }
            }
            return writer.toByteArray();
        }
        return new byte[0];
    }

    private void writePolygonGeometry(GeometryCommandWriter writer, GeoPolygon polygon) throws IOException {
        writer.writeRing(polygon.getOuterRing(), true);
        for (int i = 0; i < polygon.getInnerRingCount(); i++) {
            writer.writeRing(polygon.getInnerRing(i), false);
        }
    }

    private static int[] decodeCommandStream(byte[] encoded) {
        IntArray ints = new IntArray();
        int[] offset = new int[]{0};
        while (offset[0] < encoded.length) {
            ints.add((int) readVarint(encoded, offset));
        }
        return ints.toArray();
    }

    private static long readVarint(byte[] bytes, int[] offset) {
        long result = 0;
        int shift = 0;
        while (offset[0] < bytes.length) {
            int b = bytes[offset[0]++] & 0xff;
            result |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        return result;
    }

    static final class TileBounds {
        final double minX;
        final double minY;
        final double maxX;
        final double maxY;

        TileBounds(double minX, double minY, double maxX, double maxY) {
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
        }

        int tileX(double mercatorX) {
            return (int) Math.round((mercatorX - minX) / (maxX - minX) * EXTENT);
        }

        int tileY(double mercatorY) {
            return (int) Math.round((maxY - mercatorY) / (maxY - minY) * EXTENT);
        }
    }

    private static final class GeometryCommandWriter {
        private final ProtoWriter mWriter = new ProtoWriter();
        private final TileBounds mBounds;
        private int mCursorX;
        private int mCursorY;

        GeometryCommandWriter(TileBounds bounds) {
            mBounds = bounds;
        }

        void writeRing(GeoLineString ring, boolean outer) throws IOException {
            List<GeoPoint> source = ring != null ? ring.getPoints() : null;
            if (source == null || source.size() < 4) {
                return;
            }
            List<TilePoint> points = new ArrayList<>(source.size());
            for (GeoPoint point : source) {
                TilePoint tilePoint = new TilePoint(mBounds.tileX(point.getX()), mBounds.tileY(point.getY()));
                if (points.isEmpty() || !points.get(points.size() - 1).equals(tilePoint)) {
                    points.add(tilePoint);
                }
            }
            if (points.size() > 1 && points.get(0).equals(points.get(points.size() - 1))) {
                points.remove(points.size() - 1);
            }
            if (points.size() < 3) {
                return;
            }
            orient(points, outer);

            command(1, 1);
            point(points.get(0));
            command(2, points.size() - 1);
            for (int i = 1; i < points.size(); i++) {
                point(points.get(i));
            }
            command(7, 1);
        }

        byte[] toByteArray() {
            return mWriter.toByteArray();
        }

        private void command(int commandId, int count) throws IOException {
            mWriter.writeRawVarint(((long) count << 3) | (commandId & 0x7));
        }

        private void point(TilePoint point) throws IOException {
            int dx = point.x - mCursorX;
            int dy = point.y - mCursorY;
            mWriter.writeRawVarint(zigZag(dx));
            mWriter.writeRawVarint(zigZag(dy));
            mCursorX = point.x;
            mCursorY = point.y;
        }

        private static void orient(List<TilePoint> points, boolean outer) {
            boolean clockwise = signedArea(points) > 0;
            if (outer != clockwise) {
                java.util.Collections.reverse(points);
            }
        }

        private static long signedArea(List<TilePoint> points) {
            long area = 0;
            for (int i = 0; i < points.size(); i++) {
                TilePoint a = points.get(i);
                TilePoint b = points.get((i + 1) % points.size());
                area += (long) a.x * b.y - (long) b.x * a.y;
            }
            return area;
        }

        private static long zigZag(int value) {
            return ((long) value << 1) ^ (value >> 31);
        }
    }

    private static final class TilePoint {
        final int x;
        final int y;

        TilePoint(int x, int y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof TilePoint)) {
                return false;
            }
            TilePoint other = (TilePoint) o;
            return x == other.x && y == other.y;
        }

        @Override
        public int hashCode() {
            return 31 * x + y;
        }
    }

    private static final class IntArray {
        int[] values = new int[64];
        int size;

        void add(int value) {
            if (size >= values.length) {
                int[] next = new int[values.length * 2];
                System.arraycopy(values, 0, next, 0, values.length);
                values = next;
            }
            values[size++] = value;
        }

        int[] toArray() {
            int[] out = new int[size];
            System.arraycopy(values, 0, out, 0, size);
            return out;
        }
    }

    private static final class ProtoWriter {
        private final ByteArrayOutputStream mOut = new ByteArrayOutputStream();

        byte[] toByteArray() {
            return mOut.toByteArray();
        }

        void writeUInt32(int field, int value) throws IOException {
            tag(field, 0);
            writeRawVarint(value & 0xffffffffL);
        }

        void writeUInt64(int field, long value) throws IOException {
            tag(field, 0);
            writeRawVarint(value);
        }

        void writeSInt64(int field, long value) throws IOException {
            tag(field, 0);
            writeRawVarint((value << 1) ^ (value >> 63));
        }

        void writeBool(int field, boolean value) throws IOException {
            tag(field, 0);
            writeRawVarint(value ? 1 : 0);
        }

        void writeDouble(int field, double value) throws IOException {
            tag(field, 1);
            long bits = Double.doubleToLongBits(value);
            for (int i = 0; i < 8; i++) {
                mOut.write((int) ((bits >> (i * 8)) & 0xff));
            }
        }

        void writeString(int field, String value) throws IOException {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            tag(field, 2);
            writeRawVarint(bytes.length);
            mOut.write(bytes);
        }

        void writeMessage(int field, byte[] message) throws IOException {
            tag(field, 2);
            writeRawVarint(message.length);
            mOut.write(message);
        }

        void writePackedUInt32(int field, int[] values) throws IOException {
            if (values == null || values.length == 0) {
                return;
            }
            ProtoWriter packed = new ProtoWriter();
            for (int value : values) {
                packed.writeRawVarint(value & 0xffffffffL);
            }
            writeMessage(field, packed.toByteArray());
        }

        void writeRawBytes(byte[] bytes) throws IOException {
            mOut.write(bytes);
        }

        void tag(int field, int wireType) throws IOException {
            writeRawVarint((field << 3) | wireType);
        }

        void writeRawVarint(long value) throws IOException {
            while ((value & ~0x7fL) != 0) {
                mOut.write((int) ((value & 0x7f) | 0x80));
                value >>>= 7;
            }
            mOut.write((int) value);
        }
    }
}

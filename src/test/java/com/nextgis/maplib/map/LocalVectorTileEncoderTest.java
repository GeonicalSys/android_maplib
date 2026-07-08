package com.nextgis.maplib.map;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import com.nextgis.maplib.datasource.Feature;
import com.nextgis.maplib.datasource.GeoMultiPolygon;
import com.nextgis.maplib.datasource.GeoPoint;
import com.nextgis.maplib.datasource.GeoPolygon;

import org.junit.Test;

import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

public class LocalVectorTileEncoderTest {

    @Test
    public void emptyTile_keepsSourceLayer() throws Exception {
        byte[] tile = LocalVectorTileEncoder.encodePolygonTile(
                Collections.emptyList(),
                new LocalVectorTileEncoder.TileBounds(0, 0, 10, 10),
                7,
                null,
                null);

        String bytes = new String(tile, StandardCharsets.ISO_8859_1);
        assertTrue(tile.length > 0);
        assertTrue(bytes.contains(LocalVectorTileEncoder.SOURCE_LAYER));
    }

    @Test
    public void polygonTile_containsFeatureProperties() throws Exception {
        Feature feature = new Feature();
        feature.setId(42);
        feature.setGeometry(square(1, 1, 9, 9));

        byte[] tile = LocalVectorTileEncoder.encodePolygonTile(
                Collections.singletonList(feature),
                new LocalVectorTileEncoder.TileBounds(0, 0, 10, 10),
                7,
                null,
                "label");

        String bytes = new String(tile, StandardCharsets.ISO_8859_1);
        assertTrue(tile.length > 0);
        assertTrue(bytes.contains(LocalVectorTileEncoder.SOURCE_LAYER));
        assertTrue(bytes.contains(MplFeatureStyleProps.FEATURE_ID));
        assertTrue(bytes.contains(MplFeatureStyleProps.LAYER_ID));
        assertTrue(bytes.contains(MplFeatureStyleProps.SIGNATURE));
        assertTrue(bytes.contains("label"));
    }

    @Test
    public void multiPolygonTile_keepsContinuousGeometryCursor() throws Exception {
        GeoMultiPolygon multiPolygon = new GeoMultiPolygon();
        multiPolygon.add(square(1, 1, 2, 2));
        multiPolygon.add(square(6, 6, 7, 7));

        Feature feature = new Feature();
        feature.setId(42);
        feature.setGeometry(multiPolygon);

        byte[] tile = LocalVectorTileEncoder.encodePolygonTile(
                Collections.singletonList(feature),
                new LocalVectorTileEncoder.TileBounds(0, 0, 10, 10),
                7,
                null,
                null);

        int[] geometry = firstFeatureGeometry(tile);
        List<List<TilePoint>> rings = decodeRings(geometry);

        assertEquals(2, rings.size());
        assertBounds(rings.get(0), 410, 3277, 819, 3686);
        assertBounds(rings.get(1), 2458, 1229, 2867, 1638);
    }

    private static GeoPolygon square(double minX, double minY, double maxX, double maxY) {
        GeoPolygon polygon = new GeoPolygon();
        polygon.add(new GeoPoint(minX, minY));
        polygon.add(new GeoPoint(maxX, minY));
        polygon.add(new GeoPoint(maxX, maxY));
        polygon.add(new GeoPoint(minX, maxY));
        polygon.add(new GeoPoint(minX, minY));
        return polygon;
    }

    private static void assertBounds(
            List<TilePoint> ring,
            int expectedMinX,
            int expectedMinY,
            int expectedMaxX,
            int expectedMaxY) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (TilePoint point : ring) {
            minX = Math.min(minX, point.x);
            minY = Math.min(minY, point.y);
            maxX = Math.max(maxX, point.x);
            maxY = Math.max(maxY, point.y);
        }
        assertEquals(expectedMinX, minX, 1);
        assertEquals(expectedMinY, minY, 1);
        assertEquals(expectedMaxX, maxX, 1);
        assertEquals(expectedMaxY, maxY, 1);
    }

    private static int[] firstFeatureGeometry(byte[] tile) {
        int[] offset = new int[]{0};
        while (offset[0] < tile.length) {
            long tag = readVarint(tile, offset);
            int field = (int) (tag >> 3);
            int wireType = (int) (tag & 0x7);
            if (field == 3 && wireType == 2) {
                int length = (int) readVarint(tile, offset);
                return firstFeatureGeometryInLayer(tile, offset[0], offset[0] + length);
            }
            skipField(tile, offset, wireType);
        }
        return new int[0];
    }

    private static int[] firstFeatureGeometryInLayer(byte[] bytes, int start, int end) {
        int[] offset = new int[]{start};
        while (offset[0] < end) {
            long tag = readVarint(bytes, offset);
            int field = (int) (tag >> 3);
            int wireType = (int) (tag & 0x7);
            if (field == 2 && wireType == 2) {
                int length = (int) readVarint(bytes, offset);
                return geometryInFeature(bytes, offset[0], offset[0] + length);
            }
            skipField(bytes, offset, wireType);
        }
        return new int[0];
    }

    private static int[] geometryInFeature(byte[] bytes, int start, int end) {
        int[] offset = new int[]{start};
        while (offset[0] < end) {
            long tag = readVarint(bytes, offset);
            int field = (int) (tag >> 3);
            int wireType = (int) (tag & 0x7);
            if (field == 4 && wireType == 2) {
                int length = (int) readVarint(bytes, offset);
                List<Integer> values = new ArrayList<>();
                int geometryEnd = offset[0] + length;
                while (offset[0] < geometryEnd) {
                    values.add((int) readVarint(bytes, offset));
                }
                int[] out = new int[values.size()];
                for (int i = 0; i < values.size(); i++) {
                    out[i] = values.get(i);
                }
                return out;
            }
            skipField(bytes, offset, wireType);
        }
        return new int[0];
    }

    private static List<List<TilePoint>> decodeRings(int[] geometry) {
        List<List<TilePoint>> rings = new ArrayList<>();
        List<TilePoint> current = null;
        int cursorX = 0;
        int cursorY = 0;
        int i = 0;
        while (i < geometry.length) {
            int command = geometry[i++];
            int commandId = command & 0x7;
            int count = command >> 3;
            if (commandId == 1) {
                for (int c = 0; c < count && i + 1 < geometry.length; c++) {
                    cursorX += unZigZag(geometry[i++]);
                    cursorY += unZigZag(geometry[i++]);
                    current = new ArrayList<>();
                    current.add(new TilePoint(cursorX, cursorY));
                }
            } else if (commandId == 2) {
                for (int c = 0; c < count && i + 1 < geometry.length; c++) {
                    cursorX += unZigZag(geometry[i++]);
                    cursorY += unZigZag(geometry[i++]);
                    if (current != null) {
                        current.add(new TilePoint(cursorX, cursorY));
                    }
                }
            } else if (commandId == 7) {
                if (current != null) {
                    rings.add(current);
                    current = null;
                }
            }
        }
        return rings;
    }

    private static int unZigZag(int value) {
        return (value >>> 1) ^ -(value & 1);
    }

    private static void skipField(byte[] bytes, int[] offset, int wireType) {
        if (wireType == 0) {
            readVarint(bytes, offset);
        } else if (wireType == 1) {
            offset[0] += 8;
        } else if (wireType == 2) {
            int length = (int) readVarint(bytes, offset);
            offset[0] += length;
        } else if (wireType == 5) {
            offset[0] += 4;
        } else {
            offset[0] = bytes.length;
        }
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

    private static final class TilePoint {
        final int x;
        final int y;

        TilePoint(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }
}

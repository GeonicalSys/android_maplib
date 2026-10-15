package com.nextgis.maplib.map;

import com.nextgis.maplib.datasource.*;
import org.junit.Test;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Coordinate;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class LocalVectorTileLabelsTest {
    @Test public void oneOriginalQuarterAcrossFourTilesHasOnePointAndFourUnlabelledPolygons() throws Exception {
        Feature original = feature(square(-8, -8, 8, 8));
        int labels = 0;
        for (int x = -1; x <= 0; x++) for (int y = -1; y <= 0; y++) {
            LocalVectorTileEncoder.TileBounds bounds = bounds(x * 10, y * 10);
            Feature label = LocalVectorTileProvider.labelFeatureForTile(original, bounds);
            Feature clipped = LocalVectorTileProvider.clipFeatureToEnvelope(original,
                    new GeoEnvelope(bounds.minX, bounds.maxX, bounds.minY, bounds.maxY));
            DecodedTile decoded = decode(LocalVectorTileEncoder.encodePolygonTile(
                    Collections.singletonList(clipped), label == null ? Collections.emptyList()
                            : Collections.singletonList(label), bounds, 7, "_id", null));
            assertEquals(Collections.singletonList("features"), decoded.layers);
            for (Record record : decoded.records) {
                assertEquals(42, record.id);
                assertEquals(42L, record.properties.get(MplFeatureStyleProps.FEATURE_ID));
                if (record.type == 3) {
                    assertFalse(record.properties.containsKey(MplFeatureStyleProps.SIGNATURE));
                } else {
                    labels++;
                    assertEquals(1, record.type);
                    assertEquals(true, record.properties.get(LocalVectorTileEncoder.LABEL_ANCHOR));
                    assertEquals("42", record.properties.get(MplFeatureStyleProps.SIGNATURE));
                    assertTrue(record.geometry[1] >= 0 && record.geometry[1] < 8192);
                    assertTrue(record.geometry[2] >= 0 && record.geometry[2] < 8192);
                }
            }
        }
        assertEquals(1, labels);
    }

    @Test public void interiorAnchorAvoidsConcavityAndHoleAndChoosesOneLargestMultipartMember() throws Exception {
        GeoPolygon concave = polygon(-8,-8, 8,-8, 8,-4, -4,-4, -4,4, 8,4, 8,8, -8,8, -8,-8);
        GeoPolygon holed = square(-9,-9,9,9);
        holed.addInnerRing(square(-5,-5,5,5).getOuterRing());
        GeoMultiPolygon multi = new GeoMultiPolygon();
        multi.add(square(-30,-30,-29,-29));
        multi.add(holed);
        for (GeoGeometry geometry : Arrays.asList(concave, holed, multi)) {
            GeoPoint anchor = LocalVectorTileLabelAnchor.create(geometry);
            assertNotNull(anchor);
            org.locationtech.jts.geom.Geometry source = new WKTReader().read(geometry.toWKT(true));
            assertTrue(source.contains(new GeometryFactory().createPoint(
                    new Coordinate(anchor.getX(), anchor.getY()))));
            if (geometry == multi) assertTrue(anchor.getX() > -9 && anchor.getY() > -9);
        }
    }

    @Test public void ownerUsesUnbufferedBoundsAndRecomputesForChangedGeometry() {
        Feature original = feature(square(1,1,3,3));
        assertNotNull(LocalVectorTileProvider.labelFeatureForTile(original, bounds(0,0)));
        assertNull(LocalVectorTileProvider.labelFeatureForTile(original, bounds(10,0)));
        original.setGeometry(square(11,1,13,3));
        assertNull(LocalVectorTileProvider.labelFeatureForTile(original, bounds(0,0)));
        assertNotNull(LocalVectorTileProvider.labelFeatureForTile(original, bounds(10,0)));
    }

    @Test public void roundingNearSeamKeepsOwnedLabelRenderable() throws Exception {
        Feature original = feature(square(9.997,1,10.001,3));
        Feature label = LocalVectorTileProvider.labelFeatureForTile(original, bounds(0,0));
        DecodedTile tile = decode(LocalVectorTileEncoder.encodePolygonTile(Collections.emptyList(),
                Collections.singletonList(label), bounds(0,0), 7, null, "Quarter"));
        assertEquals(1, tile.records.size());
        assertEquals(4095, zigzag(tile.records.get(0).geometry[1]));
    }

    @Test public void noTextOrDegeneratePolygonDoesNotCreateLabelRecord() throws Exception {
        Feature original = feature(square(1,1,3,3));
        Feature label = LocalVectorTileProvider.labelFeatureForTile(original, bounds(0,0));
        assertEquals(1, decode(LocalVectorTileEncoder.encodePolygonTile(Collections.singletonList(original),
                Collections.singletonList(label), bounds(0,0), 7, null, null)).records.size());
        assertNull(LocalVectorTileLabelAnchor.create(polygon(0,0,1,0,2,0,0,0)));
        assertNull(LocalVectorTileLabelAnchor.create(null));
        assertNull(LocalVectorTileProvider.labelFeatureForTile(
                feature(square(9.999,1,9.9999,3)), bounds(0,0)));
    }

    private static Feature feature(GeoGeometry geometry) {
        Feature feature = new Feature(); feature.setId(42); feature.setGeometry(geometry); return feature;
    }
    private static LocalVectorTileEncoder.TileBounds bounds(double x, double y) {
        return new LocalVectorTileEncoder.TileBounds(x,y,x+10,y+10);
    }
    private static GeoPolygon square(double x1,double y1,double x2,double y2) {
        return polygon(x1,y1,x2,y1,x2,y2,x1,y2,x1,y1);
    }
    private static GeoPolygon polygon(double... xy) {
        GeoPolygon polygon = new GeoPolygon();
        for(int i=0;i<xy.length;i+=2) polygon.add(new GeoPoint(xy[i],xy[i+1]));
        return polygon;
    }

    // Read the emitted protobuf independently, including the complete layer/feature/tag structure.
    static DecodedTile decode(byte[] tile) {
        DecodedTile decoded = new DecodedTile();
        for (Wire layer : message(tile)) if (layer.field == 3) {
            List<String> keys = new ArrayList<>(); List<Object> values = new ArrayList<>();
            List<byte[]> features = new ArrayList<>();
            for (Wire field : message(layer.bytes)) {
                if(field.field==1) decoded.layers.add(new String(field.bytes,StandardCharsets.UTF_8));
                if(field.field==2) features.add(field.bytes);
                if(field.field==3) keys.add(new String(field.bytes,StandardCharsets.UTF_8));
                if(field.field==4) for(Wire value:message(field.bytes)) {
                    values.add(value.field==1 ? new String(value.bytes,StandardCharsets.UTF_8)
                            : value.field==7 ? value.number!=0 : (value.number>>>1)^-(value.number&1));
                }
            }
            for(byte[] bytes:features) {
                Record record = new Record();
                for(Wire field:message(bytes)) {
                    if(field.field==1) record.id=field.number;
                    if(field.field==3) record.type=(int)field.number;
                    if(field.field==4) record.geometry=packed(field.bytes);
                    if(field.field==2) {
                        int[] tags=packed(field.bytes);
                        for(int i=0;i<tags.length;i+=2) record.properties.put(keys.get(tags[i]),values.get(tags[i+1]));
                    }
                }
                decoded.records.add(record);
            }
        }
        return decoded;
    }
    static final class DecodedTile { final List<String> layers=new ArrayList<>(); final List<Record> records=new ArrayList<>(); }
    static final class Record { long id; int type; int[] geometry; final Map<String,Object> properties=new HashMap<>(); }
    private static final class Wire { int field; long number; byte[] bytes; }
    private static List<Wire> message(byte[] bytes) {
        List<Wire> fields=new ArrayList<>(); int[] offset={0};
        while(offset[0]<bytes.length) {
            long tag=varint(bytes,offset); Wire field=new Wire(); field.field=(int)(tag>>3);
            int wire=(int)(tag&7);
            if(wire==0) field.number=varint(bytes,offset);
            else if(wire==2) {int size=(int)varint(bytes,offset); field.bytes=Arrays.copyOfRange(bytes,offset[0],offset[0]+size);offset[0]+=size;}
            else throw new AssertionError("Unexpected wire type "+wire);
            fields.add(field);
        }
        return fields;
    }
    private static int[] packed(byte[] bytes) {
        int[] offset={0}; List<Integer> list=new ArrayList<>();
        while(offset[0]<bytes.length) list.add((int)varint(bytes,offset));
        int[] result=new int[list.size()];for(int i=0;i<result.length;i++)result[i]=list.get(i);return result;
    }
    private static long varint(byte[] bytes,int[] offset) {
        long value=0;int shift=0,part;
        do {part=bytes[offset[0]++]&255;value|=(long)(part&127)<<shift;shift+=7;}while((part&128)!=0);
        return value;
    }
    private static int zigzag(int value) {return (value>>>1)^-(value&1);}
}

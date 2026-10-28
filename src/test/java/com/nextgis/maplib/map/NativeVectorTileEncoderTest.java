package com.nextgis.maplib.map;

import com.google.gson.JsonParser;
import org.junit.Test;
import org.locationtech.jts.io.WKTReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class NativeVectorTileEncoderTest {
    @Test public void allSixKindsKeepMultipartCommandsHolesAndTheNative8192Grid() throws Exception {
        NativeVectorTileEncoder encoder=new NativeVectorTileEncoder(new NativeVectorTileEncoder.Bounds(0,0,8,8));
        String[] shapes={"POINT (1.25 2.5)","MULTIPOINT ((1 1),(2 2))","LINESTRING (1 1,2 1,3 1)",
                "MULTILINESTRING ((1 1,2 2),(3 3,4 4))","POLYGON ((1 1,7 1,7 7,1 7,1 1),(3 3,3 4,4 4,4 3,3 3))",
                "MULTIPOLYGON (((1 1,2 1,2 2,1 2,1 1)),((5 5,6 5,6 6,5 6,5 5)))"};
        for(int i=0;i<shapes.length;i++)encoder.add(i+1,Collections.singletonMap("featureid",Integer.toString(i+1)),new WKTReader().read(shapes[i]));
        Tile tile=decode(encoder.finish());
        assertEquals("features",tile.name);assertEquals(8192,tile.extent);assertEquals(2,tile.version);assertEquals(6,tile.records.size());
        assertArrayEquals(new int[]{1,1,2,2,3,3},tile.records.stream().mapToInt(r->r.type).toArray());
        assertArrayEquals(new int[]{1280,5632},coordinates(tile.records.get(0).commands).get(0));
        assertEquals(2,coordinates(tile.records.get(1).commands).size());
        assertEquals("Collinear original vertices must not be simplified",3,coordinates(tile.records.get(2).commands).size());
        assertEquals(2,commandCount(tile.records.get(3).commands,1));
        assertEquals(2,commandCount(tile.records.get(4).commands,7));assertEquals(2,commandCount(tile.records.get(5).commands,7));
    }
    @Test public void completeRulePaintLabelLayoutGatesAndOffsetsSurviveScalarMvtTransport() throws Exception {
        String json="{\"featureid\":\"9007199254740993\",\"layerid\":\"7\",\"order\":9007199254740993,"
                +"\"fillcolor\":\"#ff0000\",\"thinkness\":2.75,\"opacity\":0.4,\"signature\":\"Поле\","
                +"\"textoffset\":[-1.5,2.25],\"iconoffset\":[0,3],\"geometry_layout_key\":\"round|miter\","
                +"\"label_layout_key\":\"literal-group\",\"textscalewithzoom\":false,\"labelminzoom\":12,"
                +"\"textzoomstops\":\"[[8,1],[16,2]]\",\"unknownScalar\":\"kept\"}";
        NativeVectorTileEncoder encoder=new NativeVectorTileEncoder(new NativeVectorTileEncoder.Bounds(0,0,8,8));
        encoder.add(9007199254740993L,NativeVectorTilePropertyCodec.encode(JsonParser.parseString(json).getAsJsonObject()),new WKTReader().read("POINT (1 1)"));
        Record feature=decode(encoder.finish()).records.get(0);
        assertEquals(9007199254740993L,feature.id); assertEquals("9007199254740993",feature.properties.get("featureid"));
        assertEquals(9007199254740993L,feature.properties.get("order"));
        assertEquals("#ff0000",feature.properties.get("fillcolor"));assertEquals(2.75,feature.properties.get("thinkness"));
        assertEquals(0.4,feature.properties.get("opacity"));assertEquals("Поле",feature.properties.get("signature"));
        assertEquals(-1.5,feature.properties.get("__ng_text_offset_x"));assertEquals(2.25,feature.properties.get("__ng_text_offset_y"));
        assertEquals("round|miter",feature.properties.get("geometry_layout_key"));assertEquals("literal-group",feature.properties.get("label_layout_key"));
        assertEquals(false,feature.properties.get("textscalewithzoom"));assertEquals(12L,feature.properties.get("labelminzoom"));
        assertEquals("[[8,1],[16,2]]",feature.properties.get("textzoomstops"));assertEquals("kept",feature.properties.get("unknownScalar"));
    }

    // Independent protobuf reader: no encoder helpers/defaults are reused for expected contents.
    static final class Tile {String name;int extent,version;final List<Record> records=new ArrayList<>();}
    static final class Record {long id;int type;int[] commands;final Map<String,Object> properties=new LinkedHashMap<>();}
    static final class Wire {int field;long number;byte[] bytes;}
    static Tile decode(byte[] bytes) {
        Tile out=new Tile();
        for(Wire tile:message(bytes))if(tile.field==3){
            List<String> keys=new ArrayList<>();List<Object> values=new ArrayList<>();List<byte[]> features=new ArrayList<>();
            for(Wire field:message(tile.bytes)){
                if(field.field==1)out.name=new String(field.bytes,StandardCharsets.UTF_8);
                if(field.field==2)features.add(field.bytes);
                if(field.field==3)keys.add(new String(field.bytes,StandardCharsets.UTF_8));
                if(field.field==4){Wire value=message(field.bytes).get(0);values.add(value.field==1?new String(value.bytes,StandardCharsets.UTF_8):
                        value.field==3?Double.longBitsToDouble(value.number):value.field==7?(Object)(value.number!=0):(value.number>>>1)^-(value.number&1));}
                if(field.field==5)out.extent=(int)field.number;if(field.field==15)out.version=(int)field.number;
            }
            for(byte[] feature:features){Record record=new Record();for(Wire field:message(feature)){
                if(field.field==1)record.id=field.number;if(field.field==3)record.type=(int)field.number;
                if(field.field==4)record.commands=packed(field.bytes);
                if(field.field==2){int[] tags=packed(field.bytes);for(int i=0;i<tags.length;i+=2)record.properties.put(keys.get(tags[i]),values.get(tags[i+1]));}
            }out.records.add(record);}
        }return out;
    }
    private static List<Wire> message(byte[] bytes){List<Wire> out=new ArrayList<>();int[] at={0};while(at[0]<bytes.length){
        long tag=varint(bytes,at);Wire field=new Wire();field.field=(int)(tag>>>3);int wire=(int)(tag&7);
        if(wire==0)field.number=varint(bytes,at);
        else if(wire==1){for(int i=0;i<8;i++)field.number|=(long)(bytes[at[0]++]&255)<<(8*i);}
        else if(wire==2){int size=(int)varint(bytes,at);field.bytes=Arrays.copyOfRange(bytes,at[0],at[0]+size);at[0]+=size;}
        else throw new AssertionError("Unexpected protobuf wire "+wire);out.add(field);
    }return out;}
    private static int[] packed(byte[] bytes){List<Integer> values=new ArrayList<>();int[] at={0};while(at[0]<bytes.length)values.add((int)varint(bytes,at));return values.stream().mapToInt(Integer::intValue).toArray();}
    private static long varint(byte[] bytes,int[] at){long value=0;int shift=0,n;do{n=bytes[at[0]++]&255;value|=(long)(n&127)<<shift;shift+=7;}while((n&128)!=0);return value;}
    static List<int[]> coordinates(int[] commands){List<int[]> points=new ArrayList<>();int x=0,y=0;for(int i=0;i<commands.length;){int command=commands[i++],id=command&7,count=command>>>3;if(id==7)continue;
        for(int n=0;n<count;n++){int dx=commands[i++],dy=commands[i++];x+=(dx>>>1)^-(dx&1);y+=(dy>>>1)^-(dy&1);points.add(new int[]{x,y});}
    }return points;}
    static int commandCount(int[] commands,int requested){int count=0;for(int i=0;i<commands.length;){int command=commands[i++],id=command&7,n=command>>>3;if(id==requested)count+=n;if(id!=7)i+=2*n;}return count;}
}

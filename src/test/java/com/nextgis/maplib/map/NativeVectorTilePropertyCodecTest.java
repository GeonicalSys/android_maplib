package com.nextgis.maplib.map;

import com.google.gson.*;
import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class NativeVectorTilePropertyCodecTest {
    @Test public void scalarsAndUnknownScalarFieldsKeepTheirTypesWithoutMutatingRichProperties() throws Exception {
        JsonObject original = JsonParser.parseString("{\"enabled\":false,\"zero\":0,\"empty\":\"\",\"id\":9223372036854775807,\"fraction\":1.25,\"missing\":null}").getAsJsonObject();
        String before = original.toString(); Map<String,Object> out = NativeVectorTilePropertyCodec.encode(original);
        assertEquals(false,out.get("enabled")); assertEquals(0L,out.get("zero")); assertEquals("",out.get("empty"));
        assertEquals(Long.MAX_VALUE,out.get("id")); assertEquals(1.25,out.get("fraction"));
        assertFalse(out.containsKey("missing")); assertEquals(before,original.toString());
    }
    @Test public void onlyKnownPairArraysFlattenToTheExactNativeSemiliteralContract() throws Exception {
        JsonObject props = JsonParser.parseString("{\"textoffset\":[-2.5,3],\"iconoffset\":[0,7],\"textzoomstops\":\"[[8,1],[16,2]]\",\"label_min_zoom\":12}").getAsJsonObject();
        Map<String,Object> out = NativeVectorTilePropertyCodec.encode(props);
        assertEquals(-2.5,out.get("__ng_text_offset_x")); assertEquals(3L,out.get("__ng_text_offset_y"));
        assertEquals(0L,out.get("__ng_icon_offset_x")); assertEquals(7L,out.get("__ng_icon_offset_y"));
        assertEquals("[[8,1],[16,2]]",out.get("textzoomstops")); assertFalse(out.containsKey("textoffset"));
        assertEquals(2,props.getAsJsonArray("textoffset").size());
    }
    @Test public void unsupportedNestedOrInvalidPairsFailExplicitlyRatherThanStringifyingOrDropping() throws Exception {
        for (String json : new String[]{"{\"future\":[1,2]}","{\"future\":{\"a\":1}}",
                "{\"textoffset\":[1]}","{\"iconoffset\":[1,\"two\"]}",
                "{\"textoffset\":[1,2],\"__ng_text_offset_x\":99}"}) {
            try { NativeVectorTilePropertyCodec.encode(JsonParser.parseString(json).getAsJsonObject()); fail(json); }
            catch (SqliteVectorTileBackend.UnsupportedTileException expected) { assertFalse(expected.getMessage().contains("two")); }
        }
        JsonObject invalid = new JsonObject(); invalid.addProperty("width",Double.NaN);
        try { NativeVectorTilePropertyCodec.encode(invalid); fail(); }
        catch (SqliteVectorTileBackend.UnsupportedTileException expected) { assertTrue(expected.getMessage().contains("width")); }
    }
}

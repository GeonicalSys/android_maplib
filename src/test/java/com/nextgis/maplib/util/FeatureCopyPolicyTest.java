package com.nextgis.maplib.util;

import com.nextgis.maplib.datasource.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static com.nextgis.maplib.util.GeoConstants.*;

public class FeatureCopyPolicyTest {
    @Test public void onlyLosslessSingleMultiConversionsAreOffered() {
        GeoGeometry point = GeoGeometryFactory.fromWKT("POINT (1 2)", CRS_WEB_MERCATOR);
        assertTrue(FeatureCopyPolicy.accepts(point, GTPoint));
        assertTrue(FeatureCopyPolicy.accepts(point, GTMultiPoint));
        assertFalse(FeatureCopyPolicy.accepts(point, GTLineString));
        GeoGeometry multi = FeatureCopyPolicy.geometry(point, GTMultiPoint);
        assertEquals(GTMultiPoint, multi.getType()); assertEquals(CRS_WEB_MERCATOR, multi.getCRS());
        assertEquals(point.toWKT(true), FeatureCopyPolicy.geometry(multi, GTPoint).toWKT(true));
        GeoPoint extra = new GeoPoint(3, 4); extra.setCRS(CRS_WEB_MERCATOR);
        ((GeoMultiPoint) multi).add(extra);
        assertFalse(FeatureCopyPolicy.accepts(multi, GTPoint));
        assertTrue(FeatureCopyPolicy.accepts(multi, GTMultiPoint));
        assertEquals(GTPoint, point.getType());
    }

    @Test public void matchesKeysAndTypesAndPreservesNullZeroAndLargeIntegersWithoutIds() {
        List<Field> sourceFields = Arrays.asList(new Field(FTString,"name","Alias"),
                new Field(FTInteger,"zero","Zero"), new Field(FTLong,"big","Big"),
                new Field(FTString,"empty","Empty"), new Field(FTString,"mismatch","Type"),
                new Field(FTLong,Constants.FIELD_ID,"ID"));
        Feature source = new Feature(17, sourceFields);
        source.setFieldValue("name","Sample"); source.setFieldValue("zero",0);
        source.setFieldValue("big",9007199254740993L); source.setFieldValue("empty",null);
        source.setFieldValue("mismatch","42"); source.setFieldValue(Constants.FIELD_ID,17L);
        Map<String,Object> copied = FeatureCopyPolicy.attributes(source, Arrays.asList(
                new Field(FTString,"name","Different alias"), new Field(FTLong,"zero","Zero"),
                new Field(FTLong,"big","Big"), new Field(FTString,"empty","Empty"),
                new Field(FTInteger,"mismatch","Type"), new Field(FTString,"absent","Alias"),
                new Field(FTLong,Constants.FIELD_ID,"ID")));
        assertEquals(4, copied.size()); assertEquals("Sample",copied.get("name"));
        assertEquals(0,copied.get("zero")); assertEquals(9007199254740993L,copied.get("big"));
        assertTrue(copied.containsKey("empty")); assertNull(copied.get("empty"));
        assertEquals(17,source.getId());
    }
}

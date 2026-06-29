package com.nextgis.maplib.util;

import com.nextgis.maplib.datasource.Field;
import com.nextgis.maplib.datasource.ngw.Connection;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DistrictFilterUtilTest {

    @Test
    public void buildFldEqualsQuery_latinValue() {
        assertEquals("fld_district=vologda",
                DistrictFilterUtil.buildFldEqualsQuery("district", "vologda"));
    }

    @Test
    public void resolveDistrictFilter_postgisWithField_active() {
        Map<String, Field> fields = new HashMap<>();
        fields.put("district", new Field(1, "district", "District"));
        DistrictFilterUtil.Decision d = DistrictFilterUtil.resolveDistrictFilter(
                Connection.NGWResourceTypePostgisLayer,
                fields,
                "vologda");
        assertTrue(d.active);
        assertEquals("fld_district=vologda", d.serverWhere);
    }

    @Test
    public void resolveDistrictFilter_vectorLayer_inactive() {
        Map<String, Field> fields = new HashMap<>();
        fields.put("district", new Field(1, "district", "District"));
        DistrictFilterUtil.Decision d = DistrictFilterUtil.resolveDistrictFilter(
                Connection.NGWResourceTypeVectorLayer,
                fields,
                "vologda");
        assertFalse(d.active);
    }

    @Test
    public void resolveDistrictFilter_noDistrictField_inactive() {
        Map<String, Field> fields = new HashMap<>();
        fields.put("name", new Field(1, "name", "Name"));
        DistrictFilterUtil.Decision d = DistrictFilterUtil.resolveDistrictFilter(
                Connection.NGWResourceTypePostgisLayer,
                fields,
                "vologda");
        assertFalse(d.active);
    }

    @Test
    public void resolveDistrictFilter_emptyCollectorDistrict_inactive() {
        Map<String, Field> fields = new HashMap<>();
        fields.put("district", new Field(1, "district", "District"));
        DistrictFilterUtil.Decision d = DistrictFilterUtil.resolveDistrictFilter(
                Connection.NGWResourceTypePostgisLayer,
                fields,
                null);
        assertFalse(d.active);
    }

    @Test
    public void hasField_map_containsKey() {
        Map<String, Field> fields = new HashMap<>();
        fields.put("district", new Field(1, "district", "D"));
        assertTrue(DistrictFilterUtil.hasField(fields, "district"));
        assertFalse(DistrictFilterUtil.hasField(fields, "other"));
    }

    @Test
    public void hasField_collection_findsKeyname() {
        assertTrue(DistrictFilterUtil.hasField(
                Collections.singletonList(new Field(1, "district", "D")),
                "district"));
        assertFalse(DistrictFilterUtil.hasField(
                Collections.singletonList(new Field(1, "other", "O")),
                "district"));
    }
}

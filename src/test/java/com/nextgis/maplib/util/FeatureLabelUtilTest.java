package com.nextgis.maplib.util;

import static com.nextgis.maplib.util.GeoConstants.FTInteger;
import static com.nextgis.maplib.util.GeoConstants.FTString;
import static org.junit.Assert.assertEquals;

import com.nextgis.maplib.datasource.Feature;
import com.nextgis.maplib.datasource.Field;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class FeatureLabelUtilTest
{
    private final List<Field> fields = Arrays.asList(
            new Field(FTString, "name", "Name"),
            new Field(FTInteger, "num_line", "Line"));

    @Test
    public void selectedFieldIsUsedForFeatureLabel()
    {
        Feature feature = new Feature(17, fields);
        feature.setFieldValue("num_line", 42);

        String normalized = FeatureLabelUtil.normalizeFieldName("num_line", fields);

        assertEquals("num_line", normalized);
        assertEquals("42", FeatureLabelUtil.resolve(feature, normalized));
    }

    @Test
    public void idIsFallbackForMissingFieldOrValue()
    {
        Feature feature = new Feature(17, fields);

        assertEquals(
                Constants.FIELD_ID,
                FeatureLabelUtil.normalizeFieldName("removed_field", fields));
        assertEquals("17", FeatureLabelUtil.resolve(feature, Constants.FIELD_ID));
        assertEquals("17", FeatureLabelUtil.resolve(feature, "num_line"));
    }
}

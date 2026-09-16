package com.nextgis.maplib.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NgwFeatureCountParserTest {

    @Test
    public void emptyOrInvalid_isUnknown() {
        assertEquals(NgwFeatureCountParser.UNKNOWN, NgwFeatureCountParser.parse(null, false));
        assertEquals(NgwFeatureCountParser.UNKNOWN, NgwFeatureCountParser.parse("  ", true));
        assertEquals(NgwFeatureCountParser.UNKNOWN, NgwFeatureCountParser.parse("[1,2]", false));
        assertEquals(NgwFeatureCountParser.UNKNOWN, NgwFeatureCountParser.parse("{", false));
    }

    @Test
    public void unfiltered_usesTotalCount() {
        assertEquals(12, NgwFeatureCountParser.parse("{\"total_count\":12}", false));
        assertEquals(0, NgwFeatureCountParser.parse("{\"total_count\":0}", false));
        assertEquals(NgwFeatureCountParser.UNKNOWN, NgwFeatureCountParser.parse("{}", false));
    }

    @Test
    public void filtered_usesFilteredCountOnly() {
        assertEquals(4, NgwFeatureCountParser.parse(
                "{\"total_count\":100,\"filtered_count\":4}", true));
        assertEquals(0, NgwFeatureCountParser.parse(
                "{\"total_count\":100,\"filtered_count\":0}", true));
    }

    @Test
    public void filteredWithoutFilteredCount_isFallbackNotZeroOrTotal() {
        assertEquals(NgwFeatureCountParser.NEED_FALLBACK,
                NgwFeatureCountParser.parse("{\"total_count\":100}", true));
        assertEquals(NgwFeatureCountParser.NEED_FALLBACK,
                NgwFeatureCountParser.parse("{}", true));
    }
}

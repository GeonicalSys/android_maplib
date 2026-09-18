package com.nextgis.maplib.datasource;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import static com.nextgis.maplib.util.GeoConstants.*;
import static org.junit.Assert.*;

public class FeatureComparisonTest {
    private static class SequentialFields extends LinkedList<Field> {
        @Override public Field get(int index) {
            throw new AssertionError("Indexed traversal of linked schema");
        }
    }

    @Test public void wideReorderedLinkedSchemasDoNotUseIndexedTraversal() {
        SequentialFields left = new SequentialFields();
        SequentialFields right = new SequentialFields();
        for (int i = 0; i < 1000; i++) {
            left.add(new Field(FTString, "field" + i, null));
            right.addFirst(new Field(FTString, "field" + i, null));
        }
        Feature a = new Feature(1, left);
        Feature b = new Feature(1, right);
        for (int i = 0; i < 1000; i++) {
            a.setFieldValue(i, "value" + i);
            b.setFieldValue(999 - i, "value" + i);
        }
        assertTrue(a.equalsData(b));
        assertEquals(999, a.getFieldValueIndex("field999"));
        assertEquals(-1, a.getFieldValueIndex("absent"));
    }

    @Test public void missingNullAndFirstDuplicateRemainEquivalent() {
        Feature a = new Feature(1, Arrays.asList(
                new Field(FTString, "name", null), new Field(FTString, "missing", null)));
        Feature b = new Feature(1, Arrays.asList(
                new Field(FTString, "name", null), new Field(FTString, "name", null)));
        a.setFieldValue(0, "first");
        b.setFieldValue(0, "first");
        b.setFieldValue(1, "second");
        assertTrue(a.equalsData(b));
    }

    @Test public void renamedFieldIsNotHiddenByAStaleIndex() {
        Field field = new Field(FTString, "before", null);
        Feature a = new Feature(1, Collections.singletonList(field));
        Feature b = new Feature(1, Collections.singletonList(field));
        a.setFieldValue(0, "value");
        b.setFieldValue(0, "value");
        assertTrue(a.equalsData(b));
        field.setName("after");
        assertTrue(a.equalsData(b));
        assertEquals(0, a.getFieldValueIndex("after"));
    }

    @Test public void numericAndDateRepresentationsRetainEquality() {
        java.util.List<Field> fields = Arrays.asList(new Field(FTInteger, "i", null),
                new Field(FTLong, "l", null), new Field(FTReal, "r", null),
                new Field(FTDateTime, "d", null));
        Feature a = new Feature(1, fields);
        Feature b = new Feature(1, fields);
        Object[] av = {7, 12L, 3.5, 1000L};
        Object[] bv = {7L, 12, 3.5, 1000L};
        for (int i = 0; i < av.length; i++) {
            a.setFieldValue(i, av[i]);
            b.setFieldValue(i, bv[i]);
        }
        assertTrue(a.equalsData(b));
    }
}

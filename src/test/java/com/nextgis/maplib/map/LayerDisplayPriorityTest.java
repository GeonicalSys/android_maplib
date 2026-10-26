package com.nextgis.maplib.map;

import org.junit.Test;
import static com.nextgis.maplib.util.GeoConstants.*;
import static org.junit.Assert.*;

public class LayerDisplayPriorityTest {
    @Test public void fieldLineRoleRequiresBothKnownNameAndLineGeometry() {
        assertTrue(LayerDisplayPriority.isFieldLines(GTLineString, "Полевые линейные"));
        assertTrue(LayerDisplayPriority.isFieldLines(GTMultiLineString, "  ПОЛЕВЫЕ ЛИНЕЙНЫЕ  "));
        assertTrue(LayerDisplayPriority.isFieldLines(GTLineString, "field_lines"));
        assertFalse(LayerDisplayPriority.isFieldLines(GTLineString, "Прочие линии"));
        assertFalse(LayerDisplayPriority.isFieldLines(GTLineString, "Полевые линейные архив"));
        assertFalse(LayerDisplayPriority.isFieldLines(GTPolygon, "Полевые линейные"));
        assertFalse(LayerDisplayPriority.isFieldLines(GTPoint, "field_lines"));
        assertFalse(LayerDisplayPriority.isFieldLines(GTLineString, null));
    }
}

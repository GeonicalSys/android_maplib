package com.nextgis.maplib.map.MLP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.maplibre.geojson.Point;

import java.util.List;

public class PolygonSketchPolicyTest {

    @Test
    public void fallbackStarterRing_isClosedSquare() {
        List<Point> ring = PolygonEditClass.createPointsForRing(null, null, true);

        assertEquals(5, ring.size());
        assertSame(ring.get(0), ring.get(4));
        assertEquals(ring.get(0).latitude(), ring.get(1).latitude(), 0.0);
        assertEquals(ring.get(1).longitude(), ring.get(2).longitude(), 0.0);
        assertEquals(ring.get(2).latitude(), ring.get(3).latitude(), 0.0);
        assertEquals(ring.get(3).longitude(), ring.get(0).longitude(), 0.0);
    }

    @Test
    public void multipolygonSketch_acceptsOnlyFirstPolygonPart() {
        assertTrue(MultiPolygonEditClass.canAddPolygonPart(0));
        assertFalse(MultiPolygonEditClass.canAddPolygonPart(1));
        assertFalse(MultiPolygonEditClass.canAddPolygonPart(2));
    }
}

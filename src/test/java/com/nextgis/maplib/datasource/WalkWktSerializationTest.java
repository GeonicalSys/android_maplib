package com.nextgis.maplib.datasource;

import org.junit.Test;
import java.util.LinkedList;
import static org.junit.Assert.*;

/** WKT remains byte-compatible, while bulk traversal must not index a linked list. */
public class WalkWktSerializationTest {
    private static final class IteratorOnlyPoints extends LinkedList<GeoPoint> {
        @Override public GeoPoint get(int index) {
            if (index != 0 && index != size() - 1)
                throw new AssertionError("Indexed linked-list traversal grows quadratically");
            return super.get(index);
        }
    }

    private void fill(GeoLineString line) {
        line.mPoints = new IteratorOnlyPoints();
        line.add(new GeoPoint(1, 2));
        line.add(new GeoPoint(3, 4));
        line.add(new GeoPoint(5, 6));
        line.add(new GeoPoint(7, 8));
    }

    @Test public void lineUsesOrderedTraversalAndKeepsExactWkt() {
        GeoLineString line = new GeoLineString();
        fill(line);
        assertEquals("LINESTRING (1.0 2.0, 3.0 4.0, 5.0 6.0, 7.0 8.0)", line.toWKT(true));
        assertEquals("(1.0 2.0, 3.0 4.0, 5.0 6.0, 7.0 8.0)", line.toWKT(false));
        assertEquals("LINESTRING  EMPTY", new GeoLineString().toWKT(true));
    }

    @Test public void openAndClosedRingKeepTheSameClosingRule() {
        GeoLinearRing ring = new GeoLinearRing();
        fill(ring);
        String expected = "LINEARRING (1.0 2.0, 3.0 4.0, 5.0 6.0, 7.0 8.0, 1.0 2.0)";
        assertEquals(expected, ring.toWKT(true));
        ring.add(new GeoPoint(1, 2));
        assertEquals(expected, ring.toWKT(true));
        assertEquals("LINEARRING  EMPTY", new GeoLinearRing().toWKT(true));
    }
}

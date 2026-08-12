package com.nextgis.maplib.datasource;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Identify uses tap envelopes against {@link GeoLineString#intersects(GeoEnvelope)}.
 * RTree only returns bbox candidates — lines must not hit empty corners of an L-shape.
 */
public class GeoLineStringIntersectsTest {

    /** L-shape: horizontal (0,0)-(10,0) and vertical (10,0)-(10,10). */
    private static GeoLineString lShape() {
        GeoLineString line = new GeoLineString();
        line.add(new GeoPoint(0, 0));
        line.add(new GeoPoint(10, 0));
        line.add(new GeoPoint(10, 10));
        return line;
    }

    @Test
    public void lShape_emptyCornerOfBbox_misses() {
        GeoLineString line = lShape();
        // Empty corner of the feature envelope [0..10]x[0..10]
        GeoEnvelope tap = new GeoEnvelope(1, 3, 7, 9);
        assertTrue(line.getEnvelope().intersects(tap));
        assertFalse(line.intersects(tap));
    }

    @Test
    public void lShape_tapOnHorizontalArm_hits() {
        GeoLineString line = lShape();
        GeoEnvelope tap = new GeoEnvelope(4, 6, -1, 1);
        assertTrue(line.intersects(tap));
    }

    @Test
    public void lShape_tapOnVerticalArm_hits() {
        GeoLineString line = lShape();
        GeoEnvelope tap = new GeoEnvelope(9, 11, 4, 6);
        assertTrue(line.intersects(tap));
    }

    @Test
    public void shortSegment_fullyInsideTapEnvelope_hits() {
        GeoLineString line = new GeoLineString();
        line.add(new GeoPoint(5, 5));
        line.add(new GeoPoint(6, 5));
        GeoEnvelope tap = new GeoEnvelope(0, 10, 0, 10);
        assertTrue(line.intersects(tap));
    }

    @Test
    public void shortSegment_verticesInsideSmallTap_hits() {
        GeoLineString line = new GeoLineString();
        line.add(new GeoPoint(5.2, 5.2));
        line.add(new GeoPoint(5.8, 5.2));
        // Entire segment inside tap; does not cross envelope edges.
        GeoEnvelope tap = new GeoEnvelope(5, 6, 5, 6);
        assertTrue(line.intersects(tap));
    }
}

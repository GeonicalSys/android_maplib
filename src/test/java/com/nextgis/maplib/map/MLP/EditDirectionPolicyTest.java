package com.nextgis.maplib.map.MLP;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class EditDirectionPolicyTest {
    @Test
    public void openPartUsesFollowingVertex() {
        assertEquals(4, EditDirectionPolicy.nextOpenVertex(3, 2, 6));
    }

    @Test
    public void openPartHasNoNextVertexAtEnd() {
        assertEquals(-1, EditDirectionPolicy.nextOpenVertex(5, 2, 6));
    }

    @Test
    public void closedRingWrapsToItsFirstVertex() {
        assertEquals(7, EditDirectionPolicy.nextClosedVertex(10, 7, 11));
    }

    @Test
    public void closedRingDoesNotCrossPartBoundary() {
        assertEquals(-1, EditDirectionPolicy.nextClosedVertex(6, 7, 11));
    }
}

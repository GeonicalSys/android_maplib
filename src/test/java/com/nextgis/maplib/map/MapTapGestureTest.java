package com.nextgis.maplib.map;

import org.junit.Test;
import static org.junit.Assert.*;

public class MapTapGestureTest {
    @Test public void pointPlacementScalesWithDensityAndRespectsSystemSlop() {
        assertEquals(12, MapTapGesture.tolerance(1, 8, true), 0);
        assertEquals(36, MapTapGesture.tolerance(3, 24, true), 0);
        assertEquals(40, MapTapGesture.tolerance(3, 40, true), 0);
        assertEquals(24, MapTapGesture.tolerance(3, 24, false), 0);
    }

    @Test public void microMovementIsOneTapIncludingTheBoundary() {
        MapTapGesture tap = new MapTapGesture();
        tap.begin(10, 20, 100, 12, 500);
        tap.move(15, 24);
        assertTrue(tap.finish(22, 20, 200));
        assertFalse(tap.finish(22, 20, 201));
    }

    @Test public void dragReturningToStartDoesNotBecomeATap() {
        MapTapGesture tap = new MapTapGesture();
        tap.begin(10, 20, 100, 12, 500);
        tap.move(23, 20);
        tap.move(10, 20);
        assertFalse(tap.finish(10, 20, 200));
    }

    @Test public void diagonalMoveOutsideRadiusIsADrag() {
        MapTapGesture tap = new MapTapGesture();
        tap.begin(0, 0, 100, 12, 500);
        assertFalse(tap.finish(10, 10, 200));
    }

    @Test public void cancelOrPinchNeedsANewDownBeforeAnotherTap() {
        MapTapGesture tap = new MapTapGesture();
        assertFalse(tap.finish(0, 0, 100));
        tap.begin(0, 0, 100, 12, 500);
        tap.cancel();
        assertFalse(tap.finish(0, 0, 200));
        tap.begin(0, 0, 300, 12, 500);
        assertTrue(tap.finish(0, 0, 400));
    }

    @Test public void longPressDoesNotAddAPointEvenWithoutAHostCallback() {
        MapTapGesture tap = new MapTapGesture();
        tap.begin(0, 0, 100, 12, 500);
        assertFalse(tap.finish(0, 0, 600));
    }
}

package com.nextgis.maplib.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class LocationFixPolicyTest {
    private static long seconds(long value) { return value * 1_000_000_000L; }

    @Test public void screenOffFixExpiresUsingTimeThatIncludesSleep() {
        assertTrue(LocationFixPolicy.isFresh(seconds(100), seconds(108)));
        assertFalse(LocationFixPolicy.isFresh(seconds(100), seconds(109)));
        assertFalse(LocationFixPolicy.isFresh(seconds(100), seconds(500)));
        assertFalse(LocationFixPolicy.isFresh(0, seconds(500)));
    }

    @Test public void newNetworkFixReplacesGpsFromBeforeSleep() {
        assertFalse(LocationFixPolicy.preferGps(seconds(100), 2, seconds(499), 500, seconds(500)));
        assertTrue(LocationFixPolicy.preferGps(seconds(499), 8, seconds(499), 500, seconds(500)));
        assertFalse(LocationFixPolicy.preferGps(seconds(498), 300, seconds(499), 30, seconds(500)));
    }

    @Test public void corruptAndFarFutureFixesAreUnknown() {
        assertFalse(LocationFixPolicy.isFresh(seconds(503), seconds(500)));
        assertFalse(LocationFixPolicy.validPosition(Double.NaN, 37, 5));
        assertFalse(LocationFixPolicy.validPosition(91, 37, 5));
        assertFalse(LocationFixPolicy.validPosition(55, 181, 5));
        assertFalse(LocationFixPolicy.validPosition(55, 37, Float.POSITIVE_INFINITY));
        assertTrue(LocationFixPolicy.validPosition(55, 37, 5000));
    }

    @Test public void samplingRetainsCornersAndSuppressesStationaryDuplicates() {
        assertFalse(RecordingSamplingPolicy.save(60_000, 0.3, 2000, 0));
        assertFalse(RecordingSamplingPolicy.save(1000, 20, 5000, 5));
        assertTrue(RecordingSamplingPolicy.save(5000, 20, 5000, 5));
        assertTrue(RecordingSamplingPolicy.isCorner(5, 5, 90, 0));
        assertFalse(RecordingSamplingPolicy.isCorner(0.3, 0.3, 90, 0));
        assertFalse(RecordingSamplingPolicy.isCorner(10, 10, 355, 5));
    }
}

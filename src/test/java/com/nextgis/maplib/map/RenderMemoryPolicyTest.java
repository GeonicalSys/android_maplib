package com.nextgis.maplib.map;

import org.junit.Test;
import static com.nextgis.maplib.map.RenderMemoryPolicy.*;
import static org.junit.Assert.*;

public class RenderMemoryPolicyTest {
    @Test public void nominalRamSizesTheDiskCacheButDoesNotSlowARoomyDevice() {
        Limits low = limits(2 * GIB, false), normal = limits(8 * GIB, false);
        assertEquals(32 * MIB, low.diskCacheBytes); assertEquals(128 * MIB, normal.diskCacheBytes);
        assertFalse(serializeTileWork(low, GIB, 64 * MIB, 400 * MIB, false));
        assertFalse(freeMemoryUnderPressure(low, GIB, 64 * MIB, 400 * MIB, false));
        assertFalse(serializeTileWork(normal, 2 * GIB, 128 * MIB, 400 * MIB, false));
        assertTrue(canStartTile(low, GIB, 64 * MIB, 400 * MIB, false));
    }
    @Test public void busyEightGiBPhoneUsesActualFreeMemoryNotOnlyItsNominalCapacity() {
        Limits normal = limits(8 * GIB, false);
        assertTrue(serializeTileWork(normal, 256 * MIB, 128 * MIB, 400 * MIB, false));
        assertTrue(canStartTile(normal, 256 * MIB, 128 * MIB, 400 * MIB, false));
        assertFalse(canStartTile(normal, 160 * MIB, 128 * MIB, 400 * MIB, false));
    }
    @Test public void fourGiBFreeKeepsEightAndTwelveGiBPhonesUnconstrained() {
        // This matches the actual phone's >4 GiB available during the former false warning.
        // There is intentionally no used/RSS input to any admission or warning decision.
        for (long ram : new long[]{8 * GIB, 12 * GIB}) {
            Limits limits = limits(ram, false);
            assertFalse(freeMemoryUnderPressure(limits, 4 * GIB, 128 * MIB, 300 * MIB, false));
            assertFalse(serializeTileWork(limits, 4 * GIB, 128 * MIB, 300 * MIB, false));
            assertTrue(canStartTile(limits, 4 * GIB, 128 * MIB, 300 * MIB, false));
        }
    }
    @Test public void smallFreeJavaRemainderStillProtectsAgainstTheSeparateJavaHeapLimit() {
        Limits limits = limits(12 * GIB, false);
        assertTrue(freeMemoryUnderPressure(limits, 4 * GIB, 128 * MIB, 63 * MIB, false));
        assertTrue(serializeTileWork(limits, 4 * GIB, 128 * MIB, 63 * MIB, false));
        assertFalse(canStartTile(limits, 4 * GIB, 128 * MIB, 63 * MIB, false));
    }
    @Test public void freeMemoryLossWarnsBeforeTheOsCriticalBoundaryWithoutHidingTiles() {
        Limits limits = limits(8 * GIB, false);
        assertTrue(serializeTileWork(limits, 400 * MIB, 128 * MIB, 300 * MIB, false));
        assertTrue(canStartTile(limits, 400 * MIB, 128 * MIB, 300 * MIB, false));
        assertTrue(freeMemoryUnderPressure(limits, 192 * MIB, 128 * MIB, 300 * MIB, false));
    }
    @Test public void exactSystemAndJavaBoundariesAreRetryableWithoutDroppingLayerContents() {
        Limits limits = limits(2 * GIB, false);
        assertFalse(canStartTile(limits, 96 * MIB, 64 * MIB, 200 * MIB, false));
        assertTrue(canStartTile(limits, 96 * MIB + 1, 64 * MIB, 200 * MIB, false));
        assertFalse(canStartTile(limits, GIB, 64 * MIB, 32 * MIB - 1, false));
        assertTrue(canStartTile(limits, GIB, 64 * MIB, 32 * MIB, false));
        assertFalse(canStartTile(limits, GIB, 64 * MIB, 400 * MIB, true));
    }
    @Test public void unknownOsCountersChooseConservativeCachesWithoutInventingZeroFreeMemory() {
        Limits limits = limits(0, false);
        assertTrue(limits.lowMemoryDevice); assertEquals(32 * MIB, limits.diskCacheBytes);
        assertTrue(canStartTile(limits, -1, 0, 400 * MIB, false));
        assertFalse(serializeTileWork(limits, -1, 0, 400 * MIB, false));
        assertFalse(freeMemoryUnderPressure(limits, -1, 0, 400 * MIB, false));
    }
    @Test public void arithmeticOverflowCannotAccidentallyOpenAdmission() {
        Limits limits = limits(2 * GIB, false);
        assertEquals(Long.MAX_VALUE, plus(Long.MAX_VALUE, 1));
        assertEquals(Long.MAX_VALUE, times(Long.MAX_VALUE, 2));
        assertFalse(canStartTile(limits, GIB, Long.MAX_VALUE, 400 * MIB, false));
    }
}

package com.nextgis.maplib.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RasterWhiteChromaKeyTest {
    @Test
    public void punchesOnlyOpaqueExactWhite() {
        int[] pixels = new int[] {
                0xffffffff,
                0xfffffefe,
                0x00ffffff,
                0xff000000
        };
        assertTrue(RasterWhiteChromaKey.punchExactWhite(pixels));
        assertEquals(0x00ffffff, pixels[0]);
        assertEquals(0xfffffefe, pixels[1]);
        assertEquals(0x00ffffff, pixels[2]);
        assertEquals(0xff000000, pixels[3]);
    }

    @Test
    public void ignoresTilesWithoutWhite() {
        int[] pixels = new int[] {0xff010101, 0xfffefefe};
        assertFalse(RasterWhiteChromaKey.punchExactWhite(pixels));
        assertEquals(0xff010101, pixels[0]);
    }
}

package com.nextgis.maplib.display;

import org.junit.Test;

import static org.junit.Assert.*;

public class PatternTilePeriodTest {
    @Test public void rectangularArgbMotifsRetainEveryPixelAndLeaveTheInputUntouched() {
        int[] pixels = new int[512 * 512];
        for (int y = 0; y < 512; y++) for (int x = 0; x < 512; x++) {
            // All ARGB bits participate, including different RGB beneath zero alpha.
            pixels[y * 512 + x] = (y % 64 << 24) | (x % 32 << 8) | (y % 64);
        }
        int[] before = pixels.clone();
        PatternTilePeriod.Period repeat = PatternTilePeriod.find(pixels, 512, 512);
        assertEquals(32, repeat.width);
        assertEquals(64, repeat.height);
        assertWrappedPixels(pixels, 512, 512, repeat);
        assertArrayEquals(before, pixels);
    }

    @Test public void constantAndOneDimensionalImagesKeepTheMinimumAndRectangularShape() {
        int[] pixels = new int[512 * 512];
        PatternTilePeriod.Period repeat = PatternTilePeriod.find(pixels, 512, 512);
        assertEquals(16, repeat.width);
        assertEquals(16, repeat.height);
        assertWrappedPixels(pixels, 512, 512, repeat);
        for (int y = 0; y < 512; y++) for (int x = 0; x < 512; x++) {
            pixels[y * 512 + x] = 0xff000000 | y % 128;
        }
        repeat = PatternTilePeriod.find(pixels, 512, 512);
        assertEquals(16, repeat.width);
        assertEquals(128, repeat.height);
        assertWrappedPixels(pixels, 512, 512, repeat);
    }

    @Test public void aSingleLastPixelDifferencePreventsAnUnsafeCropOnEitherAxis() {
        int[] pixels = new int[512 * 512];
        pixels[pixels.length - 1] = 1;
        PatternTilePeriod.Period repeat = PatternTilePeriod.find(pixels, 512, 512);
        assertEquals(512, repeat.width);
        assertEquals(512, repeat.height);
        assertWrappedPixels(pixels, 512, 512, repeat);
    }

    @Test public void independentPixelsAndNonDivisibleDimensionsKeepTheirOriginalSize() {
        int[] pixels = new int[512 * 256];
        for (int y = 0; y < 256; y++) for (int x = 0; x < 512; x++) {
            pixels[y * 512 + x] = x | (y << 16);
        }
        PatternTilePeriod.Period repeat = PatternTilePeriod.find(pixels, 512, 256);
        assertEquals(512, repeat.width);
        assertEquals(256, repeat.height);
        assertWrappedPixels(pixels, 512, 256, repeat);
        repeat = PatternTilePeriod.find(new int[19 * 23], 19, 23);
        assertEquals(19, repeat.width);
        assertEquals(23, repeat.height);
    }

    @Test public void allBinaryFourByFourTilesMatchAnIndependentMinimalPeriodOracle() {
        int[] pixels = new int[16];
        for (int mask = 0; mask < 65536; mask++) {
            for (int i = 0; i < pixels.length; i++) pixels[i] = (mask >>> i & 1) == 0 ? 0 : 0xffa1b2c3;
            PatternTilePeriod.Period repeat = PatternTilePeriod.find(pixels, 4, 4, 1);
            assertWrappedPixels(pixels, 4, 4, repeat);
            // Enumerate all divisors and compare the full reconstruction, independently
            // of the implementation's separate horizontal and vertical scans.
            int smallestArea = 16;
            for (int h : new int[]{1, 2, 4}) for (int w : new int[]{1, 2, 4}) {
                boolean equal = true;
                for (int i = 0; i < pixels.length; i++) {
                    if (pixels[i] != pixels[(i / 4 % h) * 4 + i % 4 % w]) equal = false;
                }
                if (equal) smallestArea = Math.min(smallestArea, w * h);
            }
            assertEquals(smallestArea, repeat.width * repeat.height);
        }
    }

    @Test public void malformedOrOverflowingPixelRectanglesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> PatternTilePeriod.find(null, 16, 16));
        assertThrows(IllegalArgumentException.class, () -> PatternTilePeriod.find(new int[1], 0, 1));
        assertThrows(IllegalArgumentException.class, () -> PatternTilePeriod.find(new int[1], -1, 1));
        assertThrows(IllegalArgumentException.class, () -> PatternTilePeriod.find(new int[16], 4, 5));
        assertThrows(IllegalArgumentException.class,
                () -> PatternTilePeriod.find(new int[1], Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> PatternTilePeriod.find(new int[16], 4, 4, 0));
        assertThrows(IllegalArgumentException.class, () -> PatternTilePeriod.find(new int[16], 4, 4, 3));
    }

    private static void assertWrappedPixels(int[] source, int width, int height, PatternTilePeriod.Period repeat) {
        assertTrue(repeat.width > 0 && repeat.width <= width && width % repeat.width == 0);
        assertTrue(repeat.height > 0 && repeat.height <= height && height % repeat.height == 0);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            if (source[y * width + x] != source[y % repeat.height * width + x % repeat.width]) {
                fail("Changed pixel at " + x + "," + y + " for " + repeat.width + "x" + repeat.height);
            }
        }
    }
}

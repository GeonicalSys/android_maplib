package com.nextgis.maplib.map;

import org.junit.Test;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import static org.junit.Assert.*;

public class TrackColorPaletteTest {
    @Test public void generatedColorsStaySaturatedAndVisibleOnWhiteAndAvoidImmediateRepeats() {
        Random random = new Random(14);
        int previous = 0xff5f789c;
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            int color = TrackColorPalette.nextColor(random, previous);
            assertNotEquals(previous, color);
            assertNotEquals(0xff5f789c, color);
            assertEquals(255, color >>> 24);
            int r = color >> 16 & 255, g = color >> 8 & 255, b = color & 255;
            int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
            assertTrue("Saturation", (max - min) / (double) max >= .8);
            assertTrue("Brightness", max >= 190);
            double luminance = .2126 * linear(r) + .7152 * linear(g) + .0722 * linear(b);
            assertTrue("Contrast against white", 1.05 / (luminance + .05) >= 2.0);
            seen.add(color);
            previous = color;
        }
        assertEquals(12, seen.size());
    }

    private static double linear(int component) {
        double value = component / 255.0;
        return value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4);
    }
}

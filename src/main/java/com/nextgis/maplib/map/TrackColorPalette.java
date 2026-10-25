package com.nextgis.maplib.map;

import java.util.Random;

/** Saturated, moderately bright track colors. Stored once; rendering never randomizes them. */
public final class TrackColorPalette {
    private TrackColorPalette() { }

    private static final int[] COLORS = {
            0xff00c853, 0xff00b8d4, 0xff2979ff, 0xff651fff,
            0xffd500f9, 0xffff1744, 0xffff6d00, 0xff76c600,
            0xff00bfa5, 0xff1749ff, 0xfff500a4, 0xffea2020
    };

    public static int nextColor(Random random, int previous) {
        int index = random.nextInt(COLORS.length);
        if (COLORS[index] == previous) index = (index + 1 + random.nextInt(COLORS.length - 1)) % COLORS.length;
        return COLORS[index];
    }
}

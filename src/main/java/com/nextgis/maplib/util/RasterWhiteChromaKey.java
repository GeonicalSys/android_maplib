package com.nextgis.maplib.util;

/** Punches exact RGB(255,255,255) to alpha 0. Packed ARGB, unpremultiplied. */
public final class RasterWhiteChromaKey {
    private static final int RGB_MASK = 0x00ffffff;
    private static final int OPAQUE_WHITE = 0x00ffffff;

    private RasterWhiteChromaKey() {
    }

    /** @return true if at least one pixel was changed. */
    public static boolean punchExactWhite(int[] argb) {
        if (argb == null || argb.length == 0) {
            return false;
        }
        boolean changed = false;
        for (int i = 0; i < argb.length; i++) {
            int pixel = argb[i];
            if ((pixel & RGB_MASK) == OPAQUE_WHITE && (pixel >>> 24) != 0) {
                argb[i] = pixel & RGB_MASK;
                changed = true;
            }
        }
        return changed;
    }
}

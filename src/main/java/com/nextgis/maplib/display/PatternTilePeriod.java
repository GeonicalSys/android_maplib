package com.nextgis.maplib.display;

/** Finds a byte-identical rectangular repeat without resampling the source pixels. */
final class PatternTilePeriod {
    static final int MIN_PERIOD = 16;

    static final class Period {
        final int width;
        final int height;

        private Period(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    private PatternTilePeriod() { }

    static Period find(int[] pixels, int width, int height) {
        return find(pixels, width, height, MIN_PERIOD);
    }

    static Period find(int[] pixels, int width, int height, int minimum) {
        if (pixels == null || width <= 0 || height <= 0
                || (long) width * height != pixels.length
                || minimum <= 0 || (minimum & (minimum - 1)) != 0) {
            throw new IllegalArgumentException("Expected a complete pixel rectangle and a power-of-two minimum");
        }
        int repeatWidth = width;
        for (int candidate = minimum; candidate < width; candidate *= 2) {
            if (width % candidate == 0 && repeatsHorizontally(pixels, width, height, candidate)) {
                repeatWidth = candidate;
                break;
            }
            if (candidate > Integer.MAX_VALUE / 2) break;
        }
        int repeatHeight = height;
        for (int candidate = minimum; candidate < height; candidate *= 2) {
            if (height % candidate == 0 && repeatsVertically(pixels, width, height, candidate)) {
                repeatHeight = candidate;
                break;
            }
            if (candidate > Integer.MAX_VALUE / 2) break;
        }
        return new Period(repeatWidth, repeatHeight);
    }

    private static boolean repeatsHorizontally(int[] pixels, int width, int height, int period) {
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = period; x < width; x++) {
                if (pixels[row + x] != pixels[row + x % period]) return false;
            }
        }
        return true;
    }

    private static boolean repeatsVertically(int[] pixels, int width, int height, int period) {
        for (int y = period; y < height; y++) {
            int row = y * width;
            int repeatRow = y % period * width;
            for (int x = 0; x < width; x++) {
                if (pixels[row + x] != pixels[repeatRow + x]) return false;
            }
        }
        return true;
    }
}

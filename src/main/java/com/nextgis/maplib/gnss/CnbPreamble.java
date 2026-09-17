package com.nextgis.maplib.gnss;

/** Detects ComNav Binary sync {@code AA 44 12} across chunk boundaries. */
public final class CnbPreamble {
    static final int B0 = 0xAA;
    static final int B1 = 0x44;
    static final int B2 = 0x12;

    private int matched;

    public boolean accept(byte[] data, int length) {
        if (data == null || length <= 0) {
            return false;
        }
        boolean found = false;
        int limit = Math.min(length, data.length);
        for (int i = 0; i < limit; i++) {
            int value = data[i] & 0xFF;
            if (matched == 0) {
                matched = value == B0 ? 1 : 0;
            } else if (matched == 1) {
                matched = value == B1 ? 2 : (value == B0 ? 1 : 0);
            } else if (value == B2) {
                found = true;
                matched = 0;
            } else {
                matched = value == B0 ? 1 : 0;
            }
        }
        return found;
    }

    public void reset() {
        matched = 0;
    }
}

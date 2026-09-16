package com.nextgis.maplib.gnss;

/** Assembles NMEA lines from a byte stream. Lines longer than 1024 bytes are dropped. */
public final class NmeaLineBuffer {
    public interface Sink {
        void onLine(String line);
    }

    private final StringBuilder line = new StringBuilder(128);

    public void append(byte[] data, int length, Sink sink) {
        if (data == null || sink == null || length <= 0) {
            return;
        }
        int limit = Math.min(length, data.length);
        for (int i = 0; i < limit; i++) {
            char c = (char) (data[i] & 0xFF);
            if (c == '\r') {
                continue;
            }
            if (c == '\n') {
                if (line.length() > 0) {
                    sink.onLine(line.toString());
                    line.setLength(0);
                }
            } else {
                line.append(c);
                if (line.length() > 1024) {
                    line.setLength(0);
                }
            }
        }
    }

    public void reset() {
        line.setLength(0);
    }
}

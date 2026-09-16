package com.nextgis.maplib.gnss;

/**
 * Assembles ASCII GNSS sentences from a byte stream. A line starts only at
 * {@code $} or {@code #}; other bytes, including {@code 0x0A} inside ComNav
 * binary, are ignored. Lines longer than 1024 bytes are dropped.
 */
public final class NmeaLineBuffer {
    public interface Sink {
        void onLine(String line);
    }

    private static final int MAX_LINE = 1024;

    private final StringBuilder line = new StringBuilder(128);
    private boolean inSentence;

    public void append(byte[] data, int length, Sink sink) {
        if (data == null || sink == null || length <= 0) {
            return;
        }
        int limit = Math.min(length, data.length);
        for (int i = 0; i < limit; i++) {
            char c = (char) (data[i] & 0xFF);
            if (!inSentence) {
                if (c == '$' || c == '#') {
                    inSentence = true;
                    line.setLength(0);
                    line.append(c);
                }
                continue;
            }
            if (c == '\r') {
                continue;
            }
            if (c == '\n') {
                if (line.length() > 0) {
                    sink.onLine(line.toString());
                }
                line.setLength(0);
                inSentence = false;
                continue;
            }
            if (c == '$' || c == '#') {
                line.setLength(0);
                line.append(c);
                continue;
            }
            line.append(c);
            if (line.length() > MAX_LINE) {
                line.setLength(0);
                inSentence = false;
            }
        }
    }

    public void reset() {
        line.setLength(0);
        inSentence = false;
    }
}

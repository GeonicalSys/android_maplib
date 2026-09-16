package com.nextgis.maplib.gnss;

/**
 * Assembles ASCII GNSS sentences from a byte stream. A line starts only at
 * {@code $} plus five ASCII letters ({@code $GNGGA}) or {@code #BESTPOS};
 * other bytes, including {@code $}/{@code #} inside ComNav binary, are ignored.
 * Lines longer than 1024 bytes are dropped.
 */
public final class NmeaLineBuffer {
    public interface Sink {
        void onLine(String line);
    }

    private static final int MAX_LINE = 1024;
    private static final String HASH_PREFIX = "BESTPOS";

    private final StringBuilder line = new StringBuilder(128);
    private boolean inSentence;
    private boolean prefixComplete;

    public void append(byte[] data, int length, Sink sink) {
        if (data == null || sink == null || length <= 0) {
            return;
        }
        int limit = Math.min(length, data.length);
        for (int i = 0; i < limit; i++) {
            char c = (char) (data[i] & 0xFF);
            if (!inSentence) {
                if (c == '$' || c == '#') {
                    begin(c);
                }
                continue;
            }
            if (!prefixComplete) {
                if (c == '$' || c == '#') {
                    begin(c);
                    continue;
                }
                if (!acceptPrefix(c)) {
                    inSentence = false;
                    prefixComplete = false;
                    line.setLength(0);
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
                prefixComplete = false;
                continue;
            }
            if (c == '$' || c == '#') {
                begin(c);
                continue;
            }
            line.append(c);
            if (line.length() > MAX_LINE) {
                line.setLength(0);
                inSentence = false;
                prefixComplete = false;
            }
        }
    }

    public void reset() {
        line.setLength(0);
        inSentence = false;
        prefixComplete = false;
    }

    private void begin(char start) {
        inSentence = true;
        prefixComplete = false;
        line.setLength(0);
        line.append(start);
    }

    private boolean acceptPrefix(char c) {
        if (line.charAt(0) == '$') {
            if (!isAsciiLetter(c)) {
                return false;
            }
            line.append(c);
            if (line.length() >= 6) {
                prefixComplete = true;
            }
            return true;
        }
        int idx = line.length() - 1;
        if (idx >= HASH_PREFIX.length()) {
            prefixComplete = true;
            line.append(c);
            return true;
        }
        if (toUpperAscii(c) != HASH_PREFIX.charAt(idx)) {
            return false;
        }
        line.append(c);
        if (line.length() - 1 >= HASH_PREFIX.length()) {
            prefixComplete = true;
        }
        return true;
    }

    static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static char toUpperAscii(char c) {
        if (c >= 'a' && c <= 'z') {
            return (char) (c - ('a' - 'A'));
        }
        return c;
    }
}

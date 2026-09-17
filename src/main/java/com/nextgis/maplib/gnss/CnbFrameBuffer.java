package com.nextgis.maplib.gnss;

import java.io.ByteArrayOutputStream;

/**
 * Assembles ComNav/NovAtel binary frames that start with {@code AA 44 12}.
 * A frame is {@code header_len + msg_len + 4} bytes; CRC-32 must match.
 */
public final class CnbFrameBuffer {
    public interface Sink {
        void onFrame(byte[] frame, int length);
    }

    static final int MAX_FRAME = 4096;
    private static final int MIN_PREFIX = 10;

    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    public void append(byte[] data, int length, Sink sink) {
        if (data == null || sink == null || length <= 0) {
            return;
        }
        int limit = Math.min(length, data.length);
        pending.write(data, 0, limit);
        drain(sink);
    }

    public void reset() {
        pending.reset();
    }

    private void drain(Sink sink) {
        byte[] buf = pending.toByteArray();
        int i = 0;
        while (i + 2 < buf.length) {
            if ((buf[i] & 0xFF) != CnbPreamble.B0
                    || (buf[i + 1] & 0xFF) != CnbPreamble.B1
                    || (buf[i + 2] & 0xFF) != CnbPreamble.B2) {
                i++;
                continue;
            }
            if (i + MIN_PREFIX > buf.length) {
                keepFrom(buf, i);
                return;
            }
            int headerLen = buf[i + 3] & 0xFF;
            int msgLen = (buf[i + 8] & 0xFF) | ((buf[i + 9] & 0xFF) << 8);
            int total = headerLen + msgLen + 4;
            if (headerLen < MIN_PREFIX || total < headerLen + 4 || total > MAX_FRAME) {
                i++;
                continue;
            }
            if (i + total > buf.length) {
                keepFrom(buf, i);
                return;
            }
            if (CnbCrc32.verify(buf, i, total)) {
                byte[] frame = new byte[total];
                System.arraycopy(buf, i, frame, 0, total);
                sink.onFrame(frame, total);
                i += total;
            } else {
                i++;
            }
        }
        keepFrom(buf, i);
    }

    private void keepFrom(byte[] buf, int start) {
        pending.reset();
        if (start < buf.length) {
            pending.write(buf, start, buf.length - start);
        }
    }
}

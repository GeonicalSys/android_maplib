package com.nextgis.maplib.gnss;

/** NovAtel/ComNav OEM CRC-32 used after a CNB header and message body. */
public final class CnbCrc32 {
    private static final int POLY = 0xEDB88320;

    private CnbCrc32() {
    }

    public static int of(byte[] data, int offset, int length) {
        int crc = 0;
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            int temp1 = (crc >>> 8) & 0x00FFFFFF;
            int temp2 = value((crc ^ data[i]) & 0xFF);
            crc = temp1 ^ temp2;
        }
        return crc;
    }

    public static boolean verify(byte[] frame, int offset, int length) {
        if (frame == null || length < 5 || offset < 0 || offset + length > frame.length) {
            return false;
        }
        int got = (frame[offset + length - 4] & 0xFF)
                | ((frame[offset + length - 3] & 0xFF) << 8)
                | ((frame[offset + length - 2] & 0xFF) << 16)
                | ((frame[offset + length - 1] & 0xFF) << 24);
        return got == of(frame, offset, length - 4);
    }

    static void putLittleEndian(byte[] dest, int offset, int value) {
        dest[offset] = (byte) value;
        dest[offset + 1] = (byte) (value >>> 8);
        dest[offset + 2] = (byte) (value >>> 16);
        dest[offset + 3] = (byte) (value >>> 24);
    }

    private static int value(int i) {
        int crc = i;
        for (int j = 8; j > 0; j--) {
            if ((crc & 1) != 0) {
                crc = (crc >>> 1) ^ POLY;
            } else {
                crc >>>= 1;
            }
        }
        return crc;
    }
}

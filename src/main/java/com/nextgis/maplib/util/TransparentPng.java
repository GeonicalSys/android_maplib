package com.nextgis.maplib.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/** 256×256 fully transparent RGBA PNG, generated once. */
public final class TransparentPng {
    public static final int TILE_SIZE = 256;
    private static final byte[] PNG_SIGNATURE = new byte[] {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };
    private static final byte[] BYTES = build();

    private TransparentPng() {
    }

    public static byte[] tile256() {
        return BYTES.clone();
    }

    static byte[] rawBytes() {
        return BYTES;
    }

    private static byte[] build() {
        int rowBytes = 1 + TILE_SIZE * 4;
        byte[] raw = new byte[rowBytes * TILE_SIZE];
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(raw);
        deflater.finish();
        ByteArrayOutputStream deflated = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        while (!deflater.finished()) {
            int count = deflater.deflate(buf);
            deflated.write(buf, 0, count);
        }
        deflater.end();
        byte[] idat = deflated.toByteArray();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        try {
            png.write(PNG_SIGNATURE);
            writeChunk(png, "IHDR", ihdr());
            writeChunk(png, "IDAT", idat);
            writeChunk(png, "IEND", new byte[0]);
        } catch (IOException e) {
            throw new IllegalStateException("transparent PNG", e);
        }
        return png.toByteArray();
    }

    private static byte[] ihdr() {
        return new byte[] {
                0, 0, 1, 0,
                0, 0, 1, 0,
                8, 6, 0, 0, 0
        };
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data)
            throws IOException {
        writeInt(out, data.length);
        byte[] typeBytes = type.getBytes("US-ASCII");
        out.write(typeBytes);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        writeInt(out, (int) crc.getValue());
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }
}

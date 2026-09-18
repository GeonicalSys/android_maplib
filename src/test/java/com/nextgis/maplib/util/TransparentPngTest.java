package com.nextgis.maplib.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TransparentPngTest {
    @Test
    public void encodesPngSignatureAndIhdrSize() {
        byte[] png = TransparentPng.tile256();
        assertTrue(png.length > 32);
        assertEquals((byte) 0x89, png[0]);
        assertEquals((byte) 0x50, png[1]);
        assertEquals((byte) 0x4e, png[2]);
        assertEquals((byte) 0x47, png[3]);
        assertEquals("png", LegacyTileMbtilesMath.detectRasterFormat(png));
    }
}

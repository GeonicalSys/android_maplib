package com.nextgis.maplib.util;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SharedUnderlayKindTest {

    @Test
    public void classifyName_ngrcAndMbtiles() {
        assertEquals(SharedUnderlayKind.NGRC, SharedUnderlayKind.classifyName("Relief.NGRC"));
        assertEquals(SharedUnderlayKind.MBTILES, SharedUnderlayKind.classifyName("tiles.mbtiles"));
        assertEquals(SharedUnderlayKind.NONE, SharedUnderlayKind.classifyName("archive.zip"));
        assertEquals(SharedUnderlayKind.NONE, SharedUnderlayKind.classifyName("notes.geojson"));
        assertTrue(SharedUnderlayKind.isZipName("pack.ZIP"));
        assertFalse(SharedUnderlayKind.isZipName("pack.ngrc"));
    }

    @Test
    public void classifyZip_prefersMbtilesThenNgrc() throws Exception {
        assertEquals(SharedUnderlayKind.MBTILES, SharedUnderlayKind.classifyZip(
                new ByteArrayInputStream(zip("inner/map.mbtiles", new byte[]{1},
                        "1/0/0.tile", new byte[]{2}))));
        assertEquals(SharedUnderlayKind.NGRC, SharedUnderlayKind.classifyZip(
                new ByteArrayInputStream(zip("Mapnik.json",
                        "{\"tms_type\":1}".getBytes(StandardCharsets.UTF_8),
                        "Mapnik/1/0/0.png", new byte[]{9}))));
        assertEquals(SharedUnderlayKind.NONE, SharedUnderlayKind.classifyZip(
                new ByteArrayInputStream(zip("1/0/0.png", new byte[]{3},
                        "readme.txt", "plain".getBytes(StandardCharsets.UTF_8)))));
    }

    private static byte[] zip(String first, byte[] firstBytes, String second, byte[] secondBytes)
            throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(first));
            zip.write(firstBytes);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(second));
            zip.write(secondBytes);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}

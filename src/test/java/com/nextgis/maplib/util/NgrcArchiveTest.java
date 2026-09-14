package com.nextgis.maplib.util;

import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import static org.junit.Assert.*;

public class NgrcArchiveTest {
    private byte[] archive(String config, String tilePath, String comment) throws Exception {
        return archive(config, "mapnik/config.json", tilePath, comment);
    }
    private byte[] archive(String config, String configPath, String tilePath, String comment) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.setComment(comment);
            zip.putNextEntry(new ZipEntry(tilePath)); zip.write(new byte[]{1, 2, 3}); zip.closeEntry();
            zip.putNextEntry(new ZipEntry(configPath)); zip.write(config.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        return bytes.toByteArray();
    }
    @Test public void readsLateConfigAndHashesWholeArchiveIncludingComment() throws Exception {
        byte[] bytes = archive("{\"tms_type\":2}", "mapnik/3/2/4.tile", "comment");
        NgrcArchive.Source source = () -> new ByteArrayInputStream(bytes);
        NgrcArchive.Info info = NgrcArchive.inspect(source, () -> {});
        assertEquals(UnderlayFiles.sha256(new ByteArrayInputStream(bytes)), info.sha256);
        final int[] count = {0};
        NgrcArchive.convert(source, info, (path, data, scheme) -> {
            assertEquals("3/2/4.tile", path); assertEquals(2, scheme);
            assertEquals(3, LegacyTileMbtilesMath.toTmsRow(3, 4, scheme));
            assertArrayEquals(new byte[]{1, 2, 3}, data); count[0]++;
        }, () -> {});
        assertEquals(1, count[0]);
    }
    @Test public void unknownSchemeIsRejectedInsteadOfGuessed() throws Exception {
        byte[] bytes = archive("{}", "1/0/0.tile", "");
        try { NgrcArchive.inspect(() -> new ByteArrayInputStream(bytes), () -> {}); fail(); } catch (IOException expected) { }
    }
    @Test public void changedSourceCannotBePublished() throws Exception {
        byte[] before = archive("{\"tms_type\":1}", "1/0/0.tile", "before");
        byte[] after = archive("{\"tms_type\":1}", "1/0/0.tile", "after");
        NgrcArchive.Info info = NgrcArchive.inspect(() -> new ByteArrayInputStream(before), () -> {});
        try { NgrcArchive.convert(() -> new ByteArrayInputStream(after), info, (p, b, s) -> {}, () -> {}); fail(); }
        catch (IOException expected) { }
    }
    @Test public void maliciousEntryAndCancellationAreRejected() throws Exception {
        byte[] bytes = archive("{\"tms_type\":1}", "../outside.tile", "");
        try { NgrcArchive.inspect(() -> new ByteArrayInputStream(bytes), () -> {}); fail(); } catch (IOException expected) { }
        try { NgrcArchive.inspect(() -> new ByteArrayInputStream(bytes), () -> { throw new InterruptedIOException(); }); fail(); }
        catch (InterruptedIOException expected) { }
    }

    @Test public void readsStandardMapnikMetadataAndRasterFileExtensions() throws Exception {
        for (String extension : new String[]{"jpg", "jpeg", "png", "webp", "JPG", "PNG", "tile"}) {
            for (int scheme : new int[]{1, 2}) {
                byte[] bytes = archive("{\"tms_type\":" + scheme + ",\"name\":\"Demo relief\"}",
                        "Mapnik.json", "Mapnik/10/607/299." + extension, "standard export");
                NgrcArchive.Source source = () -> new ByteArrayInputStream(bytes);
                NgrcArchive.Info info = NgrcArchive.inspect(source, () -> {});
                assertEquals("Demo relief", info.config.getString("name"));
                assertEquals(UnderlayFiles.sha256(new ByteArrayInputStream(bytes)), info.sha256);
                final int[] count = {0};
                NgrcArchive.convert(source, info, (path, data, actualScheme) -> {
                    assertEquals("10/607/299.tile", path);
                    assertEquals(scheme, actualScheme);
                    assertArrayEquals(new byte[]{1, 2, 3}, data);
                    count[0]++;
                }, () -> {});
                assertEquals(1, count[0]);
            }
        }
    }

    @Test public void normalizesOnlySupportedRasterPathsAndMetadataAliases() throws Exception {
        assertEquals("config.json", NgrcArchive.normalized("MAPNIK.JSON"));
        assertEquals("config.json", NgrcArchive.normalized("Mapnik/CONFIG.JSON"));
        assertEquals("10/607/299.tile", NgrcArchive.normalized("mApNiK/10/607/299.JPEG"));
        assertEquals("10/607/299.tile", NgrcArchive.normalized("10/607/299.webp"));
        assertEquals("notes.jpg", NgrcArchive.normalized("notes.jpg"));
        assertEquals("other/10/607/299.jpg", NgrcArchive.normalized("other/10/607/299.jpg"));
        for (String path : new String[]{"Mapnik/../config.json", "Mapnik/10/../299.jpg", "/Mapnik.json", "Mapnik\\10\\607\\299.jpg"}) {
            try { NgrcArchive.normalized(path); fail(path); } catch (IOException expected) { }
        }
    }

    @Test public void rejectsAmbiguousMetadataEvenWhenNamesDiffer() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String name : new String[]{"config.json", "Mapnik.json"}) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write("{\"tms_type\":2}".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
        }
        try { NgrcArchive.inspect(() -> new ByteArrayInputStream(bytes.toByteArray()), () -> {}); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("multiple configurations")); }
    }
}

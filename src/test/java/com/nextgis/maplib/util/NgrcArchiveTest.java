package com.nextgis.maplib.util;

import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import static org.junit.Assert.*;

public class NgrcArchiveTest {
    private byte[] archive(String config, String tilePath, String comment) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.setComment(comment);
            zip.putNextEntry(new ZipEntry(tilePath)); zip.write(new byte[]{1, 2, 3}); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("mapnik/config.json")); zip.write(config.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
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
}

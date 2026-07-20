package com.nextgis.maplib.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class LayerFormHashUtilTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void downloadedAndUnpackedHashesMatchAfterConnectionRemoval() throws Exception {
        byte[] form = "[{\"type\":\"text_edit\"}]".getBytes(StandardCharsets.UTF_8);
        byte[] meta = ("{\"ngw_connection\":{\"url\":\"secret\"},"
                + "\"name\":\"field form\"}").getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(zipBytes)) {
            add(zip, "form.json", form);
            add(zip, "meta.json", meta);
        }

        File formFile = temporaryFolder.newFile("form.json");
        File metaFile = temporaryFolder.newFile("ngfp_meta.json");
        try (FileOutputStream out = new FileOutputStream(formFile)) {
            out.write(form);
        }
        try (FileOutputStream out = new FileOutputStream(metaFile)) {
            out.write("{\"name\":\"field form\"}".getBytes(StandardCharsets.UTF_8));
        }

        String downloaded = LayerFormHashUtil.md5NgfpZip(
                new ByteArrayInputStream(zipBytes.toByteArray()));
        String unpacked = LayerFormHashUtil.md5NgfpFiles(formFile, metaFile);

        assertFalse(downloaded.isEmpty());
        assertEquals(downloaded, unpacked);
    }

    private static void add(ZipOutputStream zip, String name, byte[] content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }
}

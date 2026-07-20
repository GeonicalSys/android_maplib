package com.nextgis.maplib.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FileUtilUnzipEntryTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void stripsLegacyRootAndKeepsOutputInsideDestination() throws Exception {
        File output = temporaryFolder.newFolder("tiles");
        ZipInputStream input = entryStream("mapnik/0/1/2.png", "tile");
        ZipEntry entry = input.getNextEntry();

        FileUtil.unzipEntry(input, entry, new byte[1024], output);

        File tile = new File(output, "0/1/2.tile");
        assertTrue(tile.isFile());
        assertEquals("tile", new String(
                Files.readAllBytes(tile.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void rejectsParentTraversal() throws Exception {
        File output = temporaryFolder.newFolder("safe");
        File escaped = new File(output.getParentFile(), "escape.txt");
        ZipInputStream input = entryStream("../escape.txt", "bad");
        ZipEntry entry = input.getNextEntry();

        boolean rejected = false;
        try {
            FileUtil.unzipEntry(input, entry, new byte[1024], output);
        } catch (IOException expected) {
            rejected = true;
        }

        assertTrue(rejected);
        assertFalse(escaped.exists());
    }

    private static ZipInputStream entryStream(String name, String value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            output.putNextEntry(new ZipEntry(name));
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()));
    }
}

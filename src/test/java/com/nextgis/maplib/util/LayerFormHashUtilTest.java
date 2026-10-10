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
import static org.junit.Assert.assertNotEquals;

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

    @Test public void serverWorkerKeyOrderAndFormattingDoNotChangeHash() throws Exception {
        String first = "[{\"type\":\"tabs\",\"attributes\":{},\"pages\":[{\"caption\":\"Main\","
                + "\"elements\":[{\"type\":\"text_edit\",\"attributes\":{\"field\":\"name\",\"last\":false}}]}]}]";
        String other = "[ { \"pages\": [{\"elements\":[{\"attributes\":{\"last\":false,\"field\":\"name\"},"
                + "\"type\":\"text_edit\"}],\"caption\":\"Main\"}],\"attributes\":{},\"type\":\"tabs\"} ]";
        String meta = "{\"fields\":[{\"keyname\":\"name\",\"display_name\":\"Name\"}],\"name\":\"Form\"}";
        String reorderedMeta = "{\"name\":\"Form\",\"fields\":[{\"display_name\":\"Name\",\"keyname\":\"name\"}]}";
        assertEquals(hash(first, meta), hash(other, reorderedMeta));
        File formFile = temporaryFolder.newFile("reordered_form.json");
        File metaFile = temporaryFolder.newFile("reordered_meta.json");
        java.nio.file.Files.write(formFile.toPath(), other.getBytes(StandardCharsets.UTF_8));
        java.nio.file.Files.write(metaFile.toPath(), reorderedMeta.getBytes(StandardCharsets.UTF_8));
        assertEquals(hash(first, meta), LayerFormHashUtil.md5NgfpFiles(formFile, metaFile));
    }

    @Test public void realContentRulesIdentityAndArrayOrderStillChangeHash() throws Exception {
        String form = "[{\"type\":\"text_label\",\"lisa_id\":\"a\",\"attributes\":{\"text\":\"First\"}},"
                + "{\"type\":\"text_label\",\"lisa_id\":\"b\",\"attributes\":{\"text\":\"Second\"}}]";
        String meta = "{\"lisa_form_rules\":{\"version\":2,\"required\":true},\"choices\":[\"a\",\"b\"]}";
        String original = hash(form, meta);
        assertNotEquals(original, hash(form.replace("First", "Edited"), meta));
        assertNotEquals(original, hash(form.replace("\"a\"", "\"new_id\""), meta));
        assertNotEquals(original, hash(form, meta.replace("true", "false")));
        assertNotEquals(original, hash(form, meta.replace("[\"a\",\"b\"]", "[\"b\",\"a\"]")));
        org.json.JSONArray elements = new org.json.JSONArray(form);
        String swapped = new org.json.JSONArray().put(elements.get(1)).put(elements.get(0)).toString();
        assertNotEquals(original, hash(swapped, meta));
    }

    @Test public void onlyTopLevelConnectionIsExcludedAndMalformedPartsRemainDistinct() throws Exception {
        assertNotEquals(hash("[]", "{\"nested\":{\"ngw_connection\":1}}"),
                hash("[]", "{\"nested\":{\"ngw_connection\":2}}"));
        assertNotEquals(hash("[broken", "{}"), hash("[other", "{}"));
        assertNotEquals(hash("[] trailing", "{}"), hash("[] other", "{}"));
    }

    private static String hash(String form, String meta) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            add(zip, "form.json", form.getBytes(StandardCharsets.UTF_8));
            add(zip, "meta.json", meta.getBytes(StandardCharsets.UTF_8));
        }
        return LayerFormHashUtil.md5NgfpZip(new ByteArrayInputStream(bytes.toByteArray()));
    }
}

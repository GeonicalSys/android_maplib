/*
 * Project:  NextGIS Mobile
 * Purpose:  Stable hash helpers for NGFP form synchronization.
 */

package com.nextgis.maplib.util;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Collector architecture foundation.
 *
 * Form resources can change without changing their NGW resource id. Keep this utility separate
 * from UI form loading so composition sync can compare the Web GIS NGFP payload with the local
 * unpacked form and decide whether a layer needs a controlled refill.
 */
public final class LayerFormHashUtil {
    private static final String FILE_FORM = "form.json";
    private static final String FILE_META_ZIP = "meta.json";
    private static final String FILE_META_LOCAL = "ngfp_meta.json";
    private static final String FILE_DATA = "data.geojson";
    private static final String JSON_NGW_CONNECTION = "ngw_connection";

    private LayerFormHashUtil() {
    }

    public static String md5NgfpZip(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        TreeMap<String, byte[]> parts = new TreeMap<>();
        ZipInputStream zis = new ZipInputStream(inputStream);
        ZipEntry entry;
        byte[] buffer = new byte[Constants.IO_BUFFER_SIZE];
        while ((entry = zis.getNextEntry()) != null) {
            if (!entry.isDirectory()) {
                String name = normalizeZipEntryName(entry.getName());
                String canonical = canonicalFormPartName(name);
                if (!isEmpty(canonical)) {
                    parts.put(canonical, normalizePartBytes(canonical, readEntryBytes(zis, buffer)));
                }
            }
            zis.closeEntry();
        }
        zis.close();
        return md5Parts(parts);
    }

    public static String md5LocalNgfpFiles(File layerPath, long formId) throws IOException {
        if (layerPath == null || formId <= 0L) {
            return "";
        }
        String prefix = formId + "_";
        return md5NgfpFiles(
                new File(layerPath, prefix + FILE_FORM),
                new File(layerPath, prefix + FILE_META_LOCAL));
    }

    /**
     * Hash an already unpacked NGFP pair before it is installed in a layer directory.
     */
    public static String md5NgfpFiles(File formFile, File metaFile) throws IOException {
        TreeMap<String, byte[]> parts = new TreeMap<>();
        readLocalPart(parts, formFile, FILE_FORM);
        readLocalPart(parts, metaFile, FILE_META_ZIP);
        return md5Parts(parts);
    }

    private static void readLocalPart(
            TreeMap<String, byte[]> parts,
            File file,
            String canonicalName) throws IOException {
        if (file == null || !file.exists() || !file.isFile()) {
            return;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FileInputStream in = new FileInputStream(file);
        try {
            byte[] buffer = new byte[Constants.IO_BUFFER_SIZE];
            FileUtil.copyStream(in, out, buffer, Constants.IO_BUFFER_SIZE);
            parts.put(canonicalName, normalizePartBytes(canonicalName, out.toByteArray()));
        } finally {
            in.close();
            out.close();
        }
    }

    private static String normalizeZipEntryName(String raw) {
        if (isEmpty(raw)) {
            return "";
        }
        String name = raw.replace('\\', '/');
        while (name.startsWith("/")) {
            name = name.substring(1);
        }
        int pos = name.indexOf('/');
        if (pos != Constants.NOT_FOUND) {
            String folderName = name.substring(0, pos);
            if (!isDigitsOnly(folderName)) {
                name = name.substring(pos + 1);
            }
        }
        return name;
    }

    private static String canonicalFormPartName(String name) {
        if (isEmpty(name)) {
            return "";
        }
        String lower = name.toLowerCase();
        if (FILE_DATA.equals(lower)) {
            return "";
        }
        if (FILE_FORM.equals(lower)) {
            return FILE_FORM;
        }
        if (FILE_META_ZIP.equals(lower) || FILE_META_LOCAL.equals(lower)) {
            return FILE_META_ZIP;
        }
        return "";
    }

    private static byte[] normalizePartBytes(String canonicalName, byte[] bytes) {
        if (!FILE_META_ZIP.equals(canonicalName) || bytes == null || bytes.length == 0) {
            return bytes;
        }
        try {
            String raw = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(raw);
            json.remove(JSON_NGW_CONNECTION);
            return json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (JSONException e) {
            return bytes;
        }
    }

    private static byte[] readEntryBytes(InputStream in, byte[] buffer) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    private static boolean isDigitsOnly(String value) {
        if (isEmpty(value)) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String md5Parts(TreeMap<String, byte[]> parts) {
        if (parts == null || parts.isEmpty()) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            for (Map.Entry<String, byte[]> entry : parts.entrySet()) {
                md.update(entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(entry.getValue());
                md.update((byte) 0);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }
}

package com.nextgis.maplib.util;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FileUtilDisplayNameTest {
    @Test
    public void rejectsSafMediaStoreDocumentIds() {
        assertTrue(FileUtil.isUnusableDisplayName(null));
        assertTrue(FileUtil.isUnusableDisplayName(""));
        assertTrue(FileUtil.isUnusableDisplayName("   "));
        assertTrue(FileUtil.isUnusableDisplayName("msf:308"));
        assertTrue(FileUtil.isUnusableDisplayName("msf: 308"));
        assertTrue(FileUtil.isUnusableDisplayName("image:12"));
        assertTrue(FileUtil.isUnusableDisplayName("VIDEO:3"));
    }

    @Test
    public void keepsOrdinaryFileNames() {
        assertFalse(FileUtil.isUnusableDisplayName("relief.ngrc"));
        assertFalse(FileUtil.isUnusableDisplayName("Mapnik.json"));
        assertFalse(FileUtil.isUnusableDisplayName("Подложка 1.mbtiles"));
        assertFalse(FileUtil.isUnusableDisplayName("layer_v2"));
    }
}

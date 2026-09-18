package com.nextgis.maplib.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MbTilesDisplaySidecarTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void bothOffKeepOriginalUrl() throws IOException {
        File original = folder.newFile("map-mbtiles.mbtiles");
        try (FileOutputStream output = new FileOutputStream(original)) {
            output.write("sqlite".getBytes(StandardCharsets.US_ASCII));
        }
        UnderlayDisplaySettings off = new UnderlayDisplaySettings(false, false);
        assertEquals(
                "mbtiles://" + original.getAbsolutePath(),
                MbTilesDisplaySidecar.urlFor(original, off));
        assertFalse(off.needsSidecar());
    }

    @Test
    public void sidecarSitsBesideOriginal() throws IOException {
        File original = folder.newFile("map-mbtiles.mbtiles");
        File sidecar = MbTilesDisplaySidecar.sidecarFile(original);
        assertEquals(original.getParentFile(), sidecar.getParentFile());
        assertEquals(MbTilesDisplaySidecar.FILENAME, sidecar.getName());
        assertFalse(original.getName().equals(sidecar.getName()));
    }

    @Test
    public void lastLevelFingerprintIsSecondFlag() {
        assertTrue(UnderlayDisplaySettings.lastLevelFromFingerprint("wl"));
        assertTrue(UnderlayDisplaySettings.lastLevelFromFingerprint("-l"));
        assertFalse(UnderlayDisplaySettings.lastLevelFromFingerprint("w-"));
        assertFalse(UnderlayDisplaySettings.lastLevelFromFingerprint("--"));
    }

    @Test
    public void rememberedLastLevelAppliesToOriginalAndSidecar() throws IOException {
        File original = folder.newFile("map-mbtiles.mbtiles");
        MbTilesDisplaySidecar.rememberLastLevel(original, true);
        assertTrue(MbTilesDisplaySidecar.lastLevelOverzoomEnabled(original));
        assertTrue(MbTilesDisplaySidecar.lastLevelOverzoomEnabled(
                MbTilesDisplaySidecar.sidecarFile(original)));
        MbTilesDisplaySidecar.rememberLastLevel(original, false);
        assertFalse(MbTilesDisplaySidecar.lastLevelOverzoomEnabled(original));
    }
}

package com.nextgis.maplib.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UnderlayDisplaySettingsTest {
    @Test
    public void defaultsEnableBothDisplayOptions() {
        UnderlayDisplaySettings settings = UnderlayDisplaySettings.from((android.content.Context) null);
        assertTrue(settings.whiteAsTransparent);
        assertTrue(settings.lastLevelOverzoom);
        assertEquals("wl", settings.fingerprint());
    }

    @Test
    public void fingerprintTracksDisabledOptions() {
        UnderlayDisplaySettings settings = new UnderlayDisplaySettings(false, false);
        assertFalse(settings.whiteAsTransparent);
        assertFalse(settings.lastLevelOverzoom);
        assertEquals("--", settings.fingerprint());
    }
}

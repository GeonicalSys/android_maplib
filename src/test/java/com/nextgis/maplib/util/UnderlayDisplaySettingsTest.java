package com.nextgis.maplib.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UnderlayDisplaySettingsTest {
    @Test
    public void defaultsNeedSidecar() {
        UnderlayDisplaySettings settings = UnderlayDisplaySettings.from((android.content.Context) null);
        assertTrue(settings.whiteAsTransparent);
        assertTrue(settings.lastLevelOverzoom);
        assertTrue(settings.needsSidecar());
        assertEquals("wl", settings.fingerprint());
    }

    @Test
    public void bothOffSkipSidecar() {
        UnderlayDisplaySettings settings = new UnderlayDisplaySettings(false, false);
        assertFalse(settings.needsSidecar());
        assertEquals("--", settings.fingerprint());
    }
}

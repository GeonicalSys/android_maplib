package com.nextgis.maplib.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class LegacyTileMbtilesMathTest {
    @Test
    public void preservesTmsRowsAndFlipsOsmRows() {
        assertEquals(1, LegacyTileMbtilesMath.toTmsRow(
                2, 1, GeoConstants.TMSTYPE_NORMAL));
        assertEquals(2, LegacyTileMbtilesMath.toTmsRow(
                2, 1, GeoConstants.TMSTYPE_OSM));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsRowsOutsideZoomGrid() {
        LegacyTileMbtilesMath.toTmsRow(2, 4, GeoConstants.TMSTYPE_OSM);
    }

    @Test
    public void worldBoundariesUseWebMercatorLimits() {
        assertEquals(-180.0, LegacyTileMbtilesMath.longitudeFromBoundary(0, 1), 1e-9);
        assertEquals(180.0, LegacyTileMbtilesMath.longitudeFromBoundary(1, 1), 1e-9);
        assertEquals(-85.05112878,
                LegacyTileMbtilesMath.latitudeFromTmsBoundary(0, 1), 1e-7);
        assertEquals(85.05112878,
                LegacyTileMbtilesMath.latitudeFromTmsBoundary(1, 1), 1e-7);
    }

    @Test
    public void detectsSupportedRasterSignatures() {
        assertEquals("png", LegacyTileMbtilesMath.detectRasterFormat(new byte[]{
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}));
        assertEquals("jpg", LegacyTileMbtilesMath.detectRasterFormat(new byte[]{
                (byte) 0xff, (byte) 0xd8, (byte) 0xff}));
        assertEquals("webp", LegacyTileMbtilesMath.detectRasterFormat(new byte[]{
                'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'}));
        assertNull(LegacyTileMbtilesMath.detectRasterFormat(new byte[]{1, 2, 3}));
    }
}

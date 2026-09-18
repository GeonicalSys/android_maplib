package com.nextgis.maplib.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MbTilesTileGridTest {
    @Test
    public void worldAtZoomZeroIsSingleTile() {
        MbTilesTileGrid grid = MbTilesTileGrid.fromWgs84(-180, -85, 180, 85, 0);
        assertEquals(0, grid.minColumn);
        assertEquals(0, grid.maxColumn);
        assertEquals(0, grid.minRow);
        assertEquals(0, grid.maxRow);
        assertEquals(1, grid.tileCount());
    }

    @Test
    public void equatorLongitudeSplitsZoomOne() {
        assertEquals(0, MbTilesTileGrid.columnFromLongitude(-90, 1));
        assertEquals(1, MbTilesTileGrid.columnFromLongitude(90, 1));
    }

    @Test
    public void southernLatitudeHasLowerTmsRow() {
        int south = MbTilesTileGrid.tmsRowFromLatitude(-40, 4);
        int north = MbTilesTileGrid.tmsRowFromLatitude(40, 4);
        assertTrue(south < north);
    }

    @Test
    public void smallEnvelopeStaysUnderHoleCap() {
        MbTilesTileGrid grid = MbTilesTileGrid.fromWgs84(30.0, 59.9, 30.2, 60.0, 16);
        assertTrue(grid.tileCount() > 0);
        assertTrue(grid.tileCount() <= MbTilesTileGrid.MAX_HOLES_PER_ZOOM);
    }
}

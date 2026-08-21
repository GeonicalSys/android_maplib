package com.nextgis.maplib.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LocationUtilAreaTest {
    @Test
    public void convertsSquareMetersToHectares() {
        assertEquals(0.0001, LocationUtil.squareMetersToHectares(1.0), 0.0);
        assertEquals(1.0, LocationUtil.squareMetersToHectares(10000.0), 0.0);
        assertEquals(12.345, LocationUtil.squareMetersToHectares(123450.0), 0.0);
    }
}

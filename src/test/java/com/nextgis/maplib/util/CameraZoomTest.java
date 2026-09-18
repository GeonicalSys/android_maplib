package com.nextgis.maplib.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CameraZoomTest {
    @Test
    public void raisesValuesBelowFloor() {
        assertEquals(GeoConstants.CAMERA_MIN_ZOOM, CameraZoom.clamp(0f), 0);
        assertEquals(GeoConstants.CAMERA_MIN_ZOOM, CameraZoom.clamp(7f), 0);
        assertEquals(GeoConstants.CAMERA_MIN_ZOOM, CameraZoom.clamp(Float.NaN), 0);
    }

    @Test
    public void keepsFloorAndHigherZooms() {
        assertEquals(7.5f, CameraZoom.clamp(7.5f), 0);
        assertEquals(12f, CameraZoom.clamp(12f), 0);
        assertEquals(GeoConstants.DEFAULT_MAX_ZOOM, CameraZoom.clamp(99f), 0);
    }

    @Test
    public void doesNotRaiseLayerVisibilityFloor() {
        assertEquals(0, GeoConstants.DEFAULT_MIN_ZOOM);
        assertEquals(7.5f, GeoConstants.CAMERA_MIN_ZOOM, 0);
    }
}

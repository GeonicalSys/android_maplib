package com.nextgis.maplib.util;

/** Clamps the MapLibre camera, not layer visibility (`DEFAULT_MIN_ZOOM`). */
public final class CameraZoom {
    private CameraZoom() {
    }

    public static float clamp(float zoom) {
        if (Float.isNaN(zoom) || zoom < GeoConstants.CAMERA_MIN_ZOOM) {
            return GeoConstants.CAMERA_MIN_ZOOM;
        }
        if (zoom > GeoConstants.DEFAULT_MAX_ZOOM) {
            return GeoConstants.DEFAULT_MAX_ZOOM;
        }
        return zoom;
    }

    public static double clamp(double zoom) {
        return clamp((float) zoom);
    }
}

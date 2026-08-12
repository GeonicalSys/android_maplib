/*
 * Project:  NextGIS Mobile / Geonical fork
 * Purpose:  Android adapter for GPS-first provider arbitration.
 */

package com.nextgis.maplib.util;

import android.location.Location;
import android.os.SystemClock;

/**
 * Keeps network locations as a fallback without interleaving them with a recent usable GPS stream.
 */
public final class LocationProviderArbiter {
    public static final long DEFAULT_GPS_FALLBACK_AFTER_MS = 12_000L;

    private final LocationProviderArbiterCore mCore =
            new LocationProviderArbiterCore(DEFAULT_GPS_FALLBACK_AFTER_MS);

    public void reset() {
        mCore.reset();
    }

    public boolean shouldProcess(Location location) {
        if (location == null) {
            return false;
        }
        return mCore.shouldProcess(location.getProvider(), SystemClock.elapsedRealtime());
    }

    /**
     * Marks a location only after the sequence filter accepted it.
     */
    public void onAccepted(Location location) {
        if (location != null) {
            mCore.onAccepted(location.getProvider(), SystemClock.elapsedRealtime());
        }
    }

    public long getSuppressedNetworkFixCount() {
        return mCore.getSuppressedNetworkFixCount();
    }
}

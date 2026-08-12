/*
 * Project:  NextGIS Mobile / Geonical fork
 * Purpose:  Prefer a recent usable GPS stream while retaining network fallback.
 */

package com.nextgis.maplib.util;

final class LocationProviderArbiterCore {
    static final String GPS_PROVIDER = "gps";
    static final String NETWORK_PROVIDER = "network";

    private final long mGpsFallbackAfterMs;
    private long mLastUsableGpsRealtimeMs = -1L;
    private long mSuppressedNetworkFixCount;

    LocationProviderArbiterCore(long gpsFallbackAfterMs) {
        mGpsFallbackAfterMs = gpsFallbackAfterMs;
    }

    void reset() {
        mLastUsableGpsRealtimeMs = -1L;
        mSuppressedNetworkFixCount = 0L;
    }

    boolean shouldProcess(String provider, long nowRealtimeMs) {
        if (GPS_PROVIDER.equals(provider)) {
            return true;
        }
        if (!NETWORK_PROVIDER.equals(provider) || mLastUsableGpsRealtimeMs < 0L) {
            return true;
        }

        long sinceGpsMs = nowRealtimeMs - mLastUsableGpsRealtimeMs;
        if (sinceGpsMs >= 0L && sinceGpsMs <= mGpsFallbackAfterMs) {
            mSuppressedNetworkFixCount++;
            return false;
        }
        return true;
    }

    void onAccepted(String provider, long nowRealtimeMs) {
        if (GPS_PROVIDER.equals(provider)) {
            mLastUsableGpsRealtimeMs = nowRealtimeMs;
        }
    }

    long getSuppressedNetworkFixCount() {
        return mSuppressedNetworkFixCount;
    }
}

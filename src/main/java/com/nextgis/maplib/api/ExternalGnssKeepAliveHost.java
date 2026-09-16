package com.nextgis.maplib.api;

/**
 * Hosts the external NMEA keep-alive foreground service so maplib does not
 * depend on maplibui.
 */
public interface ExternalGnssKeepAliveHost {
    boolean canKeepExternalGnssAlive();

    void setExternalGnssKeepAlive(boolean wanted);
}

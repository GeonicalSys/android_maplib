package com.nextgis.maplib.gnss;

/** One discovered or remembered external GNSS endpoint. */
public final class GnssDevice {
    public final String transport;
    public final String id;
    public final String displayName;

    public GnssDevice(String transport, String id, String displayName) {
        this.transport = transport;
        this.id = id == null ? "" : id;
        this.displayName = displayName == null || displayName.isEmpty() ? this.id : displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}

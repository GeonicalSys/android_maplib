package com.nextgis.maplib.gnss;

/** One discovered or remembered external GNSS endpoint. */
public final class GnssDevice {
    public final String transport;
    public final String id;
    public final String displayName;
    /** Last scan measurement; not persisted as part of receiver identity. */
    public final Integer rssiDbm;

    public GnssDevice(String transport, String id, String displayName) {
        this(transport, id, displayName, null);
    }

    public GnssDevice(String transport, String id, String displayName, Integer rssiDbm) {
        this.transport = transport;
        this.id = id == null ? "" : id;
        this.displayName = displayName == null || displayName.isEmpty() ? this.id : displayName;
        // Android ScanResult range; 127 and Short.MIN_VALUE mean unavailable.
        this.rssiDbm = rssiDbm != null && rssiDbm >= -127 && rssiDbm <= 126 ? rssiDbm : null;
    }

    @Override
    public String toString() {
        return displayName;
    }
}

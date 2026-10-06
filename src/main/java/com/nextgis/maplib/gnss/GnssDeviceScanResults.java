package com.nextgis.maplib.gnss;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One scan's address-based results; signal updates do not move rows under a user's finger. */
final class GnssDeviceScanResults {
    private final Map<String, GnssDevice> devices = new LinkedHashMap<>();

    void update(GnssDevice device) {
        devices.put(device.transport + ":" + device.id, device);
    }

    boolean contains(String transport, String id) {
        return devices.containsKey(transport + ":" + id);
    }

    List<GnssDevice> snapshot() {
        return new ArrayList<>(devices.values());
    }
}

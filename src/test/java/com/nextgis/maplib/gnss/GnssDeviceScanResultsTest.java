package com.nextgis.maplib.gnss;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class GnssDeviceScanResultsTest {
    @Test public void identicalNamesStaySeparateAndSignalUpdatesKeepRowOrder() {
        GnssDeviceScanResults results = new GnssDeviceScanResults();
        results.update(new GnssDevice("bluetooth_le", "rover", "PiGo Lite", -45));
        results.update(new GnssDevice("bluetooth_le", "base", "PiGo Lite", -80));
        results.update(new GnssDevice("bluetooth_le", "rover", "PiGo Lite", -48));
        List<GnssDevice> devices = results.snapshot();
        assertEquals(2, devices.size());
        assertEquals("rover", devices.get(0).id);
        assertEquals(Integer.valueOf(-48), devices.get(0).rssiDbm);
        assertEquals("base", devices.get(1).id);
        assertEquals(Integer.valueOf(-80), devices.get(1).rssiDbm);
        assertEquals("PiGo Lite", devices.get(0).displayName);
        assertEquals("PiGo Lite", devices.get(0).toString());
    }

    @Test public void freshScanAndPairedDevicesHaveNoFabricatedSignal() {
        assertNull(new GnssDevice("bluetooth_classic", "rover", "PiGo Lite").rssiDbm);
        assertTrue(new GnssDeviceScanResults().snapshot().isEmpty());
        for (int unavailable : new int[]{127, Short.MIN_VALUE, -128, 999})
            assertNull(new GnssDevice("bluetooth_le", "rover", "PiGo Lite", unavailable).rssiDbm);
        assertEquals(Integer.valueOf(0), new GnssDevice("bluetooth_le", "r", "P", 0).rssiDbm);
    }

    @Test public void snapshotsAreIsolatedFromLaterMeasurements() {
        GnssDeviceScanResults results = new GnssDeviceScanResults();
        results.update(new GnssDevice("bluetooth_le", "rover", "PiGo Lite", -45));
        List<GnssDevice> previous = results.snapshot();
        results.update(new GnssDevice("bluetooth_le", "rover", "PiGo Lite", -70));
        assertEquals(Integer.valueOf(-45), previous.get(0).rssiDbm);
        previous.clear();
        assertEquals(1, results.snapshot().size());
    }

    @Test public void matchingUsesTransportAndIdentityNotDisplayName() {
        GnssDeviceScanResults results = new GnssDeviceScanResults();
        results.update(new GnssDevice("bluetooth_le", "rover", "PiGo Lite", -45));
        assertFalse(results.contains("bluetooth_classic", "rover"));
        assertTrue(results.contains("bluetooth_le", "rover"));
        assertFalse(results.contains("bluetooth_le", "base"));
    }
}

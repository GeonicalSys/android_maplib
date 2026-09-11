package com.nextgis.maplib.location;

import org.junit.Test;
import static org.junit.Assert.*;

public class LocationSubscriptionControllerTest {
    private static final class Fake implements LocationSubscriptionController.Backend {
        int gpsRequests, gpsStops, networkRequests, networkStops, acquisitions, releases;
        boolean failGps;
        long interval;
        public boolean requestGps(long intervalMs) { gpsRequests++; interval = intervalMs; return !failGps; }
        public void stopGps() { gpsStops++; }
        public boolean requestNetwork() { networkRequests++; return true; }
        public void stopNetwork() { networkStops++; }
        public void setRecordingActive(boolean active) { if (active) acquisitions++; else releases++; }
    }

    @Test public void repeatedScreenCyclesNeverRestartGpsOrReleaseRecordingResources() {
        Fake backend = new Fake();
        LocationSubscriptionController controller = new LocationSubscriptionController(backend);
        controller.update(1000, true, false);
        controller.update(1000, true, true);
        for (int i = 0; i < 50; i++) {
            controller.update(1000, false, true);
            controller.update(1000, true, true);
        }
        assertEquals(1, backend.gpsRequests);
        assertEquals(0, backend.gpsStops);
        assertEquals(1, backend.acquisitions);
        assertEquals(0, backend.releases);
        controller.update(1000, true, false);
        assertEquals(1, backend.releases);
        assertEquals(0, backend.gpsStops);
        controller.update(0, false, false);
        assertEquals(1, backend.gpsStops);
    }

    @Test public void precisionLeaseChangesIntervalWithoutRemovingTheGpsListener() {
        Fake backend = new Fake();
        LocationSubscriptionController controller = new LocationSubscriptionController(backend);
        controller.update(1000, false, true);
        controller.update(250, false, true);
        controller.update(1000, false, true);
        assertEquals(3, backend.gpsRequests);
        assertEquals(0, backend.gpsStops);
        assertEquals(1, backend.acquisitions);
        assertEquals(0, backend.releases);
    }

    @Test public void permissionLossStopsResourcesAndFailedRequestsCanBeRetried() {
        Fake backend = new Fake();
        LocationSubscriptionController controller = new LocationSubscriptionController(backend);
        backend.failGps = true;
        controller.update(1000, true, true);
        assertFalse(controller.hasGps());
        backend.failGps = false;
        controller.update(1000, true, true);
        assertTrue(controller.hasGps());
        controller.update(0, true, false);
        assertFalse(controller.hasGps());
        assertTrue(controller.hasNetwork());
        assertEquals(1, backend.releases);
        controller.update(0, false, false);
        assertEquals(1, backend.releases);
    }
}

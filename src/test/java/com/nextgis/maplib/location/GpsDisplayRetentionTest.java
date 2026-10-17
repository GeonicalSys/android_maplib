package com.nextgis.maplib.location;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.os.SystemClock;
import com.nextgis.maplib.api.GpsEventListener;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
import java.lang.reflect.Method;
import java.time.Duration;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class GpsDisplayRetentionTest {
    private GpsEventSource source;
    private final Object warmOwner = new Object();

    @Before public void setUp() {
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION);
        LocationManager manager = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true);
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true);
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        source = new GpsEventSource(app);
        source.acquireBackgroundUpdates(warmOwner);
    }

    @Test public void networkPointRemainsVisibleAcrossTwentySecondUpdatesButIsNotFresh() throws Exception {
        Location point = location(LocationManager.NETWORK_PROVIDER, 4_200f);
        accept(point);
        assertNotNull(source.getLastKnownLocation());
        ShadowSystemClock.advanceBy(Duration.ofSeconds(20));
        assertNull(source.getLastKnownLocation());
        assertNull(source.getLastRecordingLocation());
        Location retained = source.getLastDisplayLocation();
        assertNotNull(retained);
        assertEquals(point.getElapsedRealtimeNanos(), retained.getElapsedRealtimeNanos());
        assertEquals(4_200f, retained.getAccuracy(), 0f);
        assertEquals(20, retained.getExtras().getLong(GpsEventSource.DISPLAY_NETWORK_AGE_SECONDS));
        Location replacement = location(LocationManager.NETWORK_PROVIDER, 35f);
        replacement.setLatitude(60.01);
        accept(replacement);
        assertEquals(60.01, source.getLastDisplayLocation().getLatitude(), 0.0);
        assertEquals(0, source.getLastDisplayLocation().getExtras().getLong(GpsEventSource.DISPLAY_NETWORK_AGE_SECONDS));
    }

    @Test public void displayOnlyAcceptsAnOlderNetworkCallbackAndPrefersFreshGps() throws Exception {
        Location network = location(LocationManager.NETWORK_PROVIDER, 20f);
        ShadowSystemClock.advanceBy(Duration.ofSeconds(15));
        accept(network);
        assertNotNull(source.getLastDisplayLocation());
        assertNull(source.getLastKnownLocation());
        accept(location(LocationManager.GPS_PROVIDER, 100f));
        assertEquals(LocationManager.GPS_PROVIDER, source.getLastDisplayLocation().getProvider());
        assertNull(source.getLastRecordingLocation());
    }

    @Test public void gpsLossDoesNotTeleportToAnOlderPreviouslyHiddenNetworkEstimate() throws Exception {
        accept(location(LocationManager.NETWORK_PROVIDER, 4_200f));
        source.getLastDisplayLocation();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(20));
        accept(location(LocationManager.GPS_PROVIDER, 100f));
        assertEquals(LocationManager.GPS_PROVIDER, source.getLastDisplayLocation().getProvider());
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10));
        assertNull(source.getLastDisplayLocation());
        assertNull(source.getLastDisplayLocation());
        Location newerNetwork = location(LocationManager.NETWORK_PROVIDER, 40f);
        accept(newerNetwork);
        assertNotNull(source.getLastDisplayLocation());
        ShadowSystemClock.advanceBy(Duration.ofSeconds(20));
        assertNotNull(source.getLastDisplayLocation());
    }

    @Test public void slowRecoveryCreatesStructuredDiagnosticsWithoutVerboseLogging() throws Exception {
        java.util.List<java.util.Map<String, Object>> reports = new java.util.ArrayList<>();
        source.setRecoveryReporter(reports::add);
        Method check = GpsEventSource.class.getDeclaredMethod("checkRecovery");
        check.setAccessible(true);
        check.invoke(source);
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31));
        check.invoke(source);
        assertTrue(reports.isEmpty());
        ShadowSystemClock.advanceBy(Duration.ofSeconds(29));
        check.invoke(source);
        assertEquals(1, reports.size());
        assertEquals(30_000L, reports.get(0).get("threshold_ms"));
        assertEquals(false, reports.get(0).get("raw_fresh"));
        assertEquals(1, reports.get(0).get("background_owners"));
        assertTrue(reports.get(0).containsKey("satellites_ephemeris"));
        assertTrue(reports.get(0).containsKey("power"));
        assertTrue(reports.get(0).containsKey("last_callback_delivery_lag_ms"));
        assertTrue(reports.get(0).containsKey("history"));
        check.invoke(source);
        assertEquals(1, reports.size());
        accept(location(LocationManager.GPS_PROVIDER, 100f));
        check.invoke(source);
        assertEquals(2, reports.size());
        assertEquals("recovered", reports.get(1).get("phase"));
        assertEquals(reports.get(0).get("episode_id"), reports.get(1).get("episode_id"));
    }

    @Test public void stoppedSubscriptionDoesNotCountClosedAppTimeAsSlowRecovery() throws Exception {
        java.util.List<java.util.Map<String, Object>> reports = new java.util.ArrayList<>();
        source.setRecoveryReporter(reports::add);
        Method check = GpsEventSource.class.getDeclaredMethod("checkRecovery");
        check.setAccessible(true);
        check.invoke(source);
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10));
        source.releaseBackgroundUpdates(warmOwner);
        ShadowSystemClock.advanceBy(Duration.ofHours(3));
        source.acquireBackgroundUpdates(warmOwner);
        check.invoke(source);
        assertTrue(reports.isEmpty());
        ShadowSystemClock.advanceBy(Duration.ofSeconds(59));
        check.invoke(source);
        assertTrue(reports.isEmpty());
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        check.invoke(source);
        assertEquals(1, reports.size());
        assertEquals(60_000L, reports.get(0).get("waiting_ms"));
    }

    @Test public void mapAttachDetachDoesNotRestartGpsWithAutomaticOwner() {
        GpsEventListener listener = new GpsEventListener() {
            public void onLocationChanged(Location location) { }
            public void onGpsStatusChanged(int event) { }
            public void onBestLocationChanged(Location location) { }
        };
        for (int i = 0; i < 20; i++) { source.addListener(listener); source.removeListener(listener); }
        assertTrue(source.getRecordingDiagnostics().contains("gpsRequests=1 gpsStops=0"));
        source.releaseBackgroundUpdates(warmOwner);
        assertTrue(source.getRecordingDiagnostics().contains("gpsRequests=1 gpsStops=1"));
    }

    private Location location(String provider, float accuracy) {
        Location location = new Location(provider);
        location.setLatitude(60); location.setLongitude(34);
        location.setAccuracy(accuracy);
        location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        return location;
    }
    private void accept(Location location) throws Exception {
        Method method = GpsEventSource.class.getDeclaredMethod("processLocation", Location.class, boolean.class);
        method.setAccessible(true); method.invoke(source, location, false);
    }
}

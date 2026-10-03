package com.nextgis.maplib.gnss;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Looper;

import com.nextgis.maplib.util.SettingsConstants;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE, sdk={26, 36})
@LooperMode(LooperMode.Mode.PAUSED)
public class ExternalGnssSessionTest {
    private final List<FakeTransport> transports = new ArrayList<>();
    private final List<Location> locations = new ArrayList<>();
    private final List<GnssFix> fixes = new ArrayList<>();
    private ExternalGnssSession session;
    private boolean pigo = true;
    private boolean failFirstOpen;

    @Before public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = context.getSharedPreferences("pigo-session-test", Context.MODE_PRIVATE);
        prefs.edit().clear().putString(SettingsConstants.KEY_PREF_GNSS_DEVICE_ID, "test-receiver").commit();
        session = new ExternalGnssSession(context, prefs, () -> {
            FakeTransport transport = new FakeTransport(pigo, failFirstOpen && transports.isEmpty());
            transports.add(transport);
            return transport;
        });
        session.setCallback(new ExternalGnssSession.Callback() {
            @Override public void onExternalLocation(Location location) { locations.add(location); }
            @Override public void onExternalFix(GnssFix fix) { fixes.add(fix); }
            @Override public void onExternalStatus(String status) { }
        });
    }

    @After public void tearDown() {
        session.setWanted(false);
        idle();
    }

    @Test public void quietPigoStartsBestPosOnlyAfterConfirmedOpenAndOnlyOnce() {
        session.setWanted(true);
        FakeTransport transport = transports.get(0);
        advance(3_000);
        assertTrue(transport.writes.isEmpty());
        transport.listener.onOpened();
        idle();
        advance(2_001);
        assertEquals(1, transport.writes.size());
        assertEquals("log bestposb ontime 1\r\n", transport.writes.get(0));
        transport.listener.onOpened();
        advance(10_000);
        assertEquals(1, transport.writes.size());
    }

    @Test public void liveRtkBinaryStreamKeepsQualityAndReceiverCadence() {
        FakeTransport transport = start();
        transport.bytes(position(CnbBestPos.POS_NARROW_FLOAT));
        idle();
        assertEquals(5, session.lastFix().quality);
        assertEquals("RTK FLOAT", locations.get(0).getExtras().getString("diffStatus"));
        advance(2_001);
        assertTrue(transport.writes.isEmpty());
        transport.bytes(position(CnbBestPos.POS_NARROW_INT));
        idle();
        assertEquals(4, session.lastFix().quality);
        assertEquals(2, locations.size());
    }

    @Test public void partialBinaryBeforeSubscriptionCannotInitializePrematurely() {
        session.setWanted(true);
        FakeTransport transport = transports.get(0);
        transport.bytes(new byte[] {(byte) 0xAA, 0x44, 0x12});
        idle();
        assertTrue(transport.writes.isEmpty());
        transport.listener.onOpened();
        idle();
        advance(2_001);
        assertEquals(1, transport.writes.size());
        assertEquals("log bestposb ontime 1\r\n", transport.writes.get(0));
    }

    @Test public void existingNoFixPositionLogIsNotReconfigured() {
        FakeTransport transport = start();
        transport.bytes(CnbBestPosTest.bestposb(55.75, 37.62, 10, 1, 0, 0));
        idle();
        advance(2_001);
        assertTrue(transport.writes.isEmpty());
        assertFalse(session.lastFix().hasFix());
        assertTrue(locations.isEmpty());
    }

    @Test public void pureNmeaTransportGetsNoBinaryInitialization() {
        pigo = false;
        FakeTransport transport = start();
        transport.bytes("$GNGGA,120000,5545.0000,N,03737.2000,E,5,16,0.6,10,M,0,M,1,0000\r\n"
                .getBytes(StandardCharsets.US_ASCII));
        idle();
        advance(3_001);
        assertTrue(transport.writes.isEmpty());
        assertEquals(5, session.lastFix().quality);
        assertEquals(1, locations.size());
    }

    @Test public void qualityExpiresWithPositionAndRecoversOnNewData() {
        FakeTransport transport = start();
        transport.bytes(position(CnbBestPos.POS_SINGLE));
        idle();
        advance(8_000);
        assertTrue(session.lastFix().hasFix());
        advance(1);
        assertFalse(session.lastFix().hasFix());
        assertFalse(fixes.get(fixes.size() - 1).hasFix());
        assertEquals(1, locations.size());
        transport.bytes(position(CnbBestPos.POS_NARROW_FLOAT));
        idle();
        assertEquals(5, session.lastFix().quality);
        assertEquals(2, locations.size());
        assertTrue(locations.get(1).getElapsedRealtimeNanos() > locations.get(0).getElapsedRealtimeNanos());
    }

    @Test public void callbacksFromClosedTransportCannotCloseOrPublishIntoNewSession() {
        FakeTransport old = start();
        GnssTransport.Listener late = old.listener;
        session.restart();
        FakeTransport current = transports.get(1);
        current.listener.onOpened();
        idle();
        late.onClosed("late disconnect");
        late.onOpened();
        late.onBytes(position(CnbBestPos.POS_SINGLE), 104);
        idle();
        assertEquals(ExternalGnssSession.STATUS_CONNECTED, session.status());
        assertFalse(current.closed);
        assertTrue(locations.isEmpty());
        current.bytes(position(CnbBestPos.POS_NARROW_FLOAT));
        idle();
        assertEquals(5, session.lastFix().quality);
    }

    @Test public void damagedNmeaCannotKeepOldQualityAlive() {
        pigo = false;
        FakeTransport transport = start();
        String gga = "$GNGGA,120000,5545.0000,N,03737.2000,E,5,16,0.6,10,M,0,M,1,0000";
        transport.bytes((gga + "\r\n").getBytes(StandardCharsets.US_ASCII));
        idle();
        advance(7_000);
        transport.bytes((gga + "*FF\r\n").getBytes(StandardCharsets.US_ASCII));
        idle();
        advance(1_001);
        assertFalse(session.lastFix().hasFix());
        assertEquals(1, locations.size());
    }

    @Test public void stopCancelsInitializationExpiryAndLateCallbacks() {
        FakeTransport transport = start();
        GnssTransport.Listener late = transport.listener;
        session.setWanted(false);
        late.onOpened();
        late.onClosed("late disconnect");
        late.onBytes(position(CnbBestPos.POS_SINGLE), 104);
        advance(30_000);
        assertEquals(ExternalGnssSession.STATUS_IDLE, session.status());
        assertTrue(transport.writes.isEmpty());
        assertTrue(locations.isEmpty());
        assertEquals(1, transports.size());
        assertNull(session.lastFix());
        assertFalse(fixes.get(fixes.size() - 1).hasFix());
    }

    @Test public void failedOpenDoesNotCrashAndReconnects() {
        failFirstOpen = true;
        session.setWanted(true);
        assertEquals(ExternalGnssSession.STATUS_CONNECTING, session.status());
        advance(2_001);
        assertEquals(2, transports.size());
        transports.get(1).listener.onOpened();
        idle();
        assertEquals(ExternalGnssSession.STATUS_CONNECTED, session.status());
    }

    private FakeTransport start() {
        session.setWanted(true);
        FakeTransport transport = transports.get(0);
        transport.listener.onOpened();
        idle();
        return transport;
    }

    private static byte[] position(int quality) {
        return CnbBestPosTest.bestposb(55.75, 37.62, 10, CnbBestPos.SOL_COMPUTED, quality, 20);
    }

    private static void idle() { shadowOf(Looper.getMainLooper()).idle(); }
    private static void advance(long ms) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); }

    private static final class FakeTransport implements GnssTransport {
        final boolean pigo;
        final boolean failOpen;
        final List<String> writes = new ArrayList<>();
        Listener listener;
        boolean closed;
        FakeTransport(boolean pigo, boolean failOpen) { this.pigo = pigo; this.failOpen = failOpen; }
        @Override public void open(Listener listener) {
            this.listener = listener;
            if (failOpen) throw new IllegalStateException("simulated permission or adapter failure");
        }
        @Override public boolean write(byte[] data) {
            if (closed) return false;
            writes.add(new String(data, StandardCharsets.US_ASCII));
            return true;
        }
        @Override public boolean usesComNavBinary() { return pigo; }
        @Override public void close() { closed = true; }
        void bytes(byte[] data) { listener.onBytes(data, data.length); }
    }
}

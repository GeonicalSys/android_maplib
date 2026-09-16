package com.nextgis.maplib.gnss;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.nextgis.maplib.util.Constants;
import com.nextgis.maplib.util.DiagnosticLog;

/**
 * Owns one external NMEA transport, reconnects while consumers remain, and
 * publishes Android Location objects without Mock Location.
 */
public final class ExternalGnssSession {
    public interface Callback {
        void onExternalLocation(Location location);
        void onExternalFix(GnssFix fix);
        void onExternalStatus(String status);
    }

    public static final String STATUS_IDLE = "idle";
    public static final String STATUS_CONNECTING = "connecting";
    public static final String STATUS_CONNECTED = "connected";
    public static final String STATUS_NO_DEVICE = "no_device";

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final NmeaParser parser = new NmeaParser();
    private final NmeaLineBuffer lines = new NmeaLineBuffer();
    private Callback callback;
    private GnssTransport transport;
    private boolean wanted;
    private int attempt;
    private String status = STATUS_IDLE;
    private GnssFix lastFix;

    public ExternalGnssSession(Context context, SharedPreferences prefs) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public String status() {
        return status;
    }

    public GnssFix lastFix() {
        return lastFix == null ? null : lastFix.copy();
    }

    public void setWanted(boolean wanted) {
        this.wanted = wanted;
        if (!wanted) {
            handler.removeCallbacksAndMessages(null);
            closeTransport();
            parser.reset();
            lines.reset();
            lastFix = null;
            setStatus(STATUS_IDLE);
            return;
        }
        ensureStarted();
    }

    public void restart() {
        closeTransport();
        parser.reset();
        lines.reset();
        lastFix = null;
        attempt = 0;
        if (wanted) {
            ensureStarted();
        }
    }

    private void ensureStarted() {
        if (!wanted) {
            return;
        }
        if (!GnssTransportFactory.hasEndpoint(prefs)) {
            closeTransport();
            setStatus(STATUS_NO_DEVICE);
            return;
        }
        if (transport != null) {
            return;
        }
        setStatus(STATUS_CONNECTING);
        transport = GnssTransportFactory.create(context, prefs);
        transport.open(new GnssTransport.Listener() {
            @Override
            public void onOpened() {
                handler.post(() -> {
                    attempt = 0;
                    setStatus(STATUS_CONNECTED);
                });
            }

            @Override
            public void onBytes(byte[] data, int length) {
                final byte[] copy = java.util.Arrays.copyOf(data, length);
                handler.post(() -> consume(copy, copy.length));
            }

            @Override
            public void onClosed(String reason) {
                handler.post(() -> onTransportClosed(reason));
            }
        });
    }

    private void consume(byte[] data, int length) {
        if (!wanted || transport == null) {
            return;
        }
        lines.append(data, length, line -> {
            boolean ready = parser.accept(line);
            GnssFix snapshot = parser.snapshot();
            lastFix = snapshot;
            Callback local = callback;
            if (local != null) {
                local.onExternalFix(snapshot);
            }
            DiagnosticLog.v("NMEA q=" + snapshot.quality
                    + " sats=" + snapshot.satellites
                    + " hdop=" + snapshot.hdop
                    + " hasFix=" + snapshot.hasFix()
                    + " locPublished=" + ready
                    + " line=" + line);
            if (!ready) {
                return;
            }
            Location location = GnssLocationFactory.toLocation(
                    snapshot, SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis());
            if (location != null && local != null) {
                local.onExternalLocation(location);
            }
        });
    }

    private void onTransportClosed(String reason) {
        Log.i(Constants.TAG, "External GNSS closed: " + reason);
        DiagnosticLog.v("External GNSS closed: " + reason);
        closeTransport();
        if (!wanted) {
            setStatus(STATUS_IDLE);
            return;
        }
        setStatus(STATUS_CONNECTING);
        long delay = Math.min(30_000L, 2_000L * (1L << Math.min(attempt, 4)));
        attempt++;
        handler.postDelayed(this::ensureStarted, delay);
    }

    private void closeTransport() {
        GnssTransport local = transport;
        transport = null;
        if (local != null) {
            local.close();
        }
    }

    private void setStatus(String value) {
        status = value;
        DiagnosticLog.v("External GNSS status=" + value);
        Callback local = callback;
        if (local != null) {
            local.onExternalStatus(value);
        }
    }
}

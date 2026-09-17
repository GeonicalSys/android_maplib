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
 * Owns one external GNSS transport, reconnects while consumers remain, and
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

    private static final long ASCII_WAIT_MS = 2_000L;
    private static final long COMMAND_GAP_MS = 400L;

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final NmeaParser parser = new NmeaParser();
    private final NmeaLineBuffer lines = new NmeaLineBuffer();
    private final CnbPreamble cnb = new CnbPreamble();
    private final CnbFrameBuffer frames = new CnbFrameBuffer();
    private final Runnable requestNmea = this::requestNmeaIfNeeded;
    private final Runnable sendNextCommand = this::sendNextCommand;
    private Callback callback;
    private GnssTransport transport;
    private boolean wanted;
    private boolean asciiSeen;
    private boolean commandsSent;
    private int commandIndex;
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
            resetParsers();
            lastFix = null;
            setStatus(STATUS_IDLE);
            return;
        }
        ensureStarted();
    }

    public void restart() {
        closeTransport();
        resetParsers();
        lastFix = null;
        attempt = 0;
        if (wanted) {
            ensureStarted();
        }
    }

    private void resetParsers() {
        parser.reset();
        lines.reset();
        cnb.reset();
        frames.reset();
        asciiSeen = false;
        commandsSent = false;
        commandIndex = 0;
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
        asciiSeen = false;
        commandsSent = false;
        commandIndex = 0;
        cnb.reset();
        frames.reset();
        transport = GnssTransportFactory.create(context, prefs);
        transport.open(new GnssTransport.Listener() {
            @Override
            public void onOpened() {
                handler.post(() -> {
                    attempt = 0;
                    setStatus(STATUS_CONNECTED);
                    handler.removeCallbacks(requestNmea);
                    if (!commandsSent && !asciiSeen) {
                        handler.postDelayed(requestNmea, ASCII_WAIT_MS);
                    }
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
        if (containsAsciiOk(data, length) && commandsSent && commandIndex > 0) {
            handler.removeCallbacks(sendNextCommand);
            sendNextCommand();
        }
        if (!asciiSeen && !commandsSent && cnb.accept(data, length)) {
            requestNmeaIfNeeded();
        }
        frames.append(data, length, this::onCnbFrame);
        lines.append(data, length, this::onAsciiLine);
    }

    private void onCnbFrame(byte[] frame, int frameLength) {
        GnssFix fromCnb = CnbBestPos.parse(frame, frameLength);
        if (fromCnb == null) {
            return;
        }
        lastFix = fromCnb;
        Callback local = callback;
        if (local != null) {
            local.onExternalFix(fromCnb);
        }
        boolean ready = fromCnb.hasFix();
        DiagnosticLog.v("CNB BESTPOSB q=" + fromCnb.quality
                + " sats=" + fromCnb.satellites
                + " hasFix=" + ready
                + " locPublished=" + ready);
        if (!ready) {
            return;
        }
        Location location = GnssLocationFactory.toLocation(
                fromCnb, SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis());
        if (location != null && local != null) {
            local.onExternalLocation(location);
        }
    }

    private void onAsciiLine(String line) {
        if (!isAsciiGnss(line)) {
            return;
        }
        asciiSeen = true;
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
    }

    private void requestNmeaIfNeeded() {
        if (!wanted || transport == null || commandsSent || asciiSeen) {
            return;
        }
        commandsSent = true;
        commandIndex = 0;
        handler.removeCallbacks(requestNmea);
        handler.removeCallbacks(sendNextCommand);
        sendNextCommand();
    }

    private void sendNextCommand() {
        byte[][] commands = ComNavAsciiCommands.nmeaEnableCommands();
        if (!wanted || transport == null || commandIndex >= commands.length) {
            return;
        }
        boolean queued = transport.write(commands[commandIndex]);
        DiagnosticLog.v("External GNSS ComNav cmd=" + commandIndex + " queued=" + queued);
        commandIndex++;
        if (commandIndex < commands.length) {
            handler.postDelayed(sendNextCommand, COMMAND_GAP_MS);
        }
    }

    static boolean isAsciiGnss(String line) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        if (line.charAt(0) == '#' && line.regionMatches(true, 1, "BESTPOSA", 0, 8)) {
            return true;
        }
        if (line.charAt(0) != '$' || line.length() < 6) {
            return false;
        }
        String upper = line.toUpperCase();
        return upper.contains("GGA") || upper.contains("RMC") || upper.contains("GST")
                || upper.contains("GSA");
    }

    static boolean containsAsciiOk(byte[] data, int length) {
        if (data == null || length <= 0) {
            return false;
        }
        int matched = 0;
        int limit = Math.min(length, data.length);
        for (int i = 0; i < limit; i++) {
            byte value = data[i];
            if (matched == 0 && value == 'O') {
                matched = 1;
            } else if (matched == 1 && value == 'K') {
                matched = 2;
            } else if (matched == 2 && value == '!') {
                return true;
            } else {
                matched = value == 'O' ? 1 : 0;
            }
        }
        return false;
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

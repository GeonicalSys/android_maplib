package com.nextgis.maplib.gnss;

import android.util.Log;

import com.nextgis.maplib.util.Constants;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/** Raw NMEA TCP stream from a receiver Wi-Fi/LAN port, not an NTRIP caster. */
public final class TcpNmeaTransport implements GnssTransport {
    private final String host;
    private final int port;
    private final AtomicBoolean running = new AtomicBoolean();
    private Socket socket;
    private OutputStream output;

    public TcpNmeaTransport(String host, int port) {
        this.host = host;
        this.port = port;
    }

    @Override
    public void open(Listener listener) {
        close();
        running.set(true);
        Thread thread = new Thread(() -> connect(listener), "nmea-tcp");
        thread.setDaemon(true);
        thread.start();
    }

    private void connect(Listener listener) {
        try {
            if (host == null || host.trim().isEmpty() || port <= 0 || port > 65535) {
                listener.onClosed("missing host");
                return;
            }
            Socket local = new Socket();
            local.connect(new InetSocketAddress(host.trim(), port), 8_000);
            local.setKeepAlive(true);
            if (!running.get()) {
                local.close();
                return;
            }
            socket = local;
            output = local.getOutputStream();
            listener.onOpened();
            InputStream in = local.getInputStream();
            byte[] buffer = new byte[1024];
            while (running.get()) {
                int n = in.read(buffer);
                if (n < 0) {
                    break;
                }
                if (n > 0) {
                    listener.onBytes(buffer, n);
                }
            }
            listener.onClosed("stream ended");
        } catch (Exception exception) {
            if (running.get()) {
                Log.w(Constants.TAG, "TCP GNSS closed", exception);
                listener.onClosed("unavailable");
            }
        } finally {
            close();
        }
    }

    @Override
    public boolean write(byte[] data) {
        OutputStream local = output;
        if (local == null || data == null || data.length == 0) {
            return false;
        }
        try {
            local.write(data);
            local.flush();
            return true;
        } catch (Exception exception) {
            Log.w(Constants.TAG, "TCP GNSS write failed", exception);
            return false;
        }
    }

    @Override
    public void close() {
        running.set(false);
        output = null;
        Socket local = socket;
        socket = null;
        if (local == null) {
            return;
        }
        try {
            local.close();
        } catch (Exception ignored) {
        }
    }
}

package com.nextgis.maplib.gnss;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import com.nextgis.maplib.util.Constants;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** RFCOMM Serial Port Profile NMEA. */
public final class BluetoothClassicTransport implements GnssTransport {
    static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final BluetoothAdapter adapter;
    private final String address;
    private final AtomicBoolean running = new AtomicBoolean();
    private BluetoothSocket socket;
    private OutputStream output;

    public BluetoothClassicTransport(BluetoothAdapter adapter, String address) {
        this.adapter = adapter;
        this.address = address;
    }

    @Override
    public void open(Listener listener) {
        close();
        running.set(true);
        Thread thread = new Thread(() -> connect(listener), "nmea-bt-classic");
        thread.setDaemon(true);
        thread.start();
    }

    private void connect(Listener listener) {
        try {
            if (adapter == null || address == null || address.isEmpty()) {
                listener.onClosed("missing adapter");
                return;
            }
            adapter.cancelDiscovery();
            BluetoothDevice device = adapter.getRemoteDevice(address);
            BluetoothSocket local;
            try {
                local = device.createRfcommSocketToServiceRecord(SPP_UUID);
                local.connect();
            } catch (Exception first) {
                local = (BluetoothSocket) device.getClass()
                        .getMethod("createRfcommSocket", int.class)
                        .invoke(device, 1);
                local.connect();
            }
            if (!running.get()) {
                closeQuietly(local);
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
                Log.w(Constants.TAG, "Bluetooth Classic GNSS closed", exception);
                listener.onClosed("unavailable");
            }
        } finally {
            close();
        }
    }

    @Override
    public void write(byte[] data) {
        OutputStream local = output;
        if (local == null || data == null || data.length == 0) {
            return;
        }
        try {
            local.write(data);
            local.flush();
        } catch (Exception exception) {
            Log.w(Constants.TAG, "Bluetooth Classic GNSS write failed", exception);
        }
    }

    @Override
    public void close() {
        running.set(false);
        output = null;
        BluetoothSocket local = socket;
        socket = null;
        closeQuietly(local);
    }

    private static void closeQuietly(BluetoothSocket local) {
        if (local == null) {
            return;
        }
        try {
            local.close();
        } catch (Exception ignored) {
        }
    }
}

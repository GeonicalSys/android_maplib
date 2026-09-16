package com.nextgis.maplib.gnss;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.nextgis.maplib.util.Constants;

import java.util.concurrent.atomic.AtomicBoolean;

/** USB Host CDC/CH340/CP210x/FTDI NMEA via usb-serial-for-android. */
public final class UsbSerialTransport implements GnssTransport {
    public static final String ACTION_USB_PERMISSION = "com.nextgis.maplib.gnss.USB_PERMISSION";

    private final Context context;
    private final String deviceId;
    private final AtomicBoolean running = new AtomicBoolean();
    private UsbSerialPort port;
    private BroadcastReceiver permissionReceiver;

    public UsbSerialTransport(Context context, String deviceId) {
        this.context = context.getApplicationContext();
        this.deviceId = deviceId;
    }

    @Override
    public void open(Listener listener) {
        close();
        running.set(true);
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        UsbDevice device = findDevice(manager);
        if (device == null) {
            listener.onClosed("usb device missing");
            return;
        }
        if (!manager.hasPermission(device)) {
            requestPermission(manager, device, listener);
            return;
        }
        openGranted(manager, device, listener);
    }

    private void requestPermission(UsbManager manager, UsbDevice device, Listener listener) {
        permissionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                context.unregisterReceiver(this);
                permissionReceiver = null;
                if (!running.get()) {
                    return;
                }
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    openGranted(manager, device, listener);
                } else {
                    listener.onClosed("usb permission denied");
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(permissionReceiver, filter);
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent pending = PendingIntent.getBroadcast(context, 0,
                new Intent(ACTION_USB_PERMISSION).setPackage(context.getPackageName()), flags);
        manager.requestPermission(device, pending);
    }

    private void openGranted(UsbManager manager, UsbDevice device, Listener listener) {
        Thread thread = new Thread(() -> read(manager, device, listener), "nmea-usb");
        thread.setDaemon(true);
        thread.start();
    }

    private void read(UsbManager manager, UsbDevice device, Listener listener) {
        UsbDeviceConnection connection = null;
        try {
            UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
            if (driver == null || driver.getPorts().isEmpty()) {
                listener.onClosed("unsupported usb serial");
                return;
            }
            connection = manager.openDevice(device);
            if (connection == null) {
                listener.onClosed("usb open failed");
                return;
            }
            UsbSerialPort local = driver.getPorts().get(0);
            local.open(connection);
            local.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            port = local;
            listener.onOpened();
            byte[] buffer = new byte[1024];
            while (running.get()) {
                int n = local.read(buffer, 1000);
                if (n > 0) {
                    listener.onBytes(buffer, n);
                }
            }
            listener.onClosed("stream ended");
        } catch (Exception exception) {
            if (running.get()) {
                Log.w(Constants.TAG, "USB GNSS closed", exception);
                listener.onClosed("unavailable");
            }
        } finally {
            closePort();
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    UsbDevice findDevice(UsbManager manager) {
        if (manager == null) {
            return null;
        }
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (matches(device, deviceId)) {
                return device;
            }
        }
        return null;
    }

    static boolean matches(UsbDevice device, String id) {
        if (device == null) {
            return false;
        }
        if (id == null || id.isEmpty()) {
            return true;
        }
        return id.equals(deviceKey(device)) || id.equals(String.valueOf(device.getDeviceId()));
    }

    public static String deviceKey(UsbDevice device) {
        return device.getVendorId() + ":" + device.getProductId() + ":" + device.getDeviceId();
    }

    public static String deviceLabel(UsbDevice device) {
        String name = device.getProductName();
        if (name != null && !name.trim().isEmpty()) {
            return name.trim();
        }
        return "USB " + Integer.toHexString(device.getVendorId()) + ":"
                + Integer.toHexString(device.getProductId());
    }

    @Override
    public void close() {
        running.set(false);
        if (permissionReceiver != null) {
            try {
                context.unregisterReceiver(permissionReceiver);
            } catch (RuntimeException ignored) {
            }
            permissionReceiver = null;
        }
        closePort();
    }

    private void closePort() {
        UsbSerialPort local = port;
        port = null;
        if (local == null) {
            return;
        }
        try {
            local.close();
        } catch (Exception ignored) {
        }
    }
}

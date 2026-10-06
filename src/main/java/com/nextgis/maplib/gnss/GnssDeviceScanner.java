package com.nextgis.maplib.gnss;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/** Lists bonded Classic, attached USB and BLE candidates, with scan-only Bluetooth RSSI. */
public final class GnssDeviceScanner {
    public interface BleListener {
        void onDevices(List<GnssDevice> devices);
    }

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothLeScanner leScanner;
    private ScanCallback leCallback;
    private BluetoothAdapter classicAdapter;
    private BroadcastReceiver classicReceiver;
    private boolean classicStarted;
    private int scanGeneration;

    public GnssDeviceScanner(Context context) {
        this.context = context.getApplicationContext();
    }

    public List<GnssDevice> bondedClassic() {
        List<GnssDevice> devices = new ArrayList<>();
        try {
            BluetoothAdapter adapter = GnssTransportFactory.adapter(context);
            if (adapter == null) return devices;
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                devices.add(new GnssDevice(GnssInputPrefs.TRANSPORT_BLUETOOTH_CLASSIC,
                        device.getAddress(), label(device)));
            }
        } catch (SecurityException ignored) { }
        return devices;
    }

    public List<GnssDevice> usbDevices() {
        List<GnssDevice> devices = new ArrayList<>();
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (manager == null) return devices;
        for (UsbDevice device : manager.getDeviceList().values()) {
            devices.add(new GnssDevice(GnssInputPrefs.TRANSPORT_USB,
                    UsbSerialTransport.deviceKey(device), UsbSerialTransport.deviceLabel(device)));
        }
        return devices;
    }

    /** Refresh RSSI for paired Classic devices; do not add unpaired endpoints. */
    public void startClassicScan(BleListener listener) {
        stopBleScan();
        int generation = scanGeneration;
        GnssDeviceScanResults found = new GnssDeviceScanResults();
        for (GnssDevice device : bondedClassic()) found.update(device);
        listener.onDevices(found.snapshot());
        try {
            classicAdapter = GnssTransportFactory.adapter(context);
            if (classicAdapter == null || !classicAdapter.isEnabled()) return;
            classicReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (generation != scanGeneration) return;
                    if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(intent.getAction())) {
                        stopBleScan();
                        return;
                    }
                    if (!BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) return;
                    try {
                        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                        if (device == null || !found.contains(GnssInputPrefs.TRANSPORT_BLUETOOTH_CLASSIC,
                                device.getAddress())) return;
                        Integer rssi = intent.hasExtra(BluetoothDevice.EXTRA_RSSI)
                                ? (int) intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE) : null;
                        found.update(new GnssDevice(GnssInputPrefs.TRANSPORT_BLUETOOTH_CLASSIC,
                                device.getAddress(), label(device), rssi));
                        listener.onDevices(found.snapshot());
                    } catch (SecurityException ignored) {
                        stopBleScan();
                    }
                }
            };
            IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
            filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
            // Bluetooth broadcasts come from a privileged Bluetooth UID, not only system UID.
            if (Build.VERSION.SDK_INT >= 33)
                context.registerReceiver(classicReceiver, filter, Context.RECEIVER_EXPORTED);
            else context.registerReceiver(classicReceiver, filter);
            classicStarted = classicAdapter.startDiscovery();
            if (!classicStarted) stopBleScan();
            else scheduleStop(generation);
        } catch (RuntimeException exception) {
            stopBleScan();
        }
    }

    public void startBleScan(BleListener listener) {
        stopBleScan();
        int generation = scanGeneration;
        GnssDeviceScanResults found = new GnssDeviceScanResults();
        listener.onDevices(found.snapshot());
        try {
            BluetoothAdapter adapter = GnssTransportFactory.adapter(context);
            if (adapter == null) return;
            leScanner = adapter.getBluetoothLeScanner();
            if (leScanner == null) return;
            leCallback = new ScanCallback() {
                @Override public void onScanResult(int callbackType, ScanResult result) {
                    accept(result);
                }

                @Override public void onBatchScanResults(List<ScanResult> results) {
                    for (ScanResult result : results) accept(result);
                }

                private void accept(ScanResult result) {
                    handler.post(() -> {
                        if (generation != scanGeneration || result == null) return;
                        try {
                            BluetoothDevice device = result.getDevice();
                            if (device == null || device.getAddress() == null) return;
                            String name = result.getScanRecord() == null ? null
                                    : result.getScanRecord().getDeviceName();
                            if (name == null || name.trim().isEmpty()) name = label(device);
                            found.update(new GnssDevice(GnssInputPrefs.TRANSPORT_BLUETOOTH_LE,
                                    device.getAddress(), name, result.getRssi()));
                            listener.onDevices(found.snapshot());
                        } catch (SecurityException ignored) {
                            stopBleScan();
                        }
                    });
                }

                @Override public void onScanFailed(int errorCode) {
                    handler.post(() -> {
                        if (generation == scanGeneration) stopBleScan();
                    });
                }
            };
            leScanner.startScan(null, new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), leCallback);
            scheduleStop(generation);
        } catch (RuntimeException exception) {
            stopBleScan();
        }
    }

    private void scheduleStop(int generation) {
        handler.postDelayed(() -> {
            if (generation == scanGeneration) stopBleScan();
        }, 12_000);
    }

    /** Legacy name retained; releases either discovery and invalidates late callbacks. */
    public void stopBleScan() {
        scanGeneration++;
        handler.removeCallbacksAndMessages(null);
        if (leScanner != null && leCallback != null) {
            try { leScanner.stopScan(leCallback); } catch (RuntimeException ignored) { }
        }
        leScanner = null;
        leCallback = null;
        if (classicReceiver != null) {
            try { context.unregisterReceiver(classicReceiver); } catch (RuntimeException ignored) { }
        }
        classicReceiver = null;
        if (classicAdapter != null && classicStarted) {
            try { classicAdapter.cancelDiscovery(); } catch (RuntimeException ignored) { }
        }
        classicAdapter = null;
        classicStarted = false;
    }

    private static String label(BluetoothDevice device) {
        try {
            String name = device.getName();
            if (name != null && !name.trim().isEmpty()) return name.trim();
        } catch (SecurityException ignored) { }
        return device.getAddress();
    }
}

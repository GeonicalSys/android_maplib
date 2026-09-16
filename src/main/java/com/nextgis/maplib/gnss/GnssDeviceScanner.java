package com.nextgis.maplib.gnss;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lists bonded Classic, attached USB, and (optionally) BLE UART candidates. */
public final class GnssDeviceScanner {
    public interface BleListener {
        void onDevices(List<GnssDevice> devices);
    }

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothLeScanner leScanner;
    private ScanCallback leCallback;

    public GnssDeviceScanner(Context context) {
        this.context = context.getApplicationContext();
    }

    public List<GnssDevice> bondedClassic() {
        List<GnssDevice> devices = new ArrayList<>();
        BluetoothAdapter adapter = GnssTransportFactory.adapter(context);
        if (adapter == null) {
            return devices;
        }
        try {
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                devices.add(new GnssDevice(GnssInputPrefs.TRANSPORT_BLUETOOTH_CLASSIC,
                        device.getAddress(), label(device)));
            }
        } catch (SecurityException ignored) {
        }
        return devices;
    }

    public List<GnssDevice> usbDevices() {
        List<GnssDevice> devices = new ArrayList<>();
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (manager == null) {
            return devices;
        }
        for (UsbDevice device : manager.getDeviceList().values()) {
            devices.add(new GnssDevice(GnssInputPrefs.TRANSPORT_USB,
                    UsbSerialTransport.deviceKey(device), UsbSerialTransport.deviceLabel(device)));
        }
        return devices;
    }

    public void startBleScan(BleListener listener) {
        stopBleScan();
        BluetoothAdapter adapter = GnssTransportFactory.adapter(context);
        if (adapter == null) {
            listener.onDevices(new ArrayList<>());
            return;
        }
        leScanner = adapter.getBluetoothLeScanner();
        if (leScanner == null) {
            listener.onDevices(new ArrayList<>());
            return;
        }
        Map<String, GnssDevice> found = new LinkedHashMap<>();
        leCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice device = result.getDevice();
                if (device == null || device.getAddress() == null) {
                    return;
                }
                found.put(device.getAddress(), new GnssDevice(
                        GnssInputPrefs.TRANSPORT_BLUETOOTH_LE, device.getAddress(), label(device)));
                listener.onDevices(new ArrayList<>(found.values()));
            }
        };
        try {
            leScanner.startScan(null, new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), leCallback);
        } catch (SecurityException exception) {
            listener.onDevices(new ArrayList<>());
            return;
        }
        handler.postDelayed(this::stopBleScan, 12_000);
    }

    public void stopBleScan() {
        handler.removeCallbacksAndMessages(null);
        if (leScanner != null && leCallback != null) {
            try {
                leScanner.stopScan(leCallback);
            } catch (RuntimeException ignored) {
            }
        }
        leScanner = null;
        leCallback = null;
    }

    private static String label(BluetoothDevice device) {
        try {
            String name = device.getName();
            if (name != null && !name.trim().isEmpty()) {
                return name.trim();
            }
        } catch (SecurityException ignored) {
        }
        return device.getAddress();
    }
}

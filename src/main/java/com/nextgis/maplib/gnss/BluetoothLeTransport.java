package com.nextgis.maplib.gnss;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.nextgis.maplib.util.Constants;

import java.util.UUID;

/**
 * NMEA over common UART GATT profiles (Nordic UART and HM-10 FFE0).
 * Proprietary binary BLE protocols are out of scope.
 */
public final class BluetoothLeTransport implements GnssTransport {
    static final UUID NORDIC_UART_SERVICE =
            UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    static final UUID NORDIC_UART_TX =
            UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");
    static final UUID HM10_SERVICE =
            UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB");
    static final UUID HM10_CHAR =
            UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB");
    static final UUID CCCD =
            UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");

    private final Context context;
    private final BluetoothAdapter adapter;
    private final String address;
    private BluetoothGatt gatt;
    private Listener listener;

    public BluetoothLeTransport(Context context, BluetoothAdapter adapter, String address) {
        this.context = context.getApplicationContext();
        this.adapter = adapter;
        this.address = address;
    }

    @Override
    public void open(Listener listener) {
        close();
        this.listener = listener;
        if (adapter == null || address == null || address.isEmpty()) {
            listener.onClosed("missing adapter");
            return;
        }
        BluetoothDevice device = adapter.getRemoteDevice(address);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        } else {
            gatt = device.connectGatt(context, false, callback);
        }
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                notifyClosed("disconnected");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            BluetoothGattCharacteristic notify = findNotifyCharacteristic(gatt);
            if (notify == null) {
                notifyClosed("no uart characteristic");
                return;
            }
            if (!gatt.setCharacteristicNotification(notify, true)) {
                notifyClosed("notify failed");
                return;
            }
            BluetoothGattDescriptor cccd = notify.getDescriptor(CCCD);
            if (cccd != null) {
                cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                gatt.writeDescriptor(cccd);
            }
            if (listener != null) {
                listener.onOpened();
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            if (value != null && value.length > 0 && listener != null) {
                listener.onBytes(value, value.length);
            }
        }
    };

    static BluetoothGattCharacteristic findNotifyCharacteristic(BluetoothGatt gatt) {
        BluetoothGattCharacteristic nordic = characteristic(gatt, NORDIC_UART_SERVICE, NORDIC_UART_TX);
        if (nordic != null) {
            return nordic;
        }
        BluetoothGattCharacteristic hm10 = characteristic(gatt, HM10_SERVICE, HM10_CHAR);
        if (hm10 != null) {
            return hm10;
        }
        for (BluetoothGattService service : gatt.getServices()) {
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                int props = characteristic.getProperties();
                if ((props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                    return characteristic;
                }
            }
        }
        return null;
    }

    private static BluetoothGattCharacteristic characteristic(
            BluetoothGatt gatt, UUID serviceUuid, UUID charUuid) {
        BluetoothGattService service = gatt.getService(serviceUuid);
        return service == null ? null : service.getCharacteristic(charUuid);
    }

    private void notifyClosed(String reason) {
        Listener local = listener;
        listener = null;
        if (local != null) {
            local.onClosed(reason);
        }
        closeGatt();
    }

    @Override
    public void close() {
        listener = null;
        closeGatt();
    }

    private void closeGatt() {
        BluetoothGatt local = gatt;
        gatt = null;
        if (local == null) {
            return;
        }
        try {
            local.disconnect();
            local.close();
        } catch (RuntimeException exception) {
            Log.w(Constants.TAG, "BLE GNSS close failed", exception);
        }
    }
}

package com.nextgis.maplib.gnss;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.nextgis.maplib.util.Constants;
import com.nextgis.maplib.util.DiagnosticLog;

import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;

/**
 * GNSS over Nordic UART, HM-10 FFE0 or the ComNav/PiGO 3A20 UART service.
 * A write channel must belong to the selected UART profile; unrelated GATT
 * characteristics are never used for receiver commands.
 */
public final class BluetoothLeTransport implements GnssTransport {
    static final UUID NORDIC_UART_SERVICE =
            UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    static final UUID NORDIC_UART_RX =
            UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
    static final UUID NORDIC_UART_TX =
            UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");
    static final UUID HM10_SERVICE =
            UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB");
    static final UUID HM10_CHAR =
            UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB");
    static final UUID COMNAV_SERVICE =
            UUID.fromString("00003A20-0000-1000-8000-00805F9B34FB");
    static final UUID COMNAV_RX =
            UUID.fromString("00003A21-0000-1000-8000-00805F9B34FB");
    static final UUID COMNAV_TX =
            UUID.fromString("00003A22-0000-1000-8000-00805F9B34FB");
    static final UUID CCCD =
            UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");
    static final int ATT_PAYLOAD = 20;
    static final int REQUEST_MTU = 185;

    private final Context context;
    private final BluetoothAdapter adapter;
    private final String address;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<byte[]> writeQueue = new ArrayDeque<>();
    private final Object writeLock = new Object();
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private BluetoothGattCharacteristic notifyChar;
    private Listener listener;
    private boolean writing;
    private boolean discovering;
    private boolean opened;
    private int attPayload = ATT_PAYLOAD;
    private final Runnable discoverFallback = this::discoverIfNeeded;
    private final Runnable writeTimeout = () -> notifyClosed("write timeout");

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
            if (gatt != BluetoothLeTransport.this.gatt) return;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                notifyClosed("connection failed status=" + status);
                return;
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                discovering = false;
                handler.removeCallbacks(discoverFallback);
                handler.postDelayed(discoverFallback, 1500);
                try {
                    if (gatt.requestMtu(REQUEST_MTU)) {
                        return;
                    }
                    startDiscovery(gatt);
                } catch (RuntimeException exception) {
                    Log.w(Constants.TAG, "BLE GNSS negotiation failed", exception);
                    notifyClosed("negotiation failed");
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                notifyClosed("disconnected");
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            if (gatt != BluetoothLeTransport.this.gatt) return;
            attPayload = status == BluetoothGatt.GATT_SUCCESS
                    ? Math.max(ATT_PAYLOAD, mtu - 3) : ATT_PAYLOAD;
            DiagnosticLog.v("BLE GNSS MTU=" + mtu + " status=" + status);
            startDiscovery(gatt);
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (gatt != BluetoothLeTransport.this.gatt || opened) return;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                notifyClosed("service discovery failed status=" + status);
                return;
            }
            try {
                BluetoothGattCharacteristic notify = findNotifyCharacteristic(gatt);
                if (notify == null) {
                    notifyClosed("no uart characteristic");
                    return;
                }
                writeChar = findWriteCharacteristic(gatt, notify);
                notifyChar = notify;
                DiagnosticLog.v("BLE GNSS rx="
                        + (writeChar == null ? "none" : writeChar.getUuid())
                        + " tx=" + notify.getUuid());
                if (!gatt.setCharacteristicNotification(notify, true)) {
                    notifyClosed("notify failed");
                    return;
                }
                BluetoothGattDescriptor cccd = notify.getDescriptor(CCCD);
                if (cccd != null) {
                    cccd.setValue(notificationValue(notify.getProperties()));
                    if (gatt.writeDescriptor(cccd)) {
                        return;
                    }
                }
                notifyClosed("notification subscription failed");
            } catch (RuntimeException exception) {
                Log.w(Constants.TAG, "BLE GNSS subscription failed", exception);
                notifyClosed("notification subscription failed");
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (gatt != BluetoothLeTransport.this.gatt || notifyChar == null
                    || descriptor.getCharacteristic() != notifyChar || !CCCD.equals(descriptor.getUuid())) return;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                notifyClosed("notification subscription failed status=" + status);
                return;
            }
            notifyOpened();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            receive(gatt, characteristic, characteristic.getValue());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic,
                byte[] value) {
            receive(gatt, characteristic, value);
        }

        private void receive(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
            if (gatt != BluetoothLeTransport.this.gatt || characteristic != notifyChar) return;
            if (value != null && value.length > 0 && listener != null) {
                listener.onBytes(value, value.length);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic,
                int status) {
            if (gatt != BluetoothLeTransport.this.gatt || characteristic != writeChar) return;
            DiagnosticLog.v("BLE GNSS write status=" + status);
            handler.removeCallbacks(writeTimeout);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                notifyClosed("write failed status=" + status);
                return;
            }
            synchronized (writeLock) {
                writing = false;
            }
            handler.post(BluetoothLeTransport.this::pumpWrite);
        }
    };

    private void discoverIfNeeded() {
        BluetoothGatt local = gatt;
        if (local != null && !discovering) {
            startDiscovery(local);
        }
    }

    private void startDiscovery(BluetoothGatt gatt) {
        if (discovering) {
            return;
        }
        discovering = true;
        handler.removeCallbacks(discoverFallback);
        try {
            if (!gatt.discoverServices()) {
                notifyClosed("service discovery rejected");
            }
        } catch (RuntimeException exception) {
            Log.w(Constants.TAG, "BLE GNSS discovery failed", exception);
            notifyClosed("service discovery failed");
        }
    }

    static BluetoothGattCharacteristic findNotifyCharacteristic(BluetoothGatt gatt) {
        return findNotifyCharacteristic(gatt.getServices());
    }

    static BluetoothGattCharacteristic findNotifyCharacteristic(List<BluetoothGattService> services) {
        BluetoothGattCharacteristic nordic = characteristic(services, NORDIC_UART_SERVICE, NORDIC_UART_TX);
        if (notifiable(nordic)) {
            return nordic;
        }
        BluetoothGattCharacteristic comnav = characteristic(services, COMNAV_SERVICE, COMNAV_TX);
        if (notifiable(comnav)) {
            return comnav;
        }
        BluetoothGattCharacteristic hm10 = characteristic(services, HM10_SERVICE, HM10_CHAR);
        if (notifiable(hm10)) {
            return hm10;
        }
        for (BluetoothGattService service : services) {
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                if (notifiable(characteristic)) {
                    return characteristic;
                }
            }
        }
        return null;
    }

    static BluetoothGattCharacteristic findWriteCharacteristic(
            BluetoothGatt gatt, BluetoothGattCharacteristic notify) {
        return findWriteCharacteristic(gatt.getServices(), notify);
    }

    static BluetoothGattCharacteristic findWriteCharacteristic(
            List<BluetoothGattService> services, BluetoothGattCharacteristic notify) {
        if (belongsTo(notify, NORDIC_UART_SERVICE, NORDIC_UART_TX)) {
            BluetoothGattCharacteristic rx = characteristic(services, NORDIC_UART_SERVICE, NORDIC_UART_RX);
            if (writable(rx)) return rx;
        } else if (belongsTo(notify, COMNAV_SERVICE, COMNAV_TX)) {
            BluetoothGattCharacteristic rx = characteristic(services, COMNAV_SERVICE, COMNAV_RX);
            if (writable(rx)) return rx;
        }
        if (writable(notify)) {
            return notify;
        }
        return null;
    }

    static boolean writable(BluetoothGattCharacteristic characteristic) {
        if (characteristic == null) {
            return false;
        }
        int props = characteristic.getProperties();
        return (props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                || (props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
    }

    static boolean notifiable(BluetoothGattCharacteristic characteristic) {
        return characteristic != null && (characteristic.getProperties()
                & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0;
    }

    static byte[] notificationValue(int properties) {
        return (properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
    }

    private static boolean belongsTo(BluetoothGattCharacteristic characteristic, UUID service, UUID id) {
        return characteristic != null && id.equals(characteristic.getUuid())
                && characteristic.getService() != null && service.equals(characteristic.getService().getUuid());
    }

    @Override
    public boolean usesComNavBinary() {
        return belongsTo(notifyChar, COMNAV_SERVICE, COMNAV_TX);
    }

    private static BluetoothGattCharacteristic characteristic(
            List<BluetoothGattService> services, UUID serviceUuid, UUID charUuid) {
        for (BluetoothGattService service : services) {
            if (serviceUuid.equals(service.getUuid())) return service.getCharacteristic(charUuid);
        }
        return null;
    }

    private void notifyOpened() {
        if (opened) return;
        opened = true;
        Listener local = listener;
        if (local != null) {
            local.onOpened();
        }
    }

    @Override
    public boolean write(byte[] data) {
        if (data == null || data.length == 0) {
            return false;
        }
        synchronized (writeLock) {
            if (gatt == null || writeChar == null || !opened) {
                return false;
            }
            int payload = Math.max(ATT_PAYLOAD, attPayload);
            int offset = 0;
            while (offset < data.length) {
                int n = Math.min(payload, data.length - offset);
                byte[] chunk = new byte[n];
                System.arraycopy(data, offset, chunk, 0, n);
                writeQueue.addLast(chunk);
                offset += n;
            }
        }
        pumpWrite();
        return true;
    }

    private void pumpWrite() {
        BluetoothGatt localGatt;
        BluetoothGattCharacteristic localChar;
        byte[] chunk;
        boolean withResponse;
        synchronized (writeLock) {
            localGatt = gatt;
            localChar = writeChar;
            if (writing || localGatt == null || localChar == null || writeQueue.isEmpty()) {
                return;
            }
            chunk = writeQueue.removeFirst();
            writing = true;
            int props = localChar.getProperties();
            withResponse = (props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
        }
        int writeType = withResponse ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        handler.removeCallbacks(writeTimeout);
        handler.postDelayed(writeTimeout, 5_000L);
        boolean accepted;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                accepted = localGatt.writeCharacteristic(localChar, chunk, writeType) == BluetoothStatusCodes.SUCCESS;
            } else {
                localChar.setWriteType(writeType);
                localChar.setValue(chunk);
                accepted = localGatt.writeCharacteristic(localChar);
            }
        } catch (RuntimeException exception) {
            Log.w(Constants.TAG, "BLE GNSS write failed", exception);
            notifyClosed("write failed");
            return;
        }
        if (!accepted) {
            DiagnosticLog.v("BLE GNSS writeCharacteristic=false");
            notifyClosed("write rejected");
        }
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
        handler.removeCallbacks(discoverFallback);
        handler.removeCallbacks(writeTimeout);
        synchronized (writeLock) {
            writeQueue.clear();
            writing = false;
            writeChar = null;
            notifyChar = null;
            opened = false;
            attPayload = ATT_PAYLOAD;
            discovering = false;
        }
        BluetoothGatt local = gatt;
        gatt = null;
        if (local == null) {
            return;
        }
        try {
            local.disconnect();
        } catch (RuntimeException exception) {
            Log.w(Constants.TAG, "BLE GNSS close failed", exception);
        } finally {
            try {
                local.close();
            } catch (RuntimeException exception) {
                Log.w(Constants.TAG, "BLE GNSS release failed", exception);
            }
        }
    }
}

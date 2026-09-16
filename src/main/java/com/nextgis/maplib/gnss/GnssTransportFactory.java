package com.nextgis.maplib.gnss;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.SharedPreferences;

/** Builds the transport matching saved GNSS input preferences. */
public final class GnssTransportFactory {
    private GnssTransportFactory() { }

    public static GnssTransport create(Context context, SharedPreferences prefs) {
        String transport = GnssInputPrefs.transport(prefs);
        String id = GnssInputPrefs.deviceId(prefs);
        BluetoothAdapter adapter = adapter(context);
        switch (transport) {
            case GnssInputPrefs.TRANSPORT_BLUETOOTH_LE:
                return new BluetoothLeTransport(context, adapter, id);
            case GnssInputPrefs.TRANSPORT_USB:
                return new UsbSerialTransport(context, id);
            case GnssInputPrefs.TRANSPORT_TCP:
                return new TcpNmeaTransport(GnssInputPrefs.tcpHost(prefs), GnssInputPrefs.tcpPort(prefs));
            case GnssInputPrefs.TRANSPORT_BLUETOOTH_CLASSIC:
            default:
                return new BluetoothClassicTransport(adapter, id);
        }
    }

    public static boolean hasEndpoint(SharedPreferences prefs) {
        String transport = GnssInputPrefs.transport(prefs);
        if (GnssInputPrefs.TRANSPORT_TCP.equals(transport)) {
            return !GnssInputPrefs.tcpHost(prefs).trim().isEmpty();
        }
        String id = GnssInputPrefs.deviceId(prefs);
        return id != null && !id.trim().isEmpty();
    }

    static BluetoothAdapter adapter(Context context) {
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        return manager == null ? BluetoothAdapter.getDefaultAdapter() : manager.getAdapter();
    }
}

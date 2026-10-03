package com.nextgis.maplib.gnss;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.os.Build;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowBluetoothGatt;
import org.robolectric.util.ReflectionHelpers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Exercises GATT completion/error callbacks instead of relying on the radio or auto-completing writes. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE, sdk={26, 36}, shadows=BluetoothLeTransportTest.ManualGatt.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class BluetoothLeTransportTest {
    private BluetoothLeTransport transport;
    private BluetoothGatt gatt;
    private BluetoothGattCallback callback;
    private BluetoothGattCharacteristic rx, tx;
    private BluetoothGattDescriptor cccd;
    private ManualGatt radio;
    private int opened, closed;
    private final List<byte[]> received = new ArrayList<>();

    @Before public void setUp() {
        transport = new BluetoothLeTransport(RuntimeEnvironment.getApplication(), null, "test");
        gatt = ShadowBluetoothGatt.newInstance(BluetoothAdapter.getDefaultAdapter()
                .getRemoteDevice("00:11:22:33:44:55"));
        radio = Shadow.extract(gatt);
        callback = ReflectionHelpers.getField(transport, "callback");
        radio.setGattCallback(callback);
        ReflectionHelpers.setField(transport, "gatt", gatt);
        ReflectionHelpers.setField(transport, "listener", new GnssTransport.Listener() {
            @Override public void onOpened() { opened++; }
            @Override public void onClosed(String reason) { closed++; }
            @Override public void onBytes(byte[] data, int length) { received.add(Arrays.copyOf(data, length)); }
        });
        BluetoothGattService uart = new BluetoothGattService(BluetoothLeTransport.COMNAV_SERVICE,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);
        rx = new BluetoothGattCharacteristic(BluetoothLeTransport.COMNAV_RX,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, 0);
        tx = new BluetoothGattCharacteristic(BluetoothLeTransport.COMNAV_TX,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0);
        cccd = new BluetoothGattDescriptor(BluetoothLeTransport.CCCD, BluetoothGattDescriptor.PERMISSION_WRITE);
        tx.addDescriptor(cccd);
        uart.addCharacteristic(rx);
        uart.addCharacteristic(tx);
        radio.addDiscoverableService(uart);
        radio.allowCharacteristicNotification(tx);
    }

    @After public void tearDown() { transport.close(); }

    @Test public void descriptorFailureNeverReportsConnected() {
        discover();
        assertEquals(0, opened);
        callback.onDescriptorWrite(gatt, cccd, BluetoothGatt.GATT_FAILURE);
        assertEquals(0, opened);
        assertEquals(1, closed);
        assertTrue(radio.isClosed());
        assertFalse(transport.write(ComNavAsciiCommands.bestPosEnable()));
    }

    @Test public void descriptorSuccessOpensOnceAndUsesPigoProfile() {
        discover();
        assertEquals(0, opened);
        assertArrayEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, cccd.getValue());
        callback.onDescriptorWrite(gatt, cccd, BluetoothGatt.GATT_SUCCESS);
        callback.onDescriptorWrite(gatt, cccd, BluetoothGatt.GATT_SUCCESS);
        assertEquals(1, opened);
        assertTrue(transport.usesComNavBinary());
    }

    @Test public void noResponseChunksWaitForCompletionAndPreserveWholeCommand() {
        open();
        byte[] command = ComNavAsciiCommands.bestPosEnable();
        assertTrue(transport.write(command));
        assertEquals(1, radio.writes.size());
        assertArrayEquals(Arrays.copyOf(command, 20), radio.writes.get(0));
        callback.onCharacteristicWrite(gatt, rx, BluetoothGatt.GATT_SUCCESS);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(2, radio.writes.size());
        assertArrayEquals(Arrays.copyOfRange(command, 20, command.length), radio.writes.get(1));
        callback.onCharacteristicWrite(gatt, rx, BluetoothGatt.GATT_SUCCESS);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6));
        assertEquals(0, closed);
    }

    @Test public void rejectedWriteClosesInsteadOfDroppingPartOfCommand() {
        open();
        radio.rejectWrite = true;
        transport.write(ComNavAsciiCommands.bestPosEnable());
        assertEquals(1, closed);
        assertTrue(radio.isClosed());
        assertFalse(transport.write(ComNavAsciiCommands.bestPosEnable()));
    }

    @Test public void failedWriteCompletionDoesNotSendTheRemainingFragment() {
        open();
        transport.write(ComNavAsciiCommands.bestPosEnable());
        callback.onCharacteristicWrite(gatt, rx, BluetoothGatt.GATT_FAILURE);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, closed);
        assertEquals(1, radio.writes.size());
    }

    @Test public void stalledWriteHasBoundedTimeout() {
        open();
        transport.write(ComNavAsciiCommands.bestPosEnable());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_001));
        assertEquals(1, closed);
        assertTrue(radio.isClosed());
    }

    @Test public void notificationsReadTheSuppliedSnapshotOnModernAndroid() {
        open();
        byte[] bytes = "$GNGGA,test\r\n".getBytes(StandardCharsets.US_ASCII);
        if (Build.VERSION.SDK_INT >= 33) {
            tx.setValue(new byte[] {0});
            callback.onCharacteristicChanged(gatt, tx, bytes);
        } else {
            tx.setValue(bytes);
            callback.onCharacteristicChanged(gatt, tx);
        }
        assertEquals(1, received.size());
        assertArrayEquals(bytes, received.get(0));
        callback.onCharacteristicChanged(gatt, rx);
        assertEquals(1, received.size());
    }

    @Test public void lateDisconnectCannotCloseReplacementGatt() {
        open();
        BluetoothGatt replacement = ShadowBluetoothGatt.newInstance(BluetoothAdapter.getDefaultAdapter()
                .getRemoteDevice("00:11:22:33:44:66"));
        ReflectionHelpers.setField(transport, "gatt", replacement);
        callback.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED);
        assertEquals(0, closed);
        assertFalse(((ShadowBluetoothGatt) Shadow.extract(replacement)).isClosed());
        gatt.close();
    }

    @Test public void serviceDiscoveryFailureNeverSubscribes() {
        callback.onServicesDiscovered(gatt, BluetoothGatt.GATT_FAILURE);
        assertEquals(0, opened);
        assertEquals(1, closed);
    }

    @Test public void rejectedSubscriptionNeverReportsConnected() {
        radio.rejectDescriptor = true;
        discover();
        assertEquals(0, opened);
        assertEquals(1, closed);
    }

    @Test public void revokedBluetoothPermissionClosesWithoutCrashingCallback() {
        radio.revokePermission = true;
        discover();
        assertEquals(0, opened);
        assertEquals(1, closed);
    }

    private void discover() { assertTrue(gatt.discoverServices()); }
    private void open() { discover(); callback.onDescriptorWrite(gatt, cccd, BluetoothGatt.GATT_SUCCESS); }

    @Implements(BluetoothGatt.class)
    public static class ManualGatt extends ShadowBluetoothGatt {
        final List<byte[]> writes = new ArrayList<>();
        boolean rejectWrite, rejectDescriptor, revokePermission;
        @Implementation protected boolean writeDescriptor(BluetoothGattDescriptor descriptor) {
            if (revokePermission) throw new SecurityException("simulated Bluetooth permission revocation");
            return !rejectDescriptor;
        }
        @Implementation protected boolean writeCharacteristic(BluetoothGattCharacteristic characteristic) {
            if (rejectWrite) return false;
            writes.add(characteristic.getValue().clone());
            return true;
        }
        @Implementation(minSdk=33) protected int writeCharacteristic(BluetoothGattCharacteristic characteristic,
                byte[] value, int writeType) {
            if (rejectWrite) return BluetoothStatusCodes.ERROR_UNKNOWN;
            writes.add(value.clone());
            return BluetoothStatusCodes.SUCCESS;
        }
    }
}

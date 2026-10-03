package com.nextgis.maplib.gnss;

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE, sdk={26, 36})
public class BluetoothLeProfileTest {
    private static final int NOTIFY = BluetoothGattCharacteristic.PROPERTY_NOTIFY;
    private static final int WRITE = BluetoothGattCharacteristic.PROPERTY_WRITE;
    private static final int WRITE_NR = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE;

    @Test public void pigoUsesSeparateReadAndWriteChannelsBeforeGenericNotifications() {
        BluetoothGattService battery = service(UUID.randomUUID());
        add(battery, UUID.randomUUID(), NOTIFY);
        BluetoothGattService pigo = service(BluetoothLeTransport.COMNAV_SERVICE);
        BluetoothGattCharacteristic rx = add(pigo, BluetoothLeTransport.COMNAV_RX, WRITE_NR);
        BluetoothGattCharacteristic tx = add(pigo, BluetoothLeTransport.COMNAV_TX, NOTIFY);
        List<BluetoothGattService> services = Arrays.asList(battery, pigo);
        assertSame(tx, BluetoothLeTransport.findNotifyCharacteristic(services));
        assertSame(rx, BluetoothLeTransport.findWriteCharacteristic(services, tx));
    }

    @Test public void missingPigoWriteChannelDoesNotUseAnotherService() {
        BluetoothGattService pigo = service(BluetoothLeTransport.COMNAV_SERVICE);
        BluetoothGattCharacteristic tx = add(pigo, BluetoothLeTransport.COMNAV_TX, NOTIFY);
        BluetoothGattService unrelated = service(UUID.randomUUID());
        add(unrelated, BluetoothLeTransport.COMNAV_RX, WRITE);
        BluetoothGattService nordic = service(BluetoothLeTransport.NORDIC_UART_SERVICE);
        add(nordic, BluetoothLeTransport.NORDIC_UART_RX, WRITE);
        assertNull(BluetoothLeTransport.findWriteCharacteristic(Arrays.asList(pigo, unrelated, nordic), tx));
    }

    @Test public void pigoReadOnlyRxIsNotWritable() {
        BluetoothGattService pigo = service(BluetoothLeTransport.COMNAV_SERVICE);
        add(pigo, BluetoothLeTransport.COMNAV_RX, BluetoothGattCharacteristic.PROPERTY_READ);
        BluetoothGattCharacteristic tx = add(pigo, BluetoothLeTransport.COMNAV_TX, NOTIFY);
        assertNull(BluetoothLeTransport.findWriteCharacteristic(Collections.singletonList(pigo), tx));
    }

    @Test public void nordicReadAndWriteChannelsStayPaired() {
        BluetoothGattService nordic = service(BluetoothLeTransport.NORDIC_UART_SERVICE);
        BluetoothGattCharacteristic tx = add(nordic, BluetoothLeTransport.NORDIC_UART_TX, NOTIFY);
        BluetoothGattCharacteristic rx = add(nordic, BluetoothLeTransport.NORDIC_UART_RX, WRITE);
        List<BluetoothGattService> services = Collections.singletonList(nordic);
        assertSame(tx, BluetoothLeTransport.findNotifyCharacteristic(services));
        assertSame(rx, BluetoothLeTransport.findWriteCharacteristic(services, tx));
    }

    @Test public void hm10KeepsItsCombinedChannel() {
        BluetoothGattService hm10 = service(BluetoothLeTransport.HM10_SERVICE);
        BluetoothGattCharacteristic uart = add(hm10, BluetoothLeTransport.HM10_CHAR, NOTIFY | WRITE_NR);
        List<BluetoothGattService> services = Collections.singletonList(hm10);
        assertSame(uart, BluetoothLeTransport.findNotifyCharacteristic(services));
        assertSame(uart, BluetoothLeTransport.findWriteCharacteristic(services, uart));
    }

    @Test public void indicationsRequireIndicationSubscription() {
        BluetoothGattService pigo = service(BluetoothLeTransport.COMNAV_SERVICE);
        BluetoothGattCharacteristic tx = add(pigo, BluetoothLeTransport.COMNAV_TX,
                BluetoothGattCharacteristic.PROPERTY_INDICATE);
        assertSame(tx, BluetoothLeTransport.findNotifyCharacteristic(Collections.singletonList(pigo)));
        assertArrayEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE,
                BluetoothLeTransport.notificationValue(tx.getProperties()));
        assertArrayEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                BluetoothLeTransport.notificationValue(NOTIFY));
    }

    @Test public void unknownProfileDoesNotGuessItsWriteChannel() {
        BluetoothGattService unknown = service(UUID.randomUUID());
        BluetoothGattCharacteristic tx = add(unknown, UUID.randomUUID(), NOTIFY);
        add(unknown, UUID.randomUUID(), WRITE);
        assertNull(BluetoothLeTransport.findWriteCharacteristic(Collections.singletonList(unknown), tx));
    }

    private static BluetoothGattService service(UUID uuid) {
        return new BluetoothGattService(uuid, BluetoothGattService.SERVICE_TYPE_PRIMARY);
    }

    private static BluetoothGattCharacteristic add(BluetoothGattService service, UUID uuid, int properties) {
        BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(uuid, properties, 0);
        service.addCharacteristic(characteristic);
        return characteristic;
    }
}

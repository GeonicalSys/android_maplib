package com.nextgis.maplib.gnss;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CnbBestPosTest {
    @Test public void bestPosBSingleThrough20ByteChunks() {
        byte[] packet = bestposb(55.75, 37.62, 150.5, CnbBestPos.SOL_COMPUTED,
                CnbBestPos.POS_SINGLE, 12);
        CnbFrameBuffer buffer = new CnbFrameBuffer();
        List<GnssFix> fixes = new ArrayList<>();
        for (int offset = 0; offset < packet.length; offset += 20) {
            int n = Math.min(20, packet.length - offset);
            byte[] chunk = new byte[n];
            System.arraycopy(packet, offset, chunk, 0, n);
            buffer.append(chunk, n, (frame, length) -> {
                GnssFix fix = CnbBestPos.parse(frame, length);
                if (fix != null) {
                    fixes.add(fix);
                }
            });
        }
        assertEquals(1, fixes.size());
        GnssFix fix = fixes.get(0);
        assertTrue(fix.hasFix());
        assertEquals("Autonomous", fix.qualityLabel());
        assertEquals(55.75, fix.latitude, 1e-9);
        assertEquals(37.62, fix.longitude, 1e-9);
        assertEquals(150.5, fix.altitude, 1e-6);
        assertEquals(12, fix.satellites);
        assertEquals(0.013f, fix.stdLat, 1e-4f);
        assertEquals(0.010f, fix.stdLon, 1e-4f);
    }

    @Test public void bestPosBNarrowIntIsRtkFix() {
        byte[] packet = bestposb(51.116, -114.038, 1064.9, CnbBestPos.SOL_COMPUTED,
                CnbBestPos.POS_NARROW_INT, 32);
        GnssFix fix = CnbBestPos.parse(packet, packet.length);
        assertNotNull(fix);
        assertEquals("RTK FIX", fix.qualityLabel());
        assertEquals(51.116, fix.latitude, 1e-6);
        assertEquals(-114.038, fix.longitude, 1e-6);
    }

    @Test public void badCrcIsDropped() {
        byte[] packet = bestposb(55.75, 37.62, 10.0, CnbBestPos.SOL_COMPUTED,
                CnbBestPos.POS_SINGLE, 8);
        packet[packet.length - 1] ^= 0x01;
        CnbFrameBuffer buffer = new CnbFrameBuffer();
        final int[] frames = {0};
        buffer.append(packet, packet.length, (frame, length) -> frames[0]++);
        assertEquals(0, frames[0]);
        assertNull(CnbBestPos.parse(new byte[] {0x00}, 1));
    }

    @Test public void dollarInsideCnbIsNotNmea() {
        byte[] packet = bestposb(55.75, 37.62, 10.0, CnbBestPos.SOL_COMPUTED,
                CnbBestPos.POS_SINGLE, 8);
        packet[40] = '$';
        packet[41] = 'B';
        packet[42] = (byte) 0xAA;
        // Recalculate CRC after mutating the body.
        int crc = CnbCrc32.of(packet, 0, packet.length - 4);
        CnbCrc32.putLittleEndian(packet, packet.length - 4, crc);
        NmeaLineBuffer lines = new NmeaLineBuffer();
        final int[] nmea = {0};
        lines.append(packet, packet.length, line -> nmea[0]++);
        assertEquals(0, nmea[0]);
        assertFalse(ExternalGnssSession.isAsciiGnss("$B"));
    }

    static byte[] bestposb(double lat, double lon, double hgt, int sol, int posType, int sats) {
        ByteBuffer header = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);
        header.put((byte) CnbPreamble.B0);
        header.put((byte) CnbPreamble.B1);
        header.put((byte) CnbPreamble.B2);
        header.put((byte) 28);
        header.putShort((short) CnbBestPos.MESSAGE_ID);
        header.put((byte) 0);
        header.put((byte) 0);
        header.putShort((short) CnbBestPos.BODY_LEN);
        header.putShort((short) 0);
        header.put((byte) 0);
        header.put((byte) 0);
        header.putShort((short) 0);
        header.putInt(0);
        header.putInt(0);
        header.putShort((short) 0);
        header.putShort((short) 0);
        ByteBuffer body = ByteBuffer.allocate(CnbBestPos.BODY_LEN).order(ByteOrder.LITTLE_ENDIAN);
        body.putInt(sol);
        body.putInt(posType);
        body.putDouble(lat);
        body.putDouble(lon);
        body.putDouble(hgt);
        body.putFloat(0f);
        body.putInt(61);
        body.putFloat(0.013f);
        body.putFloat(0.010f);
        body.putFloat(0.017f);
        body.putInt(0);
        body.putFloat(1.0f);
        body.putFloat(0f);
        body.put((byte) sats);
        body.put((byte) sats);
        body.put((byte) 0);
        body.put((byte) 0);
        body.put((byte) 0);
        body.put((byte) 0);
        body.put((byte) 0);
        body.put((byte) 0);
        byte[] packet = new byte[28 + CnbBestPos.BODY_LEN + 4];
        System.arraycopy(header.array(), 0, packet, 0, 28);
        System.arraycopy(body.array(), 0, packet, 28, CnbBestPos.BODY_LEN);
        int crc = CnbCrc32.of(packet, 0, packet.length - 4);
        CnbCrc32.putLittleEndian(packet, packet.length - 4, crc);
        assertTrue(CnbCrc32.verify(packet, 0, packet.length));
        return packet;
    }
}

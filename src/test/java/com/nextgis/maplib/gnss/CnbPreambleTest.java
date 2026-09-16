package com.nextgis.maplib.gnss;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CnbPreambleTest {
    @Test public void detectsSyncInOneChunk() {
        CnbPreamble detector = new CnbPreamble();
        byte[] packet = new byte[] {(byte) 0x00, (byte) 0xAA, 0x44, 0x12, 0x1C};
        assertTrue(detector.accept(packet, packet.length));
    }

    @Test public void detectsSyncSplitAcrossChunks() {
        CnbPreamble detector = new CnbPreamble();
        assertFalse(detector.accept(new byte[] {(byte) 0xAA, 0x44}, 2));
        assertTrue(detector.accept(new byte[] {0x12, 0x00}, 2));
    }

    @Test public void ignoresLoneAa() {
        CnbPreamble detector = new CnbPreamble();
        assertFalse(detector.accept(new byte[] {(byte) 0xAA, 0x00, 0x44, 0x12}, 4));
        assertFalse(detector.accept(new byte[] {'C', 'R', 'D', 'K'}, 4));
    }
}

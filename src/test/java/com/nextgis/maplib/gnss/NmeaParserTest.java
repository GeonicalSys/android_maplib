package com.nextgis.maplib.gnss;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NmeaParserTest {
    @Test public void ggaQualityFourIsRtkFixWithCoordinates() {
        NmeaParser parser = new NmeaParser();
        assertTrue(parser.accept(nmea(
                "GNGGA,191408.00,6147.123456,N,03421.654321,E,4,28,0.7,87.3,M,15.0,M,1.2,0001")));
        GnssFix fix = parser.snapshot();
        assertEquals("RTK FIX", fix.qualityLabel());
        assertEquals(28, fix.satellites);
        assertEquals(0.7f, fix.hdop, 0.001f);
        assertEquals(1.2f, fix.ageOfDiff, 0.001f);
        assertEquals(61.0 + 47.123456 / 60.0, fix.latitude, 1e-8);
        assertEquals(34.0 + 21.654321 / 60.0, fix.longitude, 1e-8);
        assertEquals(87.3, fix.altitude, 0.001);
    }

    @Test public void ggaQualityFiveIsRtkFloat() {
        NmeaParser parser = new NmeaParser();
        assertTrue(parser.accept(nmea(
                "GNGGA,191408.00,6147.123456,N,03421.654321,E,5,16,1.1,87.3,M,15.0,M,,")));
        assertEquals("RTK FLOAT", parser.snapshot().qualityLabel());
    }

    @Test public void gstOverridesHdopAccuracyWithCentimetreRms() {
        NmeaParser parser = new NmeaParser();
        parser.accept(nmea(
                "GNGGA,191408.00,6147.123456,N,03421.654321,E,4,28,1.8,87.3,M,15.0,M,,"));
        float hdopAccuracy = parser.snapshot().horizontalAccuracyM();
        assertEquals(1.8f * 4.7f, hdopAccuracy, 0.01f);
        parser.accept(nmea("GNGST,191408.00,0.01,0.02,0.01,0.0,0.015,0.013,0.03"));
        float gst = parser.snapshot().horizontalAccuracyM();
        assertEquals(Math.hypot(0.015, 0.013), gst, 0.0001);
        assertTrue(gst < 0.03f);
        assertTrue(gst < hdopAccuracy);
    }

    @Test public void gsaSuppliesVdop() {
        NmeaParser parser = new NmeaParser();
        parser.accept(nmea(
                "GNGGA,191408.00,6147.123456,N,03421.654321,E,1,12,1.9,10.0,M,15.0,M,,"));
        parser.accept(nmea("GNGSA,A,3,01,02,03,04,05,06,07,08,09,10,11,12,1.2,0.7,1.0"));
        GnssFix fix = parser.snapshot();
        assertEquals(0.7f, fix.hdop, 0.001f);
        assertEquals(1.0f, fix.vdop, 0.001f);
        assertEquals(1.2f, fix.pdop, 0.001f);
    }

    @Test public void badChecksumIsIgnored() {
        NmeaParser parser = new NmeaParser();
        assertFalse(parser.accept(
                "$GNGGA,191408.00,6147.123456,N,03421.654321,E,4,28,0.7,87.3,M,15.0,M,,*00"));
        assertFalse(parser.snapshot().hasFix());
    }

    @Test public void qualityZeroClearsFix() {
        NmeaParser parser = new NmeaParser();
        parser.accept(nmea(
                "GNGGA,191408.00,6147.123456,N,03421.654321,E,4,28,0.7,87.3,M,15.0,M,,"));
        assertFalse(parser.accept(nmea(
                "GNGGA,191409.00,6147.123456,N,03421.654321,E,0,00,99.9,,M,,M,,")));
        assertFalse(parser.snapshot().hasFix());
    }

    @Test public void lineBufferSplitsCrLfSentences() {
        NmeaParser parser = new NmeaParser();
        NmeaLineBuffer buffer = new NmeaLineBuffer();
        String sentence = nmea(
                "GPGGA,191408.00,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,");
        byte[] bytes = (sentence + "\r\n").getBytes();
        final boolean[] ready = {false};
        buffer.append(bytes, bytes.length, line -> ready[0] = parser.accept(line));
        assertTrue(ready[0]);
        assertEquals("Autonomous", parser.snapshot().qualityLabel());
    }

    @Test public void lineBufferIgnoresComNavBinaryThenParsesGga() {
        NmeaParser parser = new NmeaParser();
        NmeaLineBuffer buffer = new NmeaLineBuffer();
        byte[] cnb = new byte[] {
                (byte) 0xAA, 0x44, 0x12, 0x1C, 0x0A, 0x00,
                'C', 'R', 'D', 'K', '-', '9', '0', '2', 'A', 'A', 0x0A, 0x00
        };
        final int[] lines = {0};
        final boolean[] ready = {false};
        buffer.append(cnb, cnb.length, line -> lines[0]++);
        assertEquals(0, lines[0]);
        String sentence = nmea(
                "GPGGA,191408.00,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,");
        byte[] nmeaBytes = (sentence + "\r\n").getBytes();
        buffer.append(nmeaBytes, nmeaBytes.length, line -> {
            lines[0]++;
            ready[0] = parser.accept(line);
        });
        assertEquals(1, lines[0]);
        assertTrue(ready[0]);
        assertEquals("Autonomous", parser.snapshot().qualityLabel());
    }

    @Test public void lineBufferIgnoresDollarHashInsideCnb() {
        NmeaLineBuffer buffer = new NmeaLineBuffer();
        byte[] mixed = new byte[] {
                (byte) 0xAA, 0x44, 0x12, '$', 'B', (byte) 0xAA, 0x44, 0x12, 0x0A,
                '#', 0x1A, (byte) 0xC7, 0x0A
        };
        final int[] lines = {0};
        buffer.append(mixed, mixed.length, line -> lines[0]++);
        assertEquals(0, lines[0]);
        String sentence = nmea(
                "GPGGA,191408.00,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,");
        byte[] nmeaBytes = (sentence + "\r\n").getBytes();
        final boolean[] ready = {false};
        NmeaParser parser = new NmeaParser();
        buffer.append(nmeaBytes, nmeaBytes.length, line -> ready[0] = parser.accept(line));
        assertTrue(ready[0]);
    }

    @Test public void bestPosQualityFromOemCodes() {
        assertEquals(1, NmeaParser.bestPosQualityFromCode(16));
        assertEquals(5, NmeaParser.bestPosQualityFromCode(34));
        assertEquals(4, NmeaParser.bestPosQualityFromCode(50));
        assertEquals(0, NmeaParser.bestPosQualityFromCode(0));
    }

    @Test public void bestPosANarrowIntIsRtkFix() {
        NmeaParser parser = new NmeaParser();
        assertTrue(parser.accept(bestposa(
                "BESTPOSA,COM1,0,55.0,FINESTEERING,1419,336148.000,00000000,0000,1114;"
                        + "SOL_COMPUTED,NARROW_INT,51.116,-114.038,1064.9,-16.9,WGS84,"
                        + "0.013,0.010,0.017,\"AAAA\",1.0,0.0,43,32,16,16,0,06,00,33")));
        GnssFix fix = parser.snapshot();
        assertEquals("RTK FIX", fix.qualityLabel());
        assertEquals(51.116, fix.latitude, 1e-6);
        assertEquals(-114.038, fix.longitude, 1e-6);
        assertEquals(1064.9, fix.altitude, 0.001);
        assertEquals(43, fix.satellites);
        assertEquals(0.013f, fix.stdLat, 0.0001f);
        assertEquals(0.010f, fix.stdLon, 0.0001f);
        assertEquals(1.0f, fix.ageOfDiff, 0.001f);
    }

    @Test public void bestPosAFloatIsRtkFloat() {
        NmeaParser parser = new NmeaParser();
        assertTrue(parser.accept(bestposa(
                "BESTPOSA,COM1,0,55.0,FINESTEERING,1419,336148.000,00000000,0000,1114;"
                        + "SOL_COMPUTED,NARROW_FLOAT,59.9,30.3,12.0,0.0,WGS84,"
                        + "0.2,0.2,0.4,\"\",2.0,0.0,18,16,16,16,0,01,00,33")));
        assertEquals("RTK FLOAT", parser.snapshot().qualityLabel());
    }

    @Test public void bestPosASingleIsAutonomous() {
        NmeaParser parser = new NmeaParser();
        assertTrue(parser.accept(bestposa(
                "BESTPOSA,COM1,0,55.0,FINESTEERING,1419,336148.000,00000000,0000,1114;"
                        + "SOL_COMPUTED,SINGLE,59.9,30.3,12.0,0.0,WGS84,"
                        + "1.5,1.4,3.0,\"\",0.0,0.0,12,12,12,12,0,00,00,33")));
        assertEquals("Autonomous", parser.snapshot().qualityLabel());
    }

    static String nmea(String body) {
        int xor = 0;
        for (int i = 0; i < body.length(); i++) {
            xor ^= body.charAt(i);
        }
        return "$" + body + "*" + String.format("%02X", xor & 0xFF);
    }

    static String bestposa(String body) {
        int xor = 0;
        for (int i = 0; i < body.length(); i++) {
            xor ^= body.charAt(i);
        }
        return "#" + body + "*" + String.format("%02X", xor & 0xFF);
    }
}

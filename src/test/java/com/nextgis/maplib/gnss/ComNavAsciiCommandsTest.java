package com.nextgis.maplib.gnss;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ComNavAsciiCommandsTest {
    @Test public void nmeaEnableUsesCrlfAndComNavLogs() {
        String text = new String(ComNavAsciiCommands.nmeaEnable(), StandardCharsets.US_ASCII);
        assertEquals(ComNavAsciiCommands.NMEA_ENABLE_TEXT, text);
        assertTrue(text.startsWith("unlogall\r\n"));
        assertTrue(text.contains("log gpgga ontime 1\r\n"));
        assertTrue(text.contains("log gpgst ontime 1\r\n"));
        assertTrue(text.contains("log gpgsa ontime 1\r\n"));
        assertTrue(text.contains("log gprmc ontime 1\r\n"));
        assertTrue(text.contains("log bestposa ontime 1\r\n"));
        assertFalse(text.contains("\n\n"));
        assertEquals('\n', text.charAt(text.length() - 1));
        assertEquals('\r', text.charAt(text.length() - 2));
    }

    @Test public void asciiGnssDetectsGgaAndBestPos() {
        assertTrue(ExternalGnssSession.isAsciiGnss("$GNGGA,1,2"));
        assertTrue(ExternalGnssSession.isAsciiGnss("#BESTPOSA,COM1,0;SOL_COMPUTED"));
        assertFalse(ExternalGnssSession.isAsciiGnss("CRDK-902AA"));
        assertFalse(ExternalGnssSession.isAsciiGnss(""));
    }
}

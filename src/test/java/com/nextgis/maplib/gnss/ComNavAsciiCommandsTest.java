package com.nextgis.maplib.gnss;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ComNavAsciiCommandsTest {
    @Test public void nmeaEnableHasNoUnlogallAndFitsBleAtt() {
        String text = new String(ComNavAsciiCommands.nmeaEnable(), StandardCharsets.US_ASCII);
        assertFalse(text.contains("unlogall"));
        assertFalse(text.contains("bestposa"));
        byte[][] commands = ComNavAsciiCommands.nmeaEnableCommands();
        assertEquals(ComNavAsciiCommands.NMEA_ENABLE_LINES.length, commands.length);
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < commands.length; i++) {
            String line = new String(commands[i], StandardCharsets.US_ASCII);
            assertEquals(ComNavAsciiCommands.NMEA_ENABLE_LINES[i], line);
            assertTrue(line.endsWith("\r\n"));
            assertTrue(line.length() <= 20);
            joined.append(line);
        }
        assertEquals(text, joined.toString());
        assertTrue(text.contains("log gpgga ontime 1\r\n"));
        assertTrue(text.contains("log gpgst ontime 1\r\n"));
        assertTrue(text.contains("log gpgsa ontime 1\r\n"));
        assertTrue(text.contains("log gprmc ontime 1\r\n"));
    }

    @Test public void asciiGnssDetectsGgaAndBestPos() {
        assertTrue(ExternalGnssSession.isAsciiGnss("$GNGGA,1,2"));
        assertTrue(ExternalGnssSession.isAsciiGnss("#BESTPOSA,COM1,0;SOL_COMPUTED"));
        assertFalse(ExternalGnssSession.isAsciiGnss("CRDK-902AA"));
        assertFalse(ExternalGnssSession.isAsciiGnss("$B"));
        assertFalse(ExternalGnssSession.isAsciiGnss(""));
        assertTrue(ExternalGnssSession.containsAsciiOk("xOK!y".getBytes(StandardCharsets.US_ASCII), 5));
        assertFalse(ExternalGnssSession.containsAsciiOk("OK".getBytes(StandardCharsets.US_ASCII), 2));
    }
}

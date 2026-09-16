package com.nextgis.maplib.gnss;

import java.nio.charset.StandardCharsets;

/**
 * Optional ComNav ASCII logs that add GGA/GST/GSA/RMC beside factory CNB.
 * Each command fits one 20-byte BLE ATT write. {@code unlogall} is not sent:
 * it would stop BESTPOSB before ASCII may start.
 */
public final class ComNavAsciiCommands {
    private ComNavAsciiCommands() {
    }

    public static final String[] NMEA_ENABLE_LINES = {
            "log gpgga ontime 1\r\n",
            "log gpgst ontime 1\r\n",
            "log gpgsa ontime 1\r\n",
            "log gprmc ontime 1\r\n"
    };

    public static byte[][] nmeaEnableCommands() {
        byte[][] commands = new byte[NMEA_ENABLE_LINES.length][];
        for (int i = 0; i < NMEA_ENABLE_LINES.length; i++) {
            commands[i] = NMEA_ENABLE_LINES[i].getBytes(StandardCharsets.US_ASCII);
        }
        return commands;
    }

    public static byte[] nmeaEnable() {
        StringBuilder text = new StringBuilder();
        for (String line : NMEA_ENABLE_LINES) {
            text.append(line);
        }
        return text.toString().getBytes(StandardCharsets.US_ASCII);
    }
}

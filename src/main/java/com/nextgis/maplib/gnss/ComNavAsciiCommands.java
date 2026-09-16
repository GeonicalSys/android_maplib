package com.nextgis.maplib.gnss;

import java.nio.charset.StandardCharsets;

/**
 * ComNav/SinoGNSS OEM commands that stop the factory CNB observation stream
 * and request NMEA plus BESTPOSA, matching PiSun's rover setup.
 */
public final class ComNavAsciiCommands {
    private ComNavAsciiCommands() {
    }

    public static final String NMEA_ENABLE_TEXT =
            "unlogall\r\n"
                    + "log gpgga ontime 1\r\n"
                    + "log gpgst ontime 1\r\n"
                    + "log gpgsa ontime 1\r\n"
                    + "log gprmc ontime 1\r\n"
                    + "log bestposa ontime 1\r\n";

    public static byte[] nmeaEnable() {
        return NMEA_ENABLE_TEXT.getBytes(StandardCharsets.US_ASCII);
    }
}

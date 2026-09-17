package com.nextgis.maplib.gnss;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * ComNav/NovAtel {@code BESTPOSB} (message 42): WGS84 lat/lon from the 72-byte OEM6 body.
 */
public final class CnbBestPos {
    public static final int MESSAGE_ID = 42;
    public static final int BODY_LEN = 72;
    static final int SOL_COMPUTED = 0;
    static final int POS_SINGLE = 16;
    static final int POS_PSRDIFF = 17;
    static final int POS_L1_FLOAT = 32;
    static final int POS_NARROW_FLOAT = 34;
    static final int POS_L1_INT = 48;
    static final int POS_NARROW_INT = 50;

    private CnbBestPos() {
    }

    public static GnssFix parse(byte[] frame, int length) {
        if (frame == null || length < 16) {
            return null;
        }
        if ((frame[0] & 0xFF) != CnbPreamble.B0
                || (frame[1] & 0xFF) != CnbPreamble.B1
                || (frame[2] & 0xFF) != CnbPreamble.B2) {
            return null;
        }
        int headerLen = frame[3] & 0xFF;
        int msgId = (frame[4] & 0xFF) | ((frame[5] & 0xFF) << 8);
        if (msgId != MESSAGE_ID) {
            return null;
        }
        int msgLen = (frame[8] & 0xFF) | ((frame[9] & 0xFF) << 8);
        if (headerLen < 12 || msgLen < BODY_LEN || headerLen + msgLen + 4 > length) {
            return null;
        }
        ByteBuffer body = ByteBuffer.wrap(frame, headerLen, msgLen).order(ByteOrder.LITTLE_ENDIAN);
        int sol = body.getInt();
        int posType = body.getInt();
        double lat = body.getDouble();
        double lon = body.getDouble();
        double hgt = body.getDouble();
        body.getFloat();
        body.getInt();
        float stdLat = body.getFloat();
        float stdLon = body.getFloat();
        float stdHgt = body.getFloat();
        body.getInt();
        float diffAge = body.getFloat();
        body.getFloat();
        int numSvs = body.get() & 0xFF;
        int numSolnSvs = body.get() & 0xFF;
        GnssFix fix = new GnssFix();
        int quality = NmeaParser.bestPosQualityFromCode(posType);
        if (sol != SOL_COMPUTED || quality <= 0
                || !Double.isFinite(lat) || !Double.isFinite(lon)) {
            fix.hasPosition = false;
            fix.quality = 0;
            return fix;
        }
        fix.hasPosition = true;
        fix.latitude = lat;
        fix.longitude = lon;
        fix.altitude = hgt;
        fix.quality = quality;
        fix.stdLat = stdLat;
        fix.stdLon = stdLon;
        fix.stdAlt = stdHgt;
        fix.ageOfDiff = diffAge;
        fix.satellites = numSolnSvs > 0 ? numSolnSvs : numSvs;
        return fix;
    }
}

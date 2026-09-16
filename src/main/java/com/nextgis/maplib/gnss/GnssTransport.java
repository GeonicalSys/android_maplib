package com.nextgis.maplib.gnss;

/** Byte source for an NMEA stream. */
public interface GnssTransport {
    interface Listener {
        void onOpened();
        void onBytes(byte[] data, int length);
        void onClosed(String reason);
    }

    void open(Listener listener);
    void close();
}

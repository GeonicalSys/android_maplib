package com.nextgis.maplib.gnss;

/** Byte source for an NMEA or mixed GNSS stream; optional command sink. */
public interface GnssTransport {
    interface Listener {
        void onOpened();
        void onBytes(byte[] data, int length);
        void onClosed(String reason);
    }

    void open(Listener listener);

    /**
     * Send OEM ASCII to the receiver. Implementations must be safe before
     * {@link Listener#onOpened()} and after {@link #close()}.
     *
     * @return true if the bytes were accepted for sending
     */
    boolean write(byte[] data);

    void close();
}

package com.nextgis.maplib.util;

import java.io.InterruptedIOException;
import java.net.URLConnection;

/** Shared limits for snapshot connections, including connections opened after redirects. */
public final class NgwSyncIo {
    private NgwSyncIo() { }

    public static void configure(URLConnection connection) {
        connection.setConnectTimeout(NetworkUtil.TIMEOUT_CONNECTION);
        connection.setReadTimeout(NetworkUtil.TIMEOUT_SOCKET);
        connection.setUseCaches(false);
    }

    public static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            // Do not clear the flag: the account adapter must stop before the next layer.
            throw new InterruptedIOException("Sync cancelled");
        }
    }
}

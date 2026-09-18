package com.nextgis.maplib.util;

import org.junit.Test;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class NgwSyncIoTest {
    @Test public void configuresBothHttpAndHttpsBeforeConnecting() throws Exception {
        for (String scheme : new String[]{"http", "https"}) {
            HttpURLConnection connection = (HttpURLConnection) new URL(scheme + "://example.invalid").openConnection();
            NgwSyncIo.configure(connection);
            assertEquals(45000, connection.getConnectTimeout());
            assertEquals(180000, connection.getReadTimeout());
            assertFalse(connection.getUseCaches());
            connection.disconnect();
        }
    }

    @Test public void cancellationDoesNotClearTheAdaptersInterruptFlag() throws Exception {
        NgwSyncIo.checkInterrupted();
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, NgwSyncIo::checkInterrupted);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test(timeout = 10000) public void stalledResponseHeadersTimeOut() throws Exception { stalled(false); }
    @Test(timeout = 10000) public void stalledResponseBodyTimesOut() throws Exception { stalled(true); }

    private void stalled(boolean sendHeaders) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService server = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            Future<?> serving = server.submit(() -> {
                try (Socket socket = listener.accept()) {
                    if (sendHeaders) {
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nx".getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().flush();
                    }
                    release.await(5, TimeUnit.SECONDS);
                } catch (Exception exception) { throw new RuntimeException(exception); }
            });
            HttpURLConnection connection = (HttpURLConnection) new URL("http://localhost:" + listener.getLocalPort()).openConnection();
            NgwSyncIo.configure(connection);
            // Production configuration, with a shorter wall-clock limit for the test.
            connection.setReadTimeout(250);
            try {
                if (sendHeaders) {
                    assertEquals(200, connection.getResponseCode());
                    java.io.InputStream input = connection.getInputStream();
                    assertEquals('x', input.read());
                    assertThrows(SocketTimeoutException.class, () -> input.read());
                } else {
                    assertThrows(SocketTimeoutException.class, () -> connection.getResponseCode());
                }
            } finally { connection.disconnect(); release.countDown(); }
            serving.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); server.shutdownNow(); }
    }
}

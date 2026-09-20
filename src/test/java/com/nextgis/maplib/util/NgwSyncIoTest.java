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

    @Test public void cancellationGenerationAffectsOnlyExistingSessions() throws Exception {
        try (NgwSyncIo.Session current = NgwSyncIo.beginSession()) {
            NgwSyncIo.requestCancellation();
            assertThrows(InterruptedIOException.class, NgwSyncIo::checkInterrupted);
        }

        try (NgwSyncIo.Session next = NgwSyncIo.beginSession()) {
            NgwSyncIo.checkInterrupted();
        }
    }

    @Test public void outboundWriteStateIsReferenceCounted() {
        assertFalse(NgwSyncIo.isOutboundWriteInProgress());
        try (NgwSyncIo.OutboundWrite first = NgwSyncIo.beginOutboundWrite()) {
            assertTrue(NgwSyncIo.isOutboundWriteInProgress());
            try (NgwSyncIo.OutboundWrite second = NgwSyncIo.beginOutboundWrite()) {
                assertTrue(NgwSyncIo.isOutboundWriteInProgress());
            }
            assertTrue(NgwSyncIo.isOutboundWriteInProgress());
        }
        assertFalse(NgwSyncIo.isOutboundWriteInProgress());
    }

    @Test public void cancellationDisconnectsOnlyReadsOwnedByExistingSessions()
            throws Exception {
        TestConnection unrelated = new TestConnection();
        NgwSyncIo.registerReadConnection(unrelated);

        TestConnection current = new TestConnection();
        try (NgwSyncIo.Session ignored = NgwSyncIo.beginSession()) {
            NgwSyncIo.registerReadConnection(current);
            NgwSyncIo.requestCancellation();
            assertTrue(current.disconnected);
        }
        assertFalse(unrelated.disconnected);

        TestConnection next = new TestConnection();
        try (NgwSyncIo.Session ignored = NgwSyncIo.beginSession()) {
            NgwSyncIo.registerReadConnection(next);
            assertFalse(next.disconnected);
            NgwSyncIo.unregisterReadConnection(next);
        }
    }

    @Test(timeout = 10000) public void cancellationDisconnectsStalledReadWithinTwoSeconds()
            throws Exception {
        CountDownLatch accepted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean cancellationSent =
                new java.util.concurrent.atomic.AtomicBoolean();
        ExecutorService server = Executors.newSingleThreadExecutor();
        ExecutorService client = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(
                0, 1, java.net.InetAddress.getLoopbackAddress())) {
            Future<?> serving = server.submit(() -> {
                try (Socket socket = listener.accept()) {
                    accepted.countDown();
                    release.await(5, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
            Future<?> reading = client.submit(() -> {
                try (NgwSyncIo.Session ignored = NgwSyncIo.beginSession()) {
                    HttpURLConnection connection = (HttpURLConnection) new URL(
                            "http://localhost:" + listener.getLocalPort()).openConnection();
                    NgwSyncIo.configure(connection);
                    NgwSyncIo.registerReadConnection(connection);
                    try {
                        connection.getResponseCode();
                        fail("Expected cancellation to close the stalled read");
                    } finally {
                        NgwSyncIo.unregisterReadConnection(connection);
                        connection.disconnect();
                    }
                } catch (InterruptedIOException expected) {
                    // Cancellation may be observed before the socket blocks.
                } catch (java.io.IOException expected) {
                    // disconnect() unblocks HttpURLConnection with an IOException.
                } catch (RuntimeException expected) {
                    // The JDK test implementation may throw while disconnect races response setup.
                    if (!cancellationSent.get()) {
                        throw expected;
                    }
                }
            });

            assertTrue(accepted.await(2, TimeUnit.SECONDS));
            long started = System.nanoTime();
            cancellationSent.set(true);
            NgwSyncIo.requestCancellation();
            reading.get(2, TimeUnit.SECONDS);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue("Cancellation took " + elapsedMs + " ms", elapsedMs < 2000L);
            release.countDown();
            serving.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            server.shutdownNow();
            client.shutdownNow();
        }
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

    private static final class TestConnection extends HttpURLConnection {
        boolean disconnected;

        TestConnection() throws Exception {
            super(new URL("http://example.invalid"));
        }

        @Override public void disconnect() {
            disconnected = true;
        }

        @Override public boolean usingProxy() {
            return false;
        }

        @Override public void connect() {
        }
    }
}

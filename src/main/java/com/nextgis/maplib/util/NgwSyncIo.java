package com.nextgis.maplib.util;

import com.hypertrack.hyperlog.HyperLog;

import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URLConnection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Shared limits for snapshot connections, including connections opened after redirects. */
public final class NgwSyncIo {
    private static final AtomicLong CANCELLATION_GENERATION = new AtomicLong();
    private static final AtomicLong LAST_CANCELLATION_NANOS = new AtomicLong();
    private static final ThreadLocal<Long> SESSION_GENERATION = new ThreadLocal<>();
    private static final Map<HttpURLConnection, Long> ACTIVE_READ_CONNECTIONS =
            new ConcurrentHashMap<>();
    private static final AtomicInteger ACTIVE_OUTBOUND_WRITES = new AtomicInteger();

    private NgwSyncIo() { }

    /**
     * Captures the current cancellation generation for one adapter pass.
     * A later cancellation affects existing passes but not a pass started afterwards.
     */
    public static Session beginSession() {
        Long previous = SESSION_GENERATION.get();
        long generation = CANCELLATION_GENERATION.get();
        SESSION_GENERATION.set(generation);
        return new Session(previous, generation);
    }

    /**
     * Cancels all current passes and closes only read-only HTTP connections.
     * Mutation requests are not registered: their result must be resolved before
     * the local change record can be removed safely.
     */
    public static void requestCancellation() {
        LAST_CANCELLATION_NANOS.set(System.nanoTime());
        long newGeneration = CANCELLATION_GENERATION.incrementAndGet();
        for (Map.Entry<HttpURLConnection, Long> entry : ACTIVE_READ_CONNECTIONS.entrySet()) {
            if (entry.getValue() < newGeneration) {
                try {
                    entry.getKey().disconnect();
                } catch (RuntimeException ignored) {
                    // The worker also observes the generation change at its next checkpoint.
                }
            }
        }
    }

    public static void configure(URLConnection connection) {
        connection.setConnectTimeout(NetworkUtil.TIMEOUT_CONNECTION);
        connection.setReadTimeout(NetworkUtil.TIMEOUT_SOCKET);
        connection.setUseCaches(false);
    }

    public static void checkInterrupted() throws InterruptedIOException {
        if (isCancellationRequested()) {
            // Do not clear the flag: the account adapter must stop before the next layer.
            throw new InterruptedIOException("Sync cancelled");
        }
    }

    public static boolean isCancellationRequested() {
        Long sessionGeneration = SESSION_GENERATION.get();
        return Thread.currentThread().isInterrupted()
                || sessionGeneration != null
                && sessionGeneration.longValue() != CANCELLATION_GENERATION.get();
    }

    public static <T extends HttpURLConnection> T registerReadConnection(T connection)
            throws InterruptedIOException {
        checkInterrupted();
        Long sessionGeneration = SESSION_GENERATION.get();
        if (sessionGeneration == null) {
            return connection;
        }
        ACTIVE_READ_CONNECTIONS.put(connection, sessionGeneration);
        try {
            checkInterrupted();
            return connection;
        } catch (InterruptedIOException canceled) {
            ACTIVE_READ_CONNECTIONS.remove(connection);
            connection.disconnect();
            throw canceled;
        }
    }

    public static void unregisterReadConnection(HttpURLConnection connection) {
        if (connection != null) {
            ACTIVE_READ_CONNECTIONS.remove(connection);
        }
    }

    public static OutboundWrite beginOutboundWrite() {
        ACTIVE_OUTBOUND_WRITES.incrementAndGet();
        return new OutboundWrite();
    }

    public static boolean isOutboundWriteInProgress() {
        return ACTIVE_OUTBOUND_WRITES.get() > 0;
    }

    public static final class Session implements AutoCloseable {
        private final Long previousGeneration;
        private final long generation;
        private boolean closed;

        private Session(Long previousGeneration, long generation) {
            this.previousGeneration = previousGeneration;
            this.generation = generation;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previousGeneration == null) {
                SESSION_GENERATION.remove();
            } else {
                SESSION_GENERATION.set(previousGeneration);
            }
            if (generation != CANCELLATION_GENERATION.get()) {
                long requestedAt = LAST_CANCELLATION_NANOS.get();
                long elapsedMs = requestedAt == 0L
                        ? -1L
                        : Math.max(0L, (System.nanoTime() - requestedAt) / 1_000_000L);
                try {
                    HyperLog.v(Constants.TAG,
                            "NGW sync cancellation completed elapsedMs=" + elapsedMs);
                } catch (RuntimeException ignored) {
                    // Android logging is unavailable in local JVM tests and must not break cleanup.
                }
            }
        }
    }

    public static final class OutboundWrite implements AutoCloseable {
        private boolean closed;

        private OutboundWrite() {
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                ACTIVE_OUTBOUND_WRITES.decrementAndGet();
            }
        }
    }
}

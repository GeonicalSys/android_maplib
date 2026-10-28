package com.nextgis.maplib.map;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Normal devices retain two workers; a resource-constrained build owns both fair permits. */
final class RenderTileWorkGate {
    private final Semaphore permits = new Semaphore(2, true);
    Lease acquire(boolean constrained) throws InterruptedException {
        int count = constrained ? 2 : 1;
        permits.acquire(count);
        return new Lease(count);
    }
    Lease acquire(boolean constrained, BooleanSupplier current) throws InterruptedException {
        int count = constrained ? 2 : 1;
        while (true) {
            if (!current.getAsBoolean()) throw new CancellationException("Obsolete tile admission");
            if (permits.tryAcquire(count, 50, TimeUnit.MILLISECONDS)) break;
        }
        Lease lease = new Lease(count);
        boolean handedOff = false;
        try {
            if (!current.getAsBoolean()) throw new CancellationException("Obsolete tile admission");
            handedOff = true;
            return lease;
        } finally {
            if (!handedOff) lease.close();
        }
    }
    final class Lease implements AutoCloseable {
        private final int count;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Lease(int count) { this.count = count; }
        @Override public void close() { if (closed.compareAndSet(false, true)) permits.release(count); }
    }
}

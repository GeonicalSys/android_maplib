package com.nextgis.maplib.map;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Terminal cancellation ownership only: no SDK, UI dispatch, app monitor or database access. */
final class SqliteSourceRetirement {
    private final AtomicBoolean retired = new AtomicBoolean();
    private final Set<Registration> work = ConcurrentHashMap.newKeySet();

    boolean isRetired() { return retired.get(); }

    Registration register(Runnable cancelWork) {
        Registration registration = new Registration(cancelWork);
        if (!retired.get()) {
            work.add(registration);
            if (!retired.get()) return registration;
        }
        // Also covers a source created concurrently with the retire iteration.
        registration.close();
        return registration;
    }

    void retire() {
        if (!retired.compareAndSet(false, true)) return;
        RuntimeException firstFailure = null;
        for (Registration registration : work) {
            try { registration.close(); }
            catch (RuntimeException failure) {
                if (firstFailure == null) firstFailure = failure;
                else firstFailure.addSuppressed(failure);
            }
        }
        if (firstFailure != null) throw firstFailure;
    }

    final class Registration implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Runnable cancelWork;

        private Registration(Runnable cancelWork) {
            this.cancelWork = java.util.Objects.requireNonNull(cancelWork);
        }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            work.remove(this);
            cancelWork.run();
        }
    }
}

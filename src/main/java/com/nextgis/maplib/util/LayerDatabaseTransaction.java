package com.nextgis.maplib.util;

import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Commit local features and their sync records together, publishing changes after commit. */
public final class LayerDatabaseTransaction {
    private LayerDatabaseTransaction() { }
    private static final ThreadLocal<State> ACTIVE = new ThreadLocal<>();
    private static final class State {
        final SQLiteDatabase db;
        final List<Runnable> notifications = new ArrayList<>();
        final State previous;
        boolean failed;
        State(SQLiteDatabase db, State previous) { this.db = db; this.previous = previous; }
    }

    public static <T> T run(SQLiteDatabase db, Supplier<T> work) {
        State previous = ACTIVE.get();
        if (previous != null && previous.db == db) {
            try { return work.get(); }
            catch (RuntimeException | Error error) { previous.failed = true; throw error; }
        }
        if (db.inTransaction()) {
            // SQLite marks the outer transaction failed when this nested operation rolls back.
            db.beginTransaction();
            try {
                T result = work.get();
                db.setTransactionSuccessful();
                return result;
            } finally { db.endTransaction(); }
        }
        try (Scope scope = begin(db)) {
            T result = work.get();
            scope.setSuccessful();
            return result;
        }
    }

    /** A managed outer transaction also supports streaming import batches. */
    public static Scope begin(SQLiteDatabase db) { return new Scope(db); }

    public static final class Scope implements AutoCloseable {
        private final State state;
        private boolean successful, closed;
        private Scope(SQLiteDatabase db) {
            if (db.inTransaction()) throw new IllegalStateException("Transaction already active");
            state = new State(db, ACTIVE.get());
            db.beginTransaction();
            ACTIVE.set(state);
        }
        public void setSuccessful() {
            if (state.failed) throw new IllegalStateException("A nested mutation failed");
            state.db.setTransactionSuccessful();
            successful = true;
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            try {
                state.db.endTransaction();
            } finally {
                if (state.previous == null) ACTIVE.remove(); else ACTIVE.set(state.previous);
            }
            if (successful) for (Runnable notification : state.notifications) publish(notification);
        }
    }

    private static void publish(Runnable notification) {
        try { notification.run(); }
        catch (RuntimeException error) {
            // A committed database write must not be reported as failed because UI delivery failed.
            android.util.Log.w(Constants.TAG, "Layer notification failed after commit", error);
        }
    }

    public static void afterCommit(SQLiteDatabase db, Runnable notification) {
        State state = ACTIVE.get();
        if (state != null && state.db == db) state.notifications.add(notification);
        else if (!db.inTransaction()) publish(notification);
        // Raw external transactions must publish their own final refresh. Never expose uncommitted
        // rows or remove attachment files before their caller commits.
    }
}

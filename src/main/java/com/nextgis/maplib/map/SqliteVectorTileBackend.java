package com.nextgis.maplib.map;

import java.io.IOException;
import java.util.function.BooleanSupplier;
import com.nextgis.maplib.util.MapRuntimeDiagnostics;

/** Blocking complete-tile IO. The native bridge owns dispatch, cancellation and terminal close. */
public interface SqliteVectorTileBackend extends AutoCloseable {
    SqliteVectorTileRequest snapshot(int z, int x, int y) throws Exception;
    boolean isCurrent(SqliteVectorTileRequest request);
    byte[] load(SqliteVectorTileRequest request, BooleanSupplier current) throws Exception;
    default byte[] load(SqliteVectorTileRequest request, BooleanSupplier current,
                        MapRuntimeDiagnostics.SqliteScope diagnostics) throws Exception {
        return load(request, current);
    }
    @Override default void close() { }

    /** No partial/empty success: the bridge releases IO work, waits cancellably, then retries. */
    final class RetryableTileException extends IOException {
        private final long retryAfterMillis;
        public RetryableTileException(String reason, long retryAfterMillis) {
            super(reason);
            this.retryAfterMillis = Math.max(100, Math.min(2000, retryAfterMillis));
        }
        public long getRetryAfterMillis() { return retryAfterMillis; }
    }

    /** A typed contract failure, distinct from resource pressure; the host decides its fallback. */
    final class UnsupportedTileException extends IOException {
        public UnsupportedTileException(String reason) { super(reason); }
        public UnsupportedTileException(String reason, Throwable cause) { super(reason, cause); }
    }
}

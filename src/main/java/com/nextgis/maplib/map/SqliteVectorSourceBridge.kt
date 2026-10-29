package com.nextgis.maplib.map

import android.os.Looper
import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BooleanSupplier
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import org.maplibre.android.style.sources.CustomVectorSource
import org.maplibre.android.style.sources.CustomVectorTileProvider
import org.maplibre.android.style.sources.TileData
import com.nextgis.maplib.util.MapRuntimeDiagnostics
import com.nextgis.maplib.util.MapRuntimeDiagnostics.SqlitePhase
import com.nextgis.maplib.util.MapRuntimeDiagnostics.SqliteCount
import com.nextgis.maplib.util.MapRuntimeDiagnostics.SqliteOutcome

/**
 * One terminal source generation. The host must close this bridge BEFORE removeSource,
 * style replacement, project replacement or MapView destruction, on the UI thread.
 * [sourceCurrent] must read only thread-safe owner/generation state, never SDK objects.
 */
@UiThread
class SqliteVectorSourceBridge @JvmOverloads constructor(
    sourceId: String,
    backend: SqliteVectorTileBackend,
    sourceCurrent: BooleanSupplier,
    minZoom: Int = 0,
    maxZoom: Int = 18,
    diagnosticLayerId: Int = -1
) {
    private val session: SqliteVectorTileSession
    private val nativeSource: CustomVectorSource

    init {
        requireUiThread()
        require(sourceId.isNotBlank()) { "Empty native source identity" }
        require(minZoom in 0..25 && maxZoom in minZoom..25) { "Invalid native source zoom range" }
        session = SqliteVectorTileSession(backend, sourceCurrent, diagnosticLayerId = diagnosticLayerId)
        try {
            nativeSource = CustomVectorSource(
                sourceId,
                object : CustomVectorTileProvider {
                    override suspend fun fetchTile(z: Int, x: Int, y: Int): TileData =
                        TileData.Mvt(session.fetch(z, x, y))
                },
                session.scope,
                minZoom,
                maxZoom
            )
        } catch (failure: Throwable) {
            // A constructor failure must not retain a live provider scope/backend.
            try {
                session.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }

    fun getSource(): CustomVectorSource {
        requireUiThread()
        check(!session.isClosed) { "Closed source generation cannot be attached again" }
        return nativeSource
    }

    /** Terminal, idempotent and nonblocking; backend.close() must also be nonblocking. */
    fun closeBeforeDetach() {
        requireUiThread()
        session.close()
    }

    /** Cancels only Java/coroutine work; safe under the application project-switch monitor. */
    @AnyThread
    fun cancelPendingWork() {
        session.close()
    }

    /** Fixed-size provider witness only; this does not acknowledge native acceptance/rendering. */
    @AnyThread
    fun getDiagnostics(): Map<String, Any> = session.diagnostics()

    private fun requireUiThread() {
        check(Looper.myLooper() === Looper.getMainLooper()) { "Native source lifecycle requires UI thread" }
    }
}

/**
 * SDK-free lifecycle core, with injectable dispatchers for focused JVM tests. The SDK's
 * fetch/cancel jobs are children of [scope]; their individual Job is part of each read guard.
 */
internal class SqliteVectorTileSession(
    private val backend: SqliteVectorTileBackend,
    private val sourceCurrent: BooleanSupplier,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val workDispatcher: CoroutineDispatcher = SharedSqliteTileIo.dispatcher,
    private val diagnosticLayerId: Int = -1,
    private val workAdmission: SqliteTileIoAdmission = SharedSqliteTileIo.admission
) {
    private val closed = AtomicBoolean(false)
    private val ownerJob = SupervisorJob()
    private val requested = AtomicLong()
    private val ioSuccessful = AtomicLong()
    private val returnedToSdk = AtomicLong()
    private val cancelled = AtomicLong()
    private val retries = AtomicLong()
    private val failures = AtomicLong()
    private val active = AtomicInteger()
    private val lastFailureClass = AtomicReference("none")
    private val lastCancelKind = AtomicReference("none")
    val scope = CoroutineScope(mainDispatcher + ownerJob)
    val isClosed: Boolean get() = closed.get()

    suspend fun fetch(z: Int, x: Int, y: Int): ByteArray {
        requested.incrementAndGet()
        active.incrementAndGet()
        val diagnostic = MapRuntimeDiagnostics.sqliteTile(diagnosticLayerId, z)
        var diagnosticOutcome = SqliteOutcome.ABANDONED
        try {
            val requestJob = coroutineContext[Job] ?: error("Tile request requires its own coroutine Job")
            var request: SqliteVectorTileRequest? = null
            while (true) {
                ensureCurrent(requestJob, request)
                try {
                    diagnostic.phase(SqlitePhase.IO_QUEUE)
                    // Suspend before dispatch: canceled SDK jobs do not leave a blocking-work
                    // payload behind every older request in the shared IO dispatcher queue.
                    val admission = workAdmission.acquire()
                    val bytes = try {
                        ensureCurrent(requestJob, request)
                        runInterruptible(workDispatcher) {
                            try {
                                diagnostic.phase(SqlitePhase.STYLE)
                                ensureCurrent(requestJob, request)
                                val snapshot = request ?: backend.snapshot(z, x, y).also { request = it }
                                diagnostic.identity(snapshot.sourceEpoch, snapshot.contentVersion)
                                ensureCurrent(requestJob, snapshot)
                                val current = BooleanSupplier { isCurrent(requestJob, snapshot) }
                                backend.load(snapshot, current, diagnostic).also {
                                    ioSuccessful.incrementAndGet()
                                    ensureCurrent(requestJob, snapshot)
                                    // Written on IO before resuming Main; a blocked Main is visible to the watcher.
                                    diagnostic.phase(SqlitePhase.MAIN_DISPATCH)
                                }
                            } finally {
                                // Own the permit until actual blocking work unwinds, even when
                                // it ignores interruption; do not retain it until Main resumes.
                                admission.release()
                            }
                        }
                    } finally {
                        // Cancellation/current-check failure before the IO body starts has no
                        // worker finally. The same lease makes both paths exact-once.
                        admission.release()
                    }
                    // Main check still directly precedes return: no added suspension or native call.
                    ensureCurrent(requestJob, request)
                    returnedToSdk.incrementAndGet()
                    diagnosticOutcome = SqliteOutcome.RETURNED_TO_SDK
                    return bytes
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    // A late IO error must stay cancellation, not SDK nativeSetTileError.
                    ensureCurrent(requestJob, request)
                    if (failure !is SqliteVectorTileBackend.RetryableTileException) throw failure
                    retries.incrementAndGet()
                    diagnostic.count(SqliteCount.RETRIES, 1)
                    diagnostic.phase(SqlitePhase.RETRY_DELAY)
                    // IO and all backend permits have unwound before this cancellable Main delay.
                    delay(failure.retryAfterMillis)
                }
            }
        } catch (failure: CancellationException) {
            diagnosticOutcome = SqliteOutcome.CANCELLED
            cancelled.incrementAndGet()
            lastCancelKind.set(when {
                closed.get() -> "source_closed"
                !ownerJob.isActive -> "owner_cancelled"
                coroutineContext[Job]?.isActive == false -> "request_cancelled"
                failure.message == "Obsolete SQLite native source style" -> "style_stamp_stale"
                failure.message == "Obsolete SQLite native source request" -> "backend_request_stale"
                failure.message == "Stale native tile request" -> "bridge_guard_stale"
                else -> "cooperative_or_stale"
            })
            throw failure
        } catch (failure: Exception) {
            diagnosticOutcome = SqliteOutcome.FAILED
            failures.incrementAndGet()
            lastFailureClass.set(failure.javaClass.name.take(128))
            throw failure
        } finally {
            active.decrementAndGet()
            diagnostic.finish(diagnosticOutcome) // Pure Java, fail-open; no SDK call or suspension.
        }
    }

    fun diagnostics(): Map<String, Any> = linkedMapOf(
        "requested" to requested.get(),
        "io_successful" to ioSuccessful.get(),
        "returned_to_sdk" to returnedToSdk.get(),
        "cancelled" to cancelled.get(),
        "retries" to retries.get(),
        "failures" to failures.get(),
        "last_failure_class" to lastFailureClass.get(),
        "last_cancel_kind" to lastCancelKind.get(),
        "active" to active.get(),
        "closed" to closed.get(),
        "owner_job_active" to ownerJob.isActive
    )

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        ownerJob.cancel(CancellationException("Native source generation closed"))
        backend.close()
    }

    private fun isCurrent(job: Job, request: SqliteVectorTileRequest?): Boolean =
        !closed.get() && ownerJob.isActive && job.isActive && sourceCurrent.asBoolean &&
            (request == null || backend.isCurrent(request))

    private fun ensureCurrent(job: Job, request: SqliteVectorTileRequest?) {
        job.ensureActive()
        if (!isCurrent(job, request)) throw CancellationException("Stale native tile request")
    }
}

/** Shared across sources, so twenty layers do not create forty blocking workers. */
private object SharedSqliteTileIo {
    val dispatcher = Dispatchers.IO.limitedParallelism(2)
    val admission = SqliteTileIoAdmission(2)
}

/** The SDK owns pending demand; only two uncancelled blocking payloads enter IO at once. */
internal class SqliteTileIoAdmission(permits: Int) {
    private val semaphore = Semaphore(permits)

    suspend fun acquire(): Lease {
        semaphore.acquire()
        return Lease(semaphore)
    }

    class Lease internal constructor(private val semaphore: Semaphore) {
        private val released = AtomicBoolean(false)

        fun release() {
            if (released.compareAndSet(false, true)) semaphore.release()
        }
    }
}

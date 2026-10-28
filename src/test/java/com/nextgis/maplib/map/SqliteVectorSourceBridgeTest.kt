package com.nextgis.maplib.map

import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BooleanSupplier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Technical lifecycle doubles only; no native/GPU, DB contents or full-map acceptance. */
class SqliteVectorSourceBridgeTest {
    private val mainThread = AtomicReference<Thread>()
    private val ioThreads = Collections.synchronizedSet(mutableSetOf<Thread>())
    private val mainExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sqlite-test-main").also { mainThread.set(it) }
    }
    private val ioExecutor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "sqlite-test-io").also { ioThreads.add(it) }
    }
    private val main = mainExecutor.asCoroutineDispatcher()
    private val io = ioExecutor.asCoroutineDispatcher()
    private val sessions = mutableListOf<SqliteVectorTileSession>()
    private val releases = mutableListOf<CountDownLatch>()

    @After fun stopOwnedWork() {
        releases.forEach { it.countDown() }
        sessions.forEach { it.close() }
        main.close()
        io.close()
        assertTrue("Owned Main worker stopped", mainExecutor.awaitTermination(5, TimeUnit.SECONDS))
        assertTrue("Owned IO workers stopped", ioExecutor.awaitTermination(5, TimeUnit.SECONDS))
    }

    @Test fun blockingReadsUseIoAndCompletionReturnsOnMain() {
        val threads = Collections.synchronizedList(mutableListOf<Thread>())
        val backend = Backend().apply {
            onSnapshot = { z, x, y -> threads.add(Thread.currentThread()); request(z, x, y) }
            onLoad = { _, current ->
                threads.add(Thread.currentThread())
                assertTrue(current.asBoolean)
                byteArrayOf(7, 8, 9)
            }
        }
        val session = session(backend)
        val result = session.scope.async {
            val bytes = session.fetch(3, 4, 5)
            threads.add(Thread.currentThread())
            bytes
        }
        assertArrayEquals(byteArrayOf(7, 8, 9), await(result))
        assertEquals(3, threads.size)
        assertTrue("Snapshot runs on an owned IO executor thread", ioThreads.any { it === threads[0] })
        assertSame("Snapshot/load share the blocking IO worker", threads[0], threads[1])
        assertSame("Completion returns to the owned Main thread", mainThread.get(), threads[2])
        assertFalse("No blocking read runs on Main", threads[0] === mainThread.get())
        assertCounter(session, "requested", 1)
        assertCounter(session, "io_successful", 1)
        assertCounter(session, "returned_to_sdk", 1)
        assertCounter(session, "active", 0)
        assertEquals(setOf("requested", "io_successful", "returned_to_sdk", "cancelled", "retries",
            "failures", "last_failure_class", "last_cancel_kind", "active", "closed", "owner_job_active"),
            session.diagnostics().keys)
    }

    @Test fun diagnosticCallbackKeepsOneBackendReadAndOriginalCompletionBytes() {
        val backend = Backend().apply { onLoad = { _, current ->
            assertTrue(current.asBoolean)
            byteArrayOf(9, 7, 5)
        } }
        val session = SqliteVectorTileSession(backend, BooleanSupplier { true }, main, io,
            diagnosticLayerId = 7).also { sessions.add(it) }
        assertArrayEquals(byteArrayOf(9, 7, 5), await(session.scope.async { session.fetch(3, 4, 5) }))
        assertEquals(1, backend.loads.get())
        assertEquals(1, backend.diagnosticLoads.get())
        assertCounter(session, "returned_to_sdk", 1)
        assertCounter(session, "active", 0)
    }

    @Test fun closeIsTerminalAndLateSuccessfulIoCannotReachCaller() {
        val entered = CountDownLatch(1)
        val release = ownedRelease()
        val delivered = AtomicBoolean(false)
        val backend = Backend().apply {
            onLoad = { _, current ->
                entered.countDown()
                awaitIgnoringInterrupt(release)
                assertFalse("Closed request guard", current.asBoolean)
                byteArrayOf(1)
            }
        }
        val session = session(backend)
        val pending = session.scope.async { session.fetch(2, 1, 1).also { delivered.set(true) } }
        entered.mustArrive()
        session.close()
        session.close()
        release.countDown()
        assertCanceled(pending)
        assertFalse(delivered.get())
        assertEquals(1, backend.closes.get())
        val later = session.scope.async { session.fetch(2, 1, 1) }
        assertCanceled(later)
        assertEquals(1, backend.loads.get())
        assertCounter(session, "requested", 1)
        assertCounter(session, "io_successful", 1)
        assertCounter(session, "returned_to_sdk", 0)
        assertCounter(session, "cancelled", 1)
        assertCounter(session, "active", 0)
        assertEquals(true, session.diagnostics()["closed"])
        assertEquals(false, session.diagnostics()["owner_job_active"])
    }

    @Test fun lateIoErrorAfterCloseRemainsCancellation() {
        val entered = CountDownLatch(1)
        val release = ownedRelease()
        val backend = Backend().apply {
            onLoad = { _, _ ->
                entered.countDown()
                awaitIgnoringInterrupt(release)
                throw IOException("Late read failure")
            }
        }
        val session = session(backend)
        val pending = session.scope.async { session.fetch(2, 1, 1) }
        entered.mustArrive()
        session.close()
        release.countDown()
        assertCanceled(pending)
    }

    @Test fun cancelingOneTileInterruptsItsReadWithoutClosingOtherTiles() {
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val guard = AtomicReference<BooleanSupplier>()
        val backend = Backend().apply {
            onLoad = { r, current ->
                if (r.x == 1) {
                    guard.set(current)
                    entered.countDown()
                    try { CountDownLatch(1).await() }
                    catch (e: InterruptedException) { interrupted.countDown(); throw e }
                }
                byteArrayOf(42)
            }
        }
        val session = session(backend)
        val canceled = session.scope.async { session.fetch(2, 1, 1) }
        entered.mustArrive()
        canceled.cancel()
        interrupted.mustArrive()
        assertCanceled(canceled)
        assertFalse(guard.get().asBoolean)
        assertArrayEquals(byteArrayOf(42), await(session.scope.async { session.fetch(2, 2, 1) }))
        assertEquals(0, backend.closes.get())
        assertCounter(session, "requested", 2)
        assertCounter(session, "cancelled", 1)
        assertCounter(session, "returned_to_sdk", 1)
        assertCounter(session, "failures", 0)
        assertCounter(session, "active", 0)
    }

    @Test fun retryablePressureReleasesIoAndRetriesTheSameSnapshot() {
        val retryStarted = CountDownLatch(1)
        val active = AtomicInteger()
        val pressure = AtomicBoolean(true)
        val firstRequest = AtomicReference<SqliteVectorTileRequest>()
        val backend = Backend().apply {
            onLoad = { r, _ ->
                active.incrementAndGet()
                try {
                    if (r.x == 1 && pressure.get()) {
                        firstRequest.compareAndSet(null, r)
                        retryStarted.countDown()
                        throw SqliteVectorTileBackend.RetryableTileException("Busy", 2000)
                    }
                    if (r.x == 1) assertSame(firstRequest.get(), r)
                    byteArrayOf(r.x.toByte())
                } finally { active.decrementAndGet() }
            }
        }
        val session = session(backend)
        val retrying = session.scope.async { session.fetch(2, 1, 1) }
        retryStarted.mustArrive()
        assertArrayEquals(byteArrayOf(2), await(session.scope.async { session.fetch(2, 2, 1) }))
        assertEquals("No worker/permit held during retry delay", 0, active.get())
        assertFalse("Pressure is not reported as an empty success", retrying.isCompleted)
        pressure.set(false)
        assertArrayEquals(byteArrayOf(1), await(retrying))
        assertEquals("Two tiles, retry reuses snapshot", 2, backend.snapshots.get())
        assertCounter(session, "requested", 2)
        assertCounter(session, "io_successful", 2)
        assertCounter(session, "returned_to_sdk", 2)
        assertTrue((session.diagnostics()["retries"] as Number).toLong() >= 1)
        assertCounter(session, "failures", 0)
        assertCounter(session, "active", 0)
    }

    @Test fun closeDuringRetryDelayPreventsAnotherLoad() {
        val delayed = CountDownLatch(1)
        val backend = Backend().apply {
            onLoad = { _, _ -> delayed.countDown(); throw SqliteVectorTileBackend.RetryableTileException("Busy", 2000) }
        }
        val session = session(backend)
        val retrying = session.scope.async { session.fetch(2, 1, 1) }
        delayed.mustArrive()
        session.close()
        assertCanceled(retrying)
        assertEquals(1, backend.loads.get())
    }

    @Test fun changedContentEpochRejectsLateBytesAndKeepsSourceScopeUsable() {
        val entered = CountDownLatch(1)
        val release = ownedRelease()
        val current = AtomicBoolean(true)
        val backend = Backend().apply {
            onCurrent = { r -> r.x != 1 || current.get() }
            onLoad = { r, predicate ->
                if (r.x == 1) {
                    entered.countDown()
                    release.await()
                    assertFalse(predicate.asBoolean)
                }
                byteArrayOf(3)
            }
        }
        val session = session(backend)
        val stale = session.scope.async { session.fetch(2, 1, 1) }
        entered.mustArrive()
        current.set(false)
        release.countDown()
        assertCanceled(stale)
        assertArrayEquals(byteArrayOf(3), await(session.scope.async { session.fetch(2, 2, 1) }))
    }

    @Test fun replacedOwnerRejectsLateErrorsAndPreventsNewBackendReads() {
        val ownerCurrent = AtomicBoolean(true)
        val backend = Backend().apply {
            onLoad = { _, _ -> ownerCurrent.set(false); throw IOException("Obsolete owner") }
        }
        val session = session(backend, ownerCurrent)
        assertCanceled(session.scope.async { session.fetch(2, 1, 1) })
        assertCanceled(session.scope.async { session.fetch(2, 2, 1) })
        assertEquals(1, backend.loads.get())
        assertEquals(1, backend.snapshots.get())
    }

    @Test fun genuineActiveFailureIsPreservedWithoutCancelingPeerRequests() {
        val expected = IOException("Actual DB failure")
        val backend = Backend().apply {
            onLoad = { r, _ -> if (r.x == 1) throw expected else byteArrayOf(5) }
        }
        val session = session(backend)
        val failed = session.scope.async { session.fetch(2, 1, 1) }
        try { await(failed); fail("Expected original IO error") }
        catch (actual: IOException) {
            assertEquals(IOException::class.java, actual.javaClass)
            assertEquals(expected.message, actual.message)
            // Coroutine DEBUG stack recovery may copy an IOException and retain the original cause.
            val causes = generateSequence<Throwable>(actual) { it.cause }.take(16)
            assertTrue("Original active failure remains in bounded cause chain", causes.any { it === expected })
        }
        assertArrayEquals(byteArrayOf(5), await(session.scope.async { session.fetch(2, 2, 1) }))
        assertCounter(session, "requested", 2)
        assertCounter(session, "io_successful", 1)
        assertCounter(session, "returned_to_sdk", 1)
        assertCounter(session, "failures", 1)
        assertCounter(session, "cancelled", 0)
        assertCounter(session, "active", 0)
        assertEquals(IOException::class.java.name, session.diagnostics()["last_failure_class"])
        assertFalse("Error messages never enter witness", session.diagnostics().values.any { it == expected.message })
    }

    @Test fun cooperativeStyleCancellationIsVisibleWithoutClosingPeerRequests() {
        val backend = Backend().apply {
            onLoad = { r, _ ->
                if (r.x == 1) throw CancellationException("Obsolete SQLite native source style")
                byteArrayOf(12)
            }
        }
        val session = session(backend)
        assertCanceled(session.scope.async { session.fetch(2, 1, 1) })
        assertEquals("style_stamp_stale", session.diagnostics()["last_cancel_kind"])
        assertEquals(true, session.diagnostics()["owner_job_active"])
        assertEquals(false, session.diagnostics()["closed"])
        assertArrayEquals(byteArrayOf(12), await(session.scope.async { session.fetch(2, 2, 1) }))
        assertCounter(session, "requested", 2)
        assertCounter(session, "cancelled", 1)
        assertCounter(session, "failures", 0)
        assertCounter(session, "returned_to_sdk", 1)
        assertCounter(session, "active", 0)
    }

    @Test fun projectRetirementCancelsImmediatelyWhileMainCannotRun() {
        val enteredIo = CountDownLatch(1)
        val releaseIo = ownedRelease()
        val enteredMain = CountDownLatch(1)
        val releaseMain = ownedRelease()
        val retired = CountDownLatch(1)
        val guard = AtomicReference<BooleanSupplier>()
        val backend = Backend().apply {
            onLoad = { _, current ->
                guard.set(current)
                enteredIo.countDown()
                awaitIgnoringInterrupt(releaseIo)
                byteArrayOf(11)
            }
        }
        val session = session(backend)
        val pending = session.scope.async { session.fetch(2, 1, 1) }
        enteredIo.mustArrive()
        mainExecutor.execute { enteredMain.countDown(); releaseMain.await() }
        enteredMain.mustArrive()
        val owner = SqliteSourceRetirement()
        owner.register { session.close() }
        val applicationMonitor = Any()
        val switchThread = Thread({
            synchronized(applicationMonitor) { owner.retire() }
            retired.countDown()
        }, "sqlite-test-project-switch")
        try {
            switchThread.start()
            retired.mustArrive()
            assertEquals("Backend closes before Main can dispatch", 1, backend.closes.get())
            assertFalse("IO observes retirement immediately", guard.get().asBoolean)
            assertTrue(pending.isCancelled)
        } finally {
            releaseIo.countDown()
            releaseMain.countDown()
            switchThread.join(5000)
            assertFalse("Owned project-switch worker stopped", switchThread.isAlive)
        }
        assertCanceled(pending)
        assertCounter(session, "cancelled", 1)
        assertCounter(session, "returned_to_sdk", 0)
        assertCounter(session, "active", 0)
        assertEquals(true, session.diagnostics()["closed"])
    }

    @Test fun sharedWorkDispatcherBoundsConcurrencyAcrossSourceGenerations() {
        val entered = CountDownLatch(2)
        val release = ownedRelease()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val backends = List(4) {
            Backend().apply {
                onLoad = { _, _ ->
                    val count = active.incrementAndGet()
                    peak.accumulateAndGet(count, ::maxOf)
                    entered.countDown()
                    try { release.await(); byteArrayOf(6) }
                    finally { active.decrementAndGet() }
                }
            }
        }
        // Defaults share the production IO.limitedParallelism(2) singleton across sources.
        val pending = backends.map { backend ->
            val session = SqliteVectorTileSession(backend, BooleanSupplier { true }, main).also { sessions.add(it) }
            session.scope.async { session.fetch(2, 1, 1) }
        }
        entered.mustArrive()
        release.countDown()
        pending.forEach { assertArrayEquals(byteArrayOf(6), await(it)) }
        assertEquals(2, peak.get())
    }

    private fun session(backend: Backend, current: AtomicBoolean = AtomicBoolean(true)) =
        SqliteVectorTileSession(backend, BooleanSupplier { current.get() }, main, io).also { sessions.add(it) }

    private fun assertCounter(session: SqliteVectorTileSession, key: String, expected: Long) =
        assertEquals("Provider witness $key", expected, (session.diagnostics()[key] as Number).toLong())

    private fun ownedRelease() = CountDownLatch(1).also { releases.add(it) }
    private fun CountDownLatch.mustArrive() = assertTrue("Bounded lifecycle event", await(5, TimeUnit.SECONDS))
    private fun <T> await(pending: Deferred<T>): T = runBlocking { withTimeout(5000) { pending.await() } }
    private fun assertCanceled(pending: Deferred<*>) {
        try { await(pending); fail("Expected cancellation, not tile success/native error") }
        catch (_: TimeoutCancellationException) { fail("Request did not finish cancellation within its bounded wait") }
        catch (_: CancellationException) {
            assertTrue("Request completed cancellation", pending.isCompleted)
            assertTrue("Request is canceled", pending.isCancelled)
        }
    }
    private fun awaitIgnoringInterrupt(latch: CountDownLatch) {
        while (true) {
            try { latch.await(); return }
            catch (_: InterruptedException) { /* Deliberately emulate a noncooperative IO provider. */ }
        }
    }

    private class Backend : SqliteVectorTileBackend {
        val loads = AtomicInteger()
        val diagnosticLoads = AtomicInteger()
        val snapshots = AtomicInteger()
        val closes = AtomicInteger()
        var onSnapshot: (Int, Int, Int) -> SqliteVectorTileRequest = ::request
        var onCurrent: (SqliteVectorTileRequest) -> Boolean = { true }
        var onLoad: (SqliteVectorTileRequest, BooleanSupplier) -> ByteArray = { _, _ -> byteArrayOf(1) }
        override fun snapshot(z: Int, x: Int, y: Int): SqliteVectorTileRequest {
            snapshots.incrementAndGet()
            return onSnapshot(z, x, y)
        }
        override fun isCurrent(request: SqliteVectorTileRequest) = onCurrent(request)
        override fun load(request: SqliteVectorTileRequest, current: BooleanSupplier): ByteArray {
            loads.incrementAndGet()
            return onLoad(request, current)
        }
        override fun load(request: SqliteVectorTileRequest, current: BooleanSupplier,
                          diagnostics: com.nextgis.maplib.util.MapRuntimeDiagnostics.SqliteScope): ByteArray {
            diagnosticLoads.incrementAndGet()
            return load(request, current)
        }
        override fun close() { closes.incrementAndGet() }
    }

    companion object {
        private fun request(z: Int, x: Int, y: Int) =
            SqliteVectorTileRequest("technical-owner", 11, 12, "technical-style", null, z, x, y)
    }
}

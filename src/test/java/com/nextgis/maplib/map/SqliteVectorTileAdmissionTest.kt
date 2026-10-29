package com.nextgis.maplib.map

import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BooleanSupplier
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Cancellable admission only: SDK demand/bytes are preserved, not a native map benchmark. */
class SqliteVectorTileAdmissionTest {
    private val mainExecutor = Executors.newSingleThreadExecutor { Thread(it, "tile-admission-main") }
    private val otherMainExecutor = Executors.newSingleThreadExecutor { Thread(it, "tile-admission-other-main") }
    private val ioExecutor = Executors.newFixedThreadPool(2) { Thread(it, "tile-admission-io") }
    private val main = mainExecutor.asCoroutineDispatcher()
    private val otherMain = otherMainExecutor.asCoroutineDispatcher()
    private val io = ioExecutor.asCoroutineDispatcher()
    private val dispatched = AtomicInteger()
    private val trackingIo = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatched.incrementAndGet()
            io.dispatch(context, block)
        }
    }
    private val sessions = mutableListOf<SqliteVectorTileSession>()
    private val releases = mutableListOf<CountDownLatch>()

    @After fun stopOwnedWork() {
        releases.forEach { it.countDown() }
        sessions.forEach { it.close() }
        main.close()
        otherMain.close()
        io.close()
        listOf(mainExecutor, otherMainExecutor, ioExecutor).forEach {
            assertTrue("Owned test executor stopped", it.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun cancelledWaitersAndClosedSourceNeverEnterIoAheadOfFreshSameTile() {
        val entered = CountDownLatch(2)
        val release = release()
        val admission = SqliteTileIoAdmission(2)
        val blockers = Backend { _, _ -> entered.countDown(); release.await(); byteArrayOf(1) }
        val waiting = Backend { _, _ -> fail("Canceled source must not enter IO"); byteArrayOf() }
        val fresh = Backend { _, _ -> byteArrayOf(7, 8) }
        val blockerSession = session(blockers, admission)
        val waitingSession = session(waiting, admission)
        val freshSession = session(fresh, admission)
        val active = listOf(1, 2).map { x -> blockerSession.scope.async { blockerSession.fetch(8, x, 1) } }
        entered.mustArrive()
        val queued = (10..73).map { x -> waitingSession.scope.async { waitingSession.fetch(8, x, 1) } }
        barrier(mainExecutor)
        assertEquals("Only running payloads dispatched, not64 pending requests", 2, dispatched.get())
        // Exact SDK jobs are canceled independently; no address-based tombstone can erase a later request.
        listOf(0, 31, 63).forEach { queued[it].cancel() }
        listOf(0, 31, 63).forEach { assertCancelled(queued[it]) }
        waitingSession.close()
        queued.forEach { assertCancelled(it) }
        assertEquals(0, waiting.snapshots.get())
        assertEquals(1, waiting.closes.get())
        val newest = freshSession.scope.async { freshSession.fetch(8, 10, 1) }
        barrier(mainExecutor)
        assertEquals(2, dispatched.get())
        release.countDown()
        active.forEach { assertArrayEquals(byteArrayOf(1), await(it)) }
        assertArrayEquals(byteArrayOf(7, 8), await(newest))
        assertEquals("Fresh same XYZ is neither rejected nor hidden behind canceled payloads", 3, dispatched.get())
        assertEquals(1, fresh.loads.get())
        assertEquals(0, waiting.loads.get())
    }

    @Test fun cancelledNoncooperativeBodyKeepsPermitUntilItActuallyUnwinds() {
        val entered = CountDownLatch(1)
        val release = release()
        val admission = SqliteTileIoAdmission(1)
        val activeBodies = AtomicInteger()
        val peakBodies = AtomicInteger()
        val backend = Backend { request, current ->
            val active = activeBodies.incrementAndGet()
            peakBodies.accumulateAndGet(active, ::maxOf)
            try {
                if (request.x == 1) {
                    entered.countDown()
                    awaitIgnoringInterrupt(release)
                    assertFalse(current.asBoolean)
                }
                byteArrayOf(request.x.toByte())
            } finally { activeBodies.decrementAndGet() }
        }
        val session = session(backend, admission)
        val obsolete = session.scope.async { session.fetch(8, 1, 1) }
        entered.mustArrive()
        obsolete.cancel()
        val fresh = session.scope.async { session.fetch(8, 2, 1) }
        barrier(mainExecutor)
        assertEquals("Canceled Job cannot prematurely release its still-running worker", 1, dispatched.get())
        assertEquals(1, backend.loads.get())
        release.countDown()
        assertCancelled(obsolete)
        assertArrayEquals(byteArrayOf(2), await(fresh))
        assertEquals(1, peakBodies.get())
        assertEquals(2, dispatched.get())
    }

    @Test fun completedIoReleasesPermitBeforeItsBlockedMainCanResume() {
        val entered = CountDownLatch(1)
        val releaseIo = release()
        val releaseMain = release()
        val mainBlocked = CountDownLatch(1)
        val admission = SqliteTileIoAdmission(1)
        val first = session(Backend { _, _ -> entered.countDown(); releaseIo.await(); byteArrayOf(3) }, admission)
        val second = session(Backend { _, _ -> byteArrayOf(9) }, admission, dispatcher = otherMain)
        val pending = first.scope.async { first.fetch(8, 1, 1) }
        entered.mustArrive()
        mainExecutor.execute { mainBlocked.countDown(); releaseMain.await() }
        mainBlocked.mustArrive()
        val peer = second.scope.async { second.fetch(8, 2, 1) }
        barrier(otherMainExecutor)
        releaseIo.countDown()
        try {
            assertArrayEquals("Shared admission is free while first Main remains blocked", byteArrayOf(9), await(peer))
            assertFalse(pending.isCompleted)
        } finally { releaseMain.countDown() }
        assertArrayEquals(byteArrayOf(3), await(pending))
    }

    @Test fun cancellationBeforeIoBodyStartsUsesOuterLeaseReleaseWithoutReadingBackend() {
        val held = HoldingDispatcher()
        val admission = SqliteTileIoAdmission(1)
        val backend = Backend { _, _ -> byteArrayOf(4) }
        val session = session(backend, admission, work = held)
        val cancelled = session.scope.async { session.fetch(8, 1, 1) }
        val oldPayload = held.next()
        cancelled.cancel()
        // A dispatched-but-not-started withContext must still execute its cancellation wrapper.
        oldPayload.run()
        assertCancelled(cancelled)
        assertEquals(0, backend.snapshots.get())
        assertEquals(0, backend.loads.get())
        val fresh = session.scope.async { session.fetch(8, 1, 1) }
        held.next().run()
        assertArrayEquals(byteArrayOf(4), await(fresh))
        assertEquals(1, backend.loads.get())
    }

    @Test fun currentGuardFailureAfterAdmissionReleasesWithoutDispatching() {
        val checks = AtomicInteger()
        val expected = IOException("Owner guard failed")
        val admission = SqliteTileIoAdmission(1)
        val backend = Backend { _, _ -> fail("Guard failure must precede IO"); byteArrayOf() }
        val failedSession = session(backend, admission, owner = BooleanSupplier {
            if (checks.incrementAndGet() == 2) throw expected
            true
        })
        assertIoFailure(expected, failedSession.scope.async { failedSession.fetch(8, 1, 1) })
        assertEquals(0, dispatched.get())
        val peer = session(Backend { _, _ -> byteArrayOf(6) }, admission)
        assertArrayEquals(byteArrayOf(6), await(peer.scope.async { peer.fetch(8, 2, 1) }))
        assertEquals(1, dispatched.get())
    }

    @Test fun snapshotFailureReleasesIoLeaseAndPreservesTheActiveException() {
        val expected = IOException("Actual snapshot failure")
        val admission = SqliteTileIoAdmission(1)
        val backend = Backend { _, _ -> fail("Snapshot must fail before load"); byteArrayOf() }.apply {
            snapshotFailure = expected
        }
        val failedSession = session(backend, admission)
        assertIoFailure(expected, failedSession.scope.async { failedSession.fetch(8, 1, 1) })
        val peer = session(Backend { _, _ -> byteArrayOf(8) }, admission)
        assertArrayEquals(byteArrayOf(8), await(peer.scope.async { peer.fetch(8, 2, 1) }))
        assertEquals(0, backend.loads.get())
        assertEquals(2, dispatched.get())
    }

    @Test fun retryDelayOwnsNeitherAdmissionNorAnIoWorker() {
        val retryStarted = CountDownLatch(1)
        val admission = SqliteTileIoAdmission(1)
        val backend = Backend { request, _ ->
            if (request.x == 1) {
                retryStarted.countDown()
                throw SqliteVectorTileBackend.RetryableTileException("Pressure", 2000)
            }
            byteArrayOf(5)
        }
        val session = session(backend, admission)
        val retrying = session.scope.async { session.fetch(8, 1, 1) }
        retryStarted.mustArrive()
        assertArrayEquals(byteArrayOf(5), await(session.scope.async { session.fetch(8, 2, 1) }))
        assertFalse("A delayed complete tile is not replaced by an empty success", retrying.isCompleted)
        session.close()
        assertCancelled(retrying)
    }

    private fun session(backend: Backend, admission: SqliteTileIoAdmission,
                        dispatcher: CoroutineDispatcher = main, work: CoroutineDispatcher = trackingIo,
                        owner: BooleanSupplier = BooleanSupplier { true }) =
        SqliteVectorTileSession(backend, owner, dispatcher, work, workAdmission = admission).also { sessions.add(it) }

    private fun release() = CountDownLatch(1).also { releases.add(it) }
    private fun CountDownLatch.mustArrive() = assertTrue("Bounded test event", await(5, TimeUnit.SECONDS))
    private fun barrier(executor: ExecutorService) {
        val arrived = CountDownLatch(1)
        executor.execute { arrived.countDown() }
        arrived.mustArrive()
    }
    private fun <T> await(pending: Deferred<T>): T = runBlocking { withTimeout(5000) { pending.await() } }
    private fun assertCancelled(pending: Deferred<*>) {
        try { await(pending); fail("Expected cancellation") }
        catch (_: TimeoutCancellationException) { fail("Cancellation did not complete in bounded wait") }
        catch (_: CancellationException) { assertTrue(pending.isCancelled); assertTrue(pending.isCompleted) }
    }
    private fun assertIoFailure(expected: IOException, pending: Deferred<*>) {
        try { await(pending); fail("Expected active IO failure") }
        catch (actual: IOException) {
            assertTrue("Original exception is preserved through coroutine stack recovery",
                generateSequence<Throwable>(actual) { it.cause }.take(16).any { it === expected })
        }
    }
    private fun awaitIgnoringInterrupt(latch: CountDownLatch) {
        while (true) {
            try { latch.await(); return }
            catch (_: InterruptedException) { /* Deliberately noncooperative technical backend. */ }
        }
    }

    private class HoldingDispatcher : CoroutineDispatcher() {
        private val pending = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.add(block) }
        fun next(): Runnable = pending.poll(5, TimeUnit.SECONDS) ?: error("No bounded IO dispatch")
    }

    private class Backend(private val body: (SqliteVectorTileRequest, BooleanSupplier) -> ByteArray) : SqliteVectorTileBackend {
        val snapshots = AtomicInteger()
        val loads = AtomicInteger()
        val closes = AtomicInteger()
        var snapshotFailure: Exception? = null
        override fun snapshot(z: Int, x: Int, y: Int): SqliteVectorTileRequest {
            snapshots.incrementAndGet()
            snapshotFailure?.let { throw it }
            return SqliteVectorTileRequest("admission-owner", 11, 12, "admission-style", null, z, x, y)
        }
        override fun isCurrent(request: SqliteVectorTileRequest) = true
        override fun load(request: SqliteVectorTileRequest, current: BooleanSupplier): ByteArray {
            loads.incrementAndGet()
            return body(request, current)
        }
        override fun close() { closes.incrementAndGet() }
    }
}

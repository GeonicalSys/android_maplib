package com.nextgis.maplib.map;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Pure cancellation ownership, independent of Android/SDK/model fixtures. */
public class SqliteSourceRetirementTest {
    @Test public void retiresExistingAndLateSourcesOnce() {
        SqliteSourceRetirement owner = new SqliteSourceRetirement();
        AtomicInteger first = new AtomicInteger(), late = new AtomicInteger();
        SqliteSourceRetirement.Registration existing = owner.register(first::incrementAndGet);
        assertFalse(owner.isRetired());
        owner.retire(); owner.retire(); existing.close();
        owner.register(late::incrementAndGet).close();
        assertTrue(owner.isRetired());
        assertEquals(1, first.get()); assertEquals(1, late.get());
    }

    @Test public void normalSourceReplacementDoesNotRetireProject() {
        SqliteSourceRetirement owner = new SqliteSourceRetirement();
        AtomicInteger old = new AtomicInteger(), replacement = new AtomicInteger();
        owner.register(old::incrementAndGet).close();
        owner.register(replacement::incrementAndGet);
        assertFalse(owner.isRetired());
        assertEquals(1, old.get()); assertEquals(0, replacement.get());
        owner.retire();
        assertEquals(1, old.get()); assertEquals(1, replacement.get());
    }

    @Test public void concurrentRegistrationAndRetireCannotMissAWorkOwner() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            for (int attempt = 0; attempt < 200; attempt++) {
                SqliteSourceRetirement owner = new SqliteSourceRetirement();
                CountDownLatch start = new CountDownLatch(1);
                AtomicInteger canceled = new AtomicInteger();
                Future<?> registration = workers.submit(() -> {
                    await(start); owner.register(canceled::incrementAndGet);
                });
                Future<?> retirement = workers.submit(() -> { await(start); owner.retire(); });
                start.countDown();
                registration.get(5, TimeUnit.SECONDS); retirement.get(5, TimeUnit.SECONDS);
                assertTrue(owner.isRetired()); assertEquals(1, canceled.get());
            }
        } finally {
            workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void cancelCanRegisterAnotherSourceWithoutWaitingOrLosingIt() {
        SqliteSourceRetirement owner = new SqliteSourceRetirement();
        List<String> canceled = new ArrayList<>();
        owner.register(() -> {
            assertTrue(owner.isRetired());
            canceled.add("original");
            owner.register(() -> canceled.add("late"));
        });
        owner.retire();
        assertEquals(java.util.Arrays.asList("original", "late"), canceled);
    }

    @Test public void oneFailureDoesNotLeaveOtherSourceWorkLive() {
        SqliteSourceRetirement owner = new SqliteSourceRetirement();
        RuntimeException expected = new IllegalStateException("Technical cancellation failure");
        AtomicInteger other = new AtomicInteger();
        owner.register(() -> { throw expected; });
        owner.register(other::incrementAndGet);
        try { owner.retire(); fail("Expected original close failure"); }
        catch (RuntimeException actual) { assertSame(expected, actual); }
        assertTrue(owner.isRetired()); assertEquals(1, other.get());
        owner.retire(); assertEquals(1, other.get());
    }

    private static void await(CountDownLatch start) {
        try { if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Bounded start gate"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
}

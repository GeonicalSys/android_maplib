package com.nextgis.maplib.util;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Technical SharedPreferences cache/disk fault doubles, not native sync acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class ProjectSyncStatePublicationTest {
    private static final long WAIT_SECONDS = 5;

    @Test public void failedCommitKeepsPriorClockDuringAndAfterDiskWait() throws Exception {
        MemoryPreferences preferences = new MemoryPreferences();
        Context context = context(preferences);
        String path = path("held");
        assertTrue(ProjectSyncState.markComplete(context, path, 100));
        CommitStep held = CommitStep.result(false).held();
        preferences.enqueue(held);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> writing = workers.submit(() -> ProjectSyncState.markComplete(context, path, 200));
            await(held.entered);
            assertEquals(200, preferences.memoryLong(projectKey(path)));
            Future<Long> reading = workers.submit(() -> ProjectSyncState.lastComplete(context, path));
            assertEquals(100, reading.get(WAIT_SECONDS, TimeUnit.SECONDS).longValue());
            held.release.countDown();
            assertFalse(writing.get(WAIT_SECONDS, TimeUnit.SECONDS));
            assertEquals(100, ProjectSyncState.lastComplete(context, path));
            assertTrue(ProjectSyncState.markComplete(context, path, 300));
            assertEquals(300, ProjectSyncState.lastComplete(context, path));
            assertEquals(3, preferences.commits.get());
        } finally {
            held.release.countDown();
            stop(workers);
        }
    }

    @Test public void firstWriteShieldsEditPutAndCommitObservers() throws Exception {
        MemoryPreferences preferences = new MemoryPreferences();
        Context context = context(preferences);
        String path = path("observers");
        preferences.seed(projectKey(path), 100);
        ExecutorService reader = Executors.newSingleThreadExecutor();
        AtomicInteger observations = new AtomicInteger();
        Runnable observe = () -> {
            try {
                assertEquals(100, reader.submit(() -> ProjectSyncState.lastComplete(context, path))
                        .get(WAIT_SECONDS, TimeUnit.SECONDS).longValue());
                observations.incrementAndGet();
            } catch (Exception error) { throw new AssertionError(error); }
        };
        preferences.beforeEdit = observe;
        preferences.afterPut = observe;
        CommitStep failed = CommitStep.result(false);
        failed.afterMemory = observe;
        preferences.enqueue(failed);
        try {
            assertFalse(ProjectSyncState.markComplete(context, path, 200));
            assertEquals(3, observations.get());
            assertEquals(100, ProjectSyncState.lastComplete(context, path));
            assertEquals(1, preferences.commits.get());
        } finally { stop(reader); }
    }

    @Test public void nestedSuccessfulCompletionWinsOverOuterSuccess() throws Exception {
        nestedCompletion(true, true, 300);
    }

    @Test public void failedNestedCompletionDoesNotHideOuterSuccess() throws Exception {
        nestedCompletion(true, false, 200);
    }

    @Test public void nestedSuccessSurvivesOuterFailure() throws Exception {
        nestedCompletion(false, true, 300);
    }

    private static void nestedCompletion(boolean outerSuccess, boolean innerSuccess, long expected)
            throws Exception {
        MemoryPreferences preferences = new MemoryPreferences();
        Context context = context(preferences);
        String path = path("nested");
        assertTrue(ProjectSyncState.markComplete(context, path, 100));
        CommitStep outer = CommitStep.result(outerSuccess);
        outer.afterDisk = () -> assertEquals(innerSuccess,
                ProjectSyncState.markComplete(context, path, 300));
        preferences.enqueue(outer);
        preferences.enqueue(CommitStep.result(innerSuccess));
        assertEquals(outerSuccess, ProjectSyncState.markComplete(context, path, 200));
        assertEquals(expected, ProjectSyncState.lastComplete(context, path));
        assertEquals(3, preferences.commits.get());
    }

    @Test public void laterPositiveLowerTimeIsNotDiscarded() {
        MemoryPreferences preferences = new MemoryPreferences();
        Context context = context(preferences);
        String path = path("clock-adjustment");
        assertTrue(ProjectSyncState.markComplete(context, path, 1000));
        assertTrue(ProjectSyncState.markComplete(context, path, 100));
        assertEquals(100, ProjectSyncState.lastComplete(context, path));
    }

    @Test public void canonicalAliasesUseSameConfirmation() {
        MemoryPreferences preferences = new MemoryPreferences();
        Context context = context(preferences);
        String path = path("canonical");
        String alias = new File(path, "../canonical").getPath();
        assertTrue(ProjectSyncState.markComplete(context, path, 100));
        preferences.enqueue(CommitStep.result(false));
        assertFalse(ProjectSyncState.markComplete(context, alias, 200));
        assertEquals(100, ProjectSyncState.lastComplete(context, path));
        assertEquals(100, ProjectSyncState.lastComplete(context, alias));
    }

    @Test public void separateProjectsDoNotPublishRejectedCacheOnAnotherCommit() throws Exception {
        MemoryPreferences preferences = new MemoryPreferences();
        Context context = context(preferences);
        String a = path("project-a");
        String b = path("project-b");
        assertTrue(ProjectSyncState.markComplete(context, a, 100));
        assertTrue(ProjectSyncState.markComplete(context, b, 200));
        preferences.enqueue(CommitStep.result(false));
        assertFalse(ProjectSyncState.markComplete(context, a, 300));
        assertTrue(ProjectSyncState.markComplete(context, b, 400));
        assertEquals(100, ProjectSyncState.lastComplete(context, a));
        assertEquals(400, ProjectSyncState.lastComplete(context, b));
        // Whole-file flush may persist rejected cache. This fix promises a process view only.
        assertEquals(300, preferences.diskLong(projectKey(a)));
        assertEquals(4, preferences.commits.get());
    }

    @Test public void equalButDistinctPreferenceOwnersAreIsolated() {
        MemoryPreferences a = new MemoryPreferences();
        MemoryPreferences b = new MemoryPreferences();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        Context contextA = context(a);
        Context contextB = context(b);
        String path = path("same-project");
        assertTrue(ProjectSyncState.markComplete(contextA, path, 100));
        assertEquals(0, ProjectSyncState.lastComplete(contextB, path));
        assertTrue(ProjectSyncState.markComplete(contextB, path, 200));
        a.enqueue(CommitStep.result(false));
        assertFalse(ProjectSyncState.markComplete(contextA, path, 300));
        assertEquals(100, ProjectSyncState.lastComplete(contextA, path));
        assertEquals(200, ProjectSyncState.lastComplete(contextB, path));
    }

    @Test public void heldWriterDoesNotBlockAnotherPreferenceOwner() throws Exception {
        MemoryPreferences a = new MemoryPreferences();
        MemoryPreferences b = new MemoryPreferences();
        Context contextA = context(a);
        Context contextB = context(b);
        String path = path("owner-writer");
        assertTrue(ProjectSyncState.markComplete(contextA, path, 100));
        CommitStep held = CommitStep.result(false).held();
        a.enqueue(held);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> writingA = workers.submit(() -> ProjectSyncState.markComplete(contextA, path, 200));
            await(held.entered);
            Future<Boolean> writingB = workers.submit(() -> ProjectSyncState.markComplete(contextB, path, 300));
            assertTrue(writingB.get(WAIT_SECONDS, TimeUnit.SECONDS));
            assertEquals(300, ProjectSyncState.lastComplete(contextB, path));
            held.release.countDown();
            assertFalse(writingA.get(WAIT_SECONDS, TimeUnit.SECONDS));
            assertEquals(100, ProjectSyncState.lastComplete(contextA, path));
        } finally {
            held.release.countDown();
            stop(workers);
        }
    }

    @Test public void pendingOtherProjectDoesNotBlockInitialRead() throws Exception {
        MemoryPreferences preferences = new MemoryPreferences();
        Context context = context(preferences);
        String a = path("pending-project");
        String b = path("unwritten-project");
        preferences.seed(projectKey(a), 100);
        preferences.seed(projectKey(b), 200);
        CommitStep held = CommitStep.result(false).held();
        preferences.enqueue(held);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> writing = workers.submit(() -> ProjectSyncState.markComplete(context, a, 300));
            await(held.entered);
            Future<Long> reading = workers.submit(() -> ProjectSyncState.lastComplete(context, b));
            assertEquals(200, reading.get(WAIT_SECONDS, TimeUnit.SECONDS).longValue());
            held.release.countDown();
            assertFalse(writing.get(WAIT_SECONDS, TimeUnit.SECONDS));
        } finally {
            held.release.countDown();
            stop(workers);
        }
    }

    @Test public void readerMissRechecksAfterFailedCacheAndLaterRecovery() throws Exception {
        MemoryPreferences preferences = new MemoryPreferences();
        Context context = context(preferences);
        String path = path("raw-reader-race");
        preferences.seed(projectKey(path), 100);
        ReadGate gate = new ReadGate("clock-raw-reader");
        preferences.readGate = gate;
        ExecutorService reader = Executors.newSingleThreadExecutor(task -> new Thread(task, gate.threadName));
        try {
            Future<Long> reading = reader.submit(() -> ProjectSyncState.lastComplete(context, path));
            await(gate.beforeRead);
            preferences.enqueue(CommitStep.result(false));
            assertFalse(ProjectSyncState.markComplete(context, path, 200));
            gate.allowRead.countDown();
            await(gate.afterRead);
            assertEquals(200, gate.captured);
            assertTrue(ProjectSyncState.markComplete(context, path, 300));
            gate.allowReturn.countDown();
            assertEquals(300, reading.get(WAIT_SECONDS, TimeUnit.SECONDS).longValue());
        } finally {
            gate.allowRead.countDown();
            gate.allowReturn.countDown();
            stop(reader);
        }
    }

    @Test public void commitExceptionKeepsConfirmedViewAndLaterSuccessRecovers() {
        MemoryPreferences preferences = new MemoryPreferences();
        Context context = context(preferences);
        String path = path("commit-throw");
        assertTrue(ProjectSyncState.markComplete(context, path, 100));
        RuntimeException failure = new IllegalStateException("commit failed after cache mutation");
        CommitStep step = CommitStep.result(false);
        step.failure = failure;
        preferences.enqueue(step);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> ProjectSyncState.markComplete(context, path, 200)));
        assertEquals(100, ProjectSyncState.lastComplete(context, path));
        assertTrue(ProjectSyncState.markComplete(context, path, 300));
        assertEquals(300, ProjectSyncState.lastComplete(context, path));
        assertEquals(3, preferences.commits.get());
    }

    @Test public void baselineReadFailureDoesNotPublishOrWrite() throws Exception {
        MemoryPreferences preferences = new MemoryPreferences();
        Context context = context(preferences);
        String path = path("initial-read-throw");
        preferences.seed(projectKey(path), 100);
        RuntimeException failure = new IllegalStateException("initial cache read failed");
        preferences.readFailure.set(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> ProjectSyncState.markComplete(context, path, 200)));
        assertEquals(0, preferences.editors.get());
        assertEquals(100, ProjectSyncState.lastComplete(context, path));
        preferences.enqueue(CommitStep.result(false));
        assertFalse(ProjectSyncState.markComplete(context, path, 200));
        assertEquals(100, ProjectSyncState.lastComplete(context, path));
        assertTrue(ProjectSyncState.markComplete(context, path, 300));
        assertEquals(300, ProjectSyncState.lastComplete(context, path));
    }

    @Test public void persistentNamespaceCanonicalUuidAndLongValueAreUnchanged() throws Exception {
        MemoryPreferences preferences = new MemoryPreferences();
        PreferenceContext context = context(preferences);
        String path = path("persisted");
        String alias = new File(path, "../persisted").getPath();
        assertTrue(ProjectSyncState.markComplete(context, alias, 1234));
        assertEquals("full-project-sync-state", context.requestedName);
        assertEquals(Context.MODE_PRIVATE, context.requestedMode);
        Map<String, ?> written = preferences.getAll();
        assertEquals(1, written.size());
        assertEquals(Long.valueOf(1234), written.get(projectKey(path)));
        assertEquals(1234, preferences.diskLong(projectKey(path)));
        assertEquals(1, preferences.commits.get());
    }

    @Test public void invalidCompletionsDoNotCreateEditors() {
        MemoryPreferences preferences = new MemoryPreferences();
        Context context = context(preferences);
        assertFalse(ProjectSyncState.markComplete(context, null, 100));
        assertFalse(ProjectSyncState.markComplete(context, path("invalid"), 0));
        assertFalse(ProjectSyncState.markComplete(context, path("invalid"), -1));
        assertEquals(0, ProjectSyncState.lastComplete(context, null));
        assertEquals(0, preferences.editors.get());
        assertEquals(0, preferences.commits.get());
    }

    private static String path(String name) {
        return new File("Q:/Codex/Temp/project-sync-state-unit", name).getPath();
    }

    private static String projectKey(String path) throws IOException {
        return UUID.nameUUIDFromBytes(new File(path).getCanonicalPath()
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static PreferenceContext context(MemoryPreferences preferences) {
        return new PreferenceContext(preferences);
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue("bounded test rendezvous timed out", latch.await(WAIT_SECONDS, TimeUnit.SECONDS)); }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    private static void stop(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue("owned test workers did not stop", executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
    }

    private static final class PreferenceContext extends ContextWrapper {
        final MemoryPreferences preferences;
        String requestedName;
        int requestedMode;
        PreferenceContext(MemoryPreferences preferences) {
            super(RuntimeEnvironment.getApplication());
            this.preferences = preferences;
        }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            requestedName = name;
            requestedMode = mode;
            return preferences;
        }
    }

    private static final class ReadGate {
        final String threadName;
        final AtomicBoolean claimed = new AtomicBoolean();
        final CountDownLatch beforeRead = new CountDownLatch(1);
        final CountDownLatch allowRead = new CountDownLatch(1);
        final CountDownLatch afterRead = new CountDownLatch(1);
        final CountDownLatch allowReturn = new CountDownLatch(1);
        volatile long captured;
        ReadGate(String threadName) { this.threadName = threadName; }
    }

    private static final class CommitStep {
        final boolean success;
        Runnable afterMemory;
        Runnable afterDisk;
        RuntimeException failure;
        CountDownLatch entered;
        CountDownLatch release;
        private CommitStep(boolean success) { this.success = success; }
        static CommitStep result(boolean success) { return new CommitStep(success); }
        CommitStep held() {
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
            return this;
        }
    }

    /** Models the framework's cache-before-disk boundary and whole-file flush. */
    private static final class MemoryPreferences implements SharedPreferences {
        private static final Object REMOVED = new Object();
        private final Map<String, Object> memory = new HashMap<>();
        private final Map<String, Object> disk = new HashMap<>();
        private final Queue<CommitStep> steps = new ArrayDeque<>();
        final AtomicInteger editors = new AtomicInteger();
        final AtomicInteger commits = new AtomicInteger();
        final AtomicReference<RuntimeException> readFailure = new AtomicReference<>();
        volatile Runnable beforeEdit;
        volatile Runnable afterPut;
        volatile ReadGate readGate;
        synchronized void seed(String key, long value) { memory.put(key, value); disk.put(key, value); }
        synchronized void enqueue(CommitStep step) { steps.add(step); }
        synchronized long memoryLong(String key) { return (Long) memory.get(key); }
        synchronized long diskLong(String key) { return (Long) disk.get(key); }
        // Deliberately hostile to equals-based owner maps; the Android object owner is identity.
        @Override public boolean equals(Object other) { return other instanceof MemoryPreferences; }
        @Override public int hashCode() { return 7; }
        @Override public synchronized Map<String, ?> getAll() { return new HashMap<>(memory); }
        @Override public synchronized String getString(String key, String fallback) {
            return (String) memory.getOrDefault(key, fallback);
        }
        @Override @SuppressWarnings("unchecked")
        public synchronized Set<String> getStringSet(String key, Set<String> fallback) {
            Set<String> value = (Set<String>) memory.get(key);
            return value == null ? fallback : new HashSet<>(value);
        }
        @Override public synchronized int getInt(String key, int fallback) {
            return (Integer) memory.getOrDefault(key, fallback);
        }
        @Override public long getLong(String key, long fallback) {
            RuntimeException failure = readFailure.getAndSet(null);
            if (failure != null) throw failure;
            ReadGate gate = readGate;
            boolean gated = gate != null && gate.threadName.equals(Thread.currentThread().getName())
                    && gate.claimed.compareAndSet(false, true);
            if (gated) { gate.beforeRead.countDown(); await(gate.allowRead); }
            final long value;
            synchronized (this) { value = (Long) memory.getOrDefault(key, fallback); }
            if (gated) {
                gate.captured = value;
                gate.afterRead.countDown();
                await(gate.allowReturn);
            }
            return value;
        }
        @Override public synchronized float getFloat(String key, float fallback) {
            return (Float) memory.getOrDefault(key, fallback);
        }
        @Override public synchronized boolean getBoolean(String key, boolean fallback) {
            return (Boolean) memory.getOrDefault(key, fallback);
        }
        @Override public synchronized boolean contains(String key) { return memory.containsKey(key); }
        @Override public Editor edit() {
            editors.incrementAndGet();
            Runnable observer = beforeEdit;
            if (observer != null) observer.run();
            return new MemoryEditor();
        }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }

        private final class MemoryEditor implements Editor {
            private final Map<String, Object> changes = new HashMap<>();
            private boolean clear;
            @Override public Editor putString(String key, String value) { changes.put(key, value); return this; }
            @Override public Editor putStringSet(String key, Set<String> value) {
                changes.put(key, value == null ? null : new HashSet<>(value)); return this;
            }
            @Override public Editor putInt(String key, int value) { changes.put(key, value); return this; }
            @Override public Editor putLong(String key, long value) {
                changes.put(key, value);
                Runnable observer = afterPut;
                if (observer != null) observer.run();
                return this;
            }
            @Override public Editor putFloat(String key, float value) { changes.put(key, value); return this; }
            @Override public Editor putBoolean(String key, boolean value) { changes.put(key, value); return this; }
            @Override public Editor remove(String key) { changes.put(key, REMOVED); return this; }
            @Override public Editor clear() { clear = true; return this; }
            @Override public boolean commit() {
                commits.incrementAndGet();
                final Map<String, Object> toDisk;
                final CommitStep step;
                synchronized (MemoryPreferences.this) {
                    if (clear) memory.clear();
                    for (Map.Entry<String, Object> entry : changes.entrySet()) {
                        if (entry.getValue() == REMOVED || entry.getValue() == null) memory.remove(entry.getKey());
                        else memory.put(entry.getKey(), entry.getValue());
                    }
                    toDisk = new HashMap<>(memory);
                    CommitStep queued = steps.poll();
                    step = queued == null ? CommitStep.result(true) : queued;
                }
                if (step.afterMemory != null) step.afterMemory.run();
                if (step.entered != null) { step.entered.countDown(); await(step.release); }
                if (step.failure != null) throw step.failure;
                if (step.success) {
                    synchronized (MemoryPreferences.this) { disk.clear(); disk.putAll(toDisk); }
                }
                // Framework listeners can reenter after disk completion, before commit returns.
                if (step.afterDisk != null) step.afterDisk.run();
                return step.success;
            }
            @Override public void apply() { throw new AssertionError("clock must not add asynchronous writes"); }
        }
    }
}

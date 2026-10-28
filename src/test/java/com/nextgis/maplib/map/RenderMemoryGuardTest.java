package com.nextgis.maplib.map;

import android.app.ActivityManager;
import android.app.Application;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static com.nextgis.maplib.map.RenderMemoryPolicy.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, manifest = Config.NONE, application = Application.class)
public class RenderMemoryGuardTest {
    @Test public void fullQueueWithFourGiBFreeDoesNotClaimMemoryPressureThenLowFreeWarnsOnce() {
        ActivityManager manager = RuntimeEnvironment.getApplication().getSystemService(ActivityManager.class);
        memory(manager, 12 * GIB, 4 * GIB);
        AtomicInteger notices = new AtomicInteger(), trims = new AtomicInteger();
        RenderMemoryGuard guard = new RenderMemoryGuard(RuntimeEnvironment.getApplication(), new RenderMemoryGuard.Listener() {
            @Override public void restricted(int ignored) { trims.incrementAndGet(); notices.incrementAndGet(); }
            @Override public void workDelayed() { notices.incrementAndGet(); }
        });
        // The same production callback is invoked for every queue_full rejection.
        for (int i = 0; i < 4; i++) guard.noteDelayedWork();
        assertFalse(guard.shouldReduceCache()); assertFalse(guard.hasResourceNotice());
        assertEquals("none", guard.snapshot().get("global_economy"));
        assertEquals(0, notices.get()); assertEquals(0, trims.get());
        assertTrue(guard.canBuildTile()); assertEquals(2, guard.snapshot().get("tile_work_limit"));

        memory(manager, 12 * GIB, 256 * MIB);
        ShadowSystemClock.advanceBy(Duration.ofMillis(501));
        for (int i = 0; i < 4; i++) guard.noteDelayedWork();
        assertTrue(guard.shouldReduceCache()); assertTrue(guard.hasResourceNotice());
        assertEquals("tile_work_delayed", guard.snapshot().get("global_economy"));
        assertEquals(1, notices.get()); assertEquals(0, trims.get());
        assertEquals(1, guard.snapshot().get("tile_work_limit"));
        assertTrue("Low free memory need not reject a still-safe complete tile", guard.canBuildTile());
        assertTrue(guard.restrictions().isEmpty());
    }

    @Test public void twoGiBDeviceWithFreeHeadroomDoesNotWarnOrSerializeOnlyForItsClass() {
        ActivityManager manager = RuntimeEnvironment.getApplication().getSystemService(ActivityManager.class);
        memory(manager, 2 * GIB, GIB);
        AtomicInteger calls = new AtomicInteger();
        RenderMemoryGuard guard = new RenderMemoryGuard(RuntimeEnvironment.getApplication(), ignored -> calls.incrementAndGet());
        guard.noteDelayedWork(); guard.checkPressure();
        assertTrue(guard.limits().lowMemoryDevice);
        assertFalse(guard.shouldReduceCache()); assertFalse(guard.shouldSerializeTileWork());
        assertFalse(guard.hasResourceNotice()); assertEquals(0, calls.get());
        assertEquals(2, guard.snapshot().get("tile_work_limit"));
        assertEquals(32 * MIB, guard.limits().diskCacheBytes);
    }

    private static void memory(ActivityManager manager, long total, long available) {
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        memory.totalMem = total; memory.availMem = available;
        memory.threshold = 128 * MIB; memory.lowMemory = false;
        shadowOf(manager).setMemoryInfo(memory);
    }
}

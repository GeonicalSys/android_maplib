package com.nextgis.maplib.util;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.IOException;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Full project completion only. Partial uploads never write this clock. */
public final class ProjectSyncState {
    private static final String PREFERENCES = "full-project-sync-state";
    private static final Object STATE_LOCK = new Object();
    private static final ReferenceQueue<SharedPreferences> COLLECTED_OWNERS = new ReferenceQueue<>();
    private static final Map<PreferenceOwner, OwnerState> OWNERS = new HashMap<>();
    private ProjectSyncState() { }

    private static final class PreferenceOwner extends WeakReference<SharedPreferences> {
        private final int hash;
        PreferenceOwner(SharedPreferences preferences, ReferenceQueue<SharedPreferences> queue) {
            super(preferences, queue);
            hash = System.identityHashCode(preferences);
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof PreferenceOwner)) return false;
            SharedPreferences preferences = get();
            return preferences != null && preferences == ((PreferenceOwner) other).get();
        }
    }

    private static final class OwnerState {
        // SharedPreferences commits the whole file. Readers never acquire this monitor.
        final Object writer = new Object();
        final Map<String, ClockState> clocks = new HashMap<>();
    }

    private static final class ClockState {
        long confirmed;
        long nextInvocation;
        long confirmedInvocation;
        ClockState(long confirmed) { this.confirmed = confirmed; }
    }

    private static OwnerState owner(SharedPreferences preferences) {
        synchronized (STATE_LOCK) {
            PreferenceOwner collected;
            while ((collected = (PreferenceOwner) COLLECTED_OWNERS.poll()) != null) {
                OWNERS.remove(collected);
            }
            PreferenceOwner lookup = new PreferenceOwner(preferences, null);
            OwnerState state = OWNERS.get(lookup);
            if (state == null) {
                state = new OwnerState();
                OWNERS.put(new PreferenceOwner(preferences, COLLECTED_OWNERS), state);
            }
            return state;
        }
    }

    private static String key(String path) throws IOException {
        return UUID.nameUUIDFromBytes(new File(path).getCanonicalPath()
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    public static long lastComplete(Context context, String path) {
        if (path == null) return 0L;
        try {
            String project = key(path);
            SharedPreferences preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
            OwnerState owner = owner(preferences);
            synchronized (STATE_LOCK) {
                ClockState clock = owner.clocks.get(project);
                if (clock != null) return clock.confirmed;
            }
            // A writer may install a shield while getLong is reading its mutable cache.
            long value = preferences.getLong(project, 0L);
            synchronized (STATE_LOCK) {
                ClockState clock = owner.clocks.get(project);
                return clock == null ? value : clock.confirmed;
            }
        }
        catch (IOException error) { return 0L; }
    }

    public static boolean markComplete(Context context, String path, long time) {
        if (path == null || time <= 0) return false;
        try {
            String project = key(path);
            SharedPreferences preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
            OwnerState owner = owner(preferences);
            synchronized (owner.writer) {
                ClockState clock;
                synchronized (STATE_LOCK) { clock = owner.clocks.get(project); }
                if (clock == null) {
                    long previous = preferences.getLong(project, 0L);
                    synchronized (STATE_LOCK) {
                        clock = owner.clocks.get(project);
                        if (clock == null) {
                            clock = new ClockState(previous);
                            owner.clocks.put(project, clock);
                        }
                    }
                }
                final long invocation;
                synchronized (STATE_LOCK) { invocation = ++clock.nextInvocation; }
                // Install the confirmed view before edit/putLong can call any observer.
                boolean committed = preferences.edit().putLong(project, time).commit();
                if (committed) {
                    synchronized (STATE_LOCK) {
                        // A nested listener may have completed a later successful invocation.
                        if (invocation > clock.confirmedInvocation) {
                            clock.confirmed = time;
                            clock.confirmedInvocation = invocation;
                        }
                    }
                }
                // Keep the view even after success: old raw readers may still be in flight.
                // A false commit can leave the framework cache (and disk) changed; no repair write.
                return committed;
            }
        }
        catch (IOException error) { return false; }
    }
}

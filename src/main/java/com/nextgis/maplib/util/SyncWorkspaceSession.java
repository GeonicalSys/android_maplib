package com.nextgis.maplib.util;

import android.net.Uri;
import android.os.Handler;
import android.content.Intent;
import com.nextgis.maplib.api.ILayer;
import com.nextgis.maplib.map.MapBase;
import com.nextgis.maplib.map.MapContentProviderHelper;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit, non-activating owner for one project pass and all of its asynchronous children. */
public final class SyncWorkspaceSession implements AutoCloseable {
    public static final String EXTRA_TOKEN = "ngw_sync_workspace_token";
    public static final String EXTRA_TICKET = "ngw_sync_workspace_ticket";
    public static final String URI_TOKEN = "sync_workspace";
    public static final String EXTRA_MAP_PATH = "ngw_notification_workspace";
    private static final ThreadLocal<SyncWorkspaceSession> CURRENT = new ThreadLocal<>();
    private static final Map<String, SyncWorkspaceSession> OPEN = new ConcurrentHashMap<>();
    private final String token = UUID.randomUUID().toString();
    private final MapContentProviderHelper map;
    private final String projectUid;
    private int pending;
    private final Map<String, External> external = new java.util.HashMap<>();
    private final long cancellationGeneration;
    private volatile boolean failed, closed;

    public SyncWorkspaceSession(MapContentProviderHelper map) {
        this(map, map != null && map.getCollectorProjectMetadata() != null
                ? map.getCollectorProjectMetadata().getProjectUid() : "");
    }
    public SyncWorkspaceSession(MapContentProviderHelper map, String projectUid) {
        this(map, projectUid, NgwSyncIo.captureGeneration());
    }
    public SyncWorkspaceSession(MapContentProviderHelper map, String projectUid, long cancellationGeneration) {
        if (map == null) throw new IllegalArgumentException("Workspace is required");
        this.map = map;
        this.projectUid = projectUid == null ? "" : projectUid;
        this.cancellationGeneration = cancellationGeneration;
        OPEN.put(token, this);
    }
    public MapContentProviderHelper getMap() { return map; }
    public String getProjectUid() { return projectUid; }
    public String getWorkspaceKey() {
        try { return projectUid + "|" + map.getPath().getCanonicalPath(); }
        catch (java.io.IOException error) { throw new IllegalStateException("Invalid workspace", error); }
    }
    public String getToken() { return token; }
    public static SyncWorkspaceSession current() { return CURRENT.get(); }
    public static SyncWorkspaceSession resolve(String token) {
        SyncWorkspaceSession found = token == null ? null : OPEN.get(token);
        return found != null && !found.closed ? found : null;
    }
    public static MapContentProviderHelper currentMap() {
        SyncWorkspaceSession current = current();
        return current == null ? null : current.map;
    }
    public static void markFailed() {
        if (current() != null) current().failed = true;
    }
    public boolean hasFailed() { return failed; }
    public boolean isCancelled() { return cancellationGeneration != NgwSyncIo.captureGeneration(); }
    private static final class External {
        final long created = android.os.SystemClock.elapsedRealtime();
        final Runnable reject;
        boolean claimed;
        External(Runnable reject) { this.reject = reject; }
    }
    public synchronized String reserveExternal(Runnable reject) {
        if (closed) throw new IllegalStateException("Workspace has finished");
        String id = UUID.randomUUID().toString();
        external.put(id, new External(reject)); pending++; return id;
    }
    public synchronized boolean claimExternal(String id) {
        External work = external.get(id);
        if (closed || work == null || work.claimed) return false;
        work.claimed = true; return true;
    }
    public synchronized void releaseExternal(String id) {
        if (external.remove(id) != null) { pending--;notifyAll(); }
    }
    /** A service delivery that never arrives cannot keep the database reserved forever. */
    public void expireUndelivered() {
        java.util.List<Runnable> rejected = new java.util.ArrayList<>();
        synchronized (this) {
            java.util.Iterator<External> it = external.values().iterator();
            while (it.hasNext()) {
                External work = it.next();
                if (!work.claimed && android.os.SystemClock.elapsedRealtime() - work.created >= 60_000) {
                    it.remove(); pending--; failed = true; rejected.add(work.reject);
                }
            }
            notifyAll();
        }
        for (Runnable reject : rejected) if (reject != null) reject.run();
    }
    public Scope enter() {
        if (closed) throw new IllegalStateException("Workspace session has finished");
        return new Scope(this);
    }
    public static Scope enter(SyncWorkspaceSession session) { return new Scope(session); }

    public static final class Scope implements AutoCloseable {
        private final SyncWorkspaceSession previous;
        private boolean closed;
        private final NgwSyncIo.Session io;
        private Scope(SyncWorkspaceSession session) {
            if (session != null && session.closed) throw new IllegalStateException("Expired workspace");
            previous = CURRENT.get();
            if (session == null) CURRENT.remove(); else CURRENT.set(session);
            io = session == null ? null : NgwSyncIo.inheritSession(session.cancellationGeneration);
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            if (io != null) io.close();
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }
    private static final class Tracked implements Runnable {
        final SyncWorkspaceSession session;
        final Runnable action;
        final AtomicBoolean claimed = new AtomicBoolean();
        Tracked(SyncWorkspaceSession session, Runnable action) {
            this.session = session; this.action = action;
            synchronized (session) {
                if (session.closed) throw new IllegalStateException("Expired workspace");
                session.pending++;
            }
        }
        @Override public void run() {
            if (!claimed.compareAndSet(false, true)) return;
            try (Scope ignored = session.enter()) { action.run(); }
            catch (RuntimeException error) { session.failed = true; throw error; }
            finally { release(); }
        }
        void reject() {
            if (claimed.compareAndSet(false, true)) { session.failed = true; release(); }
        }
        void release() { synchronized(session) { session.pending--; session.notifyAll(); } }
    }
    public static Runnable capture(Runnable action) {
        SyncWorkspaceSession session = current();
        return session == null ? action : new Tracked(session, action);
    }
    /** Release a captured task removed from an executor queue before it ever ran. */
    public static void discard(Runnable action) {
        if (action instanceof Tracked) ((Tracked) action).reject();
    }
    public static boolean post(Handler handler, Runnable action) {
        Runnable tracked = capture(action);
        try {
            boolean posted = handler.post(tracked);
            if (!posted && tracked instanceof Tracked) ((Tracked)tracked).reject();
            return posted;
        } catch (RuntimeException error) {
            if (tracked instanceof Tracked) ((Tracked)tracked).reject();
            throw error;
        }
    }
    public static void execute(java.util.concurrent.Executor executor, Runnable action) {
        Runnable tracked = capture(action);
        try { executor.execute(tracked); }
        catch (RuntimeException error) {
            if (tracked instanceof Tracked) ((Tracked)tracked).reject();
            throw error;
        }
    }
    public static void start(String name, Runnable action) {
        Runnable tracked = capture(action);
        try { new Thread(tracked, name).start(); }
        catch (RuntimeException error) {
            if (tracked instanceof Tracked) ((Tracked)tracked).reject();
            throw error;
        }
    }
    public synchronized boolean isQuiet() { return pending == 0; }
    public synchronized void waitForProgress() throws InterruptedException { if (pending > 0) wait(250); }

    /** Opaque live registration only: an expired token never falls back to the UI workspace. */
    public static Uri bindUri(ILayer layer, Uri uri) {
        MapContentProviderHelper owner = DatabaseContext.getMapForLayer(layer);
        String existing = uri.getQueryParameter(URI_TOKEN);
        if (existing != null) {
            SyncWorkspaceSession registered = resolve(existing);
            if (registered == null || registered.map != owner) throw new IllegalStateException("URI owner mismatch");
            return uri;
        }
        if (owner == MapBase.getActiveInstance()) return uri;
        for (SyncWorkspaceSession session : OPEN.values()) if (session.map == owner) {
            return uri.buildUpon().appendQueryParameter(URI_TOKEN, session.token).build();
        }
        throw new IllegalStateException("Inactive workspace has no live sync owner");
    }
    public static Intent bindNotification(ILayer layer, Intent intent) {
        return intent.putExtra(EXTRA_MAP_PATH,
                DatabaseContext.getMapForLayer(layer).getPath().getAbsolutePath());
    }
    @Override public synchronized void close() {
        if (pending != 0) throw new IllegalStateException("Workspace still has pending work");
        closed = true;
        OPEN.remove(token);
    }
}

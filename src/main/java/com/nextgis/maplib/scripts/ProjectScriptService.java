package com.nextgis.maplib.scripts;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** No application database, account credentials, files or network are passed to this process. */
public final class ProjectScriptService extends Service {
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    private final IProjectScriptService.Stub binder = new IProjectScriptService.Stub() {
        @Override public synchronized byte[] execute(byte[] source, byte[] input, IProjectScriptHost host) {
            if (Binder.getCallingUid() != getApplicationInfo().uid
                    || !ProjectScriptProcess.isSandbox(ProjectScriptService.this))
                throw new SecurityException("Script service requires an isolated application binding");
            if (source == null || input == null || source.length > 512 * 1024
                    || input.length > 128 * 1024 || host == null) return failure();
            // Covers native failure and a host IPC that never returns. Only this sandbox is killed.
            var kill = watchdog.schedule(() -> Process.killProcess(Process.myPid()), 4, TimeUnit.SECONDS);
            try { return ProjectScriptEngine.evaluate(source, input, host); }
            catch (LinkageError | RuntimeException error) { return failure(); }
            finally { kill.cancel(false); }
        }
    };
    private static byte[] failure() {
        return "{\"error\":\"execution_failed\"}".getBytes(StandardCharsets.UTF_8);
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public void onDestroy() { watchdog.shutdownNow(); super.onDestroy(); }
}

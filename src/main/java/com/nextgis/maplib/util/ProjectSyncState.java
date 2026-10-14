package com.nextgis.maplib.util;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Full project completion only. Partial uploads never write this clock. */
public final class ProjectSyncState {
    private static final String PREFERENCES = "full-project-sync-state";
    private ProjectSyncState() { }

    private static String key(String path) throws IOException {
        return UUID.nameUUIDFromBytes(new File(path).getCanonicalPath()
                .getBytes(StandardCharsets.UTF_8)).toString();
    }
    public static long lastComplete(Context context, String path) {
        if (path == null) return 0L;
        try { return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getLong(key(path), 0L); }
        catch (IOException error) { return 0L; }
    }
    public static boolean markComplete(Context context, String path, long time) {
        if (path == null || time <= 0) return false;
        try { return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putLong(key(path), time).commit(); }
        catch (IOException error) { return false; }
    }
}

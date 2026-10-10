package com.nextgis.maplib.util;

import android.content.Context;
import android.util.Log;

import com.hypertrack.hyperlog.HyperLog;
import com.hypertrack.hyperlog.LogFormat;

/** Process-wide local HyperLog setup; remote diagnostics have their own transport. */
public final class LocalLogInitializer {
    private static boolean initialized;

    private LocalLogInitializer() { }

    public static void initialize(Context context) {
        initialize(context, null);
    }

    public static synchronized void initialize(Context context, LogFormat format) {
        Context app = context.getApplicationContext();
        if (!initialized) {
            // Pinned HyperLog reads this setting and synchronously prepares every pending upload
            // inside initialize(). A loopback placeholder still serializes the entire log backlog.
            // apply() removes the obsolete endpoint in memory before the library reads it; it does
            // not touch the log database, retention policy or local export preferences.
            app.getSharedPreferences("HyperLog", Context.MODE_PRIVATE).edit().remove("URL").apply();
            HyperLog.initialize(app, format != null ? format : new LogFormat(app));
            initialized = true;
        } else if (format != null) {
            HyperLog.setLogFormat(format);
        }
        HyperLog.setLogLevel(Log.VERBOSE);
    }
}

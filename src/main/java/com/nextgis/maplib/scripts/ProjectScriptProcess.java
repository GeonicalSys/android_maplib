package com.nextgis.maplib.scripts;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Process;

/** Must be checked before preferences, logging, account access or GIS initialization. */
public final class ProjectScriptProcess {
    private ProjectScriptProcess() { }

    public static boolean isSandbox(Context context) {
        // Isolated services have a UID distinct from their application's installed UID (API 26+).
        return Process.myUid() != context.getApplicationInfo().uid
                || (Build.VERSION.SDK_INT >= 28
                && Application.getProcessName().endsWith(":project_scripts"));
    }
}

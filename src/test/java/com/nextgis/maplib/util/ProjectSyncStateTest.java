package com.nextgis.maplib.util;

import android.app.Application;
import android.content.Context;
import java.io.File;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class ProjectSyncStateTest {
    @Test public void projectsWithTheSameAccountAndLayerNamesHaveIndependentClocks() {
        Context context = RuntimeEnvironment.getApplication();
        String a = new File(context.getFilesDir(), "project-a").getPath();
        String b = new File(context.getFilesDir(), "project-b").getPath();
        assertTrue(ProjectSyncState.markComplete(context, a, 1000));
        assertEquals(1000, ProjectSyncState.lastComplete(context, a));
        assertEquals(0, ProjectSyncState.lastComplete(context, b));
        assertTrue(ProjectSyncState.markComplete(context, b, 2000));
        assertEquals(1000, ProjectSyncState.lastComplete(context, a));
        assertEquals(2000, ProjectSyncState.lastComplete(context, b));
    }
    @Test public void canonicalAliasesReadThePersistedOwningProjectClock() {
        Context context = RuntimeEnvironment.getApplication();
        File path = new File(context.getFilesDir(), "project");
        assertTrue(ProjectSyncState.markComplete(context, path.getPath(), 1234));
        assertEquals(1234, ProjectSyncState.lastComplete(context, new File(path, "../project").getPath()));
    }
    @Test public void oldGlobalSuccessDoesNotMasqueradeAsVerifiedProjectCompletion() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(Constants.PREFERENCES, 0).edit()
                .putLong(SettingsConstants.KEY_PREF_LAST_SYNC_TIMESTAMP, 9876).commit();
        assertEquals(0, ProjectSyncState.lastComplete(context, "/unknown-project"));
        assertEquals(0, ProjectSyncState.lastComplete(context, null));
        assertFalse(ProjectSyncState.markComplete(context, null, 1234));
        assertFalse(ProjectSyncState.markComplete(context, "/unknown-project", 0));
        assertEquals(0, ProjectSyncState.lastComplete(context, "/unknown-project"));
    }
}

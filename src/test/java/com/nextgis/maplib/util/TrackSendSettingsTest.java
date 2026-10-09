package com.nextgis.maplib.util;

import android.content.SharedPreferences;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk={26,36})
public class TrackSendSettingsTest {
    private SharedPreferences preferences() {
        return RuntimeEnvironment.getApplication().getSharedPreferences("track-intent-test", 0);
    }
    @Test public void newInstallUploadsByDefaultBeforeSettingsAreOpened() {
        assertTrue(TrackSendSettings.isEnabled(preferences()));
    }
    @Test public void legacyRegistrationDisabledFlagIsEnabledOnce() {
        SharedPreferences p = preferences();
        p.edit().putBoolean(SettingsConstants.KEY_PREF_TRACK_SEND, false).commit();
        TrackSendSettings.migrateDefault(p);
        assertTrue(TrackSendSettings.isEnabled(p));
        assertTrue(p.getBoolean(TrackSendSettings.DEFAULT_MIGRATED, false));
    }
    @Test public void explicitOptOutSurvivesSubsequentStartup() {
        SharedPreferences p = preferences();
        TrackSendSettings.migrateDefault(p);
        p.edit().putBoolean(SettingsConstants.KEY_PREF_TRACK_SEND, false).commit();
        TrackSendSettings.migrateDefault(p);
        assertFalse(TrackSendSettings.isEnabled(p));
    }
}

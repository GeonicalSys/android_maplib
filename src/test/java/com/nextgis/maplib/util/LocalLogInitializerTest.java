package com.nextgis.maplib.util;

import android.app.Application;
import android.content.Context;
import com.hypertrack.hyperlog.HyperLog;
import com.hypertrack.hyperlog.LogFormat;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class, sdk={26,36})
public class LocalLogInitializerTest {
    @Test public void coldStartMigratesObsoleteEndpointWithoutLosingLocalLogsOrFormat() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        // Seed the real pinned library's database, then simulate its process-local cold state.
        HyperLog.initialize(context);
        HyperLog.setLogLevel(android.util.Log.VERBOSE);
        HyperLog.i("Synthetic", "Backlog retained across upgrade");
        Field executor = HyperLog.class.getDeclaredField("executorService");
        executor.setAccessible(true);
        ((ExecutorService) executor.get(null)).submit(() -> {}).get(5, TimeUnit.SECONDS);
        long count = HyperLog.getDeviceLogsCount();
        assertTrue(count > 0);
        HyperLog.setURL("https://127.0.0.1/nextgis-hyperlog-no-remote/");
        setField(HyperLog.class, "URL", null);
        setField(HyperLog.class, "mDeviceLogList", null);
        setField(HyperLog.class, "mLogFormat", null);
        setField(LocalLogInitializer.class, "initialized", false);

        LogFormat custom = new LogFormat(context);
        LocalLogInitializer.initialize(context, custom);
        assertNull(HyperLog.getURL());
        assertFalse(context.getSharedPreferences("HyperLog", 0).contains("URL"));
        assertTrue(HyperLog.getDeviceLogsAsStringList(false).stream()
                .anyMatch(line -> line.contains("Backlog retained across upgrade")));
        Field format = HyperLog.class.getDeclaredField("mLogFormat");
        format.setAccessible(true);
        assertSame(custom, format.get(null));
        LocalLogInitializer.initialize(context);
        assertSame(custom, format.get(null));
        assertNull(HyperLog.getURL());

        LogFormat replacement = new LogFormat(context);
        LocalLogInitializer.initialize(context, replacement);
        assertSame(replacement, format.get(null));
    }

    private static void setField(Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
}

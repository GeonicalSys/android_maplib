package com.nextgis.maplib.map;

import android.content.Context;
import com.nextgis.maplib.reliability.TestGISApplication;
import com.nextgis.maplib.util.Constants;
import com.nextgis.maplib.util.GeoConstants;
import java.io.File;
import java.util.ArrayList;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Real model/version/provider contracts; technical visibility IDs, no native/GPU acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = TestGISApplication.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class VectorLayerVisibilityEpochTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void onlyANewValidHideChangesTheVersionWithoutSendingANotification() throws Exception {
        try (Fixture f = fixture()) {
            long version = f.layer.renderContentVersion();
            int notifications = f.layer.notifications;
            f.layer.hideFeature(Constants.NOT_FOUND);
            assertEquals(version, f.layer.renderContentVersion());
            assertFalse(f.layer.isFeatureHidden(Constants.NOT_FOUND));
            f.layer.hideFeature(31);
            assertTrue(f.layer.isFeatureHidden(31));
            assertEquals(version + 1, f.layer.renderContentVersion());
            f.layer.hideFeature(31);
            assertEquals("Repeated edit activation is idempotent", version + 1, f.layer.renderContentVersion());
            assertEquals(notifications, f.layer.notifications);
            f.layer.showFeature(31);
            assertFalse("One show fully undoes repeated hide", f.layer.isFeatureHidden(31));
            assertEquals(version + 2, f.layer.renderContentVersion());
            assertEquals(notifications + 1, f.layer.notifications);
        }
    }

    @Test public void showingAnAlreadyShownEditorFeatureDoesNotInvalidateTheProviderWhileAnotherIdIsHidden() throws Exception {
        try (Fixture f = fixture()) {
            f.layer.hideFeature(31); // A separate temporary delete/undo prompt.
            f.layer.hideFeature(62); // The current Canvas/native editor feature.
            try (SqliteVectorTileProvider editing = new SqliteVectorTileProvider(f.layer, 1, () -> 1L, null)) {
                assertTrue(editing.isCurrent());
                f.layer.showFeature(62); // Actual editor finish, before capturing the next provider.
                assertFalse(editing.isCurrent());
                long finishedVersion = f.layer.renderContentVersion();
                int notifications = f.layer.notifications;
                try (SqliteVectorTileProvider finished = new SqliteVectorTileProvider(f.layer, 2, () -> 2L, null)) {
                    assertTrue(finished.isCurrent());
                    f.layer.showFeature(62); // Later MODE_HIGHLIGHT/MODE_NONE callback.
                    f.layer.showFeature(99); // A row that was never hidden.
                    f.layer.showFeature(Constants.NOT_FOUND);
                    assertEquals(finishedVersion, f.layer.renderContentVersion());
                    assertEquals(notifications, f.layer.notifications);
                    assertTrue("Another hidden object must remain hidden", f.layer.isFeatureHidden(31));
                    assertFalse(f.layer.isFeatureHidden(62));
                    assertTrue("A no-op show must not retire a freshly rebuilt provider", finished.isCurrent());
                    f.layer.showFeature(31); // Real undo still invalidates and notifies normally.
                    assertFalse(finished.isCurrent());
                    assertEquals(finishedVersion + 1, f.layer.renderContentVersion());
                    assertEquals(notifications + 1, f.layer.notifications);
                }
            }
        }
    }

    @Test public void canvasHideInvalidatesTheOldProviderAndReplacementKeepsTheRealModelMask() throws Exception {
        try (Fixture f = fixture();
             SqliteVectorTileProvider beforeEdit = new SqliteVectorTileProvider(f.layer, 10, () -> 10L, null)) {
            assertTrue(beforeEdit.isCurrent());
            f.layer.hideFeature(31);
            assertFalse("Actual edit-start mask change invalidates old content", beforeEdit.isCurrent());
            try (SqliteVectorTileProvider afterHide = new SqliteVectorTileProvider(f.layer, 11, () -> 11L, null)) {
                assertTrue(afterHide.isCurrent());
                assertTrue("Rebinding must not undo the Canvas edit mask", f.layer.isFeatureHidden(31));
                f.layer.hideFeature(31);
                assertTrue("Duplicate activation does not invalidate replacement", afterHide.isCurrent());
                f.layer.showFeature(31);
                assertFalse(afterHide.isCurrent());
            }
            try (SqliteVectorTileProvider afterFinish = new SqliteVectorTileProvider(f.layer, 12, () -> 12L, null)) {
                assertTrue(afterFinish.isCurrent());
                assertFalse(f.layer.isFeatureHidden(31));
            }
        }
    }

    @Test public void showAllOnlyNotifiesAndChangesVersionWhenAMaskActuallyExists() throws Exception {
        try (Fixture f = fixture()) {
            long version = f.layer.renderContentVersion(); int notifications = f.layer.notifications;
            f.layer.showAllFeatures();
            assertEquals(version, f.layer.renderContentVersion());
            f.layer.hideFeature(31); f.layer.hideFeature(62);
            f.layer.showAllFeatures();
            assertFalse(f.layer.isFeatureHidden(31)); assertFalse(f.layer.isFeatureHidden(62));
            assertEquals(version + 3, f.layer.renderContentVersion());
            assertEquals(notifications + 1, f.layer.notifications);
            f.layer.showAllFeatures();
            assertEquals(version + 3, f.layer.renderContentVersion());
            assertEquals(notifications + 1, f.layer.notifications);
        }
    }

    private Fixture fixture() throws Exception { return new Fixture(folder.newFolder()); }

    private static final class TestMap extends MapContentProviderHelper {
        TestMap(Context context, File path) { super(context, path, null, false); }
        void dispose() { closeSyncWorkspace(); }
    }

    private static final class CountingLayer extends VectorLayer {
        int notifications;
        CountingLayer(Context context, File path) { super(context, path); }
        @Override public void notifyLayerChanged() { notifications++; super.notifyLayerChanged(); }
    }

    private static final class Fixture implements AutoCloseable {
        final TestMap map; final CountingLayer layer;
        Fixture(File root) throws Exception {
            Context context = RuntimeEnvironment.getApplication();
            map = new TestMap(context, new File(root, "visibility.ngm"));
            layer = new CountingLayer(context, new File(root, "technical_visibility_points"));
            map.addLayer(layer);
            layer.create(GeoConstants.GTPoint, new ArrayList<>());
        }
        @Override public void close() { map.dispose(); }
    }
}

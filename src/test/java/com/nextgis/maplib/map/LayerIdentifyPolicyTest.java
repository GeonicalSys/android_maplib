package com.nextgis.maplib.map;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import com.nextgis.maplib.reliability.TestGISApplication;
import java.io.File;
import java.util.UUID;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = TestGISApplication.class)
public class LayerIdentifyPolicyTest {
    @Test
    public void visibleClassicLayerIsIncluded() {
        assertTrue(LayerIdentifyPolicy.shouldInclude(new PolicyLayer(true, false)));
    }

    @Test
    public void hiddenClassicLayerIsExcluded() {
        assertFalse(LayerIdentifyPolicy.shouldInclude(new PolicyLayer(false, false)));
    }

    @Test
    public void legacyRenderModeCannotMakeAHiddenLayerSelectable() {
        NGWVectorLayer layer = legacyLayer(false);
        assertFalse(LayerIdentifyPolicy.shouldInclude(layer));
    }

    @Test
    public void visibleLegacyConfiguredLayerIsIncluded() {
        assertTrue(LayerIdentifyPolicy.shouldInclude(legacyLayer(true)));
    }

    @Test
    public void missingLayerIsExcluded() {
        assertFalse(LayerIdentifyPolicy.shouldInclude(null));
    }

    private static NGWVectorLayer legacyLayer(boolean visible) {
        return new PolicyLayer(visible, true);
    }

    private static final class PolicyLayer extends NGWVectorLayer {
        private final boolean visible;
        private final LayerOriginMetadata origin;
        PolicyLayer(boolean visible, boolean legacy) {
            super(RuntimeEnvironment.getApplication(), new File(
                    RuntimeEnvironment.getApplication().getCacheDir(), "identify-" + UUID.randomUUID()));
            this.visible = visible;
            origin = legacy ? LayerOriginMetadata.manualNgwLayer(
                    0, LayerOriginMetadata.RENDER_MODE_LOCAL_VECTOR_TILES) : null;
        }
        @Override public boolean isVisible() { return visible; }
        @Override public LayerOriginMetadata getLayerOriginMetadata() { return origin; }
    }
}

package com.nextgis.maplib.map;

import com.nextgis.maplib.api.INGWLayer;
import com.nextgis.maplib.reliability.TestGISApplication;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = TestGISApplication.class)
public class MapContentProviderHelperTest {
    @Test
    public void databaseStaysBesideTheMapThatOwnsIt() {
        File workspaceA = new File("projects/project_a");
        File workspaceB = new File("projects/project_b");

        File databaseA = MapContentProviderHelper.resolveDatabaseFile(
                new File(workspaceA, "collector.ngm"),
                new File(workspaceB, "layers.db"));

        assertEquals(new File(workspaceA, "layers"), databaseA);
    }

    @Test
    public void accountSelectionLeavesUnboundLayersInTheMap() {
        var context = RuntimeEnvironment.getApplication();
        var map = new LayerGroup(context, new File("map"), null);
        var group = new LayerGroup(context, new File("map/group"), null);
        map.addLayer(group);
        map.addLayer(new NGWVectorLayer(context, new File("map/unbound")));
        var missingFromConfig = new NGWVectorLayer(context, new File("map/group/no-account"));
        missingFromConfig.setAccountName("");
        group.addLayer(missingFromConfig);
        var assigned = new NGWVectorLayer(context, new File("map/group/assigned"));
        assigned.setAccountName("field-account");
        group.addLayer(assigned);

        List<INGWLayer> selected = new ArrayList<>();
        MapContentProviderHelper.getLayersByAccount(map, "field-account", selected);
        assertEquals(List.of(assigned), selected);
        assertEquals(2, map.getLayerCount());
        assertEquals(2, group.getLayerCount());

        selected.clear();
        MapContentProviderHelper.getLayersByAccount(map, null, selected);
        MapContentProviderHelper.getLayersByAccount(map, "", selected);
        assertTrue(selected.isEmpty());
    }
}

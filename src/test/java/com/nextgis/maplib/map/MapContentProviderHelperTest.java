package com.nextgis.maplib.map;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;

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
}

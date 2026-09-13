package com.nextgis.maplib.util;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class SharedUnderlayCatalogTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private JSONObject config() throws Exception { return new JSONObject("{\"name\":\"Hill\",\"type\":32,\"visible\":false,\"custom\":42}"); }
    private File stage(SharedUnderlayCatalog catalog) throws Exception {
        File stage = catalog.createStage();
        Files.write(new File(stage, "payload/tile").toPath(), new byte[]{1, 2, 3});
        return stage;
    }
    @Test public void dedupReusesPayloadAndRemembersDebugIdentity() throws Exception {
        SharedUnderlayCatalog catalog = new SharedUnderlayCatalog(temp.newFolder("catalog"));
        SharedUnderlayCatalog.Asset first = catalog.publish(stage(catalog), "A", "mbtiles", "hash", 3, config(), null);
        SharedUnderlayCatalog.Asset second = catalog.publish(stage(catalog), "B", "mbtiles", "hash", 3, config(), "debug:layer");
        assertEquals(first.id, second.id); assertEquals(1, catalog.list().size());
        assertEquals(first.id, catalog.find(null, "debug:layer").id);
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(new File(catalog.payload(first.id), "tile").toPath()));
    }
    @Test public void interruptedPublishRecoversManifestWithoutCopying() throws Exception {
        File root = temp.newFolder("catalog");
        SharedUnderlayCatalog crashing = new SharedUnderlayCatalog(root, step -> { throw new IOException("power loss"); });
        try { crashing.publish(stage(crashing), "A", "mbtiles", "hash", 3, config(), null); fail(); }
        catch (IOException expected) { }
        SharedUnderlayCatalog recovered = new SharedUnderlayCatalog(root);
        recovered.recover(); recovered.recover();
        assertEquals(1, recovered.list().size()); assertNotNull(recovered.find("hash", null));
    }
    @Test public void everyMigrationCheckpointCanResumeWithoutLosingOriginalMetadata() throws Exception {
        for (String stop : new String[]{"migration-journal", "migration-moved", "migration-linked"}) {
            File root = temp.newFolder(); File source = new File(root, "project/layer");
            UnderlayFiles.writeJson(new File(source, "config.json"), config());
            Files.write(new File(source, "tile").toPath(), new byte[]{9});
            File catalogRoot = new File(root, "catalog");
            SharedUnderlayCatalog crashing = new SharedUnderlayCatalog(catalogRoot, step -> { if (stop.equals(step)) throw new IOException("power loss"); });
            try { crashing.migrateLayer(source, config(), "ngrc_tiles", "hash", 1); fail(); }
            catch (IOException expected) { }
            SharedUnderlayCatalog recovered = new SharedUnderlayCatalog(catalogRoot);
            recovered.recover(); recovered.recover();
            JSONObject thin = UnderlayFiles.readJson(new File(source, "config.json"));
            assertEquals(42, thin.getInt("custom")); assertFalse(thin.getBoolean("visible"));
            assertEquals("Hill", thin.getString("name")); assertFalse(new File(source, "tile").exists());
            assertArrayEquals(new byte[]{9}, Files.readAllBytes(new File(recovered.payload(thin.getString(SharedUnderlayCatalog.LAYER_KEY)), "tile").toPath()));
            assertEquals(1, recovered.list().size());
        }
    }
    @Test public void duplicateMoveRetainsOriginalUntilLinkIsDurable() throws Exception {
        File root = temp.newFolder(); SharedUnderlayCatalog catalog = new SharedUnderlayCatalog(new File(root, "catalog"));
        SharedUnderlayCatalog.Asset first = catalog.publish(stage(catalog), "A", "ngrc_tiles", "hash", 3, config(), null);
        File source = new File(root, "project/layer"); UnderlayFiles.writeJson(new File(source, "config.json"), config());
        Files.write(new File(source, "tile").toPath(), new byte[]{1, 2, 3});
        assertEquals(first.id, catalog.migrateLayer(source, config(), "ngrc_tiles", "hash", 3).id);
        catalog.cleanMigratedDuplicates(); assertEquals(1, catalog.list().size());
        assertEquals(first.id, UnderlayFiles.readJson(new File(source, "config.json")).getString(SharedUnderlayCatalog.LAYER_KEY));
    }
    @Test public void unlinkNestedClosedProjectPreservesOrderAndUnknownFields() throws Exception {
        File map = new File(temp.newFolder(), "map.ngm");
        UnderlayFiles.writeJson(map, new JSONObject("{\"keep\":7,\"layers\":[{\"path\":\"osm\"},{\"path\":\"group\"},{\"path\":\"missing\"}]}"));
        File group = new File(map.getParentFile(), "group/config.json");
        UnderlayFiles.writeJson(group, new JSONObject("{\"keep\":8,\"layers\":[{\"path\":\"shared\"},{\"path\":\"vector\"}]}"));
        UnderlayFiles.writeJson(new File(group.getParentFile(), "shared/config.json"), config().put(SharedUnderlayCatalog.LAYER_KEY, "asset"));
        assertTrue(UnderlayWorkspaceIndex.uses(map, "asset"));
        UnderlayWorkspaceIndex.unlink(map, "asset"); UnderlayWorkspaceIndex.unlink(map, "asset");
        assertFalse(UnderlayWorkspaceIndex.uses(map, "asset"));
        assertEquals(3, UnderlayFiles.readJson(map).getJSONArray("layers").length());
        JSONObject changed = UnderlayFiles.readJson(group);
        assertEquals(8, changed.getInt("keep"));
        assertEquals("vector", changed.getJSONArray("layers").getJSONObject(0).getString("path"));
    }
    @Test public void traversalCannotTouchAnotherWorkspace() throws Exception {
        File map = new File(temp.newFolder(), "map.ngm");
        UnderlayFiles.writeJson(map, new JSONObject("{\"layers\":[{\"path\":\"../other\"}]}"));
        try { UnderlayWorkspaceIndex.layers(map); fail(); } catch (IOException expected) { }
    }
}

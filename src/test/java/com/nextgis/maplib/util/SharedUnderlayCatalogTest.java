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

    private JSONObject rasterConfig(boolean mbtiles) throws Exception {
        JSONObject result = config().put("tms_type", mbtiles ? 3 : 2)
                .put("min_level", 8).put("max_level", 19)
                .put("bbox_minx", 10).put("bbox_miny", 20).put("bbox_maxx", 30).put("bbox_maxy", 40);
        if (!mbtiles) result.put("levels", new org.json.JSONArray("[{\"level\":12,\"bbox_minx\":1,\"bbox_maxx\":2,\"bbox_miny\":3,\"bbox_maxy\":4}]"));
        return result;
    }

    private void assertReference(JSONObject linked, SharedUnderlayCatalog.Asset target,
                                 JSONObject original) throws Exception {
        JSONObject raster = target.layerConfig();
        assertEquals(target.id, linked.getString(SharedUnderlayCatalog.LAYER_KEY));
        assertEquals(raster.getInt("tms_type"), linked.getInt("tms_type"));
        assertEquals(raster.opt("levels") == null ? null : raster.get("levels").toString(),
                linked.opt("levels") == null ? null : linked.get("levels").toString());
        for (String key : new String[]{"bbox_minx", "bbox_miny", "bbox_maxx", "bbox_maxy"})
            assertEquals(raster.get(key), linked.get(key));
        for (String key : new String[]{"name", "visible", "custom", "min_level", "max_level"})
            assertEquals(original.get(key), linked.get(key));
    }

    @Test public void crossFormatMigrationAndRecoveryUseTargetRasterAndKeepProjectSettings() throws Exception {
        for (boolean targetMbtiles : new boolean[]{true, false}) {
            for (String stop : new String[]{"migration-journal", "migration-moved", "migration-linked"}) {
                File root = temp.newFolder(), catalogRoot = new File(root, "catalog");
                SharedUnderlayCatalog catalog = new SharedUnderlayCatalog(catalogRoot);
                SharedUnderlayCatalog.Asset target = catalog.publish(stage(catalog), "Catalog name",
                        targetMbtiles ? "mbtiles" : "ngrc_tiles", "same-archive", 3, rasterConfig(targetMbtiles), null);
                JSONObject original = rasterConfig(!targetMbtiles).put("name", "Project name")
                        .put("min_level", 5).put("max_level", 21).put("bbox_minx", 999);
                File layer = new File(root, "project/layer");
                UnderlayFiles.writeJson(new File(layer, "config.json"), original);
                Files.write(new File(layer, "tile").toPath(), new byte[]{7});
                SharedUnderlayCatalog crashing = new SharedUnderlayCatalog(catalogRoot, step -> {
                    if (stop.equals(step)) throw new IOException("power loss");
                });
                try { crashing.migrateLayer(layer, original, targetMbtiles ? "ngrc_tiles" : "mbtiles", "same-archive", 1); fail(); }
                catch (IOException expected) { }
                catalog.recover(); catalog.recover();
                assertReference(UnderlayFiles.readJson(new File(layer, "config.json")), target, original);
                catalog.cleanMigratedDuplicates();
                assertEquals(1, catalog.list().size());
                assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(new File(catalog.payload(target.id), "tile").toPath()));
            }
        }
    }

    @Test public void crossFormatRedirectResumesAcrossClosedProjects() throws Exception {
        for (boolean targetMbtiles : new boolean[]{true, false}) {
            File root = temp.newFolder(), catalogRoot = new File(root, "catalog");
            SharedUnderlayCatalog catalog = new SharedUnderlayCatalog(catalogRoot);
            SharedUnderlayCatalog.Asset target = catalog.publish(stage(catalog), "Target",
                    targetMbtiles ? "mbtiles" : "ngrc_tiles", "same-archive", 3, rasterConfig(targetMbtiles), null);
            // A recovered older catalog can contain duplicate hashes before its redirect pass.
            SharedUnderlayCatalog.Asset duplicate = catalog.publish(stage(catalog), "Duplicate",
                    targetMbtiles ? "ngrc_tiles" : "mbtiles", "pending-hash", 3, rasterConfig(!targetMbtiles), "debug:old");
            File index = new File(catalogRoot, "catalog.json");
            JSONObject rows = UnderlayFiles.readJson(index);
            rows.getJSONArray("assets").getJSONObject(1).put("content_sha256", "same-archive");
            UnderlayFiles.writeJson(index, rows);
            java.util.List<File> maps = new java.util.ArrayList<>();
            JSONObject original = rasterConfig(!targetMbtiles).put("name", "Project name")
                    .put("min_level", 4).put("max_level", 20).put(SharedUnderlayCatalog.LAYER_KEY, duplicate.id);
            for (int i = 0; i < 2; i++) {
                File map = new File(root, "project" + i + "/map.ngm"); maps.add(map);
                UnderlayFiles.writeJson(map, new JSONObject("{\"layers\":[{\"path\":\"osm\"},{\"path\":\"layer\"},{\"path\":\"vector\"}]}"));
                UnderlayFiles.writeJson(new File(map.getParentFile(), "layer/config.json"), original);
            }
            SharedUnderlayCatalog crashing = new SharedUnderlayCatalog(catalogRoot, step -> {
                if ("redirect-linked".equals(step)) throw new IOException("power loss");
            });
            try { crashing.mergeDuplicates(maps); fail(); } catch (IOException expected) { }
            catalog.mergeDuplicates(maps); catalog.mergeDuplicates(maps);
            for (File map : maps) {
                assertReference(UnderlayFiles.readJson(new File(map.getParentFile(), "layer/config.json")), target, original);
                assertEquals("layer", UnderlayFiles.readJson(map).getJSONArray("layers").getJSONObject(1).getString("path"));
                assertEquals(3, UnderlayFiles.readJson(map).getJSONArray("layers").length());
            }
            assertEquals(target.id, catalog.find(null, "debug:old").id);
            catalog.cleanMigratedDuplicates(); assertEquals(1, catalog.list().size());
        }
    }

    @Test public void repairsPreviouslyMislinkedFormatOnceWithoutChangingProjectSettings() throws Exception {
        File root = temp.newFolder();
        SharedUnderlayCatalog catalog = new SharedUnderlayCatalog(new File(root, "catalog"));
        SharedUnderlayCatalog.Asset target = catalog.publish(stage(catalog), "Target", "mbtiles", "hash", 3, rasterConfig(true), null);
        File layer = new File(root, "project/layer");
        JSONObject broken = rasterConfig(false).put("min_level", 4).put("name", "Custom name")
                .put(SharedUnderlayCatalog.LAYER_KEY, target.id);
        UnderlayFiles.writeJson(new File(layer, "config.json"), broken);
        JSONObject repaired = catalog.repairReference(layer, broken);
        assertReference(repaired, target, broken);
        assertReference(UnderlayFiles.readJson(new File(layer, "config.json")), target, broken);
        assertSame(repaired, catalog.repairReference(layer, repaired));
        assertEquals(2, broken.getInt("tms_type"));
    }

    @Test public void unavailableCanonicalPayloadDoesNotMoveOrRewriteOriginal() throws Exception {
        File root = temp.newFolder(), catalogRoot = new File(root, "catalog");
        SharedUnderlayCatalog catalog = new SharedUnderlayCatalog(catalogRoot);
        SharedUnderlayCatalog.Asset target = catalog.publish(stage(catalog), "Target", "mbtiles", "hash", 3, rasterConfig(true), null);
        File source = new File(root, "project/layer");
        JSONObject original = rasterConfig(false);
        UnderlayFiles.writeJson(new File(source, "config.json"), original);
        Files.write(new File(source, "tile").toPath(), new byte[]{7});
        SharedUnderlayCatalog crashing = new SharedUnderlayCatalog(catalogRoot, step -> {
            if ("migration-journal".equals(step)) throw new IOException("power loss");
        });
        try { crashing.migrateLayer(source, original, "ngrc_tiles", "hash", 1); fail(); }
        catch (IOException expected) { }
        assertTrue(catalog.payload(target.id).renameTo(new File(root, "unavailable-payload")));
        try { catalog.recover(); fail(); } catch (IOException expected) { }
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(new File(source, "tile").toPath()));
        assertFalse(UnderlayFiles.readJson(new File(source, "config.json")).has(SharedUnderlayCatalog.LAYER_KEY));
        JSONObject broken = original.put(SharedUnderlayCatalog.LAYER_KEY, target.id);
        assertSame(broken, catalog.repairReference(source, broken));
    }
}

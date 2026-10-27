package com.nextgis.maplib.datasource;

import com.nextgis.maplib.api.IGeometryCacheItem;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class GeometryRTreePersistenceTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();

    /** Independent legacy-format fixture, spanning multiple input buffers and node levels. */
    private File legacyTree() throws Exception {
        File file = files.newFile("legacy.cache");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            out.writeInt(1000); out.writeInt(2); out.writeInt(6000);
            node(out, true, false, 0, 60000, 6);
            for (int group = 0; group < 6; group++) {
                node(out, true, true, group * 10000, group * 10000 + 9991, 1000);
                for (int i = 0; i < 1000; i++) {
                    int index = group * 1000 + i;
                    node(out, false, true, index * 10, index * 10 + 1, 0);
                    out.writeLong(10_000_000_000L + index);
                }
            }
        }
        return file;
    }

    private void node(DataOutputStream out, boolean node, boolean leaf,
                      double min, double max, int children) throws Exception {
        out.writeBoolean(node); out.writeBoolean(leaf);
        out.writeDouble(min); out.writeDouble(min); out.writeDouble(max); out.writeDouble(max);
        out.writeInt(children);
    }

    @Test public void bufferedLoadPreservesLegacyIdsCoordinatesAndSearchWithoutRewritingInput() throws Exception {
        File input = legacyTree();
        byte[] before = Files.readAllBytes(input.toPath());
        GeometryRTree tree = new GeometryRTree(); tree.load(input);
        assertEquals(6000, tree.size()); assertEquals(6000, tree.getAll().size());
        for (IGeometryCacheItem item : tree.getAll()) {
            long index = item.getFeatureId() - 10_000_000_000L;
            assertEquals(index * 10.0, item.getEnvelope().getMinX(), 0);
            assertEquals(index * 10.0 + 1, item.getEnvelope().getMaxY(), 0);
        }
        List<IGeometryCacheItem> found = tree.search(new GeoEnvelope(12_340, 12_341, 12_340, 12_341));
        assertEquals(1, found.size()); assertEquals(10_000_001_234L, found.get(0).getFeatureId());
        assertArrayEquals(before, Files.readAllBytes(input.toPath()));
        File saved = files.newFile("roundtrip.cache"); tree.save(saved);
        assertArrayEquals("The persisted binary format is unchanged", before, Files.readAllBytes(saved.toPath()));
        GeometryRTree reloaded = new GeometryRTree(); reloaded.load(saved);
        assertEquals(tree.size(), reloaded.size());
        assertEquals(found.get(0).getFeatureId(), reloaded.search(new GeoEnvelope(12_340, 12_341, 12_340, 12_341)).get(0).getFeatureId());
    }
}

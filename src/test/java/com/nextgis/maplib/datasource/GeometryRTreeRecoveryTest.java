package com.nextgis.maplib.datasource;

import com.nextgis.maplib.api.IGeometryCacheItem;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Actual RTree and independent legacy binary witnesses; no Android/map/SQL/socket/business data. */
public class GeometryRTreeRecoveryTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();
    private static final long FIRST_ID = 10_000_000_000L;

    /** Same technical envelope/ID sequence as the existing 6000-entry legacy-format test. */
    private File legacy(String name, int declared, int actual) throws Exception {
        File file = files.newFile(name);
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            out.writeInt(1000); out.writeInt(2); out.writeInt(declared);
            node(out, true, true, 0, 9991, actual);
            for (int i = 0; i < actual; i++) {
                node(out, false, true, i * 10, i * 10 + 1, 0);
                out.writeLong(FIRST_ID + i);
            }
        }
        return file;
    }

    private void node(DataOutputStream out, boolean isNode, boolean leaf,
                      double min, double max, int children) throws Exception {
        out.writeBoolean(isNode); out.writeBoolean(leaf);
        out.writeDouble(min); out.writeDouble(min); out.writeDouble(max); out.writeDouble(max);
        out.writeInt(children);
    }

    private GeometryRTree populated() {
        GeometryRTree tree = new GeometryRTree();
        tree.addItem(FIRST_ID, new GeoEnvelope(0, 1, 0, 1));
        tree.addItem(FIRST_ID + 1, new GeoEnvelope(10, 11, 10, 11));
        return tree;
    }

    private void empty(GeometryRTree tree) {
        assertEquals("Declared cardinality must describe actual nodes", 0, tree.size());
        assertTrue(tree.getAll().isEmpty());
        assertTrue(tree.search(new GeoEnvelope(-1, 10000, -1, 10000)).isEmpty());
    }

    /** Independent stream reader counts Entry records, not GeometryRTree.size/getAll. */
    private int entries(DataInputStream in) throws Exception {
        boolean isNode = in.readBoolean();
        in.readBoolean();
        for (int i = 0; i < 4; i++) in.readDouble();
        int children = in.readInt();
        assertTrue(children >= 0);
        int result = 0;
        for (int i = 0; i < children; i++) result += entries(in);
        if (!isNode) { in.readLong(); result++; }
        return result;
    }

    private void persistedCardinality(File file, int expected) throws Exception {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            in.readInt(); in.readInt();
            assertEquals(expected, in.readInt());
            assertEquals(expected, entries(in));
            assertEquals("No unparsed trailing content in technical witness", -1, in.read());
        }
    }

    @Test public void clearResetsSizeActualQueriesAndNextInsertCardinality() {
        GeometryRTree tree = populated();
        assertEquals(2, tree.size());
        tree.clear(); empty(tree);
        tree.addItem(FIRST_ID + 2, new GeoEnvelope(20, 21, 20, 21));
        assertEquals(1, tree.size());
        assertEquals(1, tree.getAll().size());
        assertEquals(FIRST_ID + 2, tree.getAll().get(0).getFeatureId());
    }

    @Test public void clearOfLoadedTreePersistsEmptyToTheSamePath() throws Exception {
        File file = legacy("loaded.cache", 2, 2);
        GeometryRTree tree = new GeometryRTree(); tree.load(file);
        tree.clear(); tree.save(file);
        persistedCardinality(file, 0);
        GeometryRTree fresh = new GeometryRTree(); fresh.load(file); empty(fresh);
    }

    @Test public void missingLoadResetsPreviouslyPopulatedCacheWithoutCreatingAFile() {
        File missing = new File(files.getRoot(), "absent.cache");
        assertFalse(missing.exists());
        GeometryRTree tree = populated(); tree.load(missing); empty(tree);
        assertFalse(missing.exists());
    }

    @Test public void positiveHeaderWithEmptyRootIsRejectedWithoutRewritingInput() throws Exception {
        File file = legacy("historical-empty-root.cache", 2, 0);
        byte[] original = Files.readAllBytes(file.toPath());
        GeometryRTree tree = new GeometryRTree(); tree.load(file); empty(tree);
        assertArrayEquals(original, Files.readAllBytes(file.toPath()));
        GeometryRTree afterRestart = new GeometryRTree(); afterRestart.load(file); empty(afterRestart);
    }

    @Test public void headerAboveActualLeafCardinalityCannotCertifyAPartialTree() throws Exception {
        File file = legacy("overstated.cache", 3, 2);
        byte[] original = Files.readAllBytes(file.toPath());
        GeometryRTree tree = populated(); tree.load(file); empty(tree);
        assertArrayEquals(original, Files.readAllBytes(file.toPath()));
    }

    @Test public void headerBelowActualLeafCardinalityCannotCertifyAPartialTree() throws Exception {
        File file = legacy("understated.cache", 1, 2);
        byte[] original = Files.readAllBytes(file.toPath());
        GeometryRTree tree = populated(); tree.load(file); empty(tree);
        assertArrayEquals(original, Files.readAllBytes(file.toPath()));
    }

    @Test public void truncatedLoadDiscardsHeaderAndAnyAlreadyParsedPrefix() throws Exception {
        File file = legacy("truncated.cache", 2, 2);
        byte[] complete = Files.readAllBytes(file.toPath());
        Files.write(file.toPath(), Arrays.copyOf(complete, complete.length - 5));
        byte[] damaged = Files.readAllBytes(file.toPath());
        GeometryRTree tree = populated(); tree.load(file); empty(tree);
        assertArrayEquals(damaged, Files.readAllBytes(file.toPath()));
    }

    @Test public void validLegacyLoadKeepsInputAndSamePathSaveUnchanged() throws Exception {
        File file = legacy("valid.cache", 2, 2);
        byte[] original = Files.readAllBytes(file.toPath());
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(1_600_000_000_000L));
        FileTime stamp = Files.getLastModifiedTime(file.toPath());
        GeometryRTree tree = new GeometryRTree(); tree.load(file);
        assertEquals(2, tree.size()); assertEquals(2, tree.getAll().size());
        List<IGeometryCacheItem> found = tree.search(new GeoEnvelope(10, 11, 10, 11));
        assertEquals(1, found.size()); assertEquals(FIRST_ID + 1, found.get(0).getFeatureId());
        tree.save(file);
        assertArrayEquals(original, Files.readAllBytes(file.toPath()));
        assertEquals("A successful unchanged load stays clean", stamp, Files.getLastModifiedTime(file.toPath()));
        File other = files.newFile("roundtrip.cache"); tree.save(other);
        assertArrayEquals("Legacy wire bytes remain exact", original, Files.readAllBytes(other.toPath()));
    }

    @Test public void validEmptyLegacyTreeRemainsAnUnmodifiedEmptySuccess() throws Exception {
        File file = legacy("valid-empty.cache", 0, 0);
        byte[] original = Files.readAllBytes(file.toPath());
        GeometryRTree tree = populated(); tree.load(file); empty(tree);
        assertArrayEquals(original, Files.readAllBytes(file.toPath()));
    }

    @Test public void corruptThenValidReloadReplacesFailureWithOnlyTheCurrentValidTree() throws Exception {
        File invalid = legacy("invalid-first.cache", 2, 0);
        File valid = legacy("valid-second.cache", 1, 1);
        GeometryRTree tree = populated(); tree.load(invalid); empty(tree);
        tree.load(valid);
        assertEquals(1, tree.size()); assertEquals(1, tree.getAll().size());
        assertEquals(FIRST_ID, tree.getAll().get(0).getFeatureId());
        persistedCardinality(valid, 1);
    }
    @Test public void wrongChildKindsCannotLeaveHiddenNodesBehindAZeroHeader() throws Exception {
        for (boolean leafRoot : new boolean[]{false, true}) {
            File file = files.newFile(leafRoot ? "node-under-leaf.cache" : "entry-under-internal.cache");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
                out.writeInt(8); out.writeInt(2); out.writeInt(0);
                node(out, true, leafRoot, 0, 11, 1);
                if (leafRoot) {
                    node(out, true, true, 0, 1, 1);
                    node(out, false, true, 0, 1, 0); out.writeLong(FIRST_ID);
                } else {
                    node(out, false, true, 0, 1, 0); out.writeLong(FIRST_ID);
                }
            }
            byte[] original = Files.readAllBytes(file.toPath());
            GeometryRTree tree = populated(); tree.load(file); empty(tree);
            assertEquals("Malformed topology must be discarded, not hidden by a zero header", 0,
                    tree.getRoot().size());
            assertArrayEquals(original, Files.readAllBytes(file.toPath()));
        }
    }

}

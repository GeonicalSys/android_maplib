package com.nextgis.maplib.map;

import com.nextgis.maplib.reliability.TestGISApplication;
import com.nextgis.maplib.util.NgwSyncIo;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application=TestGISApplication.class, sdk={26,36})
public class NgwFullSnapshotIndexTest {
    private Path directory;
    @Before public void setup() throws Exception { directory=Files.createTempDirectory("rel03-index-"); }
    @After public void cleanup() throws Exception {
        try (Stream<Path> children=Files.list(directory)) { assertEquals("Index must release its files",0,children.count()); }
        Files.delete(directory);
    }
    @Test public void largeDistinctRemoteMembershipPreservesInt64AndDoesNotCollectIds() throws Exception {
        long first=9007199254740992L;
        try (NgwFullSnapshotIndex index=new NgwFullSnapshotIndex(directory.toFile())) {
            for (int i=0;i<12000;i++) index.mark(first+i,NgwFullSnapshotIndex.REMOTE);
            assertEquals(12000,index.ids(NgwFullSnapshotIndex.REMOTE).size());
            long expected=first;
            for (long value:index.ids(NgwFullSnapshotIndex.REMOTE)) assertEquals(expected++,value);
            assertEquals(first+12000,expected);
            assertFalse(index.contains(first-1,NgwFullSnapshotIndex.REMOTE));
        }
    }
    @Test public void duplicateAndInvalidRemoteIdsKeepIndependentBackupDeleteAndMissingSets() throws Exception {
        try (NgwFullSnapshotIndex index=new NgwFullSnapshotIndex(directory.toFile())) {
            index.mark(4,NgwFullSnapshotIndex.REMOTE);
            index.mark(4,NgwFullSnapshotIndex.REMOTE|NgwFullSnapshotIndex.MISSING_INVALID);
            index.mark(9,NgwFullSnapshotIndex.DELETE|NgwFullSnapshotIndex.DESTRUCTIVE);
            index.mark(12,NgwFullSnapshotIndex.REMOTE|NgwFullSnapshotIndex.DESTRUCTIVE);
            assertEquals(Arrays.asList(4L,12L),new ArrayList<>(index.ids(NgwFullSnapshotIndex.REMOTE)));
            assertEquals(Arrays.asList(9L,12L),new ArrayList<>(index.ids(NgwFullSnapshotIndex.DESTRUCTIVE)));
            assertEquals(Collections.singletonList(4L),new ArrayList<>(index.ids(NgwFullSnapshotIndex.MISSING_INVALID)));
            index.mark(9,NgwFullSnapshotIndex.CLEANUP);
            assertEquals(Collections.singletonList(9L),new ArrayList<>(index.ids(NgwFullSnapshotIndex.CLEANUP)));
            assertEquals(2,index.count(NgwFullSnapshotIndex.REMOTE));
        }
    }
    @Test public void closingAnEarlyAbortedIterationReleasesDatabaseAndJournal() throws Exception {
        NgwFullSnapshotIndex index=new NgwFullSnapshotIndex(directory.toFile());
        index.mark(1,NgwFullSnapshotIndex.DELETE); index.mark(2,NgwFullSnapshotIndex.DELETE);
        File file=index.file();
        assertEquals(Long.valueOf(1),index.ids(NgwFullSnapshotIndex.DELETE).iterator().next());
        index.close(); assertFalse(file.exists());
    }
    @Test public void interruptedPlanningDoesNotLeaveAnIndexForTheNextAttempt() throws Exception {
        try (NgwSyncIo.Session session=NgwSyncIo.beginSession();
             NgwFullSnapshotIndex index=new NgwFullSnapshotIndex(directory.toFile())) {
            index.mark(1,NgwFullSnapshotIndex.REMOTE); NgwSyncIo.requestCancellation();
            try { index.mark(2,NgwFullSnapshotIndex.REMOTE); fail(); }
            catch (InterruptedIOException expected) { }
            assertEquals(1,index.count(NgwFullSnapshotIndex.REMOTE));
        }
    }
}

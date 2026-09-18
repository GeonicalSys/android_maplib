package com.nextgis.maplib.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class NgwSnapshotCheckpointTest {
    @Test public void reusesOnlyTheExactCommittedMismatch() throws Exception {
        String saved = NgwSnapshotCheckpoint.encode("workspace/account/resource/filter/schema", 930, 931, 1, 1000);
        assertTrue(NgwSnapshotCheckpoint.canReuse(saved, "workspace/account/resource/filter/schema", 930, 931, 1001, false));
        assertFalse(NgwSnapshotCheckpoint.canReuse(saved, "different scope", 930, 931, 1001, false));
        assertFalse(NgwSnapshotCheckpoint.canReuse(saved, "workspace/account/resource/filter/schema", 929, 931, 1001, false));
        assertFalse(NgwSnapshotCheckpoint.canReuse(saved, "workspace/account/resource/filter/schema", 930, 932, 1001, false));
    }

    @Test public void rechecksAfterExpiryClockRollbackOrExplicitRequest() throws Exception {
        String saved = NgwSnapshotCheckpoint.encode("scope", 930, 931, 1, 1000);
        assertFalse(NgwSnapshotCheckpoint.canReuse(saved, "scope", 930, 931, 1001, true));
        assertFalse(NgwSnapshotCheckpoint.canReuse(saved, "scope", 930, 931, 999, false));
        assertFalse(NgwSnapshotCheckpoint.canReuse(saved, "scope", 930, 931, 1000 + NgwSnapshotCheckpoint.RECHECK_AFTER_MS, false));
    }

    @Test public void invalidGeometryWithAnExistingRowCannotExplainAMissingRow() throws Exception {
        assertNull(NgwSnapshotCheckpoint.encode("scope", 930, 931, 0, 1000));
        assertNull(NgwSnapshotCheckpoint.encode("scope", 930, 931, 2, 1000));
        assertNull(NgwSnapshotCheckpoint.encode("scope", 931, 931, 1, 1000));
        assertNull(NgwSnapshotCheckpoint.encode("scope", -1, 931, 932, 1000));
        assertNull(NgwSnapshotCheckpoint.encode("scope", 0, 0, 1, 1000));
    }

    @Test public void absentOrCorruptEvidenceNeverSkipsDownload() {
        assertFalse(NgwSnapshotCheckpoint.canReuse(null, "scope", 930, 931, 1001, false));
        assertFalse(NgwSnapshotCheckpoint.canReuse("{", "scope", 930, 931, 1001, false));
        assertFalse(NgwSnapshotCheckpoint.canReuse("{}", "scope", 930, 931, 1001, false));
    }
}

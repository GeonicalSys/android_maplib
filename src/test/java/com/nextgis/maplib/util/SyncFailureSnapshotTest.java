package com.nextgis.maplib.util;

import android.app.Application;
import android.content.SyncResult;
import android.os.Bundle;
import android.os.Parcel;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Technical account-result transport only: no map, SQLite, socket or business input. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28)
public class SyncFailureSnapshotTest {
    private static final String PREFIX = "com.nextgis.maplib.sync.failure.";
    @After public void clearScope() { SyncResultUtil.endSync(); }

    private Bundle serverReport(SyncResult result) {
        SyncResultUtil.beginSync(result);
        SyncResultUtil.markServerTemporarilyUnavailable(result);
        Bundle bundle = new Bundle();
        SyncResultUtil.exportAccountFailureSnapshot(bundle, result);
        SyncResultUtil.endSync();
        return bundle;
    }

    @Test public void serverReasonSurvivesEndWithTheExactLocalResult() {
        SyncResult result = new SyncResult(); Bundle bundle = serverReport(result);
        assertEquals(1, result.stats.numIoExceptions);
        assertFalse(SyncResultUtil.hasServerTemporarilyUnavailable(result));
        assertEquals(SyncResultUtil.FAILURE_SERVER,
                SyncResultUtil.readAccountFailureReasons(bundle, result));
    }

    @Test public void sameCountsInAnotherResultCannotBorrowServerReason() {
        SyncResult result = new SyncResult(); Bundle bundle = serverReport(result);
        SyncResult foreign = new SyncResult(); foreign.stats.numIoExceptions = 1;
        assertEquals(SyncResultUtil.FAILURE_OTHER,
                SyncResultUtil.readAccountFailureReasons(bundle, foreign));
    }

    @Test public void parcelRoundTripCannotCertifyTheOriginalLocalResult() {
        SyncResult result = new SyncResult(); Bundle bundle = serverReport(result);
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeBundle(bundle); parcel.setDataPosition(0);
            Bundle copied = parcel.readBundle(SyncResult.class.getClassLoader());
            assertEquals(SyncResultUtil.FAILURE_OTHER,
                    SyncResultUtil.readAccountFailureReasons(copied, result));
        } finally { parcel.recycle(); }
    }

    @Test public void shallowLocalBundleCopyRetainsTheSamePassIdentity() {
        SyncResult result = new SyncResult(); Bundle bundle = serverReport(result);
        assertEquals(SyncResultUtil.FAILURE_SERVER,
                SyncResultUtil.readAccountFailureReasons(new Bundle(bundle), result));
    }

    @Test public void reasonOnlyHelpersDoNotChangeAnyNumericFailureCount() {
        SyncResult result = new SyncResult(); SyncResultUtil.beginSync(result);
        result.stats.numIoExceptions = 2; result.stats.numParseExceptions = 1;
        result.stats.numAuthExceptions = 3; result.stats.numEntries = 4;
        SyncResultUtil.recordServerTemporarilyUnavailableReason(result);
        SyncResultUtil.recordServerParseFailureReason(result);
        assertEquals(2, result.stats.numIoExceptions); assertEquals(1, result.stats.numParseExceptions);
        assertEquals(3, result.stats.numAuthExceptions); assertEquals(4, result.stats.numEntries);
    }

    @Test public void typedLookupParseHasServerReasonWithoutAnIoIncrement() {
        SyncResult result = new SyncResult(); SyncResultUtil.beginSync(result);
        result.stats.numParseExceptions++;
        SyncResultUtil.recordServerParseFailureReason(result);
        Bundle bundle = new Bundle(); SyncResultUtil.exportAccountFailureSnapshot(bundle, result);
        SyncResultUtil.endSync();
        assertEquals(0, result.stats.numIoExceptions); assertEquals(1, result.stats.numParseExceptions);
        assertEquals(SyncResultUtil.FAILURE_SERVER,
                SyncResultUtil.readAccountFailureReasons(bundle, result));
    }

    @Test public void unclassifiedIoOrParseCannotBePresentedAsPureServerFailure() {
        for (boolean parse : new boolean[]{false, true}) {
            SyncResult result = new SyncResult(); SyncResultUtil.beginSync(result);
            SyncResultUtil.markServerTemporarilyUnavailable(result);
            if (parse) result.stats.numParseExceptions++; else result.stats.numIoExceptions++;
            assertEquals(SyncResultUtil.FAILURE_SERVER | SyncResultUtil.FAILURE_OTHER,
                    SyncResultUtil.snapshotFailureReasons(result).flags);
        }
    }

    @Test public void lateIoAndParseInvalidateTheCapturedCohort() {
        for (boolean parse : new boolean[]{false, true}) {
            SyncResult result = new SyncResult(); Bundle bundle = serverReport(result);
            if (parse) result.stats.numParseExceptions++; else result.stats.numIoExceptions++;
            assertEquals(SyncResultUtil.FAILURE_SERVER | SyncResultUtil.FAILURE_OTHER,
                    SyncResultUtil.readAccountFailureReasons(bundle, result));
        }
    }

    @Test public void authConflictDatabaseBusyRetriesDeletionsAndSkippedStayOther() {
        for (int mode = 0; mode < 7; mode++) {
            SyncResult result = mode == 3 ? SyncResult.ALREADY_IN_PROGRESS : new SyncResult();
            if (mode == 3) {
                SyncResultUtil.beginSync(result);
                assertEquals(SyncResultUtil.FAILURE_OTHER, SyncResultUtil.snapshotFailureReasons(result).flags);
                continue;
            }
            SyncResultUtil.beginSync(result); SyncResultUtil.markServerTemporarilyUnavailable(result);
            if (mode == 0) result.stats.numAuthExceptions++;
            if (mode == 1) result.stats.numConflictDetectedExceptions++;
            if (mode == 2) result.databaseError = true;
            if (mode == 4) result.tooManyRetries = true;
            if (mode == 5) result.tooManyDeletions = true;
            if (mode == 6) result.stats.numSkippedEntries++;
            assertEquals(SyncResultUtil.FAILURE_SERVER | SyncResultUtil.FAILURE_OTHER,
                    SyncResultUtil.snapshotFailureReasons(result).flags);
        }
    }

    @Test public void wrongOwnerAndAnotherThreadCannotAddTrustedMetadata() throws Exception {
        SyncResult result = new SyncResult(); SyncResultUtil.beginSync(result);
        result.stats.numIoExceptions = 1;
        SyncResult foreign = new SyncResult();
        SyncResultUtil.recordServerTemporarilyUnavailableReason(foreign);
        java.util.concurrent.atomic.AtomicReference<Throwable> childFailure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread child = new Thread(() -> {
            try { SyncResultUtil.recordServerTemporarilyUnavailableReason(result); }
            catch (Throwable error) { childFailure.set(error); }
        });
        child.setDaemon(true); child.start(); child.join(5000);
        assertFalse("Technical child did not finish", child.isAlive());
        assertNull("Technical child failed", childFailure.get());
        assertEquals(SyncResultUtil.FAILURE_OTHER, SyncResultUtil.snapshotFailureReasons(result).flags);
    }

    @Test public void offlineAndConnectReasonsRemainDistinctAndMixedWithServer() {
        SyncResult result = new SyncResult(); SyncResultUtil.beginSync(result);
        SyncResultUtil.markNetworkUnavailable(result); SyncResultUtil.markConnectFailed(result);
        assertEquals(SyncResultUtil.FAILURE_OFFLINE | SyncResultUtil.FAILURE_CONNECT,
                SyncResultUtil.snapshotFailureReasons(result).flags);
        SyncResultUtil.markServerTemporarilyUnavailable(result);
        assertEquals(SyncResultUtil.FAILURE_OFFLINE | SyncResultUtil.FAILURE_CONNECT | SyncResultUtil.FAILURE_SERVER,
                SyncResultUtil.snapshotFailureReasons(result).flags);
    }

    @Test public void nextScopeAndClearRemoveOnlyPrivateOutputKeys() {
        SyncResult result = new SyncResult(); Bundle bundle = serverReport(result);
        bundle.putString("caller-kept", "kept"); SyncResultUtil.clearAccountFailureSnapshot(bundle);
        assertEquals(1, bundle.size()); assertEquals("kept", bundle.getString("caller-kept"));
        SyncResult fresh = new SyncResult(); fresh.stats.numIoExceptions = 1;
        SyncResultUtil.beginSync(fresh);
        assertEquals(SyncResultUtil.FAILURE_OTHER, SyncResultUtil.snapshotFailureReasons(fresh).flags);
    }

    @Test public void legacyNoArgScopeKeepsFormattingButCannotExportTrustedOutput() {
        SyncResult result = new SyncResult(); SyncResultUtil.beginSync();
        SyncResultUtil.markServerTemporarilyUnavailable(result);
        assertTrue(SyncResultUtil.hasServerTemporarilyUnavailable(result));
        Bundle bundle = new Bundle(); SyncResultUtil.exportAccountFailureSnapshot(bundle, result);
        assertEquals(0, bundle.size());
        assertEquals(SyncResultUtil.FAILURE_OTHER, SyncResultUtil.readAccountFailureReasons(bundle, result));
    }

    @Test public void malformedUnknownAndWrongPrimitiveReportsUseOther() {
        for (int mode = 0; mode < 6; mode++) {
            SyncResult result = new SyncResult(); Bundle bundle = serverReport(result);
            if (mode == 0) bundle.putInt(PREFIX + "version", 999);
            if (mode == 1) bundle.putInt(PREFIX + "flags", 32);
            if (mode == 2) bundle.putString(PREFIX + "io_at_capture", "1");
            if (mode == 3) bundle.putLong(PREFIX + "parse_at_capture", -1L);
            if (mode == 4) bundle.remove(PREFIX + "local_owner");
            if (mode == 5) bundle.putInt(PREFIX + "flags", 0);
            assertEquals(SyncResultUtil.FAILURE_OTHER, SyncResultUtil.readAccountFailureReasons(bundle, result));
        }
    }

    @Test public void successNeverReusesAStaleServerReport() {
        SyncResult result = new SyncResult(); Bundle bundle = serverReport(result);
        result.stats.numIoExceptions = 0;
        assertEquals(SyncResultUtil.FAILURE_NONE, SyncResultUtil.readAccountFailureReasons(bundle, result));
    }

    @Test public void successfulOperationCountersDoNotInvalidateKnownServerCause() {
        SyncResult result = new SyncResult(); SyncResultUtil.beginSync(result);
        SyncResultUtil.markServerTemporarilyUnavailable(result);
        result.stats.numInserts = 2; result.stats.numUpdates = 3; result.stats.numDeletes = 4; result.stats.numEntries = 5;
        assertEquals(SyncResultUtil.FAILURE_SERVER, SyncResultUtil.snapshotFailureReasons(result).flags);
    }
}

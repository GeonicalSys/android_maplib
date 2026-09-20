package com.nextgis.maplib.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class NgwSyncProgressTest {
    private final AtomicLong nanos = new AtomicLong();
    private final List<NgwSyncProgress.Snapshot> published = new ArrayList<>();

    @Before
    public void setUp() {
        NgwSyncProgress.resetForTest();
        nanos.set(0L);
        published.clear();
        NgwSyncProgress.setClockForTest(nanos::get);
        NgwSyncProgress.setListenerForTest(published::add);
    }

    @After
    public void tearDown() {
        NgwSyncProgress.resetForTest();
    }

    @Test
    public void layerWeightCapsPushUnits() {
        assertEquals(10, NgwSyncProgress.layerWeight(0));
        assertEquals(15, NgwSyncProgress.layerWeight(5));
        assertEquals(40, NgwSyncProgress.layerWeight(30));
        assertEquals(40, NgwSyncProgress.layerWeight(100));
        assertEquals(10, NgwSyncProgress.layerWeight(-3));
    }

    @Test
    public void twoAccountsFourLayersReachReserveThenFinish() {
        NgwSyncProgress.beginSession(null, 2);
        addAccount(0, 0);
        NgwSyncProgress.beginLayer(0);
        NgwSyncProgress.completeLayer();
        NgwSyncProgress.beginLayer(0);
        NgwSyncProgress.completeLayer();
        NgwSyncProgress.finishAccount();

        int afterFirstAccount = NgwSyncProgress.snapshot().done;
        assertTrue(afterFirstAccount > 400);
        assertTrue(afterFirstAccount < 600);

        addAccount(0, 0);
        NgwSyncProgress.beginLayer(0);
        NgwSyncProgress.completeLayer();
        NgwSyncProgress.beginLayer(0);
        NgwSyncProgress.completeLayer();
        int beforeFinish = NgwSyncProgress.snapshot().done;
        assertEquals(950, beforeFinish);

        NgwSyncProgress.finishAccount();
        assertEquals(950, NgwSyncProgress.snapshot().done);
        NgwSyncProgress.finishSession();
        NgwSyncProgress.Snapshot finished = NgwSyncProgress.snapshot();
        assertFalse(finished.active);
        assertEquals(1000, finished.done);
    }

    @Test
    public void pushRecordsFillLayerShare() {
        NgwSyncProgress.beginSession(null, 1);
        addAccount(5);
        NgwSyncProgress.beginLayer(5);
        NgwSyncProgress.reportPush(3, 5);

        // weight 15, push share 5/15, 3/5 of push = 0.2 of layer; session reserve 5%
        assertEquals(190, NgwSyncProgress.snapshot().done);
    }

    @Test
    public void unknownContentLengthHoldsDownloadShare() {
        NgwSyncProgress.beginSession(null, 1);
        addAccount(0);
        NgwSyncProgress.beginLayer(0);
        NgwSyncProgress.reportDownload(50_000L, -1L);
        assertEquals(0, NgwSyncProgress.snapshot().done);

        NgwSyncProgress.reportDownload(50_000L, 100_000L);
        assertTrue(NgwSyncProgress.snapshot().done > 0);
    }

    @Test
    public void deferredLayerIsNotCompletedUntilResumeFinishes() {
        NgwSyncProgress.beginSession(null, 1);
        addAccount(0, 0);
        NgwSyncProgress.beginLayer(0);
        NgwSyncProgress.deferCurrentLayer();
        assertEquals(0, NgwSyncProgress.snapshot().done);

        NgwSyncProgress.beginLayer(0);
        NgwSyncProgress.completeLayer();
        int afterOtherLayer = NgwSyncProgress.snapshot().done;

        NgwSyncProgress.resumeLayer(0);
        assertEquals(afterOtherLayer, NgwSyncProgress.snapshot().done);
        NgwSyncProgress.completeLayer();
        assertEquals(950, NgwSyncProgress.snapshot().done);
    }

    @Test
    public void extraWorkDoesNotRewindDisplayedFraction() {
        NgwSyncProgress.beginSession(null, 1);
        addAccount(0, 0);
        NgwSyncProgress.beginLayer(0);
        NgwSyncProgress.completeLayer();
        int mid = NgwSyncProgress.snapshot().done;
        assertTrue(mid > 400);

        NgwSyncProgress.addUpcomingLayer(0);
        assertEquals(mid, NgwSyncProgress.snapshot().done);
        NgwSyncProgress.beginLayer(0);
        NgwSyncProgress.completeLayer();
        NgwSyncProgress.beginLayer(0);
        NgwSyncProgress.completeLayer();
        assertTrue(NgwSyncProgress.snapshot().done >= mid);
    }

    @Test
    public void cancelDeactivatesSession() {
        NgwSyncProgress.beginSession(null, 1);
        addAccount(0);
        NgwSyncProgress.beginLayer(0);
        NgwSyncProgress.cancel();
        NgwSyncProgress.Snapshot snapshot = NgwSyncProgress.snapshot();
        assertFalse(snapshot.active);
        assertEquals(0, snapshot.done);
    }

    @Test
    public void throttleSkipsSubPercentAndSubIntervalUpdates() {
        NgwSyncProgress.beginSession(null, 1);
        addAccount(0);
        NgwSyncProgress.beginLayer(0);
        NgwSyncProgress.reportApply(200, 1000);
        published.clear();

        nanos.addAndGet(250_000_000L);
        NgwSyncProgress.reportApply(250, 1000);
        assertEquals(1, published.size());

        nanos.addAndGet(50_000_000L);
        NgwSyncProgress.reportApply(251, 1000);
        assertEquals(1, published.size());

        nanos.addAndGet(250_000_000L);
        NgwSyncProgress.reportApply(252, 1000);
        assertEquals(1, published.size());

        nanos.addAndGet(250_000_000L);
        NgwSyncProgress.reportApply(400, 1000);
        assertEquals(2, published.size());
    }

    private void addAccount(int... pendingChanges) {
        for (int pending : pendingChanges) {
            NgwSyncProgress.addUpcomingLayer(pending);
        }
        NgwSyncProgress.beginAccount();
    }
}

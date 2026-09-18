package com.nextgis.maplib.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class CoalescingRefreshTest {
    @Test public void idleCompletionDoesNotScheduleWork() {
        CoalescingRefresh gate = new CoalescingRefresh();
        assertTrue(gate.request());
        assertFalse(gate.complete());
        assertTrue(gate.request());
    }

    @Test public void gpsBurstHasOnlyOneFollowUp() {
        CoalescingRefresh gate = new CoalescingRefresh();
        assertTrue(gate.request());
        for (int i = 0; i < 10000; i++) assertFalse(gate.request());
        assertTrue(gate.complete());
        assertTrue(gate.request());
        assertFalse(gate.complete());
    }

    @Test public void discardedResultReleasesSlotAndPendingState() {
        CoalescingRefresh gate = new CoalescingRefresh();
        assertTrue(gate.request());
        assertFalse(gate.request());
        gate.complete(); // destroyed view discards the result and follow-up
        assertTrue(gate.request());
        assertFalse(gate.complete());
    }
}

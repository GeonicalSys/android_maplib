package com.nextgis.maplib.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NgwSyncRetryPolicyTest
{
    @Test
    public void deferredRetryWaitsAtLeastFifteenSecondsFromFailure()
    {
        long retryAt = NgwSyncRetryPolicy.retryNotBefore(1_000L);

        assertEquals(16_000L, retryAt);
        assertEquals(12_000L, NgwSyncRetryPolicy.remainingDelay(retryAt, 4_000L));
    }

    @Test
    public void elapsedDeadlineDoesNotProduceNegativeDelay()
    {
        assertEquals(0L, NgwSyncRetryPolicy.remainingDelay(10_000L, 12_000L));
    }
}

package com.nextgis.maplib.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LocationProviderArbiterCoreTest {
    @Test
    public void networkIsFallbackAroundRecentUsableGps() {
        LocationProviderArbiterCore arbiter =
                new LocationProviderArbiterCore(12_000L);

        assertTrue(arbiter.shouldProcess(
                LocationProviderArbiterCore.NETWORK_PROVIDER, 1_000L));
        assertTrue(arbiter.shouldProcess(
                LocationProviderArbiterCore.GPS_PROVIDER, 2_000L));
        arbiter.onAccepted(LocationProviderArbiterCore.GPS_PROVIDER, 2_000L);
        assertFalse(arbiter.shouldProcess(
                LocationProviderArbiterCore.NETWORK_PROVIDER, 5_000L));
        assertFalse(arbiter.shouldProcess(
                LocationProviderArbiterCore.NETWORK_PROVIDER, 14_000L));
        assertTrue(arbiter.shouldProcess(
                LocationProviderArbiterCore.NETWORK_PROVIDER, 14_001L));
        assertEquals(2L, arbiter.getSuppressedNetworkFixCount());
    }

    @Test
    public void rejectedGpsDoesNotExtendSuppressionWindow() {
        LocationProviderArbiterCore arbiter =
                new LocationProviderArbiterCore(12_000L);
        arbiter.onAccepted(LocationProviderArbiterCore.GPS_PROVIDER, 1_000L);
        arbiter.shouldProcess(LocationProviderArbiterCore.GPS_PROVIDER, 10_000L);

        assertTrue(arbiter.shouldProcess(
                LocationProviderArbiterCore.NETWORK_PROVIDER, 13_001L));
    }

    @Test
    public void resetRestoresNetworkFallbackAndCounters() {
        LocationProviderArbiterCore arbiter =
                new LocationProviderArbiterCore(12_000L);
        arbiter.onAccepted(LocationProviderArbiterCore.GPS_PROVIDER, 1_000L);
        arbiter.shouldProcess(LocationProviderArbiterCore.NETWORK_PROVIDER, 2_000L);

        arbiter.reset();

        assertTrue(arbiter.shouldProcess(
                LocationProviderArbiterCore.NETWORK_PROVIDER, 3_000L));
        assertEquals(0L, arbiter.getSuppressedNetworkFixCount());
    }
}

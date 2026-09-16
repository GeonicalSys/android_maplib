package com.nextgis.maplib.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExternalGnssFixPolicyTest {
    @Test public void receiverExtrasMatchGpsConnectorNmea() {
        assertTrue(ExternalGnssFixPolicy.hasReceiverExtras(true, false));
        assertTrue(ExternalGnssFixPolicy.hasReceiverExtras(false, true));
        assertFalse(ExternalGnssFixPolicy.hasReceiverExtras(false, false));
    }

    @Test public void placeholderFortySevenDoesNotReplaceHdopFix() {
        assertTrue(ExternalGnssFixPolicy.dropPlaceholderMock(true, false, true));
        assertFalse(ExternalGnssFixPolicy.dropPlaceholderMock(true, true, true));
        assertFalse(ExternalGnssFixPolicy.dropPlaceholderMock(true, false, false));
        assertFalse(ExternalGnssFixPolicy.dropPlaceholderMock(false, false, true));
    }

    @Test public void indoorFourSeventyKeepsReceiverOverPlaceholder() {
        assertTrue(ExternalGnssFixPolicy.dropPlaceholderMock(true, false, true));
    }

    @Test public void chipGpsYieldsToFreshMock() {
        assertTrue(ExternalGnssFixPolicy.dropChipWhileMock(false, true));
        assertFalse(ExternalGnssFixPolicy.dropChipWhileMock(true, true));
        assertFalse(ExternalGnssFixPolicy.dropChipWhileMock(false, false));
    }

    @Test public void mockSamplingIsNotCoarserThanTwoSecondsAndOneMetre() {
        assertEquals(2_000L, ExternalGnssFixPolicy.sampleMinTimeMs(true, 5_000L));
        assertEquals(1f, ExternalGnssFixPolicy.sampleMinDistanceM(true, 5f), 0f);
        assertEquals(1_000L, ExternalGnssFixPolicy.sampleMinTimeMs(true, 1_000L));
        assertEquals(0.5f, ExternalGnssFixPolicy.sampleMinDistanceM(true, 0.5f), 0f);
        assertEquals(5_000L, ExternalGnssFixPolicy.sampleMinTimeMs(false, 5_000L));
        assertEquals(5f, ExternalGnssFixPolicy.sampleMinDistanceM(false, 5f), 0f);
    }
}

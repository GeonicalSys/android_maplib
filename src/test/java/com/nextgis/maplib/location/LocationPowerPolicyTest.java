package com.nextgis.maplib.location;

import org.junit.Test;
import static org.junit.Assert.*;

public class LocationPowerPolicyTest {
    @Test public void screenOffPoliciesWarnButForegroundServicePolicyDoesNot() {
        for (int api : new int[]{28, 31, 36}) {
            assertFalse(LocationPowerPolicy.restrictsScreenOffGps(api, true, 0, false));
            assertTrue(LocationPowerPolicy.restrictsScreenOffGps(api, true, 1, false));
            assertTrue(LocationPowerPolicy.restrictsScreenOffGps(api, true, 2, false));
            assertFalse(LocationPowerPolicy.restrictsScreenOffGps(api, true, 3, false));
            assertTrue(LocationPowerPolicy.restrictsScreenOffGps(api, true, 4, false));
        }
    }

    @Test public void disabledSaverAndNativeReceiverDoNotWarn() {
        for (int mode = 0; mode <= 4; mode++) {
            assertFalse(LocationPowerPolicy.restrictsScreenOffGps(36, false, mode, false));
            assertFalse(LocationPowerPolicy.restrictsScreenOffGps(36, true, mode, true));
        }
    }

    @Test public void android8WarnsConservativelyOnlyForSystemGpsAndEnabledSaver() {
        assertTrue(LocationPowerPolicy.restrictsScreenOffGps(26, true, -1, false));
        assertFalse(LocationPowerPolicy.restrictsScreenOffGps(27, false, -1, false));
        assertFalse(LocationPowerPolicy.restrictsScreenOffGps(27, true, -1, true));
    }
}

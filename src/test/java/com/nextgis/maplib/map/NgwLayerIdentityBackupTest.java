package com.nextgis.maplib.map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NgwLayerIdentityBackupTest {
    @Test
    public void roundTripsCompleteIdentity() throws Exception {
        String encoded = NgwLayerIdentityBackup.encode(
                "demo-account",
                1291L,
                7,
                3,
                2,
                true,
                null);

        NgwLayerIdentityBackup.Snapshot decoded =
                NgwLayerIdentityBackup.decode(encoded);

        assertEquals("demo-account", decoded.accountName);
        assertEquals(1291L, decoded.remoteId);
        assertEquals(7, decoded.syncType);
        assertEquals(3, decoded.ngwLayerType);
        assertEquals(2, decoded.syncDirection);
        assertTrue(decoded.tracked);
        assertNull(decoded.layerOrigin);
    }

    @Test
    public void rejectsIncompleteOrMalformedIdentity() throws Exception {
        assertNull(NgwLayerIdentityBackup.encode(
                "", 1291L, 0, 0, 3, false, null));
        assertNull(NgwLayerIdentityBackup.encode(
                "demo-account", 0L, 0, 0, 3, false, null));
        assertNull(NgwLayerIdentityBackup.decode("{broken"));
        assertNull(NgwLayerIdentityBackup.decode(
                "{\"schema_version\":1,\"account\":\"\",\"remote_id\":1291}"));
    }

    @Test
    public void preservesTrackedFalse() throws Exception {
        NgwLayerIdentityBackup.Snapshot decoded = NgwLayerIdentityBackup.decode(
                NgwLayerIdentityBackup.encode(
                        "demo-account", 1269L, 1, 2, 3, false, null));

        assertFalse(decoded.tracked);
    }
}

package com.nextgis.maplib.util;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RemoteAttachmentSyncPolicyTest {
    @Test
    public void attachmentMetadataOnlyNeverRequiresFeatureBackup() {
        assertFalse(RemoteAttachmentSyncPolicy.remoteApplyRequiresBackup(true, false));
    }

    @Test
    public void changedFeatureWithoutPendingLocalEditRequiresBackup() {
        assertTrue(RemoteAttachmentSyncPolicy.remoteApplyRequiresBackup(false, false));
        assertFalse(RemoteAttachmentSyncPolicy.remoteApplyRequiresBackup(false, true));
    }

    @Test
    public void metadataComparisonIgnoresByteSizeButNotServerMetadata() {
        Map<String, AttachItem> remote = new LinkedHashMap<>();
        Map<String, AttachItem> persisted = new LinkedHashMap<>();
        remote.put("17", new AttachItem("17", "photo.jpg", "image/jpeg", "photo", 0));
        persisted.put("17", new AttachItem(
                "17", "photo.jpg", "image/jpeg", "photo", 6_000_000));

        assertTrue(RemoteAttachmentSyncPolicy.metadataMatches(remote, persisted));

        persisted.put("17", new AttachItem(
                "17", "renamed.jpg", "image/jpeg", "photo", 6_000_000));
        assertFalse(RemoteAttachmentSyncPolicy.metadataMatches(remote, persisted));
    }

    @Test
    public void metadataComparisonTreatsNullAndEmptyServerTextAsEquivalent() {
        Map<String, AttachItem> remote = new LinkedHashMap<>();
        Map<String, AttachItem> persisted = new LinkedHashMap<>();
        remote.put("17", new AttachItem("17", null, null, null, 0));
        persisted.put("17", new AttachItem("17", "", "", "", 0));

        assertTrue(RemoteAttachmentSyncPolicy.metadataMatches(remote, persisted));
    }
}

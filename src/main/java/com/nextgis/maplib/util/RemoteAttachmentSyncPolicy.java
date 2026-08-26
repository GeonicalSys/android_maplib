/*
 * Project: NextGIS Mobile
 * Purpose: Mobile GIS for Android.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.nextgis.maplib.util;

import java.util.Map;
import java.util.Objects;

/** Decisions that keep remote attachment metadata out of the destructive-data backup gate. */
public final class RemoteAttachmentSyncPolicy {
    private RemoteAttachmentSyncPolicy() {
    }

    /**
     * Attachment metadata refresh does not overwrite feature geometry/attributes or local files.
     * Feature deletion is handled by a separate backup gate.
     */
    public static boolean remoteApplyRequiresBackup(
            boolean featureDataEqual,
            boolean hasPendingLocalFeatureChange) {
        return !featureDataEqual && !hasPendingLocalFeatureChange;
    }

    /** Compare server/SQLite metadata while deliberately ignoring attachment byte size. */
    public static boolean metadataMatches(
            Map<String, AttachItem> remote,
            Map<String, AttachItem> persisted) {
        if (remote == null || persisted == null || remote.size() != persisted.size()) {
            return false;
        }
        for (Map.Entry<String, AttachItem> entry : remote.entrySet()) {
            AttachItem left = entry.getValue();
            AttachItem right = persisted.get(entry.getKey());
            if (left == null || right == null
                    || !Objects.equals(left.getAttachId(), right.getAttachId())
                    || !sameNullableText(left.getDescription(), right.getDescription())
                    || !sameNullableText(left.getDisplayName(), right.getDisplayName())
                    || !sameNullableText(left.getMimetype(), right.getMimetype())) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameNullableText(String left, String right) {
        return Objects.equals(emptyIfNull(left), emptyIfNull(right));
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }
}

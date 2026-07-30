/*
 * Project: NextGIS Mobile
 * Purpose: Collision-safe local layer storage allocation.
 */

package com.nextgis.maplib.map;

import java.io.File;
import java.util.UUID;

final class LayerStorageAllocator {
    private static final int MAX_RESERVATION_ATTEMPTS = 32;

    private LayerStorageAllocator() {
    }

    static File reserve(File parent, String prefix) {
        if (parent == null) {
            throw new IllegalArgumentException("Layer storage parent is null");
        }
        if ((!parent.exists() && !parent.mkdirs()) || !parent.isDirectory()) {
            throw new IllegalStateException("Cannot create layer storage parent: " + parent);
        }

        for (int attempt = 0; attempt < MAX_RESERVATION_ATTEMPTS; attempt++) {
            String name = prefix + UUID.randomUUID().toString().replace("-", "");
            File candidate = new File(parent, name);
            // mkdir is the reservation: unlike exists()+return, it is atomic across fill threads.
            if (candidate.mkdir()) {
                return candidate;
            }
        }

        throw new IllegalStateException(
                "Cannot reserve unique layer storage after " + MAX_RESERVATION_ATTEMPTS
                        + " attempts in " + parent);
    }
}

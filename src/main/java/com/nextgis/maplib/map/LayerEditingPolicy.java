package com.nextgis.maplib.map;

/**
 * Resolves the two editability contracts used by vector layers.
 *
 * <p>Collector-managed layers take their user-facing edit policy from the Collector project
 * item. Other layers keep using the layer/mobile-config flag. Outbound sync remains a hard gate
 * for managed NGW layers.</p>
 */
final class LayerEditingPolicy {
    private LayerEditingPolicy() {
    }

    static boolean isEditingAllowed(
            boolean layerEditable,
            boolean collectorEditable,
            boolean collectorManaged,
            boolean outboundSyncAllowed) {
        if (collectorManaged) {
            return collectorEditable && outboundSyncAllowed;
        }
        return layerEditable && collectorEditable;
    }

    static boolean isSyncDirectionConfigurable(
            boolean layerEditable,
            boolean collectorEditable,
            boolean collectorManaged) {
        if (collectorManaged) {
            return collectorEditable;
        }
        return layerEditable && collectorEditable;
    }
}

package com.nextgis.maplib.map;

/**
 * Keeps Collector project layers below the reserved top-of-stack tracks layer.
 *
 * <p>LayerGroup index {@code 0} is the bottom of the visual stack, so a tracks layer at index
 * {@code tracksIndex} is an upper boundary for Collector insertions.</p>
 */
final class CollectorLayerOrderPolicy {
    private CollectorLayerOrderPolicy() {
    }

    static int keepBelowTracks(int proposedIndex, int tracksIndex) {
        return tracksIndex >= 0 ? Math.min(proposedIndex, tracksIndex) : proposedIndex;
    }
}

package com.nextgis.maplib.map;

import java.io.IOException;

/** One process admission pool shared by HTTP MVT and direct native tiles; bounded lock metadata. */
final class RenderTileWorkLimiter {
    private static final RenderTileWorkGate SHARED = new RenderTileWorkGate();
    private static final Object[] LAYER_LOCKS = new Object[64];
    static { for (int i = 0; i < LAYER_LOCKS.length; i++) LAYER_LOCKS[i] = new Object(); }
    private RenderTileWorkLimiter() { }
    static RenderTileWorkGate processShared() { return SHARED; }
    static Object layerLock(String canonicalOwner) {
        return LAYER_LOCKS[(canonicalOwner.hashCode() & Integer.MAX_VALUE) % LAYER_LOCKS.length];
    }
    static Object layerLock(VectorLayer layer) {
        try { return layerLock(layer.getPath().getCanonicalPath()); }
        catch (IOException unavailable) { return layerLock(layer.getPath().getAbsolutePath()); }
    }
}

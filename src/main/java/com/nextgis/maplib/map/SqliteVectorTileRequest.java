package com.nextgis.maplib.map;

import java.util.Objects;

/** Immutable identity of one source generation; never contains business values or coordinates. */
public final class SqliteVectorTileRequest {
    private final String ownerIdentity, styleFingerprint;
    private final long sourceEpoch, contentVersion;
    private final Long excludedEditedId;
    private final int z, x, y;

    public SqliteVectorTileRequest(String ownerIdentity, long sourceEpoch, long contentVersion,
            String styleFingerprint, Long excludedEditedId, int z, int x, int y) {
        this.ownerIdentity = Objects.requireNonNull(ownerIdentity, "ownerIdentity");
        this.styleFingerprint = Objects.requireNonNull(styleFingerprint, "styleFingerprint");
        if (z < 0 || z > 30 || x < 0 || y < 0 || x >= (1L << z) || y >= (1L << z))
            throw new IllegalArgumentException("Invalid canonical tile address");
        this.sourceEpoch = sourceEpoch; this.contentVersion = contentVersion;
        this.excludedEditedId = excludedEditedId; this.z = z; this.x = x; this.y = y;
    }
    public String getOwnerIdentity() { return ownerIdentity; }
    public long getSourceEpoch() { return sourceEpoch; }
    public long getContentVersion() { return contentVersion; }
    public String getStyleFingerprint() { return styleFingerprint; }
    public Long getExcludedEditedId() { return excludedEditedId; }
    public int getZ() { return z; }
    public int getX() { return x; }
    public int getY() { return y; }

    String cacheKey() {
        return cacheKey("");
    }
    String cacheKey(String processSession) {
        return LocalVectorTileDiskCache.key("sqlite-native-v1/" + processSession + "/" + ownerIdentity + "/" + sourceEpoch,
                contentVersion, styleFingerprint, excludedEditedId == null ? "none" : excludedEditedId.toString(), z, x, y);
    }
}

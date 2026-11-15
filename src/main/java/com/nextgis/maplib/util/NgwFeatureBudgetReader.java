package com.nextgis.maplib.util;

import java.io.FilterReader;
import java.io.IOException;
import java.io.Reader;

/** Bounds a single streaming feature before JsonReader allocates its complete WKT/string. */
public final class NgwFeatureBudgetReader extends FilterReader {
    private final long limit;
    private long consumed;

    public NgwFeatureBudgetReader(Reader source) { this(source, NgwSyncResourceBudget.featureLimit()); }
    public NgwFeatureBudgetReader(Reader source, long limit) {
        super(source);
        if (limit < 1) throw new IllegalArgumentException("Feature budget must be positive");
        this.limit = limit;
    }

    /** Android JsonReader may already hold at most its 1024-character input buffer. */
    public void beginFeature() throws IOException {
        NgwSyncResourceBudget.requireHeap(); consumed = 0;
    }

    @Override public int read(char[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        int count = super.read(buffer, offset, (int) Math.min(length, Math.max(1, limit - consumed + 1)));
        if (count > 0 && (consumed += count) > limit)
            throw new NgwSyncResourceBudget.LimitException("A feature exceeds the sync memory budget");
        return count;
    }

    @Override public int read() throws IOException {
        char[] one = new char[1];
        return read(one, 0, 1) == -1 ? -1 : one[0];
    }
}

package com.nextgis.maplib.map;

/** Tracks the whole gesture so a returning drag, pinch or cancellation cannot add a point. */
public final class MapTapGesture {
    private float startX, startY, toleranceSquared;
    private long startedAt, maximumDuration;
    private boolean possible;

    public static float tolerance(float density, int systemTouchSlop, boolean placingPoints) {
        return Math.max(systemTouchSlop, (placingPoints ? 12f : 0f) * density);
    }

    public void begin(float x, float y, long time, float tolerance, long maximumDuration) {
        startX = x;
        startY = y;
        startedAt = time;
        this.maximumDuration = maximumDuration;
        toleranceSquared = tolerance * tolerance;
        possible = true;
    }

    public void move(float x, float y) {
        float dx = x - startX, dy = y - startY;
        if (dx * dx + dy * dy > toleranceSquared) possible = false;
    }

    public boolean finish(float x, float y, long time) {
        move(x, y);
        boolean tap = possible && time >= startedAt && time - startedAt < maximumDuration;
        cancel();
        return tap;
    }

    public void cancel() {
        possible = false;
    }
}

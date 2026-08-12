/*
 * Project:  NextGIS Mobile
 * Purpose:  Retry timing for transient NGW feature-pull failures.
 * *****************************************************************************
 * Copyright (c) 2026 NextGIS
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.nextgis.maplib.util;

/**
 * Keeps a failed layer out of the immediate hot loop long enough for an external database or NGW
 * worker to recover, while successful layers continue their first pass.
 */
public final class NgwSyncRetryPolicy
{
    public static final long DEFERRED_RETRY_MIN_DELAY_MS = 15_000L;

    private NgwSyncRetryPolicy()
    {
    }


    public static long retryNotBefore(long failedAtElapsedRealtime)
    {
        return failedAtElapsedRealtime + DEFERRED_RETRY_MIN_DELAY_MS;
    }


    public static long remainingDelay(
            long retryNotBeforeElapsedRealtime,
            long nowElapsedRealtime)
    {
        return Math.max(0L, retryNotBeforeElapsedRealtime - nowElapsedRealtime);
    }
}

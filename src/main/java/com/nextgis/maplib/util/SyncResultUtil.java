/*
 * Project:  NextGIS Mobile
 * Purpose:  Classify sync I/O failures for user-facing messages.
 * *****************************************************************************
 * Copyright (c) 2016-2026 NextGIS, info@nextgis.com
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.nextgis.maplib.util;

import android.content.Context;
import android.content.SyncResult;

import com.nextgis.maplib.R;

/**
 * Tags sync I/O failures on the current sync thread so
 * {@link com.nextgis.maplib.datasource.ngw.SyncAdapter} can distinguish device offline
 * from server/connect failures.
 */
public final class SyncResultUtil {

    private static final ThreadLocal<int[]> NETWORK_UNAVAILABLE = new ThreadLocal<>();
    private static final ThreadLocal<int[]> CONNECT_FAILED = new ThreadLocal<>();
    private static final ThreadLocal<int[]> SERVER_TEMPORARILY_UNAVAILABLE = new ThreadLocal<>();

    private SyncResultUtil() {
    }

    /** Call at the start of {@link android.content.AbstractThreadedSyncAdapter#onPerformSync}. */
    public static void beginSync() {
        NETWORK_UNAVAILABLE.set(new int[]{0});
        CONNECT_FAILED.set(new int[]{0});
        SERVER_TEMPORARILY_UNAVAILABLE.set(new int[]{0});
    }

    /** Call when sync finishes (success, error, or cancel). */
    public static void endSync() {
        NETWORK_UNAVAILABLE.remove();
        CONNECT_FAILED.remove();
        SERVER_TEMPORARILY_UNAVAILABLE.remove();
    }

    public static void markNetworkUnavailable(SyncResult syncResult) {
        if (syncResult == null) {
            return;
        }
        syncResult.stats.numIoExceptions++;
        increment(NETWORK_UNAVAILABLE);
    }

    public static void markConnectFailed(SyncResult syncResult) {
        if (syncResult == null) {
            return;
        }
        syncResult.stats.numIoExceptions++;
        increment(CONNECT_FAILED);
    }

    public static void markServerTemporarilyUnavailable(SyncResult syncResult) {
        if (syncResult == null) {
            return;
        }
        syncResult.stats.numIoExceptions++;
        increment(SERVER_TEMPORARILY_UNAVAILABLE);
    }

    public static boolean hasNetworkUnavailable(SyncResult syncResult) {
        return count(NETWORK_UNAVAILABLE) > 0;
    }

    public static boolean hasConnectFailed(SyncResult syncResult) {
        return count(CONNECT_FAILED) > 0;
    }

    public static boolean hasServerTemporarilyUnavailable(SyncResult syncResult) {
        return count(SERVER_TEMPORARILY_UNAVAILABLE) > 0;
    }

    /**
     * User message for {@link android.content.SyncStats#numIoExceptions}.
     */
    public static String ioErrorMessage(Context context, SyncResult syncResult) {
        if (hasNetworkUnavailable(syncResult)) {
            return context.getString(R.string.error_network_unavailable);
        }
        if (hasServerTemporarilyUnavailable(syncResult)) {
            return context.getString(R.string.error_server_temporarily_unavailable);
        }
        if (hasConnectFailed(syncResult)) {
            return context.getString(R.string.error_connect_failed);
        }
        return context.getString(R.string.sync_error_io);
    }

    private static void increment(ThreadLocal<int[]> counter) {
        int[] value = counter.get();
        if (value != null) {
            value[0]++;
        }
    }

    private static int count(ThreadLocal<int[]> counter) {
        int[] value = counter.get();
        return value == null ? 0 : value[0];
    }
}

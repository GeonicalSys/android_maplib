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
import android.os.Bundle;

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
    private static final ThreadLocal<int[]> SERVER_PARSE_FAILURE = new ThreadLocal<>();
    private static final ThreadLocal<SyncResult> TYPED_OWNER = new ThreadLocal<>();
    public static final int FAILURE_NONE = 0;
    public static final int FAILURE_OFFLINE = 1;
    public static final int FAILURE_CONNECT = 2;
    public static final int FAILURE_SERVER = 4;
    public static final int FAILURE_OTHER = 8;
    private static final int FAILURE_KNOWN_MASK = 15;
    private static final int REPORT_VERSION = 1;
    private static final String OUT_VERSION = "com.nextgis.maplib.sync.failure.version";
    private static final String OUT_FLAGS = "com.nextgis.maplib.sync.failure.flags";
    private static final String OUT_IO = "com.nextgis.maplib.sync.failure.io_at_capture";
    private static final String OUT_PARSE = "com.nextgis.maplib.sync.failure.parse_at_capture";
    // Local same-pass identity only: never forward this output Bundle to an Intent or store.
    private static final String OUT_OWNER = "com.nextgis.maplib.sync.failure.local_owner";

    private SyncResultUtil() {
    }

    /** Call at the start of {@link android.content.AbstractThreadedSyncAdapter#onPerformSync}. */
    public static void beginSync() {
        NETWORK_UNAVAILABLE.set(new int[]{0});
        CONNECT_FAILED.set(new int[]{0});
        SERVER_TEMPORARILY_UNAVAILABLE.set(new int[]{0});
        SERVER_PARSE_FAILURE.set(new int[]{0});
        TYPED_OWNER.remove();
    }

    /** Call when sync finishes (success, error, or cancel). */
    public static void endSync() {
        NETWORK_UNAVAILABLE.remove();
        CONNECT_FAILED.remove();
        SERVER_TEMPORARILY_UNAVAILABLE.remove();
        SERVER_PARSE_FAILURE.remove();
        TYPED_OWNER.remove();
    }

    public static void markNetworkUnavailable(SyncResult syncResult) {
        if (syncResult == null) {
            return;
        }
        syncResult.stats.numIoExceptions++;
        recordReasonOnly(syncResult, NETWORK_UNAVAILABLE);
    }

    public static void markConnectFailed(SyncResult syncResult) {
        if (syncResult == null) {
            return;
        }
        syncResult.stats.numIoExceptions++;
        recordReasonOnly(syncResult, CONNECT_FAILED);
    }

    public static void markServerTemporarilyUnavailable(SyncResult syncResult) {
        if (syncResult == null) {
            return;
        }
        syncResult.stats.numIoExceptions++;
        recordServerTemporarilyUnavailableReason(syncResult);
    }

    public static boolean hasNetworkUnavailable(SyncResult syncResult) {
        return matchesOwner(syncResult) && count(NETWORK_UNAVAILABLE) > 0;
    }

    public static boolean hasConnectFailed(SyncResult syncResult) {
        return matchesOwner(syncResult) && count(CONNECT_FAILED) > 0;
    }

    public static boolean hasServerTemporarilyUnavailable(SyncResult syncResult) {
        return matchesOwner(syncResult) && count(SERVER_TEMPORARILY_UNAVAILABLE) > 0;
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

    /** Trusted typed output is confined to this exact account result and thread. */
    public static void beginSync(SyncResult owner) {
        beginSync();
        if (owner != null) TYPED_OWNER.set(owner);
    }

    private static boolean matchesOwner(SyncResult result) {
        SyncResult owner = TYPED_OWNER.get();
        return owner == null || owner == result;
    }

    private static void recordReasonOnly(SyncResult result, ThreadLocal<int[]> counter) {
        if (result != null && matchesOwner(result)) increment(counter);
    }

    /** Metadata only; caller already owns its existing I/O counter increment. */
    public static void recordServerTemporarilyUnavailableReason(SyncResult result) {
        recordReasonOnly(result, SERVER_TEMPORARILY_UNAVAILABLE);
    }

    /** Metadata only; a typed lookup failure already incremented Parse once. */
    public static void recordServerParseFailureReason(SyncResult result) {
        recordReasonOnly(result, SERVER_PARSE_FAILURE);
    }

    public static final class FailureSnapshot {
        public final int flags;
        public final long ioAtCapture;
        public final long parseAtCapture;
        private FailureSnapshot(int flags, long io, long parse) {
            this.flags = flags; this.ioAtCapture = io; this.parseAtCapture = parse;
        }
    }

    private static boolean hasOtherHardError(SyncResult result) {
        return result.syncAlreadyInProgress || result.databaseError || result.tooManyDeletions
                || result.tooManyRetries || result.stats.numAuthExceptions > 0
                || result.stats.numConflictDetectedExceptions > 0
                || result.stats.numSkippedEntries > 0;
    }

    public static FailureSnapshot snapshotFailureReasons(SyncResult result) {
        if (result == null) return new FailureSnapshot(FAILURE_OTHER, 0, 0);
        long io = result.stats.numIoExceptions, parse = result.stats.numParseExceptions;
        if (!result.hasError()) return new FailureSnapshot(FAILURE_NONE, io, parse);
        if (TYPED_OWNER.get() != result || NETWORK_UNAVAILABLE.get() == null
                || CONNECT_FAILED.get() == null || SERVER_TEMPORARILY_UNAVAILABLE.get() == null
                || SERVER_PARSE_FAILURE.get() == null)
            return new FailureSnapshot(FAILURE_OTHER, io, parse);
        long offline = count(NETWORK_UNAVAILABLE), connect = count(CONNECT_FAILED);
        long serverIo = count(SERVER_TEMPORARILY_UNAVAILABLE), serverParse = count(SERVER_PARSE_FAILURE);
        int flags = (offline > 0 ? FAILURE_OFFLINE : 0) | (connect > 0 ? FAILURE_CONNECT : 0)
                | (serverIo > 0 || serverParse > 0 ? FAILURE_SERVER : 0);
        if (offline < 0 || connect < 0 || serverIo < 0 || serverParse < 0 || io < 0 || parse < 0
                || io != offline + connect + serverIo || parse != serverParse
                || hasOtherHardError(result) || flags == FAILURE_NONE) flags |= FAILURE_OTHER;
        return new FailureSnapshot(flags, io, parse);
    }

    public static void clearAccountFailureSnapshot(Bundle extras) {
        if (extras == null) return;
        extras.remove(OUT_VERSION); extras.remove(OUT_FLAGS);
        extras.remove(OUT_IO); extras.remove(OUT_PARSE); extras.remove(OUT_OWNER);
    }

    public static void exportAccountFailureSnapshot(Bundle extras, SyncResult result) {
        clearAccountFailureSnapshot(extras);
        if (extras == null || result == null || TYPED_OWNER.get() != result) return;
        FailureSnapshot snapshot = snapshotFailureReasons(result);
        extras.putInt(OUT_VERSION, REPORT_VERSION);
        extras.putInt(OUT_FLAGS, snapshot.flags);
        extras.putLong(OUT_IO, snapshot.ioAtCapture);
        extras.putLong(OUT_PARSE, snapshot.parseAtCapture);
        extras.putParcelable(OUT_OWNER, result);
    }

    /**
     * Read only the freshly cleared, same-pass Bundle with its exact local result.
     * Four primitives plus the existing framework Parcelable reference are local output,
     * not an IPC report or persistent authority. Parcel-copying loses the owner's identity.
     */
    public static int readAccountFailureReasons(Bundle extras, SyncResult result) {
        if (result == null) return FAILURE_OTHER;
        if (!result.hasError()) return FAILURE_NONE;
        if (extras == null) return FAILURE_OTHER;
        try {
            Object version = extras.get(OUT_VERSION), flagsValue = extras.get(OUT_FLAGS);
            Object io = extras.get(OUT_IO), parse = extras.get(OUT_PARSE);
            if (extras.get(OUT_OWNER) != result) return FAILURE_OTHER;
            if (!(version instanceof Integer) || (Integer) version != REPORT_VERSION
                    || !(flagsValue instanceof Integer) || !(io instanceof Long) || !(parse instanceof Long))
                return FAILURE_OTHER;
            int flags = (Integer) flagsValue;
            if (flags == FAILURE_NONE || (flags & ~FAILURE_KNOWN_MASK) != 0
                    || (Long) io < 0 || (Long) parse < 0) return FAILURE_OTHER;
            if ((Long) io != result.stats.numIoExceptions || (Long) parse != result.stats.numParseExceptions
                    || hasOtherHardError(result)) flags |= FAILURE_OTHER;
            return flags;
        } catch (RuntimeException malformed) {
            return FAILURE_OTHER;
        }
    }
}

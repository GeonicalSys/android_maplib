/*
 * Project:  NextGIS Mobile
 * Purpose:  Bounded, production-safe strings for HyperLog / diagnostics.
 */

package com.nextgis.maplib.util;

import android.content.SyncResult;
import android.text.TextUtils;

/**
 * Truncation and one-line diagnostics so release logs stay useful without huge payloads or CPU churn.
 */
public final class ProdLogUtil {

    /** Default cap for HTTP bodies and similar blobs in log lines. */
    public static final int DEFAULT_MAX_CHARS = 720;
    /** Max characters from a stack trace when embedded in a single log message (full trace via throwable). */
    public static final int MAX_CRASH_MESSAGE_CHARS = 2000;

    private ProdLogUtil() {
    }

    public static String truncateForLog(CharSequence s) {
        return truncateForLog(s, DEFAULT_MAX_CHARS);
    }

    public static String truncateForLog(CharSequence s, int maxChars) {
        if (s == null) {
            return "";
        }
        if (maxChars <= 0) {
            return "";
        }
        String t = s.toString().replace('\r', ' ').replace('\n', ' ').trim();
        if (t.length() <= maxChars) {
            return t;
        }
        return t.substring(0, maxChars) + "…(len=" + t.length() + ")";
    }

    /**
     * Reduces accidental credential leakage in URLs (basic auth in authority).
     */
    public static String scrubUrlForLog(String url) {
        if (TextUtils.isEmpty(url)) {
            return "";
        }
        String u = url.trim();
        int schemeEnd = u.indexOf("://");
        if (schemeEnd < 0) {
            return truncateForLog(u, 400);
        }
        int at = u.indexOf('@', schemeEnd + 3);
        if (at > 0) {
            String prefix = u.substring(0, schemeEnd + 3);
            String rest = u.substring(at);
            u = prefix + "***" + rest;
        }
        return truncateForLog(u, 400);
    }

    /**
     * Compact {@link SyncResult} counters for end-of-sync diagnostics.
     */
    public static String formatSyncResultStats(SyncResult sr) {
        if (sr == null) {
            return "syncResult=null";
        }
        android.content.SyncStats st = sr.stats;
        return "syncStats io=" + st.numIoExceptions
                + " parse=" + st.numParseExceptions
                + " auth=" + st.numAuthExceptions
                + " conflict=" + st.numConflictDetectedExceptions
                + " ins=" + st.numInserts
                + " upd=" + st.numUpdates
                + " del=" + st.numDeletes
                + " ent=" + st.numEntries
                + " skip=" + st.numSkippedEntries
                + " delayUntil=" + sr.delayUntil;
    }

    /**
     * One line for failed vector sync HTTP (no credentials; body truncated).
     */
    public static String ngwHttpFailure(
            String operation,
            String layerName,
            long resourceRemoteId,
            long featureId,
            long attachId,
            HttpResponse response) {
        StringBuilder sb = new StringBuilder(280);
        sb.append("NGW HTTP ").append(operation)
                .append(" layer=\"").append(truncateForLog(layerName, 100)).append("\"")
                .append(" res=").append(resourceRemoteId)
                .append(" fid=").append(featureId);
        if (attachId >= 0) {
            sb.append(" aid=").append(attachId);
        }
        if (response != null) {
            sb.append(" http=").append(response.getResponseCode());
            String msg = response.getResponseMessage();
            if (!TextUtils.isEmpty(msg)) {
                sb.append(" msg=\"").append(truncateForLog(msg, 140)).append("\"");
            }
            String body = response.getResponseBody();
            if (!TextUtils.isEmpty(body)) {
                sb.append(" body=\"").append(truncateForLog(body, 420)).append("\"");
            }
        }
        return sb.toString();
    }

    public static String ngwPullHttpStatus(String layerName, long resourceRemoteId, int httpCode, String url) {
        return "NGW pull layer=\"" + truncateForLog(layerName, 100) + "\" res=" + resourceRemoteId
                + " http=" + httpCode + " url=" + scrubUrlForLog(url);
    }

    public static String crashHeadline(Thread thread, Throwable t) {
        if (t == null) {
            return "Uncaught (throwable=null) thread=" + (thread != null ? thread.getName() : "?");
        }
        String msg = t.getMessage();
        String oneLine = t.getClass().getSimpleName()
                + (TextUtils.isEmpty(msg) ? "" : ": " + truncateForLog(msg, 400));
        return "Uncaught thread=" + (thread != null ? thread.getName() : "?") + " " + oneLine;
    }
}

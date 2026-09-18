package com.nextgis.maplib.util;

import org.json.JSONException;
import org.json.JSONObject;

/** A committed count mismatch explained only by remote geometries absent locally. */
public final class NgwSnapshotCheckpoint {
    public static final long RECHECK_AFTER_MS = 60L * 60L * 1000L;

    private NgwSnapshotCheckpoint() { }

    public static String encode(String scope, int local, int remote, int missingInvalid,
                                long now) throws JSONException {
        if (missingInvalid <= 0 || remote <= 0 || local < 0
                || remote - local != missingInvalid) {
            return null;
        }
        return new JSONObject().put("scope", scope).put("local", local)
                .put("remote", remote).put("missingInvalid", missingInvalid)
                .put("committedAt", now).toString();
    }

    public static boolean canReuse(String encoded, String scope, int local, int remote,
                                   long now, boolean force) {
        if (force || encoded == null) return false;
        try {
            JSONObject saved = new JSONObject(encoded);
            long age = now - saved.getLong("committedAt");
            int missing = saved.getInt("missingInvalid");
            return age >= 0 && age < RECHECK_AFTER_MS && missing > 0
                    && scope.equals(saved.getString("scope"))
                    && local == saved.getInt("local") && remote == saved.getInt("remote")
                    && remote - local == missing;
        } catch (JSONException | RuntimeException ignored) {
            return false;
        }
    }
}

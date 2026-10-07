package com.nextgis.maplib.scripts;

import android.content.Context;
import android.util.Log;
import com.nextgis.maplib.map.CollectorProjectMetadata;
import com.nextgis.maplib.map.LayerGroup;
import com.nextgis.maplib.util.AccountUtil;
import com.nextgis.maplib.util.NetworkUtil;
import com.nextgis.maplib.util.NGWUtil;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

/** Called only from Collector import/sync workers, never from a form or a database transaction. */
public final class ProjectScriptSync {
    private ProjectScriptSync() { }
    public static void update(Context context, LayerGroup group, JSONObject config, long[] projectLayerIds) {
        CollectorProjectMetadata metadata = group.getCollectorProjectMetadata();
        if (metadata == null) return;
        JSONObject expected = config == null ? null : config.optJSONObject(ScriptReference.CONFIG_KEY);
        if (config == null || !config.has(ScriptReference.CONFIG_KEY) || config.isNull(ScriptReference.CONFIG_KEY)) {
            metadata.setScriptsState(null, null, null);
            group.save();
            return;
        }
        String raw = expected == null ? "{}" : expected.toString();
        String active = metadata.getScriptsActive();
        try {
            if (raw.length() > 32768) throw new IllegalArgumentException("Script reference size");
            ScriptReference reference = new ScriptReference(new JSONObject(raw));
            Set<Long> allowed = new HashSet<>();
            for (long id : projectLayerIds) allowed.add(id);
            var bindings = reference.json().getJSONObject("layer_bindings");
            var keys = bindings.keys();
            while (keys.hasNext()) if (!allowed.contains(bindings.getLong(keys.next())))
                throw new SecurityException("Script binding outside Collector composition");
            boolean cached;
            try { ScriptPackageStore.load(group, reference); cached = true; }
            catch (Exception missing) { cached = false; }
            if (!cached) {
                AccountUtil.AccountData account = AccountUtil.getAccountData(context, metadata.getAccountName());
                String url = NGWUtil.getResourceUrl(account.url, reference.resourceId)
                        + "/feature/" + reference.featureId + "/attachment/" + reference.attachmentId + "/download";
                HttpURLConnection connection = NetworkUtil.getHttpConnection("GET", url, account.login, account.password);
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                try {
                    if (connection.getResponseCode() != 200) throw new java.io.IOException("Package download rejected");
                    try (InputStream in = connection.getInputStream()) {
                        ScriptPackageStore.install(group, reference, ScriptPackageStore.boundedRead(in));
                    }
                } finally { connection.disconnect(); }
            }
            metadata.setScriptsState(raw, raw, "ready");
        } catch (Exception error) {
            // A failed update never replaces the previous validated package or hides its failure.
            metadata.setScriptsState(raw, active, "update_failed");
            Log.w("ProjectScripts", "Package update failed for " + metadata.getProjectUid(), error);
        }
        group.save();
    }
}

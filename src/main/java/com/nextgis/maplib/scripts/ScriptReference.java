package com.nextgis.maplib.scripts;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.Iterator;

/** Deployment-specific reference; source code and portable layer aliases stay in the ZIP. */
public final class ScriptReference {
    public static final String CONFIG_KEY = "lisa_project_scripts";
    public final String hash, version;
    public final long resourceId, featureId, attachmentId;
    public final boolean failClosed;
    private final String encoded;

    public ScriptReference(JSONObject json) throws JSONException {
        if (json.getInt("schema_version") != 1) throw new JSONException("Unsupported script reference");
        hash = json.getString("sha256");
        version = json.getString("version");
        if (!hash.matches("[a-f0-9]{64}") || !version.matches("[A-Za-z0-9._-]{1,64}"))
            throw new JSONException("Invalid package identity");
        resourceId = positiveId(json, "resource_id");
        featureId = positiveId(json, "feature_id");
        attachmentId = positiveId(json, "attachment_id");
        String policy = json.getString("failure_policy");
        if (!policy.equals("open") && !policy.equals("closed")) throw new JSONException("Invalid failure policy");
        failClosed = policy.equals("closed");
        JSONObject bindings = json.getJSONObject("layer_bindings");
        if (bindings.length() == 0 || bindings.length() > 32) throw new JSONException("Invalid layer count");
        Iterator<String> keys = bindings.keys();
        while (keys.hasNext()) {
            String alias = keys.next();
            if (!alias.matches("[a-z][a-z0-9_]{0,63}")) throw new JSONException("Invalid layer alias");
            positiveId(bindings, alias);
        }
        encoded = json.toString();
    }

    private static long positiveId(JSONObject json, String key) throws JSONException {
        Object value = json.get(key);
        if (!(value instanceof Integer || value instanceof Long) || ((Number)value).longValue() <= 0)
            throw new JSONException("Invalid numeric resource identity");
        return ((Number)value).longValue();
    }
    public long layerId(String alias) throws JSONException {
        return json().getJSONObject("layer_bindings").getLong(alias);
    }
    public JSONObject json() throws JSONException { return new JSONObject(encoded); }
    @Override public String toString() { return encoded; }
}

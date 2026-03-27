package com.nextgis.maplib.util;

import com.nextgis.maplib.datasource.Field;
import com.nextgis.maplib.map.VectorLayer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares a server-side layer config (from NGW resource description) against
 * the local layer state and classifies changes as soft (can be applied in-place)
 * or hard (require full layer rebuild/re-download).
 */
public final class LayerConfigDiff {

    public enum ChangeLevel { MATCH, SOFT, HARD }

    private ChangeLevel mLevel = ChangeLevel.MATCH;
    private String mHardReason;

    private final List<Field> mAddedFields = new ArrayList<>();
    private final Map<String, String> mAliasChanges = new LinkedHashMap<>();
    private boolean mRendererChanged;
    private boolean mVisibilityChanged;
    private boolean mZoomChanged;
    private boolean mNameChanged;
    private boolean mSyncSettingsChanged;

    private final JSONObject mServerConfig;

    private LayerConfigDiff(JSONObject serverConfig) {
        mServerConfig = serverConfig;
    }

    public ChangeLevel getLevel() { return mLevel; }
    public boolean isMatch() { return mLevel == ChangeLevel.MATCH; }
    public boolean isSoftOnly() { return mLevel == ChangeLevel.SOFT; }
    public boolean isHard() { return mLevel == ChangeLevel.HARD; }
    public String getHardReason() { return mHardReason; }

    public List<Field> getAddedFields() { return mAddedFields; }
    public Map<String, String> getAliasChanges() { return mAliasChanges; }
    public boolean isRendererChanged() { return mRendererChanged; }
    public boolean isVisibilityChanged() { return mVisibilityChanged; }
    public boolean isZoomChanged() { return mZoomChanged; }
    public boolean isNameChanged() { return mNameChanged; }
    public boolean isSyncSettingsChanged() { return mSyncSettingsChanged; }
    public JSONObject getServerConfig() { return mServerConfig; }

    /**
     * Compare server config JSON against local VectorLayer and produce a diff.
     *
     * @param serverConfig parsed JSON from NGW resource description
     * @param local        local VectorLayer to compare against
     * @return diff object with classified changes
     */
    public static LayerConfigDiff compare(JSONObject serverConfig, VectorLayer local) {
        LayerConfigDiff diff = new LayerConfigDiff(serverConfig);
        if (serverConfig == null || local == null) {
            return diff;
        }

        try {
            diff.compareGeometryType(serverConfig, local);
            if (diff.isHard()) return diff;

            diff.compareFields(serverConfig, local);
            if (diff.isHard()) return diff;

            diff.compareRenderer(serverConfig, local);
            diff.compareVisibility(serverConfig, local);
            diff.compareZoom(serverConfig, local);
            diff.compareName(serverConfig, local);
            diff.compareSyncSettings(serverConfig, local);
        } catch (JSONException e) {
            // parse error in config -- treat as match (fail open, don't break sync)
        }
        return diff;
    }

    private void compareGeometryType(JSONObject cfg, VectorLayer local) throws JSONException {
        if (!cfg.has("geometry_type")) return;
        int serverType = cfg.getInt("geometry_type");
        if (serverType != local.getGeometryType()) {
            mLevel = ChangeLevel.HARD;
            mHardReason = "geometry_type changed: local=" + local.getGeometryType()
                    + " server=" + serverType;
        }
    }

    private void compareFields(JSONObject cfg, VectorLayer local) throws JSONException {
        if (!cfg.has("fields")) return;
        JSONArray serverFields = cfg.getJSONArray("fields");
        List<Field> localFields = local.getFields();

        Map<String, Field> localByName = new LinkedHashMap<>();
        for (Field f : localFields) {
            localByName.put(f.getName().toLowerCase(java.util.Locale.ROOT), f);
        }

        for (int i = 0; i < serverFields.length(); i++) {
            JSONObject sf = serverFields.getJSONObject(i);
            String sfName = sf.getString(Constants.JSON_NAME_KEY)
                    .toLowerCase(java.util.Locale.ROOT);
            int sfType = sf.getInt(Constants.JSON_TYPE_KEY);

            Field localField = localByName.get(sfName);
            if (localField == null) {
                String alias = sf.optString("alias", sfName);
                mAddedFields.add(new Field(sfType, sf.getString(Constants.JSON_NAME_KEY), alias));
                markSoft();
            } else if (localField.getType() != sfType) {
                mLevel = ChangeLevel.HARD;
                mHardReason = "field type changed: " + sfName
                        + " local=" + localField.getType() + " server=" + sfType;
                return;
            } else {
                String serverAlias = sf.optString("alias", "");
                if (!serverAlias.isEmpty() && !serverAlias.equals(localField.getAlias())) {
                    mAliasChanges.put(localField.getName(), serverAlias);
                    markSoft();
                }
            }
        }
    }

    private void compareRenderer(JSONObject cfg, VectorLayer local) throws JSONException {
        if (!cfg.has(Constants.JSON_RENDERERPROPS_KEY)) return;
        JSONObject serverRenderer = cfg.getJSONObject(Constants.JSON_RENDERERPROPS_KEY);
        try {
            JSONObject localConfig = local.toJSON();
            if (localConfig.has(Constants.JSON_RENDERERPROPS_KEY)) {
                JSONObject localRenderer = localConfig.getJSONObject(Constants.JSON_RENDERERPROPS_KEY);
                if (!jsonEquals(serverRenderer, localRenderer)) {
                    mRendererChanged = true;
                    markSoft();
                }
            } else {
                mRendererChanged = true;
                markSoft();
            }
        } catch (JSONException e) {
            mRendererChanged = true;
            markSoft();
        }
    }

    private void compareVisibility(JSONObject cfg, VectorLayer local) {
        if (!cfg.has(Constants.JSON_VISIBILITY_KEY)) return;
        boolean serverVis = cfg.optBoolean(Constants.JSON_VISIBILITY_KEY, true);
        if (serverVis != local.isVisible()) {
            mVisibilityChanged = true;
            markSoft();
        }
    }

    private void compareZoom(JSONObject cfg, VectorLayer local) {
        if (cfg.has(Constants.JSON_MAXLEVEL_KEY)) {
            float serverMax = (float) cfg.optDouble(Constants.JSON_MAXLEVEL_KEY, local.getMaxZoom());
            if (Float.compare(serverMax, local.getMaxZoom()) != 0) {
                mZoomChanged = true;
                markSoft();
            }
        }
        if (cfg.has(Constants.JSON_MINLEVEL_KEY)) {
            float serverMin = (float) cfg.optDouble(Constants.JSON_MINLEVEL_KEY, local.getMinZoom());
            if (Float.compare(serverMin, local.getMinZoom()) != 0) {
                mZoomChanged = true;
                markSoft();
            }
        }
    }

    private void compareName(JSONObject cfg, VectorLayer local) {
        if (!cfg.has(Constants.JSON_NAME_KEY)) return;
        String serverName = cfg.optString(Constants.JSON_NAME_KEY, "");
        if (!serverName.isEmpty() && !serverName.equals(local.getName())) {
            mNameChanged = true;
            markSoft();
        }
    }

    private void compareSyncSettings(JSONObject cfg, VectorLayer local) {
        final String[] keys = {"sync_type", "sync_direction", "tracked", "server_where", "is_editable"};
        try {
            JSONObject localCfg = local.toJSON();
            for (String k : keys) {
                if (cfg.has(k)) {
                    Object sv = cfg.opt(k);
                    Object lv = localCfg.opt(k);
                    if (sv != null && !sv.equals(lv)) {
                        mSyncSettingsChanged = true;
                        markSoft();
                        return;
                    }
                }
            }
        } catch (JSONException ignored) {}
    }

    private void markSoft() {
        if (mLevel == ChangeLevel.MATCH) {
            mLevel = ChangeLevel.SOFT;
        }
    }

    private static boolean jsonEquals(JSONObject a, JSONObject b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        Iterator<String> keys = a.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            if (!b.has(k)) return false;
            Object va = a.opt(k);
            Object vb = b.opt(k);
            if (va instanceof JSONObject && vb instanceof JSONObject) {
                if (!jsonEquals((JSONObject) va, (JSONObject) vb)) return false;
            } else if (va instanceof JSONArray && vb instanceof JSONArray) {
                if (!jsonArrayEquals((JSONArray) va, (JSONArray) vb)) return false;
            } else {
                if (va == null && vb == null) continue;
                if (va == null || !va.toString().equals(String.valueOf(vb))) return false;
            }
        }
        return true;
    }

    private static boolean jsonArrayEquals(JSONArray a, JSONArray b) {
        if (a.length() != b.length()) return false;
        for (int i = 0; i < a.length(); i++) {
            Object va = a.opt(i);
            Object vb = b.opt(i);
            if (va instanceof JSONObject && vb instanceof JSONObject) {
                if (!jsonEquals((JSONObject) va, (JSONObject) vb)) return false;
            } else if (va instanceof JSONArray && vb instanceof JSONArray) {
                if (!jsonArrayEquals((JSONArray) va, (JSONArray) vb)) return false;
            } else {
                if (va == null && vb == null) continue;
                if (va == null || !va.toString().equals(String.valueOf(vb))) return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        if (isMatch()) return "LayerConfigDiff[MATCH]";
        StringBuilder sb = new StringBuilder("LayerConfigDiff[").append(mLevel);
        if (mHardReason != null) sb.append(" reason=").append(mHardReason);
        if (!mAddedFields.isEmpty()) sb.append(" addedFields=").append(mAddedFields.size());
        if (!mAliasChanges.isEmpty()) sb.append(" aliasChanges=").append(mAliasChanges.keySet());
        if (mRendererChanged) sb.append(" renderer");
        if (mVisibilityChanged) sb.append(" visibility");
        if (mZoomChanged) sb.append(" zoom");
        if (mNameChanged) sb.append(" name");
        if (mSyncSettingsChanged) sb.append(" syncSettings");
        sb.append("]");
        return sb.toString();
    }
}

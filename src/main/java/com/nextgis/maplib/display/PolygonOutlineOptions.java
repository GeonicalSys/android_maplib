package com.nextgis.maplib.display;

import org.json.JSONException;
import org.json.JSONObject;

/** Constant native stroke layout, with an optional wider secondary casing behind the contour. */
public final class PolygonOutlineOptions implements Cloneable {
    private boolean enabled = true;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public static final String JSON_KEY = "outline_options";
    public static final int SOLID = 0, DASH = 1, DOTS = 2, DASH_DOT = 3, CUSTOM = 4;
    private int type, cap = MplStyleMapper.LINE_CAP_ROUND, join = MplStyleMapper.LINE_JOIN_ROUND;
    private String dashArray;
    private float miter = 2, offset, gap, casingWidth = 2;
    private boolean casing;
    private int casingColor = 0xffffffff, casingAlpha = 255;
    public int getType() { return type; }
    public void setType(int value) { type = Math.max(0, Math.min(CUSTOM, value)); }
    public int getCap() { return cap; }
    public void setCap(int value) { cap = Math.max(0, Math.min(2, value)); }
    public int getJoin() { return join; }
    public void setJoin(int value) { join = Math.max(0, Math.min(2, value)); }
    public float getMiter() { return miter; }
    public void setMiter(float value) { miter = PolygonPatternOptions.clamp(value, 1, 10, 2); }
    public float getOffset() { return offset; }
    public void setOffset(float value) { offset = PolygonPatternOptions.clamp(value, -64, 64, 0); }
    public float getGap() { return gap; }
    public void setGap(float value) { gap = PolygonPatternOptions.clamp(value, 0, 64, 0); }
    public String getDashArray() { return dashArray; }
    public void setDashArray(String value) { dashArray = value == null ? null : value.trim(); }
    public boolean isCasing() { return casing; }
    public void setCasing(boolean value) { casing = value; }
    public float getCasingWidth() { return casingWidth; }
    public void setCasingWidth(float value) { casingWidth = PolygonPatternOptions.clamp(value, 0, 32, 2); }
    public int getCasingColor() { return casingColor; }
    public void setCasingColor(int value) { casingColor = value | 0xff000000; }
    public int getCasingAlpha() { return casingAlpha; }
    public void setCasingAlpha(int value) { casingAlpha = Math.max(0, Math.min(255, value)); }
    public Float[] dashes() {
        if (type == SOLID) return null;
        return MplStyleMapper.dashArray(type == CUSTOM ? dashArray : null,
                type == DOTS ? MplStyleMapper.DASH_PRESET_DOTS
                        : type == DASH_DOT ? MplStyleMapper.DASH_PRESET_DOT_DASH : MplStyleMapper.DASH_PRESET_SHORT);
    }
    @Override public PolygonOutlineOptions clone() {
        try { return (PolygonOutlineOptions) super.clone(); }
        catch (CloneNotSupportedException error) { throw new AssertionError(error); }
    }
    public JSONObject toJSON() throws JSONException {
        return new JSONObject().put("version", 1).put("enabled", enabled).put("type", type).put("cap", cap).put("join", join)
                .put("miter", miter).put("offset", offset).put("gap", gap).put("dash_array", dashArray)
                .put("casing", casing).put("casing_width", casingWidth).put("casing_color", casingColor)
                .put("casing_alpha", casingAlpha);
    }
    public static PolygonOutlineOptions fromJSON(JSONObject json) {
        PolygonOutlineOptions result = new PolygonOutlineOptions();
        result.setEnabled(json.optBoolean("enabled", true));
        result.setType(json.optInt("type")); result.setCap(json.optInt("cap", MplStyleMapper.LINE_CAP_ROUND));
        result.setJoin(json.optInt("join", MplStyleMapper.LINE_JOIN_ROUND)); result.setMiter((float) json.optDouble("miter", 2));
        result.setOffset((float) json.optDouble("offset")); result.setGap((float) json.optDouble("gap"));
        result.setDashArray(json.optString("dash_array", null)); result.setCasing(json.optBoolean("casing"));
        result.setCasingWidth((float) json.optDouble("casing_width", 2)); result.setCasingColor(json.optInt("casing_color", 0xffffffff));
        result.setCasingAlpha(json.optInt("casing_alpha", 255)); return result;
    }
    public String key() {
        try { return toJSON().toString(); }
        catch (JSONException error) { throw new IllegalStateException(error); }
    }
}

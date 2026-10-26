package com.nextgis.maplib.display;

import org.json.JSONException;
import org.json.JSONObject;

/** Optional v1 procedural pattern. Absence deliberately preserves the original bundled bitmap. */
public final class PolygonPatternOptions implements Cloneable {
    private boolean enabled = true;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public static final String JSON_KEY = "pattern_options";
    private float angle = 45, secondAngle = 135, spacing = 12, width = 2;
    private int color = 0xff000000, alpha = 153;
    public float getAngle() { return angle; }
    public void setAngle(float value) { angle = angle(value, 45); }
    public float getSecondAngle() { return secondAngle; }
    public void setSecondAngle(float value) { secondAngle = angle(value, 135); }
    public float getSpacing() { return spacing; }
    public void setSpacing(float value) { spacing = clamp(value, 4, 64, 12); }
    public float getWidth() { return width; }
    public void setWidth(float value) { width = clamp(value, .5f, 16, 2); }
    public int getColor() { return color; }
    public void setColor(int value) { color = value | 0xff000000; }
    public int getAlpha() { return alpha; }
    public void setAlpha(int value) { alpha = Math.max(0, Math.min(255, value)); }
    static float clamp(float value, float min, float max, float fallback) {
        return Float.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }
    private static float angle(float value, float fallback) {
        return Float.isFinite(value) ? ((value % 180) + 180) % 180 : fallback;
    }
    @Override public PolygonPatternOptions clone() {
        try { return (PolygonPatternOptions) super.clone(); }
        catch (CloneNotSupportedException error) { throw new AssertionError(error); }
    }
    public JSONObject toJSON() throws JSONException {
        return new JSONObject().put("version", 1).put("enabled", enabled).put("angle", angle).put("second_angle", secondAngle)
                .put("spacing", spacing).put("width", width).put("color", color).put("alpha", alpha);
    }
    public static PolygonPatternOptions fromJSON(JSONObject json) {
        PolygonPatternOptions result = new PolygonPatternOptions();
        result.setEnabled(json.optBoolean("enabled", true));
        result.setAngle((float) json.optDouble("angle", 45));
        result.setSecondAngle((float) json.optDouble("second_angle", 135));
        result.setSpacing((float) json.optDouble("spacing", 12));
        result.setWidth((float) json.optDouble("width", 2));
        result.setColor(json.optInt("color", 0xff000000));
        result.setAlpha(json.optInt("alpha", 153));
        return result;
    }
    public String key() {
        return angle + "_" + secondAngle + "_" + spacing + "_" + width + "_" + color + "_" + alpha;
    }
}

package com.nextgis.maplib.map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import static com.nextgis.maplib.map.SqliteVectorTileBackend.UnsupportedTileException;

/** MVT scalar transport; known pair arrays are rebuilt by native semiliteral expressions. */
public final class NativeVectorTilePropertyCodec {
    public static final String TEXT_OFFSET_X = "__ng_text_offset_x", TEXT_OFFSET_Y = "__ng_text_offset_y";
    public static final String ICON_OFFSET_X = "__ng_icon_offset_x", ICON_OFFSET_Y = "__ng_icon_offset_y";
    private NativeVectorTilePropertyCodec() { }

    static Map<String, Object> encode(JsonObject properties) throws UnsupportedTileException {
        Map<String, Object> out = new LinkedHashMap<>();
        if (properties == null) return out;
        for (Map.Entry<String, JsonElement> entry : properties.entrySet()) {
            String key = entry.getKey(); JsonElement value = entry.getValue();
            // MVT has no null. Known renderer coalesce defaults treat null and absence identically.
            if (value == null || value.isJsonNull()) continue;
            if (key.equals(MplFeatureStyleProps.TEXT_OFFSETS) || key.equals(MplFeatureStyleProps.ICON_OFFSET)) {
                String x = key.equals(MplFeatureStyleProps.TEXT_OFFSETS) ? TEXT_OFFSET_X : ICON_OFFSET_X;
                String y = key.equals(MplFeatureStyleProps.TEXT_OFFSETS) ? TEXT_OFFSET_Y : ICON_OFFSET_Y;
                if (properties.has(x) || properties.has(y) || !value.isJsonArray()) throw unsupported(key);
                JsonArray pair = value.getAsJsonArray();
                if (pair.size() != 2) throw unsupported(key);
                Object a = scalar(pair.get(0), key), b = scalar(pair.get(1), key);
                if (!(a instanceof Number) || !(b instanceof Number)) throw unsupported(key);
                out.put(x, a); out.put(y, b);
            } else out.put(key, scalar(value, key));
        }
        return out;
    }

    private static Object scalar(JsonElement element, String key) throws UnsupportedTileException {
        if (element == null || !element.isJsonPrimitive()) throw unsupported(key);
        JsonPrimitive value = element.getAsJsonPrimitive();
        if (value.isBoolean()) return value.getAsBoolean();
        if (value.isString()) return value.getAsString();
        if (value.isNumber()) {
            try {
                Number raw = value.getAsNumber();
                if (raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long)
                    return raw.longValue();
                if (raw instanceof Float || raw instanceof Double) {
                    double decimal = raw.doubleValue();
                    if (Double.isFinite(decimal)) return decimal;
                    throw unsupported(key);
                }
                BigDecimal number = new BigDecimal(value.getAsString());
                try { return number.longValueExact(); } catch (ArithmeticException fractionalOrLarge) { }
                double decimal = number.doubleValue();
                if (Double.isFinite(decimal)) return decimal;
            } catch (NumberFormatException invalid) { }
        }
        throw unsupported(key);
    }
    private static UnsupportedTileException unsupported(String key) {
        return new UnsupportedTileException("Unsupported native tile property: " + key);
    }
}

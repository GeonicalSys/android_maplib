package com.nextgis.maplib.util;

import com.nextgis.maplib.datasource.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static com.nextgis.maplib.util.GeoConstants.*;

/** Lossless geometry compatibility and schema-key matching for a new, independent row. */
public final class FeatureCopyPolicy {
    private FeatureCopyPolicy() { }

    public static boolean accepts(GeoGeometry source, int targetType) {
        if (source == null || !source.isValid()) return false;
        int single = singleType(source.getType());
        if (single == GTNone || single != singleType(targetType)) return false;
        return source.getType() == targetType || targetType != single
                || source instanceof GeoGeometryCollection && ((GeoGeometryCollection) source).size() == 1;
    }

    public static GeoGeometry geometry(GeoGeometry source, int targetType) {
        if (!accepts(source, targetType)) throw new IllegalArgumentException("Incompatible geometry");
        GeoGeometry result = GeometryTransform.copy(source);
        if (result.getType() == targetType) return result;
        if (targetType == singleType(targetType))
            return GeometryTransform.copy(((GeoGeometryCollection) result).get(0));
        GeoGeometryCollection multi;
        switch (targetType) {
            case GTMultiPoint: multi = new GeoMultiPoint(); break;
            case GTMultiLineString: multi = new GeoMultiLineString(); break;
            case GTMultiPolygon: multi = new GeoMultiPolygon(); break;
            default: throw new IllegalArgumentException("Unsupported target geometry");
        }
        multi.setCRS(source.getCRS());
        multi.add(result);
        return multi;
    }

    private static int singleType(int type) {
        switch (type) {
            case GTPoint: case GTMultiPoint: return GTPoint;
            case GTLineString: case GTMultiLineString: return GTLineString;
            case GTPolygon: case GTMultiPolygon: return GTPolygon;
            default: return GTNone;
        }
    }

    public static Map<String, Object> attributes(Feature source, List<Field> targetFields) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Field target : targetFields) {
            String name = target.getName();
            if (Constants.FIELD_ID.equals(name) || Constants.FIELD_GEOM.equals(name)) continue;
            for (Field field : source.getFields()) {
                if (!name.equals(field.getName())) continue;
                int from = field.getType(), to = target.getType();
                // Equal schema types, plus lossless widening of 32-bit integers.
                if (from == to || from == FTInteger && (to == FTLong || to == FTReal))
                    values.put(name, source.getFieldValue(name));
                break;
            }
        }
        return values;
    }
}

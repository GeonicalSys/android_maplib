package com.nextgis.maplib.util;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;

import java.math.BigDecimal;
import java.util.Iterator;

/** Exact submitted-data comparison; similarity is never evidence of feature identity. */
public final class NgwFeatureData {
    private NgwFeatureData() { }

    public static boolean sameSubmittedData(JSONObject submitted, JSONObject actual) throws Exception {
        Iterator<String> keys = submitted.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!actual.has(key)) return false;
            Object left = submitted.get(key), right = actual.get(key);
            if ("geom".equals(key) && left instanceof String && right instanceof String) {
                if (!sameGeometry((String) left, (String) right)) return false;
            } else if (!sameValue(left, right)) return false;
        }
        return true;
    }

    private static boolean sameValue(Object left, Object right) throws Exception {
        if (left instanceof JSONObject && right instanceof JSONObject)
            return sameSubmittedData((JSONObject) left, (JSONObject) right);
        if (left instanceof JSONArray && right instanceof JSONArray) {
            JSONArray a = (JSONArray) left, b = (JSONArray) right;
            if (a.length() != b.length()) return false;
            for (int i = 0; i < a.length(); i++) if (!sameValue(a.get(i), b.get(i))) return false;
            return true;
        }
        if (left instanceof Number && right instanceof Number)
            return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString())) == 0;
        return left.equals(right);
    }

    public static boolean sameGeometry(String left, String right) throws ParseException {
        WKTReader reader = new WKTReader();
        return sameGeometry(reader.read(left), reader.read(right));
    }

    private static boolean sameGeometry(Geometry left, Geometry right) {
        if (!left.getGeometryType().equals(right.getGeometryType()) || left.isEmpty() != right.isEmpty()) return false;
        if (left instanceof Point)
            return sameCoordinates(((Point) left).getCoordinateSequence(), ((Point) right).getCoordinateSequence());
        if (left instanceof LineString)
            return sameCoordinates(((LineString) left).getCoordinateSequence(), ((LineString) right).getCoordinateSequence());
        if (left instanceof Polygon) {
            Polygon a = (Polygon) left, b = (Polygon) right;
            if (a.getNumInteriorRing() != b.getNumInteriorRing()
                    || !sameGeometry(a.getExteriorRing(), b.getExteriorRing())) return false;
            for (int i = 0; i < a.getNumInteriorRing(); i++)
                if (!sameGeometry(a.getInteriorRingN(i), b.getInteriorRingN(i))) return false;
            return true;
        }
        if (left instanceof GeometryCollection) {
            if (left.getNumGeometries() != right.getNumGeometries()) return false;
            for (int i = 0; i < left.getNumGeometries(); i++)
                if (!sameGeometry(left.getGeometryN(i), right.getGeometryN(i))) return false;
            return true;
        }
        return false;
    }

    private static boolean sameCoordinates(CoordinateSequence a, CoordinateSequence b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!Double.isFinite(a.getX(i)) || !Double.isFinite(a.getY(i))
                    || a.getX(i) != b.getX(i) || a.getY(i) != b.getY(i)
                    || !sameOrdinate(a.getZ(i), b.getZ(i)) || !sameOrdinate(a.getM(i), b.getM(i))) return false;
        }
        return true;
    }

    private static boolean sameOrdinate(double a, double b) {
        return a == b || Double.isNaN(a) && Double.isNaN(b);
    }

    /** Extracts the resource CRS, which may differ from the external PostGIS storage CRS. */
    public static int resourceSrs(JSONObject metadata) {
        JSONObject resource = metadata.optJSONObject("resource");
        JSONObject component = resource == null ? null : metadata.optJSONObject(resource.optString("cls"));
        JSONObject srs = component == null ? null : component.optJSONObject("srs");
        Object id = srs == null ? null : srs.opt("id");
        if (!(id instanceof Integer || id instanceof Long)) return -1;
        long value = ((Number) id).longValue();
        return value > 0 && value <= Integer.MAX_VALUE ? (int) value : -1;
    }
}

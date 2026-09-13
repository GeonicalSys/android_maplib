package com.nextgis.maplib.map;

import com.nextgis.maplib.datasource.GeoGeometry;
import com.nextgis.maplib.datasource.GeoGeometryCollection;
import com.nextgis.maplib.datasource.GeoLineString;
import com.nextgis.maplib.datasource.GeoLinearRing;
import com.nextgis.maplib.datasource.GeoPoint;
import com.nextgis.maplib.datasource.GeoPolygon;
import com.nextgis.maplib.util.GeoConstants;

import org.json.JSONException;
import org.json.JSONObject;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;

import java.util.ArrayList;
import java.util.List;

/** GeoJSON suitable for a passive preview, including one-node and unfinished polygon drafts. */
public final class WalkPreviewGeometry {
    private WalkPreviewGeometry() { }

    public static FeatureCollection build(GeoGeometry geometry) throws JSONException {
        List<Feature> features = new ArrayList<>();
        if (geometry != null) {
            GeoGeometry copy = geometry.copy();
            // Geometry copy constructors do not retain the root CRS. Without restoring it,
            // project() returns false and metre coordinates reach GeoJSON as lon/lat.
            copy.setCRS(geometry.getCRS());
            if (copy.getCRS() != GeoConstants.CRS_WGS84 && !copy.project(GeoConstants.CRS_WGS84))
                throw new IllegalArgumentException("Walk preview requires a known coordinate reference system");
            append(copy, features);
        }
        return FeatureCollection.fromFeatures(features);
    }

    private static void append(GeoGeometry geometry, List<Feature> features) throws JSONException {
        if (geometry instanceof GeoGeometryCollection) {
            GeoGeometryCollection parts = (GeoGeometryCollection) geometry;
            for (int i = 0; i < parts.size(); i++) append(parts.get(i), features);
        } else if (geometry instanceof GeoPolygon) {
            GeoPolygon polygon = (GeoPolygon) geometry;
            boolean fill = polygon.getOuterRing().getPointCount() >= 3;
            for (int i = 0; i < polygon.getInnerRingCount(); i++)
                fill &= polygon.getInnerRing(i).getPointCount() >= 3;
            if (fill) {
                close(polygon.getOuterRing());
                for (int i = 0; i < polygon.getInnerRingCount(); i++) close(polygon.getInnerRing(i));
                add(polygon, features);
            } else {
                append(polygon.getOuterRing(), features);
                for (int i = 0; i < polygon.getInnerRingCount(); i++) append(polygon.getInnerRing(i), features);
            }
        } else if (geometry instanceof GeoLineString) {
            GeoLineString line = (GeoLineString) geometry;
            if (line.getPointCount() == 1) add(line.getPoint(0), features);
            else if (line.getPointCount() > 1) {
                GeoLineString visible = new GeoLineString();
                visible.setCRS(GeoConstants.CRS_WGS84);
                for (int i = 0; i < line.getPointCount(); i++) visible.add(line.getPoint(i));
                add(visible, features);
            }
        }
    }

    private static void close(GeoLinearRing ring) {
        if (!ring.isClosed()) ring.add((GeoPoint) ring.getPoint(0).copy());
    }

    private static void add(GeoGeometry geometry, List<Feature> features) throws JSONException {
        features.add(Feature.fromJson(new JSONObject().put("type", "Feature")
                .put("properties", new JSONObject()).put("geometry", geometry.toJSON()).toString()));
    }
}

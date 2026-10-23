package com.nextgis.maplib.util;

import com.nextgis.maplib.datasource.*;
import java.util.function.Consumer;

/** Rigid edits in the map's projected coordinates; every component belongs to the same edit. */
public final class GeometryTransform {
    private GeometryTransform() { }

    public static GeoGeometry copy(GeoGeometry geometry) {
        GeoGeometry result = geometry.copy();
        // Several legacy copy constructors do not retain the container CRS.
        setCrs(result, geometry.getCRS());
        return result;
    }

    public static GeoGeometry apply(GeoGeometry geometry, double dx, double dy,
                                    double angle, double cx, double cy) {
        if (geometry.getCRS() != GeoConstants.CRS_WEB_MERCATOR
                || !Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(angle)
                || !Double.isFinite(cx) || !Double.isFinite(cy))
            throw new IllegalArgumentException("A finite projected transform is required");
        GeoGeometry result = copy(geometry);
        double cos = Math.cos(angle), sin = Math.sin(angle);
        points(result, point -> {
            double x = point.getX() - cx, y = point.getY() - cy;
            point.setCoordinates(cx + x * cos - y * sin + dx,
                                 cy + x * sin + y * cos + dy);
        });
        return result;
    }

    private static void setCrs(GeoGeometry geometry, int crs) {
        geometry.setCRS(crs);
        if (geometry instanceof GeoPolygon) {
            GeoPolygon polygon = (GeoPolygon) geometry;
            setCrs(polygon.getOuterRing(), crs);
            for (GeoLinearRing ring : polygon.getInnerRings()) setCrs(ring, crs);
        } else if (geometry instanceof GeoGeometryCollection) {
            GeoGeometryCollection collection = (GeoGeometryCollection) geometry;
            for (int i = 0; i < collection.size(); i++) setCrs(collection.get(i), crs);
        } else if (geometry instanceof GeoLineString) {
            for (GeoPoint point : ((GeoLineString) geometry).getPoints()) point.setCRS(crs);
        }
    }

    private static void points(GeoGeometry geometry, Consumer<GeoPoint> action) {
        if (geometry instanceof GeoPoint) action.accept((GeoPoint) geometry);
        else if (geometry instanceof GeoLineString)
            ((GeoLineString) geometry).getPoints().forEach(action);
        else if (geometry instanceof GeoPolygon) {
            GeoPolygon polygon = (GeoPolygon) geometry;
            points(polygon.getOuterRing(), action);
            for (GeoLinearRing ring : polygon.getInnerRings()) points(ring, action);
        } else if (geometry instanceof GeoGeometryCollection) {
            GeoGeometryCollection collection = (GeoGeometryCollection) geometry;
            for (int i = 0; i < collection.size(); i++) points(collection.get(i), action);
        } else throw new IllegalArgumentException("Unsupported editable geometry");
    }
}

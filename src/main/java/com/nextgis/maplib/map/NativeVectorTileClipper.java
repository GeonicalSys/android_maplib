package com.nextgis.maplib.map;

import com.nextgis.maplib.datasource.GeoGeometry;
import com.nextgis.maplib.datasource.GeoGeometryCollection;
import com.nextgis.maplib.datasource.GeoLineString;
import com.nextgis.maplib.datasource.GeoPoint;
import com.nextgis.maplib.datasource.GeoPolygon;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.operation.overlayng.OverlayNG;
import org.locationtech.jts.operation.overlayng.OverlayNGRobust;
import java.util.ArrayList;
import java.util.List;
import static com.nextgis.maplib.util.GeoConstants.*;
import static com.nextgis.maplib.map.SqliteVectorTileBackend.UnsupportedTileException;

/** Full-precision rectangular intersection only: no simplification, repair, LOD or DB writes. */
final class NativeVectorTileClipper {
    static final GeometryFactory FACTORY = new GeometryFactory();
    private NativeVectorTileClipper() { }

    static Geometry clip(GeoGeometry original, NativeVectorTileEncoder.Bounds bounds)
            throws UnsupportedTileException {
        Geometry geometry = convert(original);
        try {
            Envelope clip = new Envelope(bounds.queryMinX(), bounds.queryMaxX(), bounds.queryMinY(), bounds.queryMaxY());
            // Most small objects are wholly inside their tile; avoid overlay for these hot cases.
            if (clip.contains(geometry.getEnvelopeInternal())) return geometry;
            if (!clip.intersects(geometry.getEnvelopeInternal())) return FACTORY.createGeometryCollection();
            Geometry result = OverlayNGRobust.overlay(geometry,
                    FACTORY.toGeometry(clip), OverlayNG.INTERSECTION);
            List<Geometry> parts = new ArrayList<>();
            collectDimension(result, geometry.getDimension(), parts);
            return FACTORY.buildGeometry(parts);
        } catch (RuntimeException unsupported) {
            throw new UnsupportedTileException("Native tile geometry intersection failed", unsupported);
        }
    }

    private static void collectDimension(Geometry geometry, int dimension, List<Geometry> out) {
        if (geometry.isEmpty()) return;
        if (geometry instanceof GeometryCollection) {
            for (int i = 0; i < geometry.getNumGeometries(); i++)
                collectDimension(geometry.getGeometryN(i), dimension, out);
        } else if (geometry.getDimension() == dimension) out.add(geometry);
    }

    static Geometry convert(GeoGeometry geometry) throws UnsupportedTileException {
        if (geometry == null) throw new UnsupportedTileException("Missing native tile geometry");
        try {
            switch (geometry.getType()) {
                case GTPoint: return FACTORY.createPoint(coordinate((GeoPoint) geometry));
                case GTLineString: return FACTORY.createLineString(coordinates((GeoLineString) geometry, false));
                case GTPolygon: {
                    GeoPolygon polygon = (GeoPolygon) geometry;
                    LinearRing outer = FACTORY.createLinearRing(coordinates(polygon.getOuterRing(), true));
                    LinearRing[] holes = new LinearRing[polygon.getInnerRingCount()];
                    for (int i = 0; i < holes.length; i++)
                        holes[i] = FACTORY.createLinearRing(coordinates(polygon.getInnerRing(i), true));
                    return FACTORY.createPolygon(outer, holes);
                }
                case GTMultiPoint: case GTMultiLineString: case GTMultiPolygon: {
                    GeoGeometryCollection multi = (GeoGeometryCollection) geometry;
                    List<Geometry> parts = new ArrayList<>(multi.size());
                    for (int i = 0; i < multi.size(); i++) parts.add(convert(multi.get(i)));
                    return FACTORY.buildGeometry(parts);
                }
                default: throw new UnsupportedTileException("Unsupported native tile geometry type");
            }
        } catch (RuntimeException invalid) {
            throw new UnsupportedTileException("Invalid native tile geometry", invalid);
        }
    }

    private static Coordinate coordinate(GeoPoint point) {
        if (point == null || !Double.isFinite(point.getX()) || !Double.isFinite(point.getY()))
            throw new IllegalArgumentException("Non-finite native tile coordinate");
        return new Coordinate(point.getX(), point.getY());
    }
    private static Coordinate[] coordinates(GeoLineString line, boolean close) {
        List<Coordinate> result = new ArrayList<>();
        for (GeoPoint point : line.getPoints()) result.add(coordinate(point));
        if (close && !result.isEmpty() && !result.get(0).equals2D(result.get(result.size() - 1)))
            result.add(new Coordinate(result.get(0)));
        return result.toArray(new Coordinate[0]);
    }
}

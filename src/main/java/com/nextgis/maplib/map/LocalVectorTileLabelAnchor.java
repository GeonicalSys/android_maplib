package com.nextgis.maplib.map;

import com.nextgis.maplib.datasource.GeoGeometry;
import com.nextgis.maplib.datasource.GeoLinearRing;
import com.nextgis.maplib.datasource.GeoMultiPolygon;
import com.nextgis.maplib.datasource.GeoPoint;
import com.nextgis.maplib.datasource.GeoPolygon;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;

import java.util.ArrayList;
import java.util.List;

/** One interior anchor in the original geometry's CRS; never retains the source geometry. */
final class LocalVectorTileLabelAnchor {
    private static final GeometryFactory FACTORY = new GeometryFactory();

    private LocalVectorTileLabelAnchor() { }

    static GeoPoint create(GeoGeometry geometry) {
        return create(geometry, null);
    }

    static GeoPoint create(GeoGeometry geometry, LocalVectorTileEncoder.TileBounds bounds) {
        GeoPoint best = null;
        Polygon bestPolygon = null;
        double largestArea = 0;
        int count = geometry instanceof GeoMultiPolygon ? ((GeoMultiPolygon) geometry).size() : 1;
        for (int i = 0; i < count; i++) {
            GeoGeometry part = geometry instanceof GeoMultiPolygon
                    ? ((GeoMultiPolygon) geometry).get(i) : geometry;
            if (!(part instanceof GeoPolygon)) continue;
            try {
                GeoPolygon source = (GeoPolygon) part;
                LinearRing shell = ring(source.getOuterRing());
                LinearRing[] holes = new LinearRing[source.getInnerRingCount()];
                for (int j = 0; j < holes.length; j++) holes[j] = ring(source.getInnerRing(j));
                Polygon polygon = FACTORY.createPolygon(shell, holes);
                double area = polygon.getArea();
                if (!Double.isFinite(area) || area <= largestArea) continue;
                org.locationtech.jts.geom.Point interior = polygon.getInteriorPoint();
                if (interior.isEmpty() || !polygon.contains(interior)) continue;
                best = new GeoPoint(interior.getX(), interior.getY());
                best.setCRS(geometry.getCRS());
                bestPolygon = polygon;
                largestArea = area;
            } catch (RuntimeException ignored) {
                // Malformed or collapsed members cannot supply a reliable interior anchor.
            }
        }
        if (best == null || bounds == null) return best;
        if (!bounds.ownsLabelPoint(best)) return null;
        // Validate the actual integer-grid position, not only its unrounded interior point.
        int x = Math.min(LocalVectorTileEncoder.EXTENT - 1, bounds.tileX(best.getX()));
        int y = Math.min(LocalVectorTileEncoder.EXTENT - 1, bounds.tileY(best.getY()));
        GeoPoint snapped = new GeoPoint(
                bounds.minX + (bounds.maxX - bounds.minX) * x / LocalVectorTileEncoder.EXTENT,
                bounds.maxY - (bounds.maxY - bounds.minY) * y / LocalVectorTileEncoder.EXTENT);
        if (!bestPolygon.contains(FACTORY.createPoint(new Coordinate(snapped.getX(), snapped.getY())))) {
            // At this zoom a collapsed/thin member may have no representable interior position.
            return null;
        }
        snapped.setCRS(geometry.getCRS());
        return snapped;
    }

    private static LinearRing ring(GeoLinearRing source) {
        if (source == null || source.getPoints() == null) {
            throw new IllegalArgumentException("Missing ring");
        }
        List<Coordinate> coordinates = new ArrayList<>(source.getPoints().size() + 1);
        for (GeoPoint point : source.getPoints()) {
            if (point == null || !Double.isFinite(point.getX()) || !Double.isFinite(point.getY())) {
                throw new IllegalArgumentException("Invalid ring coordinate");
            }
            Coordinate coordinate = new Coordinate(point.getX(), point.getY());
            if (coordinates.isEmpty() || !coordinates.get(coordinates.size() - 1).equals2D(coordinate)) {
                coordinates.add(coordinate);
            }
        }
        if (coordinates.size() < 3) throw new IllegalArgumentException("Incomplete ring");
        if (!coordinates.get(0).equals2D(coordinates.get(coordinates.size() - 1))) {
            coordinates.add(new Coordinate(coordinates.get(0)));
        }
        return FACTORY.createLinearRing(coordinates.toArray(new Coordinate[0]));
    }
}

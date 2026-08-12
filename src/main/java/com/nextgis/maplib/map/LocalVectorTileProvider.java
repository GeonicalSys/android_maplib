/*
 * Project: NextGIS Mobile
 * Purpose: On-demand local vector tile generation from VectorLayer storage.
 */

package com.nextgis.maplib.map;

import android.text.TextUtils;
import android.util.Log;

import com.hypertrack.hyperlog.HyperLog;
import com.nextgis.maplib.api.ITextStyle;
import com.nextgis.maplib.datasource.Feature;
import com.nextgis.maplib.datasource.GeoEnvelope;
import com.nextgis.maplib.datasource.GeoGeometry;
import com.nextgis.maplib.datasource.GeoGeometryCollection;
import com.nextgis.maplib.datasource.GeoLinearRing;
import com.nextgis.maplib.datasource.GeoMultiPolygon;
import com.nextgis.maplib.datasource.GeoPoint;
import com.nextgis.maplib.datasource.GeoPolygon;
import com.nextgis.maplib.display.LabelAttributes;
import com.nextgis.maplib.display.LabelTemplate;
import com.nextgis.maplib.display.RuleFeatureRenderer;
import com.nextgis.maplib.display.SimpleMarkerStyle;
import com.nextgis.maplib.display.Style;
import com.nextgis.maplib.util.Constants;
import com.nextgis.maplib.util.GeoConstants;

import java.util.ArrayList;
import java.util.List;

/**
 * Local vector tiles foundation.
 *
 * Generates MVT tiles lazily from the existing local SQLite layer. Supported geometry is scoped to
 * polygon/multipolygon plus read-only simple points rendered as ordinary circles. Unsupported
 * geometry/style combinations return empty tiles instead of breaking map loading.
 */
final class LocalVectorTileProvider {
    private static final String TAG = "LocalVectorTiles";
    private static final double MERCATOR_MAX = 20037508.342789244;
    private static final double TILE_BUFFER_RATIO = 0.125;
    private static final double EPS = 1e-9;
    private static final int EDGE_LEFT = 0;
    private static final int EDGE_RIGHT = 1;
    private static final int EDGE_BOTTOM = 2;
    private static final int EDGE_TOP = 3;

    private LocalVectorTileProvider() {
    }

    static byte[] buildTile(VectorLayer layer, int z, int x, int y) throws Exception {
        if (layer == null || z < 0 || x < 0 || y < 0) {
            return new byte[0];
        }
        int geometryType = layer.getGeometryType();
        if (geometryType != GeoConstants.GTPoint
                && geometryType != GeoConstants.GTPolygon
                && geometryType != GeoConstants.GTMultiPolygon) {
            return new byte[0];
        }

        LocalVectorTileEncoder.TileBounds bounds = tileBounds(z, x, y);
        GeoEnvelope queryEnvelope = queryEnvelope(bounds);
        List<Long> ids = layer.query(queryEnvelope);
        if (ids == null || ids.isEmpty()) {
            return encodeTile(
                    geometryType, new ArrayList<>(), bounds, layer.getId(), null, null);
        }

        Style style = layer.getDefaultStyleNoExcept();
        String labelField = style instanceof ITextStyle ? ((ITextStyle) style).getField() : null;
        String commonLabel = style instanceof ITextStyle ? ((ITextStyle) style).getText() : null;

        List<Feature> tileFeatures = new ArrayList<>();
        for (Long id : ids) {
            if (id == null || layer.isFeatureHidden(id)) {
                continue;
            }
            Feature feature = layer.getFeature(id);
            if (feature == null) {
                continue;
            }
            GeoGeometry geometry = feature.getGeometry();
            if (geometry == null || !geometry.intersects(queryEnvelope)) {
                continue;
            }
            Feature clippedFeature = clipFeatureToEnvelope(feature, queryEnvelope);
            if (clippedFeature != null) {
                tileFeatures.add(clippedFeature);
            }
        }
        byte[] tile = encodeTile(
                geometryType,
                tileFeatures,
                bounds,
                layer.getId(),
                TextUtils.isEmpty(labelField) ? null : labelField,
                TextUtils.isEmpty(commonLabel) ? null : commonLabel);
        if (Constants.DEBUG_MODE) {
            Log.d(TAG, "tile layer=" + layer.getName()
                    + " z=" + z + " x=" + x + " y=" + y
                    + " candidates=" + ids.size()
                    + " encoded=" + tileFeatures.size()
                    + " bytes=" + tile.length);
        }
        return tile;
    }

    static boolean canServe(VectorLayer layer) {
        if (layer == null) {
            return false;
        }
        int geometryType = layer.getGeometryType();
        boolean supported = geometryType == GeoConstants.GTPolygon
                || geometryType == GeoConstants.GTMultiPolygon
                || isSupportedSimplePointLayer(layer);
        if (!supported) {
            HyperLog.w(Constants.TAG, "Local vector tiles unsupported geometry/style layer=\""
                    + layer.getName() + "\" geometryType=" + geometryType
                    + " editingAllowed=" + layer.isEditingAllowed());
        }
        return supported;
    }

    private static boolean isSupportedSimplePointLayer(VectorLayer layer) {
        if (layer == null || layer.getGeometryType() != GeoConstants.GTPoint) {
            return false;
        }
        return isSupportedSimplePointStyle(
                layer.getDefaultStyleNoExcept(),
                layer.isEditingAllowed(),
                layer.getRenderer() instanceof RuleFeatureRenderer);
    }

    static boolean isSupportedSimplePointStyle(
            Style style,
            boolean editingAllowed,
            boolean ruleRenderer) {
        if (!(style instanceof SimpleMarkerStyle)) {
            return false;
        }
        SimpleMarkerStyle markerStyle = (SimpleMarkerStyle) style;
        return isSupportedSimplePointStyle(
                markerStyle.getType(),
                markerStyle.getIconImage(),
                LabelTemplate.hasTemplate(
                        LabelAttributes.fromStyle(style).getLabelTemplate()),
                editingAllowed,
                ruleRenderer);
    }

    static boolean isSupportedSimplePointStyle(
            int markerType,
            String iconImage,
            boolean hasLabelTemplate,
            boolean editingAllowed,
            boolean ruleRenderer) {
        return !editingAllowed
                && !ruleRenderer
                && markerType == SimpleMarkerStyle.MarkerStyleCircle
                && (iconImage == null || iconImage.length() == 0)
                && !hasLabelTemplate;
    }

    private static byte[] encodeTile(
            int geometryType,
            List<Feature> features,
            LocalVectorTileEncoder.TileBounds bounds,
            int layerId,
            String labelField,
            String commonLabel) throws Exception {
        if (geometryType == GeoConstants.GTPoint) {
            return LocalVectorTileEncoder.encodePointTile(
                    features, bounds, layerId, labelField, commonLabel);
        }
        return LocalVectorTileEncoder.encodePolygonTile(
                features, bounds, layerId, labelField, commonLabel);
    }

    static Feature clipFeatureToEnvelope(Feature feature, GeoEnvelope envelope) {
        if (feature == null || feature.getGeometry() == null || envelope == null) {
            return null;
        }
        if (feature.getGeometry() instanceof GeoPoint) {
            return envelope.contains((GeoPoint) feature.getGeometry())
                    ? new Feature(feature)
                    : null;
        }
        GeoGeometry clippedGeometry = clipGeometry(feature.getGeometry(), envelope);
        if (clippedGeometry == null) {
            return null;
        }
        Feature clippedFeature = new Feature(feature);
        clippedFeature.setGeometry(clippedGeometry);
        return clippedFeature;
    }

    private static GeoGeometry clipGeometry(GeoGeometry geometry, GeoEnvelope envelope) {
        if (geometry == null) {
            return null;
        }
        if (geometry.getType() == GeoConstants.GTPolygon && geometry instanceof GeoPolygon) {
            return clipPolygon((GeoPolygon) geometry, envelope);
        }
        if (geometry.getType() == GeoConstants.GTMultiPolygon
                && geometry instanceof GeoGeometryCollection) {
            GeoGeometryCollection collection = (GeoGeometryCollection) geometry;
            GeoMultiPolygon clippedMultiPolygon = new GeoMultiPolygon();
            clippedMultiPolygon.setCRS(geometry.getCRS());
            for (int i = 0; i < collection.size(); i++) {
                GeoGeometry part = collection.get(i);
                if (part instanceof GeoPolygon) {
                    GeoPolygon clippedPolygon = clipPolygon((GeoPolygon) part, envelope);
                    if (clippedPolygon != null) {
                        clippedMultiPolygon.add(clippedPolygon);
                    }
                }
            }
            return clippedMultiPolygon.size() > 0 ? clippedMultiPolygon : null;
        }
        return null;
    }

    private static GeoPolygon clipPolygon(GeoPolygon polygon, GeoEnvelope envelope) {
        if (polygon == null || polygon.getOuterRing() == null) {
            return null;
        }
        GeoLinearRing outerRing = clipRing(polygon.getOuterRing(), envelope);
        if (outerRing == null) {
            return null;
        }
        GeoPolygon clippedPolygon = new GeoPolygon();
        clippedPolygon.setCRS(polygon.getCRS());
        for (GeoPoint point : outerRing.getPoints()) {
            clippedPolygon.add(new GeoPoint(point));
        }
        for (int i = 0; i < polygon.getInnerRingCount(); i++) {
            GeoLinearRing innerRing = clipRing(polygon.getInnerRing(i), envelope);
            if (innerRing != null) {
                clippedPolygon.addInnerRing(innerRing);
            }
        }
        return clippedPolygon;
    }

    private static GeoLinearRing clipRing(GeoLinearRing ring, GeoEnvelope envelope) {
        List<GeoPoint> points = normalizedRingPoints(ring);
        if (points.size() < 3) {
            return null;
        }
        points = clipAgainstEdge(points, envelope, EDGE_LEFT);
        points = clipAgainstEdge(points, envelope, EDGE_RIGHT);
        points = clipAgainstEdge(points, envelope, EDGE_BOTTOM);
        points = clipAgainstEdge(points, envelope, EDGE_TOP);
        return buildClosedRing(points, ring.getCRS());
    }

    private static List<GeoPoint> normalizedRingPoints(GeoLinearRing ring) {
        List<GeoPoint> result = new ArrayList<>();
        if (ring == null || ring.getPoints() == null) {
            return result;
        }
        for (GeoPoint point : ring.getPoints()) {
            if (point == null) {
                continue;
            }
            GeoPoint copy = new GeoPoint(point);
            if (result.isEmpty() || !samePoint(result.get(result.size() - 1), copy)) {
                result.add(copy);
            }
        }
        if (result.size() > 1 && samePoint(result.get(0), result.get(result.size() - 1))) {
            result.remove(result.size() - 1);
        }
        return result;
    }

    private static List<GeoPoint> clipAgainstEdge(
            List<GeoPoint> input,
            GeoEnvelope envelope,
            int edge) {
        List<GeoPoint> output = new ArrayList<>();
        if (input == null || input.isEmpty()) {
            return output;
        }
        GeoPoint previous = input.get(input.size() - 1);
        boolean previousInside = inside(previous, envelope, edge);
        for (GeoPoint current : input) {
            boolean currentInside = inside(current, envelope, edge);
            if (currentInside) {
                if (!previousInside) {
                    addIfDistinct(output, intersection(previous, current, envelope, edge));
                }
                addIfDistinct(output, current);
            } else if (previousInside) {
                addIfDistinct(output, intersection(previous, current, envelope, edge));
            }
            previous = current;
            previousInside = currentInside;
        }
        return output;
    }

    private static boolean inside(GeoPoint point, GeoEnvelope envelope, int edge) {
        switch (edge) {
            case EDGE_LEFT:
                return point.getX() >= envelope.getMinX() - EPS;
            case EDGE_RIGHT:
                return point.getX() <= envelope.getMaxX() + EPS;
            case EDGE_BOTTOM:
                return point.getY() >= envelope.getMinY() - EPS;
            case EDGE_TOP:
                return point.getY() <= envelope.getMaxY() + EPS;
            default:
                return false;
        }
    }

    private static GeoPoint intersection(
            GeoPoint a,
            GeoPoint b,
            GeoEnvelope envelope,
            int edge) {
        double x1 = a.getX();
        double y1 = a.getY();
        double x2 = b.getX();
        double y2 = b.getY();
        double x;
        double y;
        if (edge == EDGE_LEFT || edge == EDGE_RIGHT) {
            x = edge == EDGE_LEFT ? envelope.getMinX() : envelope.getMaxX();
            double dx = x2 - x1;
            double t = Math.abs(dx) < EPS ? 0.0 : (x - x1) / dx;
            y = y1 + (y2 - y1) * t;
        } else {
            y = edge == EDGE_BOTTOM ? envelope.getMinY() : envelope.getMaxY();
            double dy = y2 - y1;
            double t = Math.abs(dy) < EPS ? 0.0 : (y - y1) / dy;
            x = x1 + (x2 - x1) * t;
        }
        GeoPoint point = new GeoPoint(clamp(x, envelope.getMinX(), envelope.getMaxX()),
                clamp(y, envelope.getMinY(), envelope.getMaxY()));
        point.setCRS(a.getCRS());
        return point;
    }

    private static GeoLinearRing buildClosedRing(List<GeoPoint> points, int crs) {
        List<GeoPoint> normalized = new ArrayList<>();
        if (points != null) {
            for (GeoPoint point : points) {
                addIfDistinct(normalized, point);
            }
        }
        if (normalized.size() < 3) {
            return null;
        }
        GeoLinearRing ring = new GeoLinearRing();
        ring.setCRS(crs);
        for (GeoPoint point : normalized) {
            GeoPoint copy = new GeoPoint(point);
            copy.setCRS(crs);
            ring.add(copy);
        }
        GeoPoint first = normalized.get(0);
        GeoPoint close = new GeoPoint(first);
        close.setCRS(crs);
        ring.add(close);
        return ring.getPointCount() >= 4 ? ring : null;
    }

    private static void addIfDistinct(List<GeoPoint> points, GeoPoint point) {
        if (point == null) {
            return;
        }
        if (points.isEmpty() || !samePoint(points.get(points.size() - 1), point)) {
            points.add(point);
        }
    }

    private static boolean samePoint(GeoPoint a, GeoPoint b) {
        return a != null && b != null
                && Math.abs(a.getX() - b.getX()) < EPS
                && Math.abs(a.getY() - b.getY()) < EPS;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static LocalVectorTileEncoder.TileBounds tileBounds(int z, int x, int y) {
        double tiles = Math.pow(2.0, z);
        double tileSize = (MERCATOR_MAX * 2.0) / tiles;
        double minX = -MERCATOR_MAX + x * tileSize;
        double maxX = minX + tileSize;
        double maxY = MERCATOR_MAX - y * tileSize;
        double minY = maxY - tileSize;
        return new LocalVectorTileEncoder.TileBounds(minX, minY, maxX, maxY);
    }

    private static GeoEnvelope queryEnvelope(LocalVectorTileEncoder.TileBounds bounds) {
        double bufferX = (bounds.maxX - bounds.minX) * TILE_BUFFER_RATIO;
        double bufferY = (bounds.maxY - bounds.minY) * TILE_BUFFER_RATIO;
        return new GeoEnvelope(
                bounds.minX - bufferX,
                bounds.maxX + bufferX,
                bounds.minY - bufferY,
                bounds.maxY + bufferY);
    }
}

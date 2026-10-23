package com.nextgis.maplib.map;

import android.graphics.PointF;
import android.view.MotionEvent;
import com.nextgis.maplib.datasource.GeoGeometry;
import com.nextgis.maplib.datasource.GeoPoint;
import com.nextgis.maplib.util.GeometryTransform;
import com.nextgis.maplib.util.GeoConstants;
import org.maplibre.android.geometry.LatLng;

/** Owns the complete touch stream while whole-object editing is enabled. */
final class GeometryTransformGesture {
    private GeoGeometry original, segment;
    private double anchorX, anchorY, angle, pivotX, pivotY;
    private int count;
    private boolean changed;

    void reset() { original = null; segment = null; count = 0; changed = false; }

    boolean touch(MapDrawable map, MaplibreMapInteraction host, MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            reset();
            original = GeometryTransform.copy(map.originalSelectedFeature.getGeometry());
            begin(map, host, event, -1);
        } else if (original != null && action == MotionEvent.ACTION_POINTER_DOWN) {
            begin(map, host, event, -1);
        } else if (original != null && action == MotionEvent.ACTION_POINTER_UP) {
            begin(map, host, event, event.getActionIndex());
        } else if (original != null && action == MotionEvent.ACTION_MOVE) {
            int pointers = event.getPointerCount();
            if (pointers != count || pointers > 2) return true;
            GeoGeometry result;
            if (count == 1) {
                GeoPoint next = projected(map, event.getX(), event.getY());
                result = GeometryTransform.apply(segment, next.getX() - anchorX,
                        next.getY() - anchorY, 0, 0, 0);
            } else {
                double next = Math.atan2(event.getY(1) - event.getY(0), event.getX(1) - event.getX(0));
                // Screen y points down; projected north points up. Pinch distance never scales geometry.
                result = GeometryTransform.apply(segment, 0, 0, -(next - angle), pivotX, pivotY);
            }
            map.replaceGeometryFromHistoryChanges(result);
            map.hideVertex();
            map.hideMarker();
            changed = !result.equals(original);
            host.previewGeometryTransform(result, map.originalSelectedFeature);
        } else if (original != null && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
            if (action == MotionEvent.ACTION_CANCEL) {
                map.replaceGeometryFromHistoryChanges(original);
                host.previewGeometryTransform(original, map.originalSelectedFeature);
            } else if (changed) {
                host.updateGeometryFromMaplibre(map.editingObject.editingFeature,
                        map.originalSelectedFeature, map.editingObject);
            }
            map.hideVertex();
            map.hideMarker();
            reset();
        }
        return true;
    }

    private void begin(MapDrawable map, MaplibreMapInteraction host, MotionEvent event, int ignored) {
        segment = host.getGeometryFromMaplibreGeometry(map.editingObject.editingFeature);
        count = event.getPointerCount() - (ignored < 0 ? 0 : 1);
        int first = ignored == 0 ? 1 : 0;
        if (count == 1) {
            GeoPoint point = projected(map, event.getX(first), event.getY(first));
            anchorX = point.getX(); anchorY = point.getY();
        } else if (count == 2) {
            int second = first + 1 == ignored ? first + 2 : first + 1;
            angle = Math.atan2(event.getY(second) - event.getY(first), event.getX(second) - event.getX(first));
            GeoPoint center = segment.getEnvelope().getCenter();
            pivotX = center.getX(); pivotY = center.getY();
        }
    }

    private static GeoPoint projected(MapDrawable map, float x, float y) {
        LatLng coordinate = map.getMaplibreMap().getProjection().fromScreenLocation(new PointF(x, y));
        GeoPoint point = new GeoPoint(coordinate.getLongitude(), coordinate.getLatitude());
        point.setCRS(GeoConstants.CRS_WGS84);
        point.project(GeoConstants.CRS_WEB_MERCATOR);
        return point;
    }
}

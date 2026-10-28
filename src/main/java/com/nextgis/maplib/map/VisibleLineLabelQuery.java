package com.nextgis.maplib.map;

import com.nextgis.maplib.datasource.GeoEnvelope;
import org.maplibre.geojson.Feature;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Viewport candidates only; exact visible-line clipping remains owned by the existing layout. */
final class VisibleLineLabelQuery {
    private static final double RADIUS = 6378137.0;
    private static final double HALF_WORLD = Math.PI * RADIUS;
    private static final double MAX_LATITUDE = 85.0511287798066;
    private VisibleLineLabelQuery() { }

    interface Reader {
        List<Feature> read(List<GeoEnvelope> bounds3857, BooleanSupplier current) throws Exception;
    }
    interface StreamingReader {
        void visit(List<GeoEnvelope> bounds3857, BooleanSupplier current, Consumer<Feature> visitor) throws Exception;
    }

    static List<VisibleLineLabelLayout.Label> planStreaming(double[][] corners,double centerLongitude,double bearing,
                                                           StreamingReader reader,BooleanSupplier current) throws Exception {
        check(current);
        double[][] captured=copy(corners);
        List<GeoEnvelope> envelopes=bounds(captured,centerLongitude);
        VisibleLineLabelLayout.OriginalPlanner planner=new VisibleLineLabelLayout.OriginalPlanner(captured,bearing,current);
        reader.visit(envelopes,current,planner::accept);
        check(current);
        return planner.finish();
    }

    static List<VisibleLineLabelLayout.Label> plan(double[][] corners, double centerLongitude, double bearing,
                                                  Reader reader, BooleanSupplier current) throws Exception {
        check(current);
        double[][] captured = copy(corners);
        List<Feature> fullLines = reader.read(bounds(captured, centerLongitude), current);
        check(current); // A DB/style/owner change while reading must not publish an older label.
        return VisibleLineLabelLayout.plan(fullLines, captured, bearing, current);
    }

    static List<GeoEnvelope> bounds(double[][] corners, double centerLongitude) {
        double[][] valid = copy(corners);
        if (!Double.isFinite(centerLongitude)) throw new IllegalArgumentException("Invalid camera longitude");
        double center = normalize(centerLongitude), west = center, east = center;
        double south = MAX_LATITUDE, north = -MAX_LATITUDE;
        for (double[] point : valid) {
            // The map camera is inside its viewport. Unwrap around it rather than guessing
            // the shortest arc from normalized corners (which loses a wide rotated viewport).
            double longitude = center + normalize(point[0] - center);
            west = Math.min(west, longitude); east = Math.max(east, longitude);
            double latitude = Math.max(-MAX_LATITUDE, Math.min(MAX_LATITUDE, point[1]));
            south = Math.min(south, latitude); north = Math.max(north, latitude);
        }
        if (west == east || south == north) throw new IllegalArgumentException("Empty viewport");
        List<GeoEnvelope> result = new ArrayList<>(2);
        double minY = y(south), maxY = y(north);
        if (west < -180) {
            add(result, west + 360, 180, minY, maxY);
            add(result, -180, east, minY, maxY);
        } else if (east > 180) {
            add(result, west, 180, minY, maxY);
            add(result, -180, east - 360, minY, maxY);
        } else add(result, west, east, minY, maxY);
        return Collections.unmodifiableList(result);
    }

    private static void add(List<GeoEnvelope> result, double west, double east, double minY, double maxY) {
        if (west < east) result.add(new GeoEnvelope(RADIUS * Math.toRadians(west),
                RADIUS * Math.toRadians(east), minY, maxY));
    }
    private static double y(double latitude) {
        return Math.max(-HALF_WORLD, Math.min(HALF_WORLD,
                RADIUS * Math.log(Math.tan(Math.PI / 4 + Math.toRadians(latitude) / 2))));
    }
    private static double normalize(double longitude) { return ((longitude + 180) % 360 + 360) % 360 - 180; }
    private static double[][] copy(double[][] corners) {
        if (corners == null || corners.length != 4) throw new IllegalArgumentException("Four viewport corners required");
        double[][] copy = new double[4][2];
        for (int i = 0; i < 4; i++) {
            if (corners[i] == null || corners[i].length != 2
                    || !Double.isFinite(corners[i][0]) || !Double.isFinite(corners[i][1]))
                throw new IllegalArgumentException("Invalid viewport corner");
            copy[i][0] = corners[i][0]; copy[i][1] = corners[i][1];
        }
        return copy;
    }
    private static void check(BooleanSupplier current) { if (!current.getAsBoolean()) throw new CancellationException(); }
}

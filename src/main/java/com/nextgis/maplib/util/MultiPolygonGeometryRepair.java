/*
 * Project: NextGIS Mobile
 * Purpose: Repair invalid multi-polygon topology while preserving one feature.
 */

package com.nextgis.maplib.util;

import com.nextgis.maplib.datasource.GeoLinearRing;
import com.nextgis.maplib.datasource.GeoMultiPolygon;
import com.nextgis.maplib.datasource.GeoPoint;
import com.nextgis.maplib.datasource.GeoPolygon;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.geom.util.GeometryFixer;
import org.locationtech.jts.operation.valid.IsValidOp;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts an invalid {@link GeoMultiPolygon} into a valid multi-polygon without creating
 * additional features. Attribute ownership therefore stays with the original feature.
 */
public final class MultiPolygonGeometryRepair {
    public enum Status {
        UNCHANGED,
        REPAIRED,
        FAILED
    }

    public static final class Result {
        private final Status mStatus;
        private final GeoMultiPolygon mGeometry;
        private final String mDiagnostic;

        private Result(Status status, GeoMultiPolygon geometry, String diagnostic) {
            mStatus = status;
            mGeometry = geometry;
            mDiagnostic = diagnostic;
        }

        public Status getStatus() {
            return mStatus;
        }

        public GeoMultiPolygon getGeometry() {
            return mGeometry;
        }

        public String getDiagnostic() {
            return mDiagnostic;
        }

        public int getPolygonCount() {
            return mGeometry == null ? 0 : mGeometry.size();
        }
    }

    private MultiPolygonGeometryRepair() {
    }

    public static boolean supportsLayerType(int geometryType) {
        return geometryType == GeoConstants.GTMultiPolygon;
    }

    public static Result repairIfNeeded(GeoMultiPolygon input) {
        if (input == null) {
            return failed(null, "input is null");
        }

        try {
            int effectiveCrs = resolveSupportedCrs(input);
            if (!isSupportedCrs(effectiveCrs)) {
                return failed(input, "geometry has no supported CRS");
            }
            GeometryFactory factory =
                    new GeometryFactory(new PrecisionModel(), effectiveCrs);
            MultiPolygon jtsInput = toJtsMultiPolygon(input, factory);
            IsValidOp inputValidity = new IsValidOp(jtsInput);
            if (inputValidity.isValid()) {
                return new Result(Status.UNCHANGED, input, null);
            }

            GeometryFixer fixer = new GeometryFixer(jtsInput);
            fixer.setKeepCollapsed(false);
            fixer.setKeepMulti(true);
            Geometry fixed = fixer.getResult();
            if (fixed == null || fixed.isEmpty()) {
                return failed(input, "repair produced empty geometry");
            }
            if (!(fixed instanceof Polygon) && !(fixed instanceof MultiPolygon)) {
                return failed(input, "repair produced non-polygonal geometry "
                        + fixed.getGeometryType());
            }

            IsValidOp fixedValidity = new IsValidOp(fixed);
            if (!fixedValidity.isValid()) {
                return failed(input, validationDiagnostic(
                        "repair remains invalid", fixedValidity));
            }

            GeoMultiPolygon repaired = fromJtsPolygonal(fixed, effectiveCrs);
            if (repaired.size() == 0 || !repaired.isValid()) {
                return failed(input, "converted repair is empty or invalid");
            }
            return new Result(
                    Status.REPAIRED,
                    repaired,
                    validationDiagnostic("invalid multi-polygon", inputValidity));
        } catch (GeometryInputException exception) {
            return failed(input, exception.getMessage());
        } catch (RuntimeException exception) {
            // JTS exception messages can contain coordinates. Keep production diagnostics spatially
            // anonymous; the exception class plus the bounded test corpus is sufficient here.
            return failed(input, exception.getClass().getSimpleName());
        }
    }

    private static String validationDiagnostic(String prefix, IsValidOp validity) {
        return validity.getValidationError() == null
                ? prefix
                : prefix + " code=" + validity.getValidationError().getErrorType();
    }

    private static Result failed(GeoMultiPolygon input, String diagnostic) {
        return new Result(Status.FAILED, input, diagnostic);
    }

    private static MultiPolygon toJtsMultiPolygon(
            GeoMultiPolygon input,
            GeometryFactory factory) {
        Polygon[] polygons = new Polygon[input.size()];
        for (int i = 0; i < input.size(); i++) {
            polygons[i] = toJtsPolygon(input.get(i), factory);
        }
        return factory.createMultiPolygon(polygons);
    }

    private static Polygon toJtsPolygon(GeoPolygon input, GeometryFactory factory) {
        LinearRing shell = toJtsRing(input.getOuterRing(), factory);
        LinearRing[] holes = new LinearRing[input.getInnerRingCount()];
        for (int i = 0; i < input.getInnerRingCount(); i++) {
            holes[i] = toJtsRing(input.getInnerRing(i), factory);
        }
        return factory.createPolygon(shell, holes);
    }

    private static LinearRing toJtsRing(GeoLinearRing input, GeometryFactory factory) {
        if (input == null) {
            throw new GeometryInputException("ring is null");
        }

        List<GeoPoint> source = input.getPoints();
        if (source.size() < 3) {
            throw new GeometryInputException("ring has fewer than three positions");
        }

        List<Coordinate> coordinates = new ArrayList<>(source.size() + 1);
        for (GeoPoint point : source) {
            if (point == null
                    || !isFinite(point.getX())
                    || !isFinite(point.getY())) {
                throw new GeometryInputException("ring contains an invalid coordinate");
            }
            Coordinate coordinate = new Coordinate(point.getX(), point.getY());
            if (coordinates.isEmpty()
                    || !coordinates.get(coordinates.size() - 1).equals2D(coordinate)) {
                coordinates.add(coordinate);
            }
        }

        if (coordinates.size() < 3) {
            throw new GeometryInputException(
                    "ring collapses below three distinct positions");
        }
        if (!coordinates.get(0).equals2D(coordinates.get(coordinates.size() - 1))) {
            coordinates.add(new Coordinate(coordinates.get(0)));
        }
        if (coordinates.size() < 4) {
            throw new GeometryInputException("closed ring has fewer than four positions");
        }

        return factory.createLinearRing(coordinates.toArray(new Coordinate[0]));
    }

    private static GeoMultiPolygon fromJtsPolygonal(Geometry geometry, int crs) {
        GeoMultiPolygon output = new GeoMultiPolygon();
        output.setCRS(crs);
        if (geometry instanceof Polygon) {
            output.add(fromJtsPolygon((Polygon) geometry, crs));
            return output;
        }

        MultiPolygon multiPolygon = (MultiPolygon) geometry;
        for (int i = 0; i < multiPolygon.getNumGeometries(); i++) {
            output.add(fromJtsPolygon((Polygon) multiPolygon.getGeometryN(i), crs));
        }
        return output;
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    /**
     * MapLibre edit callbacks historically left the collection CRS unset while preserving it on
     * the polygon or its points. Recover that non-spatial metadata before the JTS round trip.
     */
    private static int resolveSupportedCrs(GeoMultiPolygon input) {
        if (isSupportedCrs(input.getCRS())) {
            return input.getCRS();
        }
        for (int polygonIndex = 0; polygonIndex < input.size(); polygonIndex++) {
            GeoPolygon polygon = input.get(polygonIndex);
            if (isSupportedCrs(polygon.getCRS())) {
                return polygon.getCRS();
            }
            GeoLinearRing outerRing = polygon.getOuterRing();
            if (outerRing != null) {
                if (isSupportedCrs(outerRing.getCRS())) {
                    return outerRing.getCRS();
                }
                for (GeoPoint point : outerRing.getPoints()) {
                    if (point != null && isSupportedCrs(point.getCRS())) {
                        return point.getCRS();
                    }
                }
            }
        }
        return 0;
    }

    private static boolean isSupportedCrs(int crs) {
        return crs == GeoConstants.CRS_WGS84 || crs == GeoConstants.CRS_WEB_MERCATOR;
    }

    private static GeoPolygon fromJtsPolygon(Polygon input, int crs) {
        GeoPolygon output = new GeoPolygon();
        output.setCRS(crs);
        copyCoordinates(input.getExteriorRing().getCoordinates(), output.getOuterRing(), crs);
        for (int i = 0; i < input.getNumInteriorRing(); i++) {
            GeoLinearRing hole = new GeoLinearRing();
            hole.setCRS(crs);
            copyCoordinates(input.getInteriorRingN(i).getCoordinates(), hole, crs);
            output.addInnerRing(hole);
        }
        return output;
    }

    private static void copyCoordinates(
            Coordinate[] coordinates,
            GeoLinearRing output,
            int crs) {
        output.setCRS(crs);
        for (Coordinate coordinate : coordinates) {
            GeoPoint point = new GeoPoint(coordinate.getX(), coordinate.getY());
            point.setCRS(crs);
            output.add(point);
        }
    }

    private static final class GeometryInputException extends IllegalArgumentException {
        GeometryInputException(String message) {
            super(message);
        }
    }
}

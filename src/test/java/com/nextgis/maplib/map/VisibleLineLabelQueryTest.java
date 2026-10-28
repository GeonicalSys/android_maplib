package com.nextgis.maplib.map;

import com.google.gson.JsonObject;
import com.nextgis.maplib.datasource.GeoEnvelope;
import org.junit.Test;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.MultiLineString;
import org.maplibre.geojson.Point;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class VisibleLineLabelQueryTest {
    private static final double METRES_PER_DEGREE = 111319.49079327357;
    private static double[][] box(double west, double south, double east, double north) {
        return new double[][]{{west,north},{east,north},{east,south},{west,south}};
    }
    private static Feature line(String id, double... coordinates) {
        java.util.ArrayList<Point> points = new java.util.ArrayList<>();
        for (int i = 0; i < coordinates.length; i += 2) points.add(Point.fromLngLat(coordinates[i], coordinates[i+1]));
        JsonObject properties = new JsonObject();
        properties.addProperty(MPLFeaturesUtils.prop_featureid, id);
        properties.addProperty(MPLFeaturesUtils.prop_signature_text, "Полная подпись");
        properties.addProperty("custom_style", 17);
        return Feature.fromGeometry(LineString.fromLngLats(points), properties, id);
    }

    @Test public void rotatedViewportBoundsIncludeEveryMercatorCorner() {
        double[][] diamond = {{-1,0},{0,1},{1,0},{0,-1}};
        List<GeoEnvelope> bounds = VisibleLineLabelQuery.bounds(diamond, 0);
        assertEquals(1, bounds.size()); GeoEnvelope envelope = bounds.get(0);
        assertEquals(-111319.49079327357, envelope.getMinX(), 1e-7);
        assertEquals(111319.49079327357, envelope.getMaxX(), 1e-7);
        assertEquals(-111325.1428663851, envelope.getMinY(), 1e-7);
        assertEquals(111325.1428663851, envelope.getMaxY(), 1e-7);
        assertThrows(UnsupportedOperationException.class, () -> bounds.add(new GeoEnvelope()));
        assertArrayEquals(new double[]{-1,0}, diamond[0], 0);
    }

    @Test public void datelineUsesTwoNarrowQueriesInsteadOfAnAlmostGlobalEnvelope() {
        for (double offset : new double[]{0,360}) {
            List<GeoEnvelope> bounds = VisibleLineLabelQuery.bounds(box(179+offset,-1,-179+offset,1),180+offset);
            assertEquals(2, bounds.size());
            GeoEnvelope positive = bounds.stream().filter(e -> e.getMinX() > 0).findFirst().get();
            GeoEnvelope negative = bounds.stream().filter(e -> e.getMaxX() < 0).findFirst().get();
            assertEquals(179 * METRES_PER_DEGREE, positive.getMinX(), 1e-7);
            assertEquals(180 * METRES_PER_DEGREE, positive.getMaxX(), 1e-7);
            assertEquals(-180 * METRES_PER_DEGREE, negative.getMinX(), 1e-7);
            assertEquals(-179 * METRES_PER_DEGREE, negative.getMaxX(), 1e-7);
            for (GeoEnvelope envelope : bounds) assertTrue(envelope.getMaxX()-envelope.getMinX() < 120000);
        }
    }

    @Test public void cameraReferenceDoesNotChooseTheShortArcBehindAWideViewport() {
        List<GeoEnvelope> bounds = VisibleLineLabelQuery.bounds(box(-120,-1,120,1),0);
        assertEquals(1, bounds.size());
        assertEquals(-120 * METRES_PER_DEGREE, bounds.get(0).getMinX(), 1e-7);
        assertEquals(120 * METRES_PER_DEGREE, bounds.get(0).getMaxX(), 1e-7);
    }

    @Test public void originalUnclippedLineAndMultipartGeometryProduceOneLabelPerIdentity() throws Exception {
        Feature vertical = line("7",-20,10,10,10,10,-10);
        Feature secondPart = line("8",9,-5,9,5);
        Feature multi = Feature.fromGeometry(MultiLineString.fromLngLats(Arrays.asList(
                ((LineString)secondPart.geometry()).coordinates(),
                ((LineString)line("8",10,20,10,30).geometry()).coordinates())), secondPart.properties(), "8");
        AtomicInteger reads = new AtomicInteger();
        List<VisibleLineLabelLayout.Label> labels = VisibleLineLabelQuery.plan(box(8,-1,11,1),9.5,0,(bounds,current) -> {
            reads.incrementAndGet(); assertTrue(current.getAsBoolean()); assertEquals(1,bounds.size());
            assertEquals(8 * METRES_PER_DEGREE,bounds.get(0).getMinX(),1e-7);
            return Arrays.asList(vertical,vertical,multi);
        },() -> true);
        assertEquals(1,reads.get()); assertEquals(2,labels.size());
        Feature label = labels.get(0).toFeature();
        assertEquals("7",label.getStringProperty(MPLFeaturesUtils.prop_featureid));
        assertEquals("Полная подпись",label.getStringProperty(MPLFeaturesUtils.prop_signature_text));
        assertEquals(17,label.getNumberProperty("custom_style").intValue());
        assertEquals(90,Math.abs(label.getNumberProperty(VisibleLineLabelLayout.ANGLE).doubleValue()),1e-6);
        assertEquals(3,((LineString)vertical.geometry()).coordinates().size());
        assertEquals(-20,((LineString)vertical.geometry()).coordinates().get(0).longitude(),0);
        assertEquals(2,((MultiLineString)multi.geometry()).coordinates().size());
        assertFalse(vertical.hasProperty(VisibleLineLabelLayout.ANGLE));
    }

    @Test public void candidateBoundingBoxDoesNotReplaceExactRotatedVisibleClipping() throws Exception {
        double[][] diamond = {{-1,0},{0,1},{1,0},{0,-1}};
        List<VisibleLineLabelLayout.Label> labels = VisibleLineLabelQuery.plan(diamond,0,0,
                (bounds,current) -> Arrays.asList(line("outside",.8,.9,.9,.8),line("crossing",-2,0,2,0)),() -> true);
        assertEquals(1,labels.size());
        assertEquals("crossing",labels.get(0).toFeature().getStringProperty(MPLFeaturesUtils.prop_featureid));
    }

    @Test public void obsoleteCameraCancelsBeforeDbAndDataChangeDuringReadCannotPublish() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        assertThrows(CancellationException.class,() -> VisibleLineLabelQuery.plan(box(-1,-1,1,1),0,0,
                (bounds,current) -> { reads.incrementAndGet(); return List.of(line("1",-2,0,2,0)); },() -> false));
        assertEquals(0,reads.get());
        AtomicBoolean current = new AtomicBoolean(true);
        assertThrows(CancellationException.class,() -> VisibleLineLabelQuery.plan(box(-1,-1,1,1),0,0,
                (bounds,stillCurrent) -> { reads.incrementAndGet(); current.set(false); return List.of(line("1",-2,0,2,0)); },current::get));
        assertEquals(1,reads.get());
    }

    @Test public void datelineOriginalIsNotClippedOrDuplicatedByItsTwoDbWindows() throws Exception {
        Feature original = line("dateline",179.5,0,-179.5,0);
        List<VisibleLineLabelLayout.Label> labels = VisibleLineLabelQuery.plan(box(179,-1,-179,1),180,0,
                (bounds,current) -> { assertEquals(2,bounds.size()); return Arrays.asList(original,original); },() -> true);
        assertEquals(1,labels.size());
        assertEquals(180,Math.abs(((Point)labels.get(0).toFeature().geometry()).longitude()),1e-7);
        assertEquals(-179.5,((LineString)original.geometry()).coordinates().get(1).longitude(),0);
    }

    @Test public void invalidProjectionDoesNotQueryDbAndLatitudeBoundsStayFinite() {
        List<GeoEnvelope> polar = VisibleLineLabelQuery.bounds(box(-1,-90,1,90),0);
        assertEquals(-20037508.342789244,polar.get(0).getMinY(),1e-7);
        assertEquals(20037508.342789244,polar.get(0).getMaxY(),1e-7);
        AtomicInteger reads = new AtomicInteger();
        assertThrows(IllegalArgumentException.class,() -> VisibleLineLabelQuery.plan(box(Double.NaN,-1,1,1),0,0,
                (bounds,current) -> { reads.incrementAndGet(); return List.of(); },() -> true));
        assertEquals(0,reads.get());
    }
}

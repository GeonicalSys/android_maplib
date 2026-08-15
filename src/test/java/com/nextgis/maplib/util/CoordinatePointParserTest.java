package com.nextgis.maplib.util;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

public class CoordinatePointParserTest {
    @Test
    public void kmlFlattensPointLineAndPolygonCoordinatesInOrder() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>"
                + "<Placemark><name>Survey A</name><Point><coordinates>30.1,60.2,12.5</coordinates></Point></Placemark>"
                + "<Placemark><name>Route</name><LineString><coordinates>"
                + "30.2,60.3 30.3,60.4,15</coordinates></LineString></Placemark>"
                + "<Placemark><Polygon><outerBoundaryIs><LinearRing><coordinates>"
                + "30,60 31,60 30,60</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>"
                + "</Document></kml>";
        List<CoordinatePointParser.PointRecord> points = new ArrayList<>();

        CoordinatePointParser.ParseResult result = parse(
                xml, CoordinatePointParser.Format.KML, points);

        assertEquals(6, result.getPointCount());
        assertEquals(0, result.getSkippedCoordinateCount());
        assertEquals("kml_point", points.get(0).getSourceType());
        assertEquals("Survey A", points.get(0).getName());
        assertEquals(30.1, points.get(0).getLongitude(), 0.0);
        assertEquals(60.2, points.get(0).getLatitude(), 0.0);
        assertEquals(12.5, points.get(0).getElevation(), 0.0);
        assertEquals("kml_linestring", points.get(1).getSourceType());
        assertEquals("Route", points.get(2).getName());
        assertEquals("kml_polygon", points.get(3).getSourceType());
        assertEquals(30.0, points.get(5).getLongitude(), 0.0);
        assertNull(points.get(5).getElevation());
    }

    @Test
    public void gpxFlattensWaypointsRoutesAndTracksWithMetadata() throws Exception {
        String xml = "<?xml version=\"1.0\"?><gpx xmlns=\"http://www.topografix.com/GPX/1/1\">"
                + "<wpt lat=\"60.1\" lon=\"30.1\"><ele>7.5</ele><name>Base</name>"
                + "<time>2026-08-15T06:00:00Z</time></wpt>"
                + "<rte><rtept lat=\"60.2\" lon=\"30.2\"><name>R1</name></rtept></rte>"
                + "<trk><trkseg><trkpt lat=\"60.3\" lon=\"30.3\"/></trkseg></trk>"
                + "</gpx>";
        List<CoordinatePointParser.PointRecord> points = new ArrayList<>();

        CoordinatePointParser.ParseResult result = parse(
                xml, CoordinatePointParser.Format.GPX, points);

        assertEquals(3, result.getPointCount());
        assertEquals("gpx_wpt", points.get(0).getSourceType());
        assertEquals("Base", points.get(0).getName());
        assertEquals("2026-08-15T06:00:00Z", points.get(0).getTime());
        assertEquals(7.5, points.get(0).getElevation(), 0.0);
        assertEquals("gpx_rtept", points.get(1).getSourceType());
        assertEquals("gpx_trkpt", points.get(2).getSourceType());
        assertEquals(30.3, points.get(2).getLongitude(), 0.0);
    }

    @Test
    public void invalidCoordinatesAreSkippedWithoutChangingValidOrder() throws Exception {
        String xml = "<gpx><wpt lat=\"91\" lon=\"30\"/>"
                + "<trk><trkseg><trkpt lat=\"60\" lon=\"bad\"/>"
                + "<trkpt lat=\"60.5\" lon=\"30.5\"/></trkseg></trk></gpx>";
        List<CoordinatePointParser.PointRecord> points = new ArrayList<>();

        CoordinatePointParser.ParseResult result = parse(
                xml, CoordinatePointParser.Format.GPX, points);

        assertEquals(1, result.getPointCount());
        assertEquals(2, result.getSkippedCoordinateCount());
        assertEquals(30.5, points.get(0).getLongitude(), 0.0);
    }

    @Test
    public void doctypeAndEntitiesAreRejected() {
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE gpx [<!ENTITY x \"30\">]>"
                + "<gpx><wpt lat=\"60\" lon=\"&x;\"/></gpx>";

        assertThrows(Exception.class, () -> parse(
                xml, CoordinatePointParser.Format.GPX, new ArrayList<>()));
    }

    private static CoordinatePointParser.ParseResult parse(
            String xml,
            CoordinatePointParser.Format format,
            List<CoordinatePointParser.PointRecord> points) throws Exception {
        return CoordinatePointParser.parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                format,
                points::add);
    }
}

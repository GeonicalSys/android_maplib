package com.nextgis.maplib.map;

import com.nextgis.maplib.datasource.*;
import org.locationtech.jts.geom.Geometry;
import org.junit.Test;
import static org.junit.Assert.*;

public class NativeVectorTileClipperTest {
    @Test public void concaveClipKeepsDisconnectedMembersAndLeavesEveryOriginalCoordinateUntouched() throws Exception {
        GeoPolygon original = polygon(0,0,6,0,6,6,4,6,4,2,2,2,2,6,0,6,0,0);
        String before = original.toWKT(true);
        // At the classic128/512 margin these base bounds expand to y[2.75,5.25].
        // Keep the independently reviewed clip window/output, not the old64px base bounds.
        Geometry clipped = NativeVectorTileClipper.clip(original,
                new NativeVectorTileEncoder.Bounds(0,19.0/6.0,6,29.0/6.0));
        assertEquals(2,clipped.getNumGeometries()); assertEquals(10,clipped.getArea(),1e-9);
        boolean left=false,right=false;
        for(int i=0;i<clipped.getNumGeometries();i++) {
            Geometry part=clipped.getGeometryN(i);
            org.locationtech.jts.geom.Envelope envelope=part.getEnvelopeInternal();
            assertEquals(2.75,envelope.getMinY(),1e-9); assertEquals(5.25,envelope.getMaxY(),1e-9);
            assertEquals(2,envelope.getWidth(),1e-9); assertEquals(5,part.getArea(),1e-9);
            if(envelope.getMinX()==0) { left=true; assertEquals(2,envelope.getMaxX(),1e-9); }
            else { assertEquals(4,envelope.getMinX(),1e-9); assertEquals(6,envelope.getMaxX(),1e-9); right=true; }
        }
        assertTrue("Both disconnected original arms must remain",left&&right);
        assertEquals(before,original.toWKT(true)); assertTrue(clipped.isValid());
    }
    @Test public void completeInteriorPolygonKeepsHolesAndSubpixelOriginalVerticesWithoutSimplification() throws Exception {
        GeoPolygon original = polygon(0,0,10,0,10,10,0,10,0,0);
        GeoLinearRing hole = new GeoLinearRing();
        for(double[] xy:new double[][]{{4,4},{4,6},{6,6},{6,4},{4,4}})hole.add(new GeoPoint(xy[0],xy[1]));
        original.addInnerRing(hole);
        Geometry clipped = NativeVectorTileClipper.clip(original,new NativeVectorTileEncoder.Bounds(0,0,10,10));
        assertEquals(96,clipped.getArea(),1e-9); assertEquals(10,clipped.getNumPoints());
        assertEquals(1,((org.locationtech.jts.geom.Polygon)clipped).getNumInteriorRing());
    }
    @Test public void allSixGeometryKindsClipWithoutInventingLineConnectionsOrLowerDimensionalPolygonEdges() throws Exception {
        GeoLineString line=line(-5,2,5,2,15,2);
        GeoMultiLineString lines=new GeoMultiLineString(); lines.add(line);lines.add(line(-5,8,15,8));
        GeoMultiPoint points=new GeoMultiPoint();points.add(new GeoPoint(2,2));points.add(new GeoPoint(20,20));
        GeoMultiPolygon polygons=new GeoMultiPolygon();polygons.add(polygon(1,1,2,1,2,2,1,2,1,1));polygons.add(polygon(7,7,8,7,8,8,7,8,7,7));
        NativeVectorTileEncoder.Bounds bounds=new NativeVectorTileEncoder.Bounds(0,0,10,10);
        assertEquals(1,NativeVectorTileClipper.clip(new GeoPoint(2,2),bounds).getNumPoints());
        assertEquals(1,NativeVectorTileClipper.clip(points,bounds).getNumPoints());
        assertEquals(3,NativeVectorTileClipper.clip(line,bounds).getNumPoints());
        assertEquals(2,NativeVectorTileClipper.clip(lines,bounds).getNumGeometries());
        assertEquals(2,NativeVectorTileClipper.clip(polygons,bounds).getNumGeometries());
        assertTrue(NativeVectorTileClipper.clip(polygon(20,20,21,20,21,21,20,21,20,20),bounds).isEmpty());
    }
    static GeoLineString line(double...xy){GeoLineString line=new GeoLineString();for(int i=0;i<xy.length;i+=2)line.add(new GeoPoint(xy[i],xy[i+1]));return line;}
    static GeoPolygon polygon(double...xy){GeoPolygon polygon=new GeoPolygon();for(int i=0;i<xy.length;i+=2)polygon.add(new GeoPoint(xy[i],xy[i+1]));return polygon;}
}

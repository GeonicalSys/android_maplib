package com.nextgis.maplib.map;

import android.app.Application;
import com.nextgis.maplib.datasource.GeoEnvelope;
import com.nextgis.maplib.datasource.GeoGeometry;
import com.nextgis.maplib.datasource.GeoLinearRing;
import com.nextgis.maplib.datasource.GeoPoint;
import com.nextgis.maplib.datasource.GeoPolygon;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.Point;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static com.nextgis.maplib.util.GeoConstants.GTPolygon;
import static org.junit.Assert.*;

/** Literal SDK128/512 buffer expectations, independent of production bounds/constants. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=36, manifest=Config.NONE, application=Application.class)
public class NativeVectorTileBufferParityTest {
    // z16/x32768/y32767 is the tile [0,span] in both Mercator axes.
    private static final double METRES_PER_PIXEL = 40075016.685578488 / 65536 / 512;

    @Test public void translatedEdgePolygonsReachAllFourTileEdgesWithFullHolesAndVertices() throws Exception {
        Access source = new Access();
        GeoPolygon west = polygon(-96,250, -90,250, -84,250, -84,262, -96,262, -96,250);
        west.addInnerRing(ring(-92,254, -92,258, -88,258, -88,254, -92,254));
        source.add(1,west);
        source.add(2,polygon(596,250, 608,250, 608,262, 596,262, 596,250));
        source.add(3,polygon(250,-96, 262,-96, 262,-84, 250,-84, 250,-96));
        source.add(4,polygon(250,596, 262,596, 262,608, 250,608, 250,596));
        List<String> originals = new ArrayList<>();
        for (com.nextgis.maplib.datasource.Feature feature : source.features.values())
            originals.add(feature.getGeometry().toWKT(true));
        SqliteVectorTileProvider provider = new SqliteVectorTileProvider(source,1,()->1,null);
        NativeVectorTileEncoderTest.Tile tile = NativeVectorTileEncoderTest.decode(
                provider.load(provider.snapshot(16,32768,32767),()->true));
        assertEquals("The128px classic margin must include every90px outside-edge original",4,tile.records.size());
        assertEquals(4,source.reads);
        assertEquals(8192,tile.extent);
        assertQuery128(source.lastQuery);
        Map<Long,NativeVectorTileEncoderTest.Record> records = new LinkedHashMap<>();
        for (NativeVectorTileEncoderTest.Record record : tile.records) {
            records.put(record.id,record);
            assertEquals(3,record.type);
            assertEquals(Long.toString(record.id),record.properties.get("featureid"));
            assertEquals("#12ab34",record.properties.get("fillcolor"));
            assertEquals(false,record.properties.get("scalesizewithzoom"));
        }
        NativeVectorTileEncoderTest.Record decodedWest = records.get(1L);
        assertEquals("An exterior collinear original vertex must survive",9,
                NativeVectorTileEncoderTest.coordinates(decodedWest.commands).size());
        assertEquals("Full exterior and hole must survive",2,
                NativeVectorTileEncoderTest.commandCount(decodedWest.commands,7));
        for (int[] expected : new int[][]{{-1536,4192},{-1440,4192},{-1344,4192},
                {-1344,4000},{-1536,4000},{-1472,4128},{-1472,4064},{-1408,4064},{-1408,4128}})
            assertCoordinate(decodedWest,expected[0],expected[1]);
        assertCoordinate(records.get(2L),9536,4192);
        assertCoordinate(records.get(3L),4000,9728);
        assertCoordinate(records.get(4L),4000,-1344);
        // Independent style-pixel contract:110px fill-translate moves each90px-away
        // object's centre20px inside.64px query padding cannot retrieve these objects.
        assertEquals(320,centre(records.get(1L),0)+110*16);
        assertEquals(7872,centre(records.get(2L),0)-110*16);
        assertEquals(7872,centre(records.get(3L),1)-110*16);
        assertEquals(320,centre(records.get(4L),1)+110*16);
        int i=0;
        for (com.nextgis.maplib.datasource.Feature feature : source.features.values()) {
            assertEquals(originals.get(i++),feature.getGeometry().toWKT(true));
            assertSame(feature,provider.loadOriginalFeature(feature.getId()));
        }
    }

    @Test public void strictBackendQueryAndClipShareExactlyTheClassic128PixelBoundary() throws Exception {
        Access source = new Access();
        source.add(11,polygon(-130,250, -126,250, -126,262, -130,262, -130,250));
        source.add(12,polygon(-136,250, -130,250, -130,262, -136,262, -136,250));
        String original = source.features.get(11L).getGeometry().toWKT(true);
        SqliteVectorTileProvider provider = new SqliteVectorTileProvider(source,1,()->1,null);
        NativeVectorTileEncoderTest.Tile tile = NativeVectorTileEncoderTest.decode(
                provider.load(provider.snapshot(16,32768,32767),()->true));
        assertEquals("The beyond128px polygon must not be read or encoded",1,source.reads);
        assertEquals(1,tile.records.size()); assertEquals(11,tile.records.get(0).id);
        assertQuery128(source.lastQuery);
        List<int[]> coordinates = NativeVectorTileEncoderTest.coordinates(tile.records.get(0).commands);
        assertEquals(4,coordinates.size());
        for (int[] xy : coordinates) assertTrue("Full-precision clip must use the same128px margin",xy[0]>=-2048);
        assertCoordinate(tile.records.get(0),-2048,4192);
        assertCoordinate(tile.records.get(0),-2016,4000);
        assertEquals(original,source.features.get(11L).getGeometry().toWKT(true));
    }

    private static void assertQuery128(GeoEnvelope envelope) {
        assertNotNull(envelope);
        assertEquals(-128*METRES_PER_PIXEL,envelope.getMinX(),1e-7);
        assertEquals(640*METRES_PER_PIXEL,envelope.getMaxX(),1e-7);
        assertEquals(-128*METRES_PER_PIXEL,envelope.getMinY(),1e-7);
        assertEquals(640*METRES_PER_PIXEL,envelope.getMaxY(),1e-7);
    }
    private static void assertCoordinate(NativeVectorTileEncoderTest.Record record,int x,int y) {
        assertNotNull(record);
        assertTrue("Missing independently expected tile coordinate "+x+","+y,
                NativeVectorTileEncoderTest.coordinates(record.commands).stream()
                        .anyMatch(actual->Arrays.equals(new int[]{x,y},actual)));
    }
    private static int centre(NativeVectorTileEncoderTest.Record record,int axis) {
        List<int[]> coordinates=NativeVectorTileEncoderTest.coordinates(record.commands);
        int min=coordinates.stream().mapToInt(xy->xy[axis]).min().getAsInt();
        int max=coordinates.stream().mapToInt(xy->xy[axis]).max().getAsInt();
        return (min+max)/2;
    }
    private static GeoLinearRing ring(double...pixels) {
        GeoLinearRing ring = new GeoLinearRing();
        for (int i=0;i<pixels.length;i+=2) ring.add(new GeoPoint(pixels[i]*METRES_PER_PIXEL,pixels[i+1]*METRES_PER_PIXEL));
        return ring;
    }
    private static GeoPolygon polygon(double...pixels) {
        GeoPolygon polygon = new GeoPolygon(); polygon.setOuterRing(ring(pixels)); return polygon;
    }
    private static final class Access implements SqliteVectorTileProvider.Access {
        final Map<Long,com.nextgis.maplib.datasource.Feature> features = new LinkedHashMap<>();
        GeoEnvelope lastQuery; int reads;
        void add(long id,GeoGeometry geometry) {
            com.nextgis.maplib.datasource.Feature feature = new com.nextgis.maplib.datasource.Feature();
            feature.setId(id); feature.setGeometry(geometry); features.put(id,feature);
        }
        public String ownerIdentity(){return "technical-buffer-parity";}
        public boolean ownerCurrent(){return true;}
        public long contentVersion(){return 0;}
        public String styleFingerprint(){return "buffer-parity";}
        public int geometryType(){return GTPolygon;}
        public int layerId(){return 1;}
        public List<Long> query(GeoEnvelope envelope) {
            lastQuery=envelope; List<Long> ids=new ArrayList<>();
            for (com.nextgis.maplib.datasource.Feature feature:features.values())
                if(feature.getGeometry().getEnvelope().intersects(envelope)) ids.add(feature.getId());
            return ids;
        }
        public com.nextgis.maplib.datasource.Feature read(long id){reads++;return features.get(id);}
        public boolean hidden(long id){return false;}
        public Feature styled(com.nextgis.maplib.datasource.Feature original,boolean full) {
            Feature feature=Feature.fromGeometry(full?MPLFeaturesUtils.getFeatureFromNGFeature(original.getGeometry()).geometry():Point.fromLngLat(0,0));
            feature.addStringProperty("featureid",Long.toString(original.getId()));
            feature.addStringProperty("fillcolor","#12ab34");
            feature.addBooleanProperty("scalesizewithzoom",false);
            return feature;
        }
        public RenderMemoryGuard memory(){return null;}
        public File cacheDirectory(){return null;}
    }
}

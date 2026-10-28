package com.nextgis.maplib.map;

import android.app.Application;
import com.nextgis.maplib.datasource.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.File;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;
import static com.nextgis.maplib.util.GeoConstants.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=36,manifest=Config.NONE,application=Application.class)
public class SqliteVectorTileProviderTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();

    @Test public void complete4097IdsAndRulePropertiesSurviveColdThenWarmCacheWithoutGlobalReads() throws Exception {
        Fake source=new Fake(folder.getRoot());for(long id=1;id<=4097;id++)source.add(id,new GeoPoint(id,100));
        SqliteVectorTileProvider provider=new SqliteVectorTileProvider(source,7,()->7,null);
        int fingerprints=source.fingerprints;
        byte[] cold=provider.load(provider.snapshot(0,0,0),()->true);
        assertTrue("Style serialization must not scale with feature count",source.fingerprints-fingerprints<=2);
        NativeVectorTileEncoderTest.Tile tile=NativeVectorTileEncoderTest.decode(cold);
        assertEquals(4097,tile.records.size());Set<Long> ids=new HashSet<>();
        for(NativeVectorTileEncoderTest.Record feature:tile.records){ids.add(feature.id);
            assertEquals(Long.toString(feature.id),feature.properties.get(MplFeatureStyleProps.FEATURE_ID));
            assertEquals(feature.id,feature.properties.get(MplFeatureStyleProps.ORDER));
            assertEquals("#112233",feature.properties.get(MplFeatureStyleProps.COLOR_FILL_RULE));
            assertEquals(false,feature.properties.get(MplFeatureStyleProps.TEXT_SCALE_WITH_ZOOM));
            assertEquals(12L,feature.properties.get(MplFeatureStyleProps.LABEL_MIN_ZOOM));
        }
        for(long id=1;id<=4097;id++)assertTrue(ids.contains(id));
        assertEquals(4097,source.reads);assertArrayEquals(cold,provider.load(provider.snapshot(0,0,0),()->true));
        assertEquals("Warm PBF cache must avoid feature reads",4097,source.reads);assertEquals(1,source.queries);
        int after=source.fingerprints;for(int i=0;i<100;i++)assertTrue(provider.isCurrent());
        assertEquals("Cheap epoch fences must not serialize styles",after,source.fingerprints);
    }
    @Test public void explicitExclusionAndHiddenFeatureKeepStableIdsAndOriginalRichCoordinates() throws Exception {
        Fake source=new Fake(folder.getRoot());for(long id=1;id<=4;id++)source.add(id,new GeoPoint(id,100));source.hidden.add(3L);
        SqliteVectorTileProvider provider=new SqliteVectorTileProvider(source,1,()->1,2L);
        List<NativeVectorTileEncoderTest.Record> rows=NativeVectorTileEncoderTest.decode(provider.load(provider.snapshot(0,0,0),()->true)).records;
        assertEquals(2,rows.size());assertEquals(1,rows.get(0).id);assertEquals(4,rows.get(1).id);
        assertEquals(4L,rows.get(1).properties.get(MplFeatureStyleProps.ORDER));
        assertEquals(4,((GeoPoint)provider.loadOriginalFeature(4).getGeometry()).getX(),0);
        assertEquals("4",provider.readOriginalFeature(4).getStringProperty(MplFeatureStyleProps.FEATURE_ID));
        assertEquals(4,source.features.size());
    }
    @Test public void contentStyleOwnerCloseAndJobChangesNeverPublishOrCachePartialTiles() throws Exception {
        for(String cause:Arrays.asList("content","style","owner","job")){
            File cache=folder.newFolder(cause);Fake source=new Fake(cache);for(long id=1;id<=80;id++)source.add(id,new GeoPoint(id,100));
            source.mutate=cause;SqliteVectorTileProvider provider=new SqliteVectorTileProvider(source,1,()->1,null);
            try{provider.load(provider.snapshot(0,0,0),()->source.job);fail(cause);}catch(CancellationException expected){}
            assertEquals("Failed/cancelled work must never write a partial PBF",0,cache.listFiles().length);
        }
        Fake source=new Fake(folder.newFolder("closed"));source.add(1,new GeoPoint(1,1));AtomicLong epoch=new AtomicLong(1);
        SqliteVectorTileProvider provider=new SqliteVectorTileProvider(source,1,epoch::get,null);SqliteVectorTileRequest request=provider.snapshot(0,0,0);
        epoch.incrementAndGet();assertFalse(provider.isCurrent(request));provider.close();provider.close();assertFalse(provider.isCurrent());
        try{provider.load(request,()->true);fail();}catch(CancellationException expected){}
    }
    @Test public void unexpectedNestedNativePropertyFailsExplicitlyAndDoesNotBecomeEmptySuccess() throws Exception {
        Fake source=new Fake(folder.getRoot());source.add(1,new GeoPoint(1,1));source.unsupported=true;
        SqliteVectorTileProvider provider=new SqliteVectorTileProvider(source,1,()->1,null);
        try{provider.load(provider.snapshot(0,0,0),()->true);fail();}
        catch(SqliteVectorTileBackend.UnsupportedTileException expected){assertTrue(expected.getMessage().contains("unexpected"));}
        assertEquals(0,folder.getRoot().listFiles().length);assertEquals(1,source.features.size());
    }
    @Test public void viewportLinesAreDeduplicatedButRemainFullUnclippedStyledOriginals() throws Exception {
        Fake source=new Fake(folder.getRoot());source.type=GTLineString;
        source.add(9,NativeVectorTileClipperTest.line(-100,0,0,0,100,0));
        SqliteVectorTileProvider provider=new SqliteVectorTileProvider(source,1,()->1,null);
        List<Feature> rows=provider.readVisibleLines(Arrays.asList(new GeoEnvelope(-1,1,-1,1),new GeoEnvelope(0,2,-1,1)),()->true);
        assertEquals(1,rows.size());LineString line=(LineString)rows.get(0).geometry();assertEquals(3,line.coordinates().size());
        assertEquals(MPLFeaturesUtils.convert3857To4326(-100,0)[0],line.coordinates().get(0).longitude(),0);
        assertEquals(MPLFeaturesUtils.convert3857To4326(100,0)[0],line.coordinates().get(2).longitude(),0);
        assertEquals("9",rows.get(0).getStringProperty(MplFeatureStyleProps.FEATURE_ID));assertEquals(1,source.reads);
    }
    @Test public void viewportVisitorDeliversEachFullOriginalBeforeReadingTheNextAndCancelsWithoutCollecting() throws Exception {
        Fake source=new Fake(folder.getRoot());source.type=GTLineString;
        for(long id=1;id<=4;id++)source.add(id,NativeVectorTileClipperTest.line(-100,id,0,id,100,id));
        SqliteVectorTileProvider provider=new SqliteVectorTileProvider(source,1,()->1,null);
        java.util.concurrent.atomic.AtomicInteger count=new java.util.concurrent.atomic.AtomicInteger();
        provider.visitVisibleLines(Arrays.asList(new GeoEnvelope(-1,1,-1,5),new GeoEnvelope(0,2,-1,5)),()->true,feature->{
            int visited=count.incrementAndGet();assertEquals("No full feature list may be read before delivery",visited,source.reads);
            assertEquals(Integer.toString(visited),feature.getStringProperty(MplFeatureStyleProps.FEATURE_ID));
            assertEquals(3,((LineString)feature.geometry()).coordinates().size());
            assertEquals(MPLFeaturesUtils.convert3857To4326(-100,visited)[0],((LineString)feature.geometry()).coordinates().get(0).longitude(),0);
        });assertEquals(4,count.get());assertEquals(4,source.reads);
        source.reads=0;count.set(0);
        assertThrows(CancellationException.class,()->provider.visitVisibleLines(List.of(new GeoEnvelope(-1,1,-1,5)),()->true,feature->{
            if(count.incrementAndGet()==2)throw new CancellationException();
        }));assertEquals(2,source.reads);assertTrue(provider.isCurrent());assertEquals(4,source.features.size());
    }
    @Test public void classicPolygonAnchorHasOneHalfOpenOwnerAndLabelOnlyPropertiesAcrossFourTiles() throws Exception {
        Fake source=new Fake(folder.getRoot());source.type=GTPolygon;
        GeoPolygon polygon=NativeVectorTileClipperTest.polygon(-1000000,-1000000,1000000,-1000000,
                1000000,1000000,-1000000,1000000,-1000000,-1000000);
        String before=polygon.toWKT(true);source.add(42,polygon);
        SqliteVectorTileProvider provider=new SqliteVectorTileProvider(source,1,()->1,null);int labels=0;
        for(int x=0;x<2;x++)for(int y=0;y<2;y++){
            for(NativeVectorTileEncoderTest.Record record:NativeVectorTileEncoderTest.decode(provider.load(provider.snapshot(1,x,y),()->true)).records){
                assertEquals(42,record.id);
                if(record.type==1){labels++;assertEquals(1,x);assertEquals(1,y);
                    assertEquals(true,record.properties.get("_label_anchor"));assertEquals("label",record.properties.get(MplFeatureStyleProps.SIGNATURE));
                    assertFalse(record.properties.containsKey(MplFeatureStyleProps.COLOR_FILL_RULE));
                }else{assertEquals(3,record.type);assertFalse(record.properties.containsKey(MplFeatureStyleProps.SIGNATURE));
                    assertEquals("#112233",record.properties.get(MplFeatureStyleProps.COLOR_FILL_RULE));}
            }
        }
        assertEquals(1,labels);assertEquals(before,polygon.toWKT(true));
    }

    static final class Fake implements SqliteVectorTileProvider.Access {
        final File cache;final Map<Long,com.nextgis.maplib.datasource.Feature> features=new LinkedHashMap<>();final Set<Long> hidden=new HashSet<>();
        int type=GTPoint,reads,queries,fingerprints;long version;String style="style",mutate;boolean owner=true,job=true,unsupported;
        Fake(File cache){this.cache=cache;}
        void add(long id,GeoGeometry geometry){com.nextgis.maplib.datasource.Feature feature=new com.nextgis.maplib.datasource.Feature();feature.setId(id);feature.setGeometry(geometry);features.put(id,feature);}
        public String ownerIdentity(){return cache.getAbsolutePath();}public boolean ownerCurrent(){return owner;}
        public long contentVersion(){return version;}public String styleFingerprint(){fingerprints++;return style;}
        public int geometryType(){return type;}public int layerId(){return 7;}
        public List<Long> query(GeoEnvelope envelope){queries++;return new ArrayList<>(features.keySet());}
        public com.nextgis.maplib.datasource.Feature read(long id){reads++;if(reads==2 && mutate!=null){
            if(mutate.equals("content"))version++;if(mutate.equals("style"))style="changed";if(mutate.equals("owner"))owner=false;if(mutate.equals("job"))job=false;
        }return features.get(id);}
        public boolean hidden(long id){return hidden.contains(id);}public RenderMemoryGuard memory(){return null;}public File cacheDirectory(){return cache;}
        public Feature styled(com.nextgis.maplib.datasource.Feature original,boolean full){
            Feature feature=full?MPLFeaturesUtils.getFeatureFromNGFeature(original.getGeometry()):Feature.fromGeometry(Point.fromLngLat(0,0));
            feature.addStringProperty(MplFeatureStyleProps.FEATURE_ID,Long.toString(original.getId()));feature.addStringProperty(MplFeatureStyleProps.LAYER_ID,"7");
            feature.addNumberProperty(MplFeatureStyleProps.ORDER,original.getId());feature.addStringProperty(MplFeatureStyleProps.COLOR_FILL_RULE,"#112233");
            feature.addStringProperty(MplFeatureStyleProps.SIGNATURE,"label");feature.addBooleanProperty(MplFeatureStyleProps.TEXT_SCALE_WITH_ZOOM,false);
            feature.addNumberProperty(MplFeatureStyleProps.LABEL_MIN_ZOOM,12);
            if(unsupported){com.google.gson.JsonObject nested=new com.google.gson.JsonObject();nested.addProperty("private","not-logged");feature.addProperty("unexpected",nested);}
            return feature;
        }
    }
}

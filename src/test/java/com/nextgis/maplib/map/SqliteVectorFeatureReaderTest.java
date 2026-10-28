package com.nextgis.maplib.map;

import android.app.Application;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import com.nextgis.maplib.datasource.Feature;
import com.nextgis.maplib.datasource.Field;
import com.nextgis.maplib.datasource.GeoPoint;
import com.nextgis.maplib.datasource.GeoPolygon;
import com.nextgis.maplib.datasource.GeoLinearRing;
import com.nextgis.maplib.datasource.GeoMultiPolygon;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.nextgis.maplib.util.GeoConstants.*;
import static org.junit.Assert.*;

/** Independent technical rows only; neither business inputs nor native BLOB descriptor acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE,application=Application.class)
public class SqliteVectorFeatureReaderTest {
    @Test public void obsoleteDuringAttributePreparationDoesNotStartGeometryOrPfdQuery() throws Exception {
        AtomicBoolean live=new AtomicBoolean(true);
        Store store=new Store(){@Override public Cursor attributes(List<Long> ids){Cursor c=super.attributes(ids);live.set(false);return c;}};
        store.geometryEnabled=true;
        assertThrows(CancellationException.class,()->SqliteVectorFeatureReader.visit(Arrays.asList(9L,7L).iterator(),store,live::get,null,f->fail("Obsolete row delivered")));
        assertEquals(1,store.cursorsClosed);assertEquals(0,store.geometryQueries);assertEquals(0,store.blobOpens);
    }
    @Test public void unsupportedMidWindowFallsBackOnlyForUndeliveredIdsAndDisablesLaterWindows() throws Exception {
        Store store=new Store(){@Override public Cursor geometries(List<Long> ids)throws Exception {
            geometryQueries++;
            MatrixCursor rows=new MatrixCursor(geometryColumns()){
                @Override public byte[] getBlob(int column){if(getPosition()==1)throw new UnsupportedOperationException("technical driver");return super.getBlob(column);}
                @Override public void close(){super.close();geometryClosed++;}
            };
            for(long id:ids){byte[] bytes=body(id);rows.addRow(new Object[]{id,"blob",bytes.length,bytes});}return rows;
        }};
        List<Long> ids=new ArrayList<>();for(long id=65;id>0;id--)ids.add(id);
        List<Long> delivered=new ArrayList<>();SqliteVectorFeatureReader.visit(ids.iterator(),store,()->true,null,f->delivered.add(f.getId()));
        assertEquals(ids,delivered);assertEquals(1,store.geometryQueries);assertEquals(1,store.geometryClosed);
        assertEquals(64,store.blobReads);assertEquals(1,store.blobsClosed);assertEquals(3,store.cursorsClosed);
    }
    @Test public void geometryFactoryFailureIncludingUnsupportedCleanupIsNotAReadFallback() throws Exception {
        UnsupportedOperationException cleanup=new UnsupportedOperationException("technical cleanup failure");
        cleanup.addSuppressed(new UnsupportedOperationException("technical unavailable window"));
        Store store=new Store(){@Override public Cursor geometries(List<Long> ids){throw cleanup;}};
        assertSame(cleanup,assertThrows(UnsupportedOperationException.class,()->read(store,Arrays.asList(9L,7L))));
        assertEquals(0,store.blobOpens);assertEquals(1,store.cursorsClosed);
    }
    @Test public void windowRealSqliteAndCloseFailuresCannotBecomeSuccessfulPfdFallback() throws Exception {
        SQLiteException sql=new SQLiteException("technical geometry query failure");
        Store query=new Store(){@Override public Cursor geometries(List<Long> ids){throw sql;}};
        assertSame(sql,assertThrows(SQLiteException.class,()->read(query,Arrays.asList(9L))));assertEquals(0,query.blobReads);
        UnsupportedOperationException cleanup=new UnsupportedOperationException("technical close failure");
        Store close=new Store(){@Override public Cursor geometries(List<Long> ids){
            MatrixCursor c=new MatrixCursor(geometryColumns()){
                @Override public byte[] getBlob(int column){throw new UnsupportedOperationException("technical driver");}
                @Override public void close(){geometryClosed++;throw cleanup;}
            };c.addRow(new Object[]{9L,"blob",1,new byte[]{1}});return c;
        }};
        assertSame(cleanup,assertThrows(UnsupportedOperationException.class,()->read(close,Arrays.asList(9L))));
        assertEquals(0,close.blobReads);assertEquals(1,close.geometryClosed);assertEquals(1,close.cursorsClosed);assertEquals(1,cleanup.getSuppressed().length);
    }
    @Test public void geometryIdCardinalityNullAndLengthMismatchesFailInsteadOfReturningPartialSuccess() throws Exception {
        byte[] bytes=new GeoPoint(9,-9).toBlob();
        List<Object[][]> invalid=Arrays.asList(
                new Object[][]{{7L,"blob",bytes.length,bytes}},
                new Object[][]{},
                new Object[][]{{9L,"blob",bytes.length,bytes},{7L,"blob",bytes.length,bytes}},
                new Object[][]{{9L,"null",null,null}},
                new Object[][]{{9L,"blob",bytes.length,null}},
                new Object[][]{{9L,"blob",bytes.length+1,bytes}},
                new Object[][]{{9L,"blob",-1,null}},
                new Object[][]{{9L,"blob",16385,bytes}},
                new Object[][]{{9L,"blob",bytes.length+"",bytes}},
                new Object[][]{{9L,"text",4,null}});
        for(Object[][] data:invalid){
            Store store=new Store(){@Override public Cursor geometries(List<Long> ids){geometryQueries++;MatrixCursor c=new MatrixCursor(geometryColumns()){@Override public void close(){super.close();geometryClosed++;}};for(Object[] row:data)c.addRow(row);return c;}};
            assertThrows(IOException.class,()->read(store,Collections.singletonList(9L)));
            assertEquals(1,store.geometryClosed);assertEquals(1,store.cursorsClosed);assertEquals(0,store.blobReads);
        }
    }
    @Test public void largeFallbackMustReturnExactlyTheDeclaredFullBlobLength() throws Exception {
        Store store=new Store(){@Override public Cursor geometries(List<Long> ids){MatrixCursor c=new MatrixCursor(geometryColumns());c.addRow(new Object[]{9L,"blob",16385,null});return c;}};
        store.bodies.put(9L,new byte[16384]);assertThrows(IOException.class,()->read(store,Collections.singletonList(9L)));assertEquals(1,store.blobReads);assertEquals(1,store.blobsClosed);
    }
    @Test public void cancellationDuringInlineReadClosesBothCursorsWithoutPfdOrDelivery() throws Exception {
        AtomicBoolean live=new AtomicBoolean(true);
        Store store=new Store(){@Override public Cursor geometries(List<Long> ids)throws Exception {
            MatrixCursor c=new MatrixCursor(geometryColumns()){
                @Override public byte[] getBlob(int column){byte[] bytes=super.getBlob(column);live.set(false);return bytes;}
                @Override public void close(){super.close();geometryClosed++;}
            };byte[] bytes=body(9L);c.addRow(new Object[]{9L,"blob",bytes.length,bytes});return c;
        }};
        assertThrows(CancellationException.class,()->SqliteVectorFeatureReader.visit(Collections.singletonList(9L).iterator(),store,live::get,null,f->fail("Cancelled row delivered")));
        assertEquals(1,store.geometryClosed);assertEquals(1,store.cursorsClosed);assertEquals(0,store.blobOpens);
    }
    @Test public void mixedInlineAndLargeVisitClosesAllThreeResourcesAfterVisitorFailure() throws Exception {
        Store store=new Store();store.geometryEnabled=true;
        byte[] large=new byte[16385];store.bodies.put(7L,large);
        // Use a valid large multipart original so the deliberate visitor failure follows full decoding.
        GeoPolygon polygon=new GeoPolygon();for(int i=0;i<1100;i++)polygon.add(new GeoPoint(i,-i));polygon.add(new GeoPoint(0,0));
        store.bodies.put(7L,polygon.toBlob());assertTrue(store.bodies.get(7L).length>16384);
        IOException expected=new IOException("technical visitor refusal");List<Long> delivered=new ArrayList<>();
        assertSame(expected,assertThrows(IOException.class,()->SqliteVectorFeatureReader.visit(Arrays.asList(9L,7L,5L).iterator(),store,()->true,null,f->{delivered.add(f.getId());if(f.getId()==7L)throw expected;})));
        assertEquals(Arrays.asList(9L,7L),delivered);assertEquals(1,store.blobReads);assertEquals(1,store.blobsClosed);assertEquals(1,store.geometryClosed);assertEquals(1,store.cursorsClosed);
    }
    @Test @Config(sdk=30) public void actualSqliteWindowReturnsOnlyWholeSmallBlobsAndNoLargeOrTextPayload() throws Exception {
        try(SQLiteDatabase db=SQLiteDatabase.create(null)) {
            db.execSQL("CREATE TABLE technical (_id INTEGER PRIMARY KEY, _geom, note TEXT)");
            Map<Long,byte[]> bodies=new HashMap<>();int[] lengths={16384,16385,3*1024*1024};
            for(int i=0;i<lengths.length;i++){long id=i+1;byte[] bytes=new byte[lengths[i]];new Random(31+i).nextBytes(bytes);bodies.put(id,bytes);ContentValues row=new ContentValues();row.put("_id",id);row.put("_geom",bytes);row.put("note","technical");db.insertOrThrow("technical",null,row);}
            ContentValues text=new ContentValues();text.put("_id",4);text.put("_geom",String.join("",Collections.nCopies(1024*1024,"x")));db.insertOrThrow("technical",null,text);
            ContentValues nil=new ContentValues();nil.put("_id",5);nil.putNull("_geom");db.insertOrThrow("technical",null,nil);
            SqliteVectorFeatureReader.Storage storage=SqliteVectorFeatureReader.sqlite(db,"technical",Arrays.asList(new Field(FTString,"note","")));
            try(Cursor c=storage.geometries(Arrays.asList(3L,1L,4L,2L,5L))){
                assertTrue(c instanceof SQLiteCursor);assertNotNull(((SQLiteCursor)c).getWindow());assertEquals(5,c.getCount());
                for(long id:new long[]{3,1,4,2,5}){assertTrue(c.moveToNext());assertEquals(id,c.getLong(0));
                    if(id<=3){assertEquals("blob",c.getString(1));assertEquals(bodies.get(id).length,c.getLong(2));if(id==1)assertArrayEquals(bodies.get(id),c.getBlob(3));else assertTrue(c.isNull(3));}
                    else {assertEquals(id==4?"text":"null",c.getString(1));assertTrue(c.isNull(2));assertTrue(c.isNull(3));}
                }assertFalse(c.moveToNext());
            }
            try(Cursor attributes=storage.attributes(Collections.singletonList(1L))){assertArrayEquals(new String[]{"_id","note"},attributes.getColumnNames());}
            assertThrows(IllegalArgumentException.class,()->storage.geometries(Collections.emptyList()));
            assertThrows(IllegalArgumentException.class,()->storage.geometries(Collections.nCopies(33,1L)));
            db.execSQL("DROP TABLE technical");assertThrows(SQLiteException.class,()->storage.geometries(Collections.singletonList(1L)));
        }
    }
    @Test @Config(sdk=26) public void api26UsesOnlyExistingPfdPathWithoutGeometryCursor() throws Exception {assertOldApiHasNoGeometryQuery();}
    @Test @Config(sdk=27) public void api27UsesOnlyExistingPfdPathWithoutGeometryCursor() throws Exception {assertOldApiHasNoGeometryQuery();}
    private static void assertOldApiHasNoGeometryQuery()throws Exception {
        try(SQLiteDatabase db=SQLiteDatabase.create(null)) {
            // No table is intentional: a geometry query here would fail rather than return null.
            assertNull(SqliteVectorFeatureReader.sqlite(db,"absent",Collections.emptyList()).geometries(Collections.singletonList(9L)));
        }
        Store store=new Store();read(store,Arrays.asList(9L,7L));assertEquals(2,store.blobReads);assertEquals(1,store.blobsClosed);
    }
    private static String[] geometryColumns(){return new String[]{"_id","__blob_type","__blob_length","__small_blob"};}
    @Test public void smallOriginalsUseThreeGeometryWindowsAndNoPfdReads() throws Exception {
        Store store=new Store();store.geometryEnabled=true;List<Long> ids=new ArrayList<>();for(long id=65;id>0;id--)ids.add(id);
        List<Long> actual=new ArrayList<>();SqliteVectorFeatureReader.visit(ids.iterator(),store,()->true,null,f->actual.add(f.getId()));
        assertEquals(ids,actual);assertEquals(3,store.queries);
        assertEquals("65 small original buffers must not require65 PFD reads",0,store.blobReads);
        assertEquals(3,store.geometryQueries);assertEquals(3,store.geometryClosed);assertEquals(0,store.blobOpens);
    }
    @Test public void transportThresholdUsesWholeBuffersAndPfdOnlyFor16385AndMultiMib() throws Exception {
        Store store=new Store();store.geometryEnabled=true;List<Long> ids=Arrays.asList(9L,7L,5L,3L,1L);
        int[] lengths={1,16383,16384,16385,3*1024*1024};
        for(int i=0;i<ids.size();i++){byte[] bytes=new byte[lengths[i]];new Random(19+i).nextBytes(bytes);store.bodies.put(ids.get(i),bytes);}
        try(SqliteVectorFeatureReader.BlobReader fallback=store.blobs();SqliteVectorFeatureReader.GeometryRows window=new SqliteVectorFeatureReader.GeometryState(store).open(ids,com.nextgis.maplib.util.MapRuntimeDiagnostics.sqliteTile(-1,0))){
            for(long id:ids)assertArrayEquals(store.bodies.get(id),window.read(id,fallback,com.nextgis.maplib.util.MapRuntimeDiagnostics.sqliteTile(-1,0),()->true));
            window.finish();
        }
        assertEquals("Only complete16385-byte and3MiB buffers need PFD",2,store.blobReads);
        assertEquals(1,store.geometryQueries);assertEquals(1,store.geometryClosed);
    }
    @Test public void all65OriginalsKeepCandidateOrderTypesNullsAndFullBlobWithThreeAttributeQueries() throws Exception {
        Store store=new Store();List<Long> ids=new ArrayList<>();
        for(long low=1,high=65;low<=high;low++,high--){ids.add(high);if(low!=high)ids.add(low);}
        List<Long> visited=new ArrayList<>();
        SqliteVectorFeatureReader.visit(ids.iterator(),store,()->true,null,feature->{
            long id=feature.getId();visited.add(id);
            assertEquals(id%2==0 ? null : "technical-"+id,feature.getFieldValue("text"));
            assertEquals(Long.valueOf(9007199254740993L+id),feature.getFieldValue("wide"));
            assertEquals(Integer.valueOf((int)-id),feature.getFieldValue("integer"));
            assertEquals(Double.valueOf(id+0.125),feature.getFieldValue("real"));
            assertEquals(Long.valueOf(1700000000000L+id),feature.getFieldValue("date"));
            assertArrayEquals(new GeoPoint(id,-id).toBlob(),feature.getGeometry().toBlob());
        });
        assertEquals(ids,visited);assertEquals(65,store.blobReads);
        assertEquals("65 scoped IDs require three bounded attribute queries, not65",3,store.queries);
        assertTrue(store.maxIds<=32);assertEquals(store.queries,store.cursorsClosed);
    }
    @Test public void oneVisitCompilesOneBlobReaderAndClosesItExactlyOnce() throws Exception {
        Store store=new Store();read(store,Arrays.asList(9L,1L,7L,3L,5L));
        assertEquals(5,store.blobReads);assertEquals(1,store.blobOpens);assertEquals(1,store.blobsClosed);
    }
    @Test public void fullLargeMultipartAndHoleBlobIsPreservedWithoutAttributeGeometryProjection() throws Exception {
        GeoPolygon polygon=new GeoPolygon();
        for(int i=0;i<3000;i++){double angle=2*Math.PI*i/3000;polygon.add(new GeoPoint(Math.cos(angle)*10000,Math.sin(angle)*10000));}
        polygon.add(new GeoPoint(10000,0));GeoLinearRing hole=new GeoLinearRing();
        for(double[] xy:new double[][]{{-1,-1},{1,-1},{1,1},{-1,1},{-1,-1}})hole.add(new GeoPoint(xy[0],xy[1]));
        polygon.addInnerRing(hole);GeoMultiPolygon multi=new GeoMultiPolygon();multi.add(polygon);
        multi.add(NativeVectorTileClipperTest.polygon(20000,20000,20001,20000,20001,20001,20000,20001,20000,20000));
        Store store=new Store();byte[] expected=multi.toBlob();assertTrue(expected.length>32768);store.customBlob=expected;
        SqliteVectorFeatureReader.visit(Arrays.asList(9L).iterator(),store,()->true,null,f->{
            assertArrayEquals(expected,f.getGeometry().toBlob());GeoMultiPolygon actual=(GeoMultiPolygon)f.getGeometry();
            assertEquals(2,actual.size());assertEquals(3001,actual.get(0).getOuterRing().getPointCount());assertEquals(1,actual.get(0).getInnerRingCount());
        });assertEquals(1,store.blobReads);assertEquals(1,store.blobsClosed);
    }
    @Test public void emptyAndAlreadyCancelledRequestsDoNotOpenResources() throws Exception {
        Store store=new Store();read(store,Collections.emptyList());assertEquals(0,store.queries);assertEquals(0,store.blobOpens);
        assertThrows(CancellationException.class,()->SqliteVectorFeatureReader.visit(Arrays.asList(1L).iterator(),store,()->false,null,f->{}));
        assertEquals(0,store.queries);assertEquals(0,store.blobOpens);
    }
    @Test public void cancellationAfterTwoDeliveriesClosesCursorAndStatementWithoutDecodingMoreGeometry() throws Exception {
        Store store=new Store();AtomicBoolean live=new AtomicBoolean(true);List<Long> delivered=new ArrayList<>();
        assertThrows(CancellationException.class,()->SqliteVectorFeatureReader.visit(Arrays.asList(9L,7L,5L,3L).iterator(),store,live::get,null,f->{delivered.add(f.getId());if(delivered.size()==2)live.set(false);}));
        assertEquals(Arrays.asList(9L,7L),delivered);assertEquals(2,store.blobReads);
        assertEquals(store.queries,store.cursorsClosed);assertEquals(store.blobOpens,store.blobsClosed);
    }
    @Test public void resourceAdmissionRunsBeforeQueryAndBeforeAnyFullBlobAllocation() throws Exception {
        Store first=new Store();IOException denied=new IOException("technical admission refusal");
        SqliteVectorFeatureReader.Visitor refuse=new SqliteVectorFeatureReader.Visitor(){
            public void beforeRead()throws Exception{throw denied;}
            public void accept(Feature f){fail("Denied row was delivered");}
        };
        assertSame(denied,assertThrows(IOException.class,()->SqliteVectorFeatureReader.visit(Arrays.asList(9L,7L).iterator(),first,()->true,null,refuse)));
        assertEquals(0,first.queries);assertEquals(0,first.blobOpens);assertEquals(0,first.blobReads);
        Store later=new Store();
        assertSame(denied,assertThrows(IOException.class,()->SqliteVectorFeatureReader.visit(Arrays.asList(9L,7L).iterator(),later,()->true,null,new SqliteVectorFeatureReader.Visitor(){
            int before;
            public void beforeRead()throws Exception{if(++before==2)throw denied;}
            public void accept(Feature f){assertEquals(9L,f.getId());}
        })));
        assertEquals(1,later.blobReads);assertEquals(1,later.cursorsClosed);assertEquals(1,later.blobsClosed);
    }
    @Test public void missingIndexedIdCannotBecomeSuccessfulPartialRead() throws Exception {
        Store store=new Store();store.missing=7L;
        assertThrows(IOException.class,()->read(store,Arrays.asList(9L,7L,5L)));
        assertEquals(store.queries,store.cursorsClosed);assertEquals(store.blobOpens,store.blobsClosed);
    }
    @Test public void unexpectedExtraRowsAreNotSilentlyAccepted() throws Exception {
        Store store=new Store();store.extra=true;
        assertThrows(IOException.class,()->read(store,Arrays.asList(9L,7L)));
        assertEquals(store.queries,store.cursorsClosed);assertEquals(store.blobOpens,store.blobsClosed);
    }
    @Test public void currentRealReadErrorAndVisitorErrorRemainTheOriginalFailures() throws Exception {
        Store store=new Store();IOException expected=new IOException("technical IO failure");store.failure=expected;
        assertSame(expected,assertThrows(IOException.class,()->read(store,Arrays.asList(9L,7L))));
        assertEquals(store.queries,store.cursorsClosed);assertEquals(store.blobOpens,store.blobsClosed);
        Store other=new Store();IOException visitor=new IOException("technical visitor failure");
        assertSame(visitor,assertThrows(IOException.class,()->SqliteVectorFeatureReader.visit(Arrays.asList(9L,7L).iterator(),other,()->true,null,f->{throw visitor;})));
        assertEquals(other.queries,other.cursorsClosed);assertEquals(other.blobOpens,other.blobsClosed);
    }
    @Test public void failedStatementOpenStillClosesAttributesCursor() throws Exception {
        Store store=new Store();store.openFailure=true;
        assertThrows(IOException.class,()->read(store,Arrays.asList(9L,7L)));assertEquals(store.queries,store.cursorsClosed);
    }
    @Test public void wrongOrderMissingBlobAndDecodeErrorsCannotBecomePartialSuccess() throws Exception {
        Store wrongOrder=new Store();wrongOrder.reverse=true;
        assertThrows(IOException.class,()->read(wrongOrder,Arrays.asList(9L,7L,5L)));assertEquals(0,wrongOrder.blobReads);
        assertEquals(wrongOrder.queries,wrongOrder.cursorsClosed);
        Store absent=new Store();absent.nullBlob=true;
        assertThrows(IOException.class,()->read(absent,Arrays.asList(9L,7L)));assertEquals(absent.queries,absent.cursorsClosed);assertEquals(1,absent.blobsClosed);
        Store invalid=new Store();invalid.badBlob=true;
        assertThrows(IOException.class,()->read(invalid,Arrays.asList(9L,7L)));assertEquals(invalid.queries,invalid.cursorsClosed);assertEquals(1,invalid.blobsClosed);
    }
    @Test public void cleanupFailureIsSuppressedWithoutReplacingTheOriginalRealFailure() throws Exception {
        Store store=new Store();store.failure=new IOException("technical read failure");store.closeFailure=true;
        IOException actual=assertThrows(IOException.class,()->read(store,Arrays.asList(9L,7L)));
        assertSame(store.failure,actual);assertEquals(1,actual.getSuppressed().length);assertEquals(1,store.blobsClosed);assertEquals(1,store.cursorsClosed);
    }
    @Test public void actualSqliteAttributeCursorHonoursNonPkOrderAndPreservesNullsAndWideIntegers() throws Exception {
        try(SQLiteDatabase db=SQLiteDatabase.create(null)) {
            db.execSQL("CREATE TABLE technical (_id INTEGER PRIMARY KEY, text TEXT, wide INTEGER, integer_value INTEGER, real_value REAL, date_value INTEGER)");
            List<Field> fields=Arrays.asList(new Field(FTString,"text",""),new Field(FTLong,"wide",""));
            for(long id:new long[]{1,3,9}){ContentValues row=new ContentValues();row.put("_id",id);row.putNull("text");row.put("wide",9007199254740993L+id);db.insertOrThrow("technical",null,row);}
            try(Cursor cursor=SqliteVectorFeatureReader.sqlite(db,"technical",fields).attributes(Arrays.asList(9L,1L,3L))){
                for(long id:new long[]{9,1,3}){assertTrue(cursor.moveToNext());Feature f=new Feature(id,fields);f.fromCursorWithoutGeometry(cursor);assertEquals(id,f.getId());assertNull(f.getFieldValue("text"));assertEquals(Long.valueOf(9007199254740993L+id),f.getFieldValue("wide"));}
                assertFalse(cursor.moveToNext());assertEquals(-1,cursor.getColumnIndex("geom"));
            }
            SqliteVectorFeatureReader.Storage storage=SqliteVectorFeatureReader.sqlite(db,"technical",fields);db.execSQL("DROP TABLE technical");
            assertThrows(SQLiteException.class,()->storage.attributes(Arrays.asList(1L)));
        }
    }
    private static void read(Store store,List<Long> ids) throws Exception {SqliteVectorFeatureReader.visit(ids.iterator(),store,()->true,null,f->{});}
    private static class Store implements SqliteVectorFeatureReader.Storage {
        int queries,cursorsClosed,blobOpens,blobsClosed,blobReads,maxIds,geometryQueries,geometryClosed;Long missing;byte[] customBlob;boolean extra,openFailure,reverse,nullBlob,badBlob,closeFailure,geometryEnabled;IOException failure;
        final Map<Long,byte[]> bodies=new HashMap<>();
        final List<Field> fields=Arrays.asList(new Field(FTString,"text",""),new Field(FTLong,"wide",""),new Field(FTInteger,"integer",""),new Field(FTReal,"real",""),new Field(FTDateTime,"date",""));
        public List<Field> fields(){return fields;}
        public Cursor attributes(List<Long> ids){queries++;maxIds=Math.max(maxIds,ids.size());MatrixCursor c=new MatrixCursor(new String[]{"_id","text","wide","integer","real","date"}){@Override public void close(){super.close();cursorsClosed++;}};
            List<Long> rows=new ArrayList<>(ids);if(reverse)Collections.reverse(rows);
            for(long id:rows)if(!Objects.equals(missing,id))c.addRow(new Object[]{id,id%2==0?null:"technical-"+id,9007199254740993L+id,(int)-id,id+0.125,1700000000000L+id});
            if(extra)c.addRow(new Object[]{999L,null,1L,1,1.0,1L});return c;
        }
        byte[] body(long id)throws IOException{return bodies.containsKey(id)?bodies.get(id):customBlob!=null?customBlob:new GeoPoint(id,-id).toBlob();}
        public Cursor geometries(List<Long> ids)throws Exception {
            if(!geometryEnabled)return null;geometryQueries++;
            MatrixCursor c=new MatrixCursor(new String[]{"_id","__blob_type","__blob_length","__small_blob"}){@Override public void close(){super.close();geometryClosed++;}};
            for(long id:ids){byte[] bytes=body(id);c.addRow(new Object[]{id,bytes==null?"null":"blob",bytes==null?null:bytes.length,bytes!=null&&bytes.length<=16384?bytes:null});}
            return c;
        }
        public SqliteVectorFeatureReader.BlobReader blobs() throws IOException {if(openFailure)throw new IOException("technical statement failure");blobOpens++;
            return new SqliteVectorFeatureReader.BlobReader(){public byte[] read(long id)throws Exception{blobReads++;if(failure!=null)throw failure;if(nullBlob)return null;if(badBlob)return new byte[]{0,1};return body(id);}public void close()throws IOException{blobsClosed++;if(closeFailure)throw new IOException("technical close failure");}};
        }
    }
}

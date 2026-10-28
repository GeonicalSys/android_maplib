package com.nextgis.maplib.map;

import android.database.Cursor;
import android.database.CursorWindow;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteCursor;
import android.database.sqlite.SQLiteDoneException;
import android.database.sqlite.SQLiteStatement;
import android.os.ParcelFileDescriptor;
import android.os.Build;
import android.annotation.TargetApi;
import com.nextgis.maplib.datasource.Feature;
import com.nextgis.maplib.datasource.Field;
import com.nextgis.maplib.datasource.GeoGeometryFactory;
import com.nextgis.maplib.util.MapRuntimeDiagnostics;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import static com.nextgis.maplib.util.Constants.FIELD_ID;
import static com.nextgis.maplib.util.Constants.FIELD_GEOM;
import static com.nextgis.maplib.util.MapRuntimeDiagnostics.SqlitePhase.*;

/** Originals only: a separate bounded small-BLOB window never enlarges the attributes projection. */
final class SqliteVectorFeatureReader {
    private static final int ATTRIBUTE_BATCH_ROWS = 32;
    static final int SMALL_BLOB_MAX_BYTES = 16384;
    static final long GEOMETRY_WINDOW_BYTES = 1024 * 1024;
    static final String BLOB_TYPE = "__blob_type", BLOB_LENGTH = "__blob_length", SMALL_BLOB = "__small_blob";
    interface Visitor {
        default void beforeRead() throws Exception {}
        void accept(Feature feature) throws Exception;
    }
    interface BlobReader extends AutoCloseable {
        byte[] read(long id) throws Exception;
        @Override void close() throws Exception;
    }
    interface Storage {
        List<Field> fields();
        Cursor attributes(List<Long> ids) throws Exception;
        BlobReader blobs() throws Exception;
        default Cursor geometries(List<Long> ids) throws Exception { return null; }
    }

    static Storage sqlite(SQLiteDatabase database, String table, List<Field> fields) {
        final List<Field> snapshot = new ArrayList<>(fields);
        final String[] projection = new String[snapshot.size()+1];
        projection[0] = quote(FIELD_ID);
        for(int i=0;i<snapshot.size();i++) projection[i+1] = quote(snapshot.get(i).getName());
        return new Storage() {
            public List<Field> fields() { return snapshot; }
            public Cursor attributes(List<Long> ids) {
                // Candidate IDs are already RTree-scoped. Do not swallow real SQLite exceptions.
                return orderedQuery(database,table,projection,ids);
            }
            public Cursor geometries(List<Long> ids) throws Exception {
                // API26/27 retain the existing complete-PFD path rather than an unbounded default window.
                if(Build.VERSION.SDK_INT<28)return null;
                return geometryWindow(database,table,ids);
            }
            public BlobReader blobs() {
                SQLiteStatement statement = database.compileStatement("SELECT "+quote(FIELD_GEOM)+" FROM "+quote(table)+" WHERE "+quote(FIELD_ID)+" = ?");
                return new BlobReader() {
                    public byte[] read(long id) {
                        statement.bindLong(1,id);
                        ParcelFileDescriptor descriptor;
                        try { descriptor = statement.simpleQueryForBlobFileDescriptor(); }
                        catch(SQLiteDoneException absent) { return null; }
                        return descriptor == null ? null : VectorLayer.readBlob(descriptor);
                    }
                    public void close() { statement.close(); }
                };
            }
        };
    }

    private static Cursor orderedQuery(SQLiteDatabase database,String table,String[] projection,List<Long> ids) {
        StringBuilder selection=new StringBuilder(quote(FIELD_ID)+" IN (");
        StringBuilder order=new StringBuilder("CASE "+quote(FIELD_ID));String[] args=new String[ids.size()*2];
        for(int i=0;i<ids.size();i++) {
            if(i>0)selection.append(',');selection.append('?');order.append(" WHEN ? THEN ").append(i);
            args[i]=args[i+ids.size()]=Long.toString(ids.get(i));
        }
        selection.append(')');order.append(" END");
        return database.query(quote(table),projection,selection.toString(),args,null,null,order.toString());
    }
    @TargetApi(28)
    private static Cursor geometryWindow(SQLiteDatabase database,String table,List<Long> ids)throws Exception {
        if(ids.isEmpty() || ids.size()>ATTRIBUTE_BATCH_ROWS)
            throw new IllegalArgumentException("Geometry window requires1..32 scoped IDs");
        String column=quote(FIELD_GEOM),isBlob="typeof("+column+") = 'blob'";
        String[] projection={quote(FIELD_ID),"typeof("+column+") AS "+quote(BLOB_TYPE),
                "CASE WHEN "+isBlob+" THEN length("+column+") ELSE NULL END AS "+quote(BLOB_LENGTH),
                "CASE WHEN "+isBlob+" THEN CASE WHEN length("+column+") <= "+SMALL_BLOB_MAX_BYTES+
                        " THEN "+column+" ELSE NULL END ELSE NULL END AS "+quote(SMALL_BLOB)};
        Cursor cursor=orderedQuery(database,table,projection,ids);
        if(cursor==null)throw incomplete();
        if(cursor.getClass()!=SQLiteCursor.class){cursor.close();return null;}
        CursorWindow unowned=null;
        try {
            unowned=new CursorWindow("LISA-small-geometry",GEOMETRY_WINDOW_BYTES);
            ((SQLiteCursor)cursor).setWindow(unowned);unowned=null; // Ownership now belongs only to cursor.
            cursor.getCount(); // Fill/preflight before any row is emitted, charged to BLOB_READ.
            return cursor;
        } catch(UnsupportedOperationException | AbstractMethodError unsupported) {
            try {closeWindowResources(cursor,unowned);}catch(RuntimeException | Error cleanup){cleanup.addSuppressed(unsupported);throw cleanup;}
            return null;
        } catch(RuntimeException | Error failure) {
            try {closeWindowResources(cursor,unowned);}catch(RuntimeException | Error cleanup){if(cleanup!=failure)failure.addSuppressed(cleanup);}
            throw failure;
        }
    }
    private static void closeWindowResources(Cursor cursor,CursorWindow unowned) {
        try(Cursor closingCursor=cursor;CursorWindow closingUnowned=unowned) { /* independent ownership */ }
    }

    static void visit(Iterator<Long> ids, Storage storage, BooleanSupplier current,
                      MapRuntimeDiagnostics.SqliteScope diagnostics, Visitor visitor) throws Exception {
        MapRuntimeDiagnostics.SqliteScope scope = diagnostics == null ? MapRuntimeDiagnostics.sqliteTile(-1,0) : diagnostics;
        requireCurrent(current);
        GeometryState windows=new GeometryState(storage);
        try(BlobSession blobs=new BlobSession(storage)) {
            while(ids.hasNext()) {
                requireCurrent(current);scope.phase(SQL_ATTRIBUTES);
                List<Long> chunk=new ArrayList<>(ATTRIBUTE_BATCH_ROWS);
                while(chunk.size()<ATTRIBUTE_BATCH_ROWS && ids.hasNext()) {
                    requireCurrent(current);chunk.add(ids.next());
                }
                visitor.beforeRead();requireCurrent(current);
                try(Cursor cursor=storage.attributes(chunk)) {
                    if(cursor==null)throw incomplete();
                    requireCurrent(current);
                    scope.phase(BLOB_READ);
                    try(GeometryRows geometry=windows.open(chunk,scope)) {
                    requireCurrent(current);
                    for(int row=0;row<chunk.size();row++) {
                        if(row>0)visitor.beforeRead();
                        long id=chunk.get(row);
                        requireCurrent(current);scope.phase(SQL_ATTRIBUTES);
                        if(!cursor.moveToNext() || cursor.getLong(cursor.getColumnIndexOrThrow(FIELD_ID))!=id)
                            throw incomplete();
                        Feature feature=new Feature(id,storage.fields());feature.fromCursorWithoutGeometry(cursor);
                        scope.phase(BLOB_READ);byte[] bytes=geometry.read(id,blobs,scope,current);requireCurrent(current);
                        if(bytes==null)throw incomplete();
                        scope.count(MapRuntimeDiagnostics.SqliteCount.BLOB_BYTES,bytes.length);scope.phase(GEOMETRY_DECODE);
                        feature.setGeometry(GeoGeometryFactory.fromBlob(bytes));
                        if(feature.getGeometry()==null)throw incomplete();
                        requireCurrent(current);visitor.accept(feature);
                    }
                    scope.phase(BLOB_READ);geometry.finish();
                    }
                    scope.phase(SQL_ATTRIBUTES);
                    if(cursor.moveToNext())throw incomplete();
                }
            }
        }
        requireCurrent(current);
    }
    /** Lazy and visit-owned: no statement on an empty/missing-row request, never shared across threads. */
    private static final class BlobSession implements BlobReader {
        final Storage storage;BlobReader reader;
        BlobSession(Storage storage){this.storage=storage;}
        public byte[] read(long id)throws Exception{if(reader==null)reader=storage.blobs();return reader.read(id);}
        public void close()throws Exception{if(reader!=null)reader.close();}
    }
    /** Optional bounded transport; an unsupported driver is disabled only for this visit. */
    static final class GeometryState {
        final Storage storage;boolean unsupported;
        GeometryState(Storage storage){this.storage=storage;}
        GeometryRows open(List<Long> ids,MapRuntimeDiagnostics.SqliteScope scope)throws Exception {
            if(unsupported)return new GeometryRows(null,this);
            // The factory returns null only after a safe unsupported-window cleanup. Any thrown
            // failure (including cleanup UOE) must fail the tile rather than become PFD success.
            Cursor cursor=storage.geometries(ids);
            if(cursor==null)unsupported=true;
            return new GeometryRows(cursor,this);
        }
    }
    static final class GeometryRows implements AutoCloseable {
        Cursor cursor;final GeometryState state;
        GeometryRows(Cursor cursor,GeometryState state){this.cursor=cursor;this.state=state;}
        byte[] read(long id,BlobReader fallback,MapRuntimeDiagnostics.SqliteScope scope,BooleanSupplier current)throws Exception {
            requireCurrent(current);Long expectedLength=null;
            if(cursor!=null)try {
                if(!cursor.moveToNext() || cursor.getLong(cursor.getColumnIndexOrThrow(FIELD_ID))!=id)throw incomplete();
                int typeColumn=cursor.getColumnIndexOrThrow(BLOB_TYPE),lengthColumn=cursor.getColumnIndexOrThrow(BLOB_LENGTH),smallColumn=cursor.getColumnIndexOrThrow(SMALL_BLOB);
                String type=cursor.getString(typeColumn);
                if("null".equals(type))throw incomplete();
                if("blob".equals(type)) {
                    if(cursor.getType(lengthColumn)!=Cursor.FIELD_TYPE_INTEGER)throw incomplete();
                    long length=cursor.getLong(lengthColumn);if(length<0)throw incomplete();expectedLength=length;
                    if(length<=SMALL_BLOB_MAX_BYTES) {
                        if(cursor.getType(smallColumn)!=Cursor.FIELD_TYPE_BLOB)throw incomplete();
                        byte[] bytes=cursor.getBlob(smallColumn);requireCurrent(current);
                        if(bytes==null || bytes.length!=length)throw incomplete();
                        scope.count(MapRuntimeDiagnostics.SqliteCount.INLINE_BLOBS,1);return bytes;
                    }
                    if(!cursor.isNull(smallColumn))throw incomplete();
                } else {
                    if(!("text".equals(type)||"integer".equals(type)||"real".equals(type)) || !cursor.isNull(lengthColumn)||!cursor.isNull(smallColumn))throw incomplete();
                }
            } catch(UnsupportedOperationException | AbstractMethodError unavailable) {
                state.unsupported=true;
                try {close();}catch(RuntimeException | Error cleanup){cleanup.addSuppressed(unavailable);throw cleanup;}
                expectedLength=null; // Unavailable metadata keeps the old complete-PFD semantics.
            }
            requireCurrent(current);byte[] bytes=fallback.read(id);requireCurrent(current);
            if(bytes!=null && expectedLength!=null && bytes.length!=expectedLength)throw incomplete();
            if(bytes!=null)scope.count(MapRuntimeDiagnostics.SqliteCount.PFD_BLOBS,1);return bytes;
        }
        void finish()throws IOException {if(cursor!=null && cursor.moveToNext())throw incomplete();}
        public void close(){Cursor old=cursor;cursor=null;if(old!=null)old.close();}
    }
    private static IOException incomplete() { return new IOException("Incomplete indexed original feature read"); }
    private static String quote(String value) { return "\""+value.replace("\"","\"\"")+"\""; }
    private static void requireCurrent(BooleanSupplier current) {
        if(Thread.currentThread().isInterrupted() || !current.getAsBoolean())
            throw new CancellationException("Obsolete SQLite original reader");
    }
    private SqliteVectorFeatureReader() {}
}

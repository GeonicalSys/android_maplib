package com.nextgis.maplib.util;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class WorkspaceSqliteSnapshotTest {
    private static final class Fixture implements AutoCloseable {
        final File root=new File(RuntimeEnvironment.getApplication().getCacheDir(),"snapshot-"+UUID.randomUUID());
        final File copy=new File(root,"copy.db");
        final SQLiteDatabase source;
        Fixture() {
            assertTrue(root.mkdirs());source=SQLiteDatabase.openOrCreateDatabase(new File(root,"source.db"),null);
            source.setVersion(17);source.execSQL("PRAGMA application_id=1196446465");
        }
        void snapshot() throws IOException {
            source.beginTransaction();
            try {WorkspaceSqliteSnapshot.copy(source,copy);} finally {source.endTransaction();}
        }
        @Override public void close() {source.close();FileUtil.deleteRecursive(root);}
    }
    private static long number(SQLiteDatabase db,String sql) {
        try(Cursor c=db.rawQuery(sql,null)) {assertTrue(c.moveToFirst());return c.getLong(0);}
    }
    private static byte[] digest(SQLiteDatabase db,String table,String column,String condition,int bytes) throws Exception {
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        // Independent oracle uses different read boundaries and an externally known byte count.
        for(int start=0;start<bytes;start+=98317) {
            try(Cursor c=db.rawQuery("SELECT substr(CAST("+column+" AS BLOB),?,?) FROM "+table+" WHERE "+condition,
                    new String[]{Integer.toString(start+1),Integer.toString(Math.min(98317,bytes-start))})) {
                assertTrue(c.moveToFirst());hash.update(c.getBlob(0));
            }
        }
        return hash.digest();
    }
    @Test public void completeSnapshotPreservesLargeValuesIdsTypesAndSchema() throws Exception {
        try(Fixture f=new Fixture()) {
            byte[] geometry=new byte[6*1024*1024+13];
            for(int i=0;i<geometry.length;i++)geometry[i]=(byte)(i*17+(i>>>11));
            byte[] text=new byte[3*1024*1024+7];Arrays.fill(text,(byte)'x');
            byte[] unicode="лес🌲\u0000".getBytes(StandardCharsets.UTF_8);
            System.arraycopy(unicode,0,text,1234,unicode.length);
            f.source.execSQL("CREATE TABLE features(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, geom BLOB, n INTEGER, r REAL, empty BLOB)");
            f.source.execSQL("INSERT INTO features VALUES(7,CAST(? AS TEXT),?,-9223372036854775808,1.25,X'')",new Object[]{text,geometry});
            f.source.execSQL("INSERT INTO features VALUES(99,NULL,NULL,9223372036854775807,NULL,NULL)");
            f.source.execSQL("INSERT INTO features(id) VALUES(200)");f.source.delete("features","id=200",null);
            f.source.execSQL("CREATE TABLE children(id INTEGER PRIMARY KEY,parent INTEGER REFERENCES features(id))");
            f.source.execSQL("INSERT INTO children VALUES(8,99)");
            f.source.execSQL("CREATE TABLE audit(id INTEGER)");
            f.source.execSQL("CREATE TRIGGER changed AFTER UPDATE ON features BEGIN INSERT INTO audit VALUES(new.id); END");
            f.source.execSQL("CREATE INDEX by_n ON features(n)");
            f.source.execSQL("CREATE VIEW counts AS SELECT count(*) AS n FROM features");
            f.snapshot();
            try(SQLiteDatabase restored=SQLiteDatabase.openOrCreateDatabase(f.copy,null)) {
                WorkspaceSqliteSnapshot.check(restored);
                assertEquals(17,restored.getVersion());assertEquals(1196446465,number(restored,"PRAGMA application_id"));
                assertEquals(2,number(restored,"SELECT n FROM counts"));
                assertEquals(Long.MIN_VALUE,number(restored,"SELECT n FROM features WHERE id=7"));
                assertEquals(Long.MAX_VALUE,number(restored,"SELECT n FROM features WHERE id=99"));
                assertEquals(1,number(restored,"SELECT empty IS NOT NULL AND length(empty)=0 FROM features WHERE id=7"));
                assertEquals(1,number(restored,"SELECT title IS NULL AND geom IS NULL FROM features WHERE id=99"));
                assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(geometry),digest(restored,"features","geom","id=7",geometry.length));
                assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(text),digest(restored,"features","title","id=7",text.length));
                assertEquals(1,number(restored,"SELECT typeof(title)='text' AND typeof(geom)='blob' AND typeof(r)='real' AND r=1.25 FROM features WHERE id=7"));
                restored.execSQL("UPDATE features SET n=2 WHERE id=7");assertEquals(7,number(restored,"SELECT id FROM audit"));
                restored.execSQL("INSERT INTO features(title) VALUES('after restore')");
                assertEquals(201,number(restored,"SELECT id FROM features WHERE title='after restore'"));
            }
            assertEquals(200,number(f.source,"SELECT seq FROM sqlite_sequence WHERE name='features'"));
            assertEquals(0,number(f.source,"SELECT count(*) FROM audit"));
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(geometry),digest(f.source,"features","geom","id=7",geometry.length));
        }
    }
    @Test public void implicitRowIdsAndCompositePrimaryKeysSurvive() throws Exception {
        try(Fixture f=new Fixture()) {
            f.source.execSQL("CREATE TABLE ordinary(name TEXT)");
            f.source.execSQL("INSERT INTO ordinary(rowid,name) VALUES(3,'first'),(700,'last')");
            f.source.execSQL("CREATE TABLE keyed(a TEXT,b INTEGER,value BLOB,PRIMARY KEY(a,b)) WITHOUT ROWID");
            f.source.execSQL("INSERT INTO keyed VALUES('лес',8,X'0001FF'),('лес',9,NULL)");
            f.snapshot();
            try(SQLiteDatabase restored=SQLiteDatabase.openOrCreateDatabase(f.copy,null)) {
                assertEquals(700,number(restored,"SELECT rowid FROM ordinary WHERE name='last'"));
                assertEquals(3,number(restored,"SELECT rowid FROM ordinary WHERE name='first'"));
                assertEquals(1,number(restored,"SELECT value=X'0001FF' FROM keyed WHERE a='лес' AND b=8"));
                assertEquals(1,number(restored,"SELECT value IS NULL FROM keyed WHERE a='лес' AND b=9"));
            }
        }
    }
    @Test public void inconsistentSourceAndExistingDestinationAreRejected() throws Exception {
        try(Fixture f=new Fixture()) {
            f.source.execSQL("CREATE TABLE parent(id INTEGER PRIMARY KEY)");
            f.source.execSQL("CREATE TABLE child(parent_id INTEGER REFERENCES parent(id))");
            f.source.execSQL("INSERT INTO child VALUES(42)");
            try {f.snapshot();fail("Broken source must not produce an admitted snapshot");}catch(IOException expected) { }
            assertFalse(f.copy.exists());assertEquals(1,number(f.source,"SELECT count(*) FROM child"));
            f.source.execSQL("INSERT INTO parent VALUES(42)");f.snapshot();
            long length=f.copy.length();
            try {f.snapshot();fail("Existing destination must stay intact");}catch(IOException expected) { }
            assertEquals(length,f.copy.length());
        }
    }
    @Test public void cancellationAndMissingTransactionLeaveSourceUntouched() throws Exception {
        try(Fixture f=new Fixture()) {
            f.source.execSQL("CREATE TABLE original(value TEXT)");f.source.execSQL("INSERT INTO original VALUES('saved')");
            try {WorkspaceSqliteSnapshot.copy(f.source,f.copy);fail("Snapshot needs a stable source transaction");}catch(IOException expected) { }
            assertFalse(f.copy.exists());
            Thread.currentThread().interrupt();
            try {f.snapshot();fail("Cancellation must prevent a complete snapshot");}catch(IOException expected) { }
            finally {Thread.interrupted();}
            assertEquals(1,number(f.source,"SELECT count(*) FROM original WHERE value='saved'"));
        }
    }
}

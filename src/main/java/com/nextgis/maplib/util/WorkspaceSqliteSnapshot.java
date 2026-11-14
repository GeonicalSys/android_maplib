package com.nextgis.maplib.util;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Comparator;

/** A consistent logical snapshot also works on API26 SQLite, without copying a live WAL. */
public final class WorkspaceSqliteSnapshot {
    private static final int CELL_CHUNK = 64 * 1024;
    private WorkspaceSqliteSnapshot() { }
    private static final class Schema {
        final String type, name, sql;
        Schema(Cursor c) { type=c.getString(0);name=c.getString(1);sql=c.getString(2); }
    }
    private static String quoted(String name) { return "\""+name.replace("\"","\"\"")+"\""; }

    public static void check(SQLiteDatabase db) throws IOException {
        try(Cursor c=db.rawQuery("PRAGMA integrity_check",null)) {
            if(!c.moveToFirst()||!"ok".equals(c.getString(0))||c.moveToNext())
                throw new IOException("SQLite integrity check failed");
        }
        try(Cursor c=db.rawQuery("PRAGMA foreign_key_check",null)) {
            if(c.moveToFirst())throw new IOException("SQLite foreign-key check failed");
        }
    }

    /** The caller holds this read/write transaction until the accompanying files are archived. */
    public static void copy(SQLiteDatabase source,File destination) throws IOException {
        if(!source.inTransaction())throw new IOException("Snapshot requires a source transaction");
        if(destination.exists())throw new IOException("Snapshot destination already exists");
        check(source);
        List<Schema> schemas=new ArrayList<>();Set<String> shadow=new HashSet<>();
        try(Cursor c=source.rawQuery("SELECT type,name,sql FROM sqlite_master WHERE sql IS NOT NULL"
                +" AND substr(name,1,7)<>'sqlite_' ORDER BY CASE type WHEN 'table' THEN 0 ELSE 1 END,name",null)) {
            while(c.moveToNext()) {
                Schema row=new Schema(c);schemas.add(row);
                if(row.sql.trim().toUpperCase(Locale.ROOT).startsWith("CREATE VIRTUAL TABLE")) {
                    if(!row.sql.matches("(?is).*\\bUSING\\s+rtree(?:_i32)?\\b.*"))
                        throw new IOException("Unsupported virtual-table format");
                    for(String suffix:new String[]{"_node","_rowid","_parent"})shadow.add(row.name+suffix);
                }
            }
        }
        String bootstrap="__lisa_snapshot_"+java.util.UUID.randomUUID().toString().replace("-","");
        try(SQLiteDatabase initial=SQLiteDatabase.openDatabase(destination.getPath(),null,
                SQLiteDatabase.CREATE_IF_NECESSARY|SQLiteDatabase.NO_LOCALIZED_COLLATORS)) {
            // CAST(text AS BLOB) below preserves the stored bytes, including non-ASCII text.
            // Set the destination encoding before its first table is created.
            try (Cursor encoding = source.rawQuery("PRAGMA encoding", null)) {
                if (!encoding.moveToFirst()) throw new IOException("Missing SQLite encoding");
                String value = encoding.getString(0);
                if (!value.matches("UTF-(?:8|16le|16be)")) throw new IOException("Unsupported SQLite encoding");
                initial.execSQL("PRAGMA encoding='" + value + "'");
            }
            // Persist the encoding before Android opens its localized-collation connection.
            initial.execSQL("CREATE TABLE "+quoted(bootstrap)+"(value INTEGER)");
        } catch(RuntimeException error) {throw new IOException("Cannot initialize SQLite snapshot",error);}
        try(SQLiteDatabase target=SQLiteDatabase.openOrCreateDatabase(destination,null)) {
            try(Cursor application=source.rawQuery("PRAGMA application_id",null)) {
                if(!application.moveToFirst())throw new IOException("Missing SQLite application identity");
                target.execSQL("PRAGMA application_id="+application.getInt(0));
            }
            target.beginTransaction();
            try {
                target.execSQL("DROP TABLE "+quoted(bootstrap));
                // Android creates this table while configuring localized collations. Restore its
                // original schema/rows below together with the rest of this fresh destination.
                target.execSQL("DROP TABLE IF EXISTS android_metadata");
                for(Schema row:schemas) if("table".equals(row.type)&&!shadow.contains(row.name))
                    target.execSQL(row.sql);
                for(Schema row:schemas) if("table".equals(row.type)&&!shadow.contains(row.name))
                    copyRows(source,target,row.name,!row.sql.trim().toUpperCase(Locale.ROOT).startsWith("CREATE VIRTUAL TABLE"));
                if(tableExists(source,"sqlite_sequence")&&tableExists(target,"sqlite_sequence")) {
                    target.delete("sqlite_sequence",null,null);copyRows(source,target,"sqlite_sequence",true);
                }
                for(Schema row:schemas) if(!"table".equals(row.type))target.execSQL(row.sql);
                target.setVersion(source.getVersion());
                check(target);target.setTransactionSuccessful();
            } finally {target.endTransaction();}
        } catch(RuntimeException | OutOfMemoryError error) {throw new IOException("Cannot create complete SQLite snapshot",error);}
        try(RandomAccessFile file=new RandomAccessFile(destination,"rw")) {file.getFD().sync();}
        try(SQLiteDatabase verified=SQLiteDatabase.openDatabase(destination.getPath(),null,SQLiteDatabase.OPEN_READONLY)) {
            check(verified);
        }
    }

    private static boolean tableExists(SQLiteDatabase db,String table) {
        try(Cursor c=db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",new String[]{table})) {
            return c.moveToFirst();
        }
    }
    private static final class Column {
        final String name;
        final int primaryKey;
        Column(String name, int primaryKey) { this.name=name; this.primaryKey=primaryKey; }
    }

    private static List<Column> columns(SQLiteDatabase db, String table) throws IOException {
        // table_xinfo includes generated columns on newer SQLite; those must be recomputed.
        // API26 SQLite only has table_info, so fall back when the newer PRAGMA has no result.
        for (String pragma : new String[]{"table_xinfo", "table_info"}) {
            List<Column> result=new ArrayList<>();
            try (Cursor c=db.rawQuery("PRAGMA "+pragma+"("+quoted(table)+")",null)) {
                int name=c.getColumnIndex("name"), pk=c.getColumnIndex("pk"), hidden=c.getColumnIndex("hidden");
                if (name<0 || pk<0) continue;
                while(c.moveToNext()) if(hidden<0 || c.getInt(hidden)==0)
                    result.add(new Column(c.getString(name),c.getInt(pk)));
            }
            if(!result.isEmpty())return result;
        }
        throw new IOException("Missing SQLite table columns");
    }

    private static String rowId(SQLiteDatabase db, String table, List<Column> columns) {
        Set<String> allColumns=new HashSet<>();
        // Generated columns are omitted from INSERT, but still shadow SQLite's rowid aliases.
        try(Cursor shape=db.rawQuery("SELECT * FROM "+quoted(table)+" LIMIT 0",null)) {
            for(String column:shape.getColumnNames())allColumns.add(column.toLowerCase(Locale.ROOT));
        }
        for(String candidate:new String[]{"_rowid_","rowid","oid"}) {
            if(allColumns.contains(candidate))continue;
            try(Cursor ignored=db.rawQuery("SELECT "+candidate+" FROM "+quoted(table)+" LIMIT 0",null)) {
                ignored.getColumnCount();return candidate;
            } catch(android.database.sqlite.SQLiteException error) {
                if(error.getMessage()==null || !error.getMessage().toLowerCase(Locale.ROOT).contains("no such column"))throw error;
            }
        }
        return null;
    }

    private static void cancelled() throws IOException {
        if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("Snapshot cancelled");
    }

    /** Metadata fits CursorWindow even when a single geometry or text value does not. */
    private static void copyRows(SQLiteDatabase source,SQLiteDatabase target,String table,boolean preserveRowId) throws IOException {
        List<Column> columns=columns(source,table);
        String rowId=rowId(source,table,columns);
        List<Column> keys=new ArrayList<>();
        for(Column c:columns)if(c.primaryKey>0)keys.add(c);
        keys.sort(Comparator.comparingInt(c->c.primaryKey));
        StringBuilder order=new StringBuilder();
        for(Column c:keys.isEmpty()?columns:keys) {
            if(order.length()>0)order.append(',');order.append(quoted(c.name));
        }
        StringBuilder descriptor=new StringBuilder(rowId==null?"":""+rowId+",");
        for(Column c:columns) {
            if(descriptor.length()>0 && descriptor.charAt(descriptor.length()-1)!=',')descriptor.append(',');
            descriptor.append("typeof(").append(quoted(c.name)).append("),CASE WHEN typeof(")
                    .append(quoted(c.name)).append(") IN ('text','blob') THEN length(CAST(")
                    .append(quoted(c.name)).append(" AS BLOB)) ELSE 0 END");
        }
        String from=" FROM "+quoted(table);
        try(Cursor metadata=source.rawQuery("SELECT "+descriptor+from+" ORDER BY "+(rowId==null?order:rowId),null)) {
            long offset=0;
            while(metadata.moveToNext()) {
                cancelled();int base=rowId==null?0:1;
                String[] types=new String[columns.size()];int[] lengths=new int[columns.size()];
                long bytes=0,allocation=0;
                for(int i=0;i<columns.size();i++) {
                    types[i]=metadata.getString(base+2*i);long size=metadata.getLong(base+2*i+1);
                    if(size<0 || size>Integer.MAX_VALUE)throw new IOException("SQLite value is too large");
                    lengths[i]=(int)size;bytes=Math.addExact(bytes,size);
                    allocation=Math.addExact(allocation,Math.addExact(Math.multiplyExact(size,4L),64));
                }
                Runtime runtime=Runtime.getRuntime();
                long available=runtime.maxMemory()-runtime.totalMemory()+runtime.freeMemory()-8L*1024*1024;
                if(allocation>Math.min(runtime.maxMemory()/4,available))throw new IOException("Not enough memory for this SQLite row");
                String where=rowId==null?" ORDER BY "+order+" LIMIT 1 OFFSET "+offset:" WHERE "+rowId+"=?";
                String[] args=rowId==null?null:new String[]{Long.toString(metadata.getLong(0))};
                boolean identity=preserveRowId && rowId!=null;
                Object[] values=new Object[columns.size()+(identity?1:0)];
                StringBuilder names=new StringBuilder(identity?rowId+",":""), placeholders=new StringBuilder(identity?"?,":"");
                if(identity)values[0]=metadata.getLong(0);
                StringBuilder projection=new StringBuilder();
                for(int i=0;i<columns.size();i++) {
                    if(i>0) {names.append(',');placeholders.append(',');projection.append(',');}
                    names.append(quoted(columns.get(i).name));
                    boolean text="text".equals(types[i]);
                    placeholders.append(text?"CAST(? AS TEXT)":"?");
                    projection.append(text?"CAST("+quoted(columns.get(i).name)+" AS BLOB)":quoted(columns.get(i).name));
                }
                if(bytes<=CELL_CHUNK) {
                    try(Cursor row=source.rawQuery("SELECT "+projection+from+where,args)) {
                        if(!row.moveToFirst())throw new IOException("SQLite row disappeared from snapshot");
                        for(int i=0;i<columns.size();i++)values[i+(identity?1:0)]=cell(row,i,types[i]);
                    }
                } else for(int i=0;i<columns.size();i++) {
                    cancelled();String column=quoted(columns.get(i).name);
                    if("text".equals(types[i])||"blob".equals(types[i])) {
                        byte[] value=new byte[lengths[i]];
                        for(int position=0;position<value.length;) {
                            cancelled();int count=Math.min(CELL_CHUNK,value.length-position);
                            String[] parameters=new String[args==null?2:3];
                            parameters[0]=Integer.toString(position+1);parameters[1]=Integer.toString(count);
                            if(args!=null)parameters[2]=args[0];
                            try(Cursor chunk=source.rawQuery("SELECT substr(CAST("+column+" AS BLOB),?,?)"+from+where,parameters)) {
                                if(!chunk.moveToFirst()||chunk.isNull(0))throw new IOException("Missing SQLite value chunk");
                                byte[] part=chunk.getBlob(0);
                                if(part.length!=count)throw new IOException("Incomplete SQLite value chunk");
                                System.arraycopy(part,0,value,position,count);position+=count;
                            }
                        }
                        values[i+(identity?1:0)]=value;
                    } else try(Cursor row=source.rawQuery("SELECT "+column+from+where,args)) {
                        if(!row.moveToFirst())throw new IOException("SQLite row disappeared from snapshot");
                        values[i+(identity?1:0)]=cell(row,0,types[i]);
                    }
                }
                target.execSQL("INSERT INTO "+quoted(table)+" ("+names+") VALUES ("+placeholders+")",values);
                offset++;
            }
        }
    }

    private static Object cell(Cursor row,int column,String type) throws IOException {
        switch(type) {
            case "null":return null;
            case "integer":return row.getLong(column);
            case "real":return row.getDouble(column);
            case "blob":case "text":return row.getBlob(column);
            default:throw new IOException("Unsupported SQLite storage type");
        }
    }
}

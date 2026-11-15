package com.nextgis.maplib.map;

import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.util.Log;
import com.nextgis.maplib.util.NgwSyncIo;
import com.nextgis.maplib.util.NgwSyncResourceBudget;
import java.io.File;
import java.io.IOException;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;

/** Disposable disk-backed membership/backup/delete plan; never changes the owning map database. */
final class NgwFullSnapshotIndex implements AutoCloseable {
    static final int REMOTE = 1, DESTRUCTIVE = 2, DELETE = 4, MISSING_INVALID = 8, CLEANUP = 16;
    private final File file;
    private final SQLiteDatabase database;
    private final SQLiteStatement insert, update, member, flags;
    private final int[] counts = new int[5];
    private final ArrayList<Cursor> cursors = new ArrayList<>();
    private int writes;

    NgwFullSnapshotIndex(File directory) throws IOException {
        NgwSyncResourceBudget.requireSpace(directory, 64 * 1024);
        file = File.createTempFile(".ngw-sync-snapshot-", ".sqlite", directory);
        SQLiteDatabase opened = null;
        try {
            opened = SQLiteDatabase.openOrCreateDatabase(file, null);
            try (Cursor mode = opened.rawQuery("PRAGMA journal_mode=DELETE", null)) {
                if (!mode.moveToFirst() || !"delete".equalsIgnoreCase(mode.getString(0)))
                    throw new android.database.sqlite.SQLiteException("Cannot bound snapshot journal");
            }
            opened.execSQL("PRAGMA temp_store=FILE");
            opened.execSQL("PRAGMA cache_size=-512");
            opened.execSQL("CREATE TABLE ids (id INTEGER PRIMARY KEY, flags INTEGER NOT NULL)");
            insert = opened.compileStatement("INSERT OR IGNORE INTO ids(id,flags) VALUES (?,0)");
            update = opened.compileStatement("UPDATE ids SET flags=flags|? WHERE id=?");
            member = opened.compileStatement("SELECT count(*) FROM ids WHERE id=? AND (flags&?)!=0");
            flags = opened.compileStatement("SELECT flags FROM ids WHERE id=?");
            // One private transient transaction prevents an fsync for every identifier.
            opened.beginTransaction();
            database = opened;
        } catch (RuntimeException error) {
            if (opened != null) opened.close();
            SQLiteDatabase.deleteDatabase(file);
            throw error;
        }
    }

    void mark(long id, int flags) throws IOException {
        NgwSyncIo.checkInterrupted();
        if ((writes++ & 127) == 0) NgwSyncResourceBudget.requireSpace(file.getParentFile(), 64 * 1024);
        insert.bindLong(1, id); insert.executeInsert();
        this.flags.bindLong(1, id);
        long prior = this.flags.simpleQueryForLong();
        update.bindLong(1, flags); update.bindLong(2, id); update.executeUpdateDelete();
        for (int bit = 0; bit < counts.length; bit++) {
            if ((flags & (1 << bit)) != 0 && (prior & (1 << bit)) == 0)
                counts[bit] = Math.addExact(counts[bit], 1);
        }
    }

    boolean contains(long id, int mask) {
        member.bindLong(1, id); member.bindLong(2, mask);
        return member.simpleQueryForLong() != 0;
    }

    int count(int mask) {
        if (Integer.bitCount(mask) == 1 && mask > 0 && mask <= CLEANUP)
            return counts[Integer.numberOfTrailingZeros(mask)];
        return Math.toIntExact(DatabaseUtils.longForQuery(database,
                "SELECT count(*) FROM ids WHERE (flags&?)!=0", new String[]{Integer.toString(mask)}));
    }

    Collection<Long> ids(int mask) {
        return new AbstractCollection<Long>() {
            @Override public int size() { return count(mask); }
            @Override public boolean contains(Object value) {
                return value instanceof Long && NgwFullSnapshotIndex.this.contains((Long) value, mask);
            }
            @Override public Iterator<Long> iterator() {
                Cursor cursor = database.rawQuery("SELECT id FROM ids WHERE (flags&?)!=0 ORDER BY id",
                        new String[]{Integer.toString(mask)});
                cursors.add(cursor);
                return new Iterator<Long>() {
                    boolean available = cursor.moveToFirst();
                    private void finish() { cursor.close(); cursors.remove(cursor); }
                    @Override public boolean hasNext() {
                        if (!available && !cursor.isClosed()) finish();
                        return available;
                    }
                    @Override public Long next() {
                        if (!hasNext()) throw new NoSuchElementException();
                        long result = cursor.getLong(0);
                        available = cursor.moveToNext();
                        if (!available) finish();
                        return result;
                    }
                };
            }
        };
    }

    File file() { return file; }

    @Override public void close() {
        for (Cursor cursor : new ArrayList<>(cursors)) cursor.close();
        cursors.clear();
        insert.close(); update.close(); member.close(); flags.close();
        try { if (database.inTransaction()) database.endTransaction(); }
        finally {
            database.close();
            if (!SQLiteDatabase.deleteDatabase(file))
                Log.w("NGWFullSnapshot", "Disposable snapshot index cleanup is pending");
        }
    }
}

/*
 * Project:  NextGIS Mobile
 * Purpose:  Mobile GIS for Android.
 * Author:   Dmitry Baryshnikov (aka Bishop), bishop.dev@gmail.com
 * Author:   NikitaFeodonit, nfeodonit@yandex.com
 * Author:   Stanislav Petriakov, becomeglory@gmail.com
 * *****************************************************************************
 * Copyright (c) 2015-2016, 2019 NextGIS, info@nextgis.com
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.nextgis.maplib.util;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteFullException;
import android.database.sqlite.SQLiteReadOnlyDatabaseException;
import android.text.TextUtils;
import android.util.Log;
import com.nextgis.maplib.map.MapBase;
import com.nextgis.maplib.map.MapContentProviderHelper;

import static com.nextgis.maplib.util.Constants.*;


public class FeatureChanges
{
    public static void initialize(String tableName)
    {
        initialize(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName);
    }

    public static void initialize(SQLiteDatabase db, String tableName)
    {
        if (Constants.DEBUG_MODE)
            Log.d(TAG, "init the change log for the layer " + tableName);

        String sqlCreateTable = " CREATE TABLE IF NOT EXISTS " + tableName + " ( ";
        sqlCreateTable += FIELD_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, ";
        sqlCreateTable += FIELD_FEATURE_ID + " INTEGER, ";
        sqlCreateTable += FIELD_OPERATION + " INTEGER, ";
        sqlCreateTable += FIELD_ATTACH_ID + " INTEGER, ";
        sqlCreateTable += FIELD_ATTACH_OPERATION + " INTEGER";
        sqlCreateTable += " );";

        if (Constants.DEBUG_MODE)
            Log.d(TAG, "create the layer change table: " + sqlCreateTable);

        db.execSQL(sqlCreateTable);
    }


    public static Cursor query(
            String tableName,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder,
            String limit)

    {
        return query(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, projection, selection, selectionArgs, sortOrder, limit);
    }

    public static Cursor query(SQLiteDatabase db, String tableName,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder,
            String limit)
    {

        return db.query(tableName, projection, selection, selectionArgs, null, null, sortOrder, limit);
    }


    public static Cursor query(
            String tableName,
            String selection,
            String sortOrder,
            String limit)
    {
        return query(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, selection, sortOrder, limit);
    }

    public static Cursor query(SQLiteDatabase db, String tableName,
            String selection,
            String sortOrder,
            String limit)
    {
        return query(db, tableName, null, selection, null, sortOrder, limit);
    }


    public static long insert(
            String tableName,
            ContentValues values)
    {
        return insert(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, values);
    }

    public static long insert(SQLiteDatabase db, String tableName,
            ContentValues values)
    {
        return db.insertOrThrow(tableName, null, values);
    }


    public static long replace(
            String tableName,
            ContentValues values)
    {
        return replace(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, values);
    }

    public static long replace(SQLiteDatabase db, String tableName,
            ContentValues values)
    {
        long featureId = values.getAsLong(FIELD_FEATURE_ID);
        int featureOperation = values.getAsInteger(FIELD_OPERATION);
        long attachId = values.getAsLong(FIELD_ATTACH_ID);
        int attachOperation = values.getAsInteger(FIELD_ATTACH_OPERATION);

        String selection = FIELD_FEATURE_ID + " = " + featureId + " AND " +
                FIELD_OPERATION + " = " + featureOperation + " AND " +
                FIELD_ATTACH_ID + " = " + attachId + " AND " +
                FIELD_ATTACH_OPERATION + " = " + attachOperation;

        Cursor cursor = query(db, tableName, selection, null, "1");
        long res = 0;

        if (null != cursor) {
            res = cursor.getCount();
            cursor.close();
        }

        if (res > 0) {
            return res;
        }

        return insert(db, tableName, values);
    }


    public static int update(
            String tableName,
            ContentValues values,
            String selection,
            String[] selectionArgs)
    {
        return update(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, values, selection, selectionArgs);
    }

    public static int update(SQLiteDatabase db, String tableName,
            ContentValues values,
            String selection,
            String[] selectionArgs)
    {
        return db.update(tableName, values, selection, selectionArgs);
    }


    public static int delete(
            String tableName,
            String selection,
            String[] selectionArgs)
    {
        return delete(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, selection, selectionArgs);
    }

    public static int delete(SQLiteDatabase db, String tableName,
            String selection,
            String[] selectionArgs)
    {
        return db.delete(tableName, selection, selectionArgs);
    }


    public static int delete(
            String tableName,
            String selection)
    {
        return delete(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, selection);
    }

    public static int delete(SQLiteDatabase db, String tableName,
            String selection)
    {
        return delete(db, tableName, selection, null);
    }


    public static void delete(String tableName)
    {
        delete(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName);
    }

    public static void delete(SQLiteDatabase db, String tableName)
    {
        db.execSQL("DROP TABLE IF EXISTS " + tableName);
    }


    public static boolean isRecords(
            String tableName,
            String selection)
    {
        return isRecords(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, selection);
    }

    public static boolean isRecords(SQLiteDatabase db, String tableName,
            String selection)
    {
        if (db == null) throw new SQLiteException("Missing layer database");
        Cursor cursor = query(db, tableName, selection, null, "1");
        if (cursor == null) throw new SQLiteException("Cannot read layer changes");
        boolean ret = false;

        if (null != cursor) {
            if (cursor.getCount() > 0) {
                ret = true;
            }
            cursor.close();
        }

        return ret;
    }


    public static long getEntriesCount(String tableName)
    {
        return getEntriesCount(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName);
    }

    public static long getEntriesCount(SQLiteDatabase db, String tableName)
    {
        return DatabaseUtils.queryNumEntries(db, tableName);
    }


    protected static String getSelectionForSync()
    {
        return "( " +

                "0 == " + FIELD_OPERATION + " & " + CHANGE_OPERATION_ATTACH +
                " AND " +
                "0 == " + FIELD_OPERATION + " & " + CHANGE_OPERATION_TEMP +
                " AND " +
                "0 == " + FIELD_OPERATION + " & " + CHANGE_OPERATION_NOT_SYNC +

                " OR " +

                "0 != " + FIELD_OPERATION + " & " + CHANGE_OPERATION_ATTACH +
                " AND " +
                "0 == " + FIELD_ATTACH_OPERATION + " & " + CHANGE_OPERATION_TEMP +
                " AND " +
                "0 == " + FIELD_ATTACH_OPERATION + " & " + CHANGE_OPERATION_NOT_SYNC +

                " )";
    }


    public static long getChangeCount(String tableName)
    {
        return getChangeCount(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName);
    }

    public static long getChangeCount(SQLiteDatabase db, String tableName)
    {
        String selection = getSelectionForSync();

        String s = (!TextUtils.isEmpty(selection)) ? " where " + selection : "";
        return DatabaseUtils.longForQuery(db, "select count(*) from " + tableName + s, null);
    }


    public static Cursor getFirstChangeFromRecordId(
            String tableName,
            long recordId)
    {
        return getFirstChangeFromRecordId(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, recordId);
    }

    public static Cursor getFirstChangeFromRecordId(SQLiteDatabase db, String tableName,
            long recordId)
    {
        String sortOrder = FIELD_ID + " ASC";
        String selection = FIELD_ID + " >= " + recordId + " AND " + getSelectionForSync();
        return query(db, tableName, selection, sortOrder, "1");
    }


    public static long getLastChangeRecordId(String tableName)
    {
        return getLastChangeRecordId(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName);
    }

    public static long getLastChangeRecordId(SQLiteDatabase db, String tableName)
    {
        String sortOrder = FIELD_ID + " DESC";
        String selection = getSelectionForSync();
        Cursor cursor = query(db, tableName, selection, sortOrder, "1");
        long ret = NOT_FOUND;

        if (null == cursor) {
            return ret;
        }

        try {
            if (cursor.moveToFirst()) {
                ret = cursor.getLong(cursor.getColumnIndex(FIELD_ID));
            }
        } catch (Exception e) {
            //Log.d(TAG, e.getLocalizedMessage());
        } finally {
            cursor.close();
        }

        return ret;
    }


    public static Cursor getChanges(String tableName)
    {
        return getChanges(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName);
    }

    public static Cursor getChanges(SQLiteDatabase db, String tableName)
    {
        String sortOrder = FIELD_ID + " ASC";
        String selection = getSelectionForSync();
        return query(db, tableName, selection, sortOrder, null);
    }


    public static boolean isChanges(String tableName)
    {
        try { return isChanges(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName); }
        catch (RuntimeException error) {
            Log.w(TAG, "Cannot read pending changes; preserving the layer", error);
            return true;
        }
    }

    public static boolean isChanges(SQLiteDatabase db, String tableName)
    {
        String selection = getSelectionForSync();
        return isRecords(db, tableName, selection);
    }


    public static Cursor getChanges(
            String tableName,
            long featureId)
    {
        return getChanges(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, featureId);
    }

    public static Cursor getChanges(SQLiteDatabase db, String tableName,
            long featureId)
    {
        String sortOrder = FIELD_ID + " ASC";
        String selection = FIELD_FEATURE_ID + " = " + featureId + " AND " + getSelectionForSync();
        return query(db, tableName, selection, sortOrder, null);
    }


    public static boolean isChanges(
            String tableName,
            long featureId)
    {
        try { return isChanges(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, featureId); }
        catch (RuntimeException error) {
            Log.w(TAG, "Cannot read pending changes; preserving the layer", error);
            return true;
        }
    }

    public static boolean isChanges(SQLiteDatabase db, String tableName,
            long featureId)
    {
        String selection = FIELD_FEATURE_ID + " = " + featureId + " AND " + getSelectionForSync();
        return isRecords(db, tableName, selection);
    }


    public static Cursor getChanges(
            String tableName,
            long featureId,
            int operation)
    {
        return getChanges(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, featureId, operation);
    }

    public static Cursor getChanges(SQLiteDatabase db, String tableName,
            long featureId,
            int operation)
    {
        String sortOrder = FIELD_ID + " ASC";
        String selection = FIELD_FEATURE_ID + " = " + featureId + " AND " +
                "( 0 != ( " + FIELD_OPERATION + " & " + operation + " ) )" + " AND "
                + getSelectionForSync();

        return query(db, tableName, selection, sortOrder, null);
    }


    public static boolean isChanges(
            String tableName,
            long featureId,
            int operation)
    {
        try { return isChanges(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, featureId, operation); }
        catch (RuntimeException error) {
            Log.w(TAG, "Cannot read pending changes; preserving the layer", error);
            return true;
        }
    }

    public static boolean isChanges(SQLiteDatabase db, String tableName,
            long featureId,
            int operation)
    {
        String selection = FIELD_FEATURE_ID + " = " + featureId + " AND " +
                "( 0 != ( " + FIELD_OPERATION + " & " + operation + " ) )" + " AND "
                + getSelectionForSync();

        return isRecords(db, tableName, selection);
    }


    public static Cursor getAttachChanges(
            String tableName,
            long featureId)
    {
        return getAttachChanges(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, featureId);
    }

    public static Cursor getAttachChanges(SQLiteDatabase db, String tableName,
            long featureId)
    {
        String sortOrder = FIELD_ID + " ASC";
        String selection = FIELD_FEATURE_ID + " = " + featureId + " AND " +
                "( 0 != ( " + FIELD_OPERATION + " & " + CHANGE_OPERATION_ATTACH + " ) )" + " AND "
                + getSelectionForSync();

        return query(db, tableName, selection, sortOrder, null);
    }


    public static boolean isAttachChanges(
            String tableName,
            long featureId)
    {
        return isAttachChanges(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, featureId);
    }

    public static boolean isAttachChanges(SQLiteDatabase db, String tableName,
            long featureId)
    {
        String selection = FIELD_FEATURE_ID + " = " + featureId + " AND " +
                "( 0 != ( " + FIELD_OPERATION + " & " + CHANGE_OPERATION_ATTACH + " ) )" + " AND "
                + getSelectionForSync();

        return isRecords(db, tableName, selection);
    }


    public static Cursor getAttachChanges(
            String tableName,
            long featureId,
            long attachId)
    {
        return getAttachChanges(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, featureId, attachId);
    }

    public static Cursor getAttachChanges(SQLiteDatabase db, String tableName,
            long featureId,
            long attachId)
    {
        String sortOrder = FIELD_ID + " ASC";
        String selection = FIELD_FEATURE_ID + " = " + featureId + " AND " +
                "( 0 != ( " + FIELD_OPERATION + " & " + CHANGE_OPERATION_ATTACH +
                " ) ) AND " +
                FIELD_ATTACH_ID + " = " + attachId + " AND " +
                getSelectionForSync();

        return query(db, tableName, selection, sortOrder, null);
    }


    public static boolean isAttachChanges(
            String tableName,
            long featureId,
            long attachId)
    {
        return isAttachChanges(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, featureId, attachId);
    }

    public static boolean isAttachChanges(SQLiteDatabase db, String tableName,
            long featureId,
            long attachId)
    {
        String selection = FIELD_FEATURE_ID + " = " + featureId + " AND " +
                "( 0 != ( " + FIELD_OPERATION + " & " + CHANGE_OPERATION_ATTACH +
                " ) ) AND " +
                FIELD_ATTACH_ID + " = " + attachId + " AND " +
                getSelectionForSync();

        return isRecords(db, tableName, selection);
    }


    public static Cursor getAttachChanges(
            String tableName,
            long featureId,
            long attachId,
            int attachOperation)
    {
        return getAttachChanges(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, featureId, attachId, attachOperation);
    }

    public static Cursor getAttachChanges(SQLiteDatabase db, String tableName,
            long featureId,
            long attachId,
            int attachOperation)
    {
        String sortOrder = FIELD_ID + " ASC";
        String selection = FIELD_FEATURE_ID + " = " + featureId + " AND " +
                "( 0 != ( " + FIELD_OPERATION + " & " + CHANGE_OPERATION_ATTACH +
                " ) ) AND " +
                FIELD_ATTACH_ID + " = " + attachId + " AND " +
                "( 0 != ( " + FIELD_ATTACH_OPERATION + " & " + attachOperation + " ) )" + " AND "
                + getSelectionForSync();

        return query(db, tableName, selection, sortOrder, null);
    }


    public static boolean isAttachChanges(
            String tableName,
            long featureId,
            long attachId,
            int attachOperation)
    {
        return isAttachChanges(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, featureId, attachId, attachOperation);
    }

    public static boolean isAttachChanges(SQLiteDatabase db, String tableName,
            long featureId,
            long attachId,
            int attachOperation)
    {
        String selection = FIELD_FEATURE_ID + " = " + featureId + " AND " +
                "( 0 != ( " + FIELD_OPERATION + " & " + CHANGE_OPERATION_ATTACH +
                " ) ) AND " +
                FIELD_ATTACH_ID + " = " + attachId + " AND " +
                "( 0 != ( " + FIELD_ATTACH_OPERATION + " & " + attachOperation + " ) )" + " AND " +
                getSelectionForSync();

        return isRecords(db, tableName, selection);
    }


    public static boolean isAttachesForDelete(
            String tableName,
            long featureId)
    {
        return isAttachesForDelete(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, featureId);
    }

    public static boolean isAttachesForDelete(SQLiteDatabase db, String tableName,
            long featureId)
    {
        String selection = FIELD_FEATURE_ID + " = " + featureId + " AND " +
                "( 0 != ( " + FIELD_OPERATION + " & " + CHANGE_OPERATION_ATTACH +
                " ) ) AND " +
                "( 0 != ( " + FIELD_ATTACH_OPERATION + " & " + CHANGE_OPERATION_DELETE +
                " ) )" + " AND " +
                getSelectionForSync();

        return isRecords(db, tableName, selection);
    }


    public static long add(
            String tableName,
            long featureId,
            int operation)
    {
        return add(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, featureId, operation);
    }

    public static long add(SQLiteDatabase db, String tableName,
            long featureId,
            int operation)
    {
        ContentValues values = new ContentValues();
        values.put(FIELD_FEATURE_ID, featureId);
        values.put(FIELD_OPERATION, operation);
        values.put(FIELD_ATTACH_ID, NOT_FOUND);
        values.put(FIELD_ATTACH_OPERATION, 0);

        return insert(db, tableName, values);
    }


    public static long add(
            String tableName,
            long featureId,
            long attachId,
            int attachOperation)
    {
        return add(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, featureId, attachId, attachOperation);
    }

    public static long add(SQLiteDatabase db, String tableName,
            long featureId,
            long attachId,
            int attachOperation)
    {
        ContentValues values = new ContentValues();
        values.put(FIELD_FEATURE_ID, featureId);
        values.put(FIELD_OPERATION, CHANGE_OPERATION_ATTACH);
        values.put(FIELD_ATTACH_ID, attachId);
        values.put(FIELD_ATTACH_OPERATION, attachOperation);

        return insert(db, tableName, values);
    }


    public static int setOperation(
            String tableName,
            long recordId,
            int operation)
    {
        return setOperation(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, recordId, operation);
    }

    public static int setOperation(SQLiteDatabase db, String tableName,
            long recordId,
            int operation)
    {
        String selection = FIELD_ID + " = " + recordId;

        ContentValues values = new ContentValues();
        values.put(FIELD_OPERATION, operation);

        return update(db, tableName, values, selection, null);
    }


    public static int setOperation(
            String tableName,
            long recordId,
            long featureId,
            long attachId,
            int attachOperation)
    {
        return setOperation(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, recordId, featureId, attachId, attachOperation);
    }

    public static int setOperation(SQLiteDatabase db, String tableName,
            long recordId,
            long featureId,
            long attachId,
            int attachOperation)
    {
        String selection = FIELD_ID + " = " + recordId + " AND " +
                FIELD_FEATURE_ID + " = " + featureId + " AND " +
                FIELD_ATTACH_ID + " = " + attachId;

        ContentValues values = new ContentValues();
        values.put(FIELD_ATTACH_OPERATION, attachOperation);

        return update(db, tableName, values, selection, null);
    }


    public static int changeFeatureId(
            String tableName,
            long oldFeatureId,
            long newFeatureId)
    {
        return changeFeatureId(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, oldFeatureId, newFeatureId);
    }

    public static int changeFeatureId(SQLiteDatabase db, String tableName,
            long oldFeatureId,
            long newFeatureId)
    {
        String selection = FIELD_FEATURE_ID + " = " + oldFeatureId;

        ContentValues values = new ContentValues();
        values.put(FIELD_FEATURE_ID, newFeatureId);

        return update(db, tableName, values, selection, null);
    }


    public static int changeFeatureIdForAttaches(
            String tableName,
            long oldFeatureId,
            long newFeatureId)
    {
        return changeFeatureIdForAttaches(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, oldFeatureId, newFeatureId);
    }

    public static int changeFeatureIdForAttaches(SQLiteDatabase db, String tableName,
            long oldFeatureId,
            long newFeatureId)
    {
        String selection = FIELD_FEATURE_ID + " = " + oldFeatureId + " AND " +
                "( 0 != ( " + FIELD_OPERATION + " & " + CHANGE_OPERATION_ATTACH +
                " ) )";

        ContentValues values = new ContentValues();
        values.put(FIELD_FEATURE_ID, newFeatureId);

        return update(db, tableName, values, selection, null);
    }


    public static int removeAllChanges(String tableName)
    {
        return removeAllChanges(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName);
    }

    public static int removeAllChanges(SQLiteDatabase db, String tableName)
    {
        String selection = getSelectionForSync();
        return delete(db, tableName, selection);
    }


    public static int removeAllChangesToLast(
            String tableName,
            long lastRecordId)
    {
        return removeAllChangesToLast(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, lastRecordId);
    }

    public static int removeAllChangesToLast(SQLiteDatabase db, String tableName,
            long lastRecordId)
    {
        String selection = FIELD_ID + " <= " + lastRecordId + " AND " + getSelectionForSync();
        return delete(db, tableName, selection);
    }


    public static int removeChanges(
            String tableName,
            long featureId)
    {
        return removeChanges(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, featureId);
    }

    public static int removeChanges(SQLiteDatabase db, String tableName,
            long featureId)
    {
        String selection = FIELD_FEATURE_ID + " = " + featureId + " AND " + getSelectionForSync();
        return delete(db, tableName, selection);
    }


    public static int removeChangesToLast(
            String tableName,
            long featureId,
            long lastRecordId)
    {
        return removeChangesToLast(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, featureId, lastRecordId);
    }

    public static int removeChangesToLast(SQLiteDatabase db, String tableName,
            long featureId,
            long lastRecordId)
    {
        String selection = FIELD_ID + " <= " + lastRecordId + " AND " +
                FIELD_FEATURE_ID + " = " + featureId + " AND " +
                getSelectionForSync();

        return delete(db, tableName, selection);
    }


    public static int removeChanges(
            String tableName,
            long featureId,
            int operation)
    {
        return removeChanges(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, featureId, operation);
    }

    public static int removeChanges(SQLiteDatabase db, String tableName,
            long featureId,
            int operation)
    {
        String selection = FIELD_FEATURE_ID + " = " + featureId + " AND " +
                "( 0 != ( " + FIELD_OPERATION + " & " + operation + " ) )" + " AND " +
                getSelectionForSync();

        return delete(db, tableName, selection);
    }


    public static int removeChangesToLast(
            String tableName,
            long featureId,
            int operation,
            long lastRecordId)
    {
        return removeChangesToLast(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, featureId, operation, lastRecordId);
    }

    public static int removeChangesToLast(SQLiteDatabase db, String tableName,
            long featureId,
            int operation,
            long lastRecordId)
    {
        String selection = FIELD_ID + " <= " + lastRecordId + " AND " +
                FIELD_FEATURE_ID + " = " + featureId + " AND " +
                "( 0 != ( " + FIELD_OPERATION + " & " + operation + " ) )" + " AND " +
                getSelectionForSync();

        return delete(db, tableName, selection);
    }


    public static int removeAllAttachChanges(
            String tableName,
            long featureId)
    {
        return removeAllAttachChanges(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, featureId);
    }

    public static int removeAllAttachChanges(SQLiteDatabase db, String tableName,
            long featureId)
    {
        String selection = FIELD_FEATURE_ID + " = " + featureId + " AND " +
                "( 0 != ( " + FIELD_OPERATION + " & " + CHANGE_OPERATION_ATTACH + " ) )" + " AND "
                + getSelectionForSync();

        return delete(db, tableName, selection);
    }


    public static int removeAllAttachChangesToLast(
            String tableName,
            long featureId,
            long lastRecordId)
    {
        return removeAllAttachChangesToLast(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, featureId, lastRecordId);
    }

    public static int removeAllAttachChangesToLast(SQLiteDatabase db, String tableName,
            long featureId,
            long lastRecordId)
    {
        String selection = FIELD_ID + " <= " + lastRecordId + " AND " +
                FIELD_FEATURE_ID + " = " + featureId + " AND " +
                "( 0 != ( " + FIELD_OPERATION + " & " + CHANGE_OPERATION_ATTACH + " ) )" + " AND "
                + getSelectionForSync();

        return delete(db, tableName, selection);
    }


    public static int removeAttachChanges(
            String tableName,
            long featureId,
            long attachId)
    {
        return removeAttachChanges(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, featureId, attachId);
    }

    public static int removeAttachChanges(SQLiteDatabase db, String tableName,
            long featureId,
            long attachId)
    {
        String selection = FIELD_FEATURE_ID + " = " + featureId + " AND " +
                "( 0 != ( " + FIELD_OPERATION + " & " + CHANGE_OPERATION_ATTACH +
                " ) ) AND " +
                FIELD_ATTACH_ID + " = " + attachId + " AND " +
                getSelectionForSync();

        return delete(db, tableName, selection);
    }


    public static int removeAttachChangesToLast(
            String tableName,
            long featureId,
            long attachId,
            long lastRecordId)
    {
        return removeAttachChangesToLast(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, featureId, attachId, lastRecordId);
    }

    public static int removeAttachChangesToLast(SQLiteDatabase db, String tableName,
            long featureId,
            long attachId,
            long lastRecordId)
    {
        String selection = FIELD_ID + " <= " + lastRecordId + " AND " +
                FIELD_FEATURE_ID + " = " + featureId + " AND " +
                "( 0 != ( " + FIELD_OPERATION + " & " + CHANGE_OPERATION_ATTACH +
                " ) ) AND " +
                FIELD_ATTACH_ID + " = " + attachId + " AND " +
                getSelectionForSync();

        return delete(db, tableName, selection);
    }


    public static int removeAttachChanges(
            String tableName,
            long featureId,
            long attachId,
            int attachOperation)
    {
        return removeAttachChanges(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, featureId, attachId, attachOperation);
    }

    public static int removeAttachChanges(SQLiteDatabase db, String tableName,
            long featureId,
            long attachId,
            int attachOperation)
    {
        String selection = FIELD_FEATURE_ID + " = " + featureId + " AND " +
                "( 0 != ( " + FIELD_OPERATION + " & " + CHANGE_OPERATION_ATTACH +
                " ) ) AND " +
                FIELD_ATTACH_ID + " = " + attachId + " AND " +
                "( 0 != ( " + FIELD_ATTACH_OPERATION + " & " + attachOperation + " ) )" + " AND "
                + getSelectionForSync();

        return delete(db, tableName, selection);
    }


    public static int removeAttachChangesToLast(
            String tableName,
            long featureId,
            long attachId,
            int attachOperation,
            long lastRecordId)
    {
        return removeAttachChangesToLast(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, featureId, attachId, attachOperation, lastRecordId);
    }

    public static int removeAttachChangesToLast(SQLiteDatabase db, String tableName,
            long featureId,
            long attachId,
            int attachOperation,
            long lastRecordId)
    {
        String selection = FIELD_ID + " <= " + lastRecordId + " AND " +
                FIELD_FEATURE_ID + " = " + featureId + " AND " +
                "( 0 != ( " + FIELD_OPERATION + " & " + CHANGE_OPERATION_ATTACH +
                " ) ) AND " +
                FIELD_ATTACH_ID + " = " + attachId + " AND " +
                "( 0 != ( " + FIELD_ATTACH_OPERATION + " & " + attachOperation + " ) )" + " AND " +
                getSelectionForSync();

        return delete(db, tableName, selection);
    }


    public static int removeChangeRecord(
            String tableName,
            long recordId)
    {
        return removeChangeRecord(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, recordId);
    }

    public static int removeChangeRecord(SQLiteDatabase db, String tableName,
            long recordId)
    {
        String selection = FIELD_ID + " = " + recordId + " AND " + getSelectionForSync();
        return delete(db, tableName, selection);
    }


    public static boolean hasFeatureFlags(
            String tableName,
            long featureId)
    {
        return hasFeatureFlags(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, featureId);
    }

    public static boolean hasFeatureFlags(SQLiteDatabase db, String tableName,
            long featureId)
    {
        return hasFeatureTempFlag(db, tableName, featureId)
                || hasFeatureNotSyncFlag(db, tableName, featureId);
    }


    public static boolean hasAttachFlags(
            String tableName,
            long featureId,
            long attachId)
    {
        return hasAttachFlags(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, featureId, attachId);
    }

    public static boolean hasAttachFlags(SQLiteDatabase db, String tableName,
            long featureId,
            long attachId)
    {
        return hasAttachTempFlag(db, tableName, featureId, attachId)
                || hasAttachNotSyncFlag(db, tableName, featureId, attachId);
    }


    public static boolean hasFeatureTempFlag(
            String tableName,
            long featureId)
    {
        return hasFeatureTempFlag(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, featureId);
    }

    public static boolean hasFeatureTempFlag(SQLiteDatabase db, String tableName,
            long featureId)
    {
        String selection = FIELD_FEATURE_ID + " = " + featureId + " AND " +
                "( 0 != ( " + FIELD_OPERATION + " & " + CHANGE_OPERATION_TEMP + " ) )";

        Cursor changesCursor = query(db, tableName, selection, null, "1");

        boolean res = false;
        if (null != changesCursor) {
            res = changesCursor.getCount() > 0;
            changesCursor.close();
        }

        return res;
    }


    public static boolean haveFeaturesNotSyncFlag(String tableName)
    {
        return haveFeaturesNotSyncFlag(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName);
    }

    public static boolean haveFeaturesNotSyncFlag(SQLiteDatabase db, String tableName)
    {
        String selection =
                "( 0 != ( " + FIELD_OPERATION + " & " + CHANGE_OPERATION_NOT_SYNC + " ) )";

        Cursor changesCursor = query(db, tableName, selection, null, "1");

        boolean res = false;
        if (null != changesCursor) {
            res = changesCursor.getCount() > 0;
            changesCursor.close();
        }

        return res;
    }


    public static boolean hasFeatureNotSyncFlag(
            String tableName,
            long featureId)
    {
        return hasFeatureNotSyncFlag(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, featureId);
    }

    public static boolean hasFeatureNotSyncFlag(SQLiteDatabase db, String tableName,
            long featureId)
    {
        String selection = FIELD_FEATURE_ID + " = " + featureId + " AND " +
                "( 0 != ( " + FIELD_OPERATION + " & " + CHANGE_OPERATION_NOT_SYNC + " ) )";

        Cursor changesCursor = query(db, tableName, selection, null, "1");

        boolean res = false;
        if (null != changesCursor) {
            res = changesCursor.getCount() > 0;
            changesCursor.close();
        }

        return res;
    }


    public static boolean hasAttachTempFlag(
            String tableName,
            long featureId,
            long attachId)
    {
        return hasAttachTempFlag(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, featureId, attachId);
    }

    public static boolean hasAttachTempFlag(SQLiteDatabase db, String tableName,
            long featureId,
            long attachId)
    {
        String selection = FIELD_FEATURE_ID + " = " + featureId + " AND " +
                "( 0 != ( " + FIELD_OPERATION + " & " + CHANGE_OPERATION_ATTACH +
                " ) ) AND " +
                FIELD_ATTACH_ID + " = " + attachId + " AND " +
                "( 0 != ( " + FIELD_ATTACH_OPERATION + " & " + CHANGE_OPERATION_TEMP + " ) )";

        Cursor changesCursor = query(db, tableName, selection, null, "1");

        boolean res = false;
        if (null != changesCursor) {
            res = changesCursor.getCount() > 0;
            changesCursor.close();
        }

        return res;
    }


    public static boolean hasAttachNotSyncFlag(
            String tableName,
            long featureId,
            long attachId)
    {
        return hasAttachNotSyncFlag(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, featureId, attachId);
    }

    public static boolean hasAttachNotSyncFlag(SQLiteDatabase db, String tableName,
            long featureId,
            long attachId)
    {
        String selection = FIELD_FEATURE_ID + " = " + featureId + " AND " +
                "( 0 != ( " + FIELD_OPERATION + " & " + CHANGE_OPERATION_ATTACH +
                " ) ) AND " +
                FIELD_ATTACH_ID + " = " + attachId + " AND " +
                "( 0 != ( " + FIELD_ATTACH_OPERATION + " & " + CHANGE_OPERATION_NOT_SYNC + " ) )";

        Cursor changesCursor = query(db, tableName, selection, null, "1");

        boolean res = false;
        if (null != changesCursor) {
            res = changesCursor.getCount() > 0;
            changesCursor.close();
        }

        return res;
    }


    public static long setFeatureTempFlag(
            String tableName,
            long featureId)
    {
        return setFeatureTempFlag(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, featureId);
    }

    public static long setFeatureTempFlag(SQLiteDatabase db, String tableName,
            long featureId)
    {
        ContentValues values = new ContentValues();
        values.put(FIELD_FEATURE_ID, featureId);
        values.put(FIELD_OPERATION, CHANGE_OPERATION_TEMP);
        values.put(FIELD_ATTACH_ID, NOT_FOUND);
        values.put(FIELD_ATTACH_OPERATION, 0);

        return replace(db, tableName, values);
    }


    public static long setFeatureNotSyncFlag(
            String tableName,
            long featureId)
    {
        return setFeatureNotSyncFlag(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, featureId);
    }

    public static long setFeatureNotSyncFlag(SQLiteDatabase db, String tableName,
            long featureId)
    {
        ContentValues values = new ContentValues();
        values.put(FIELD_FEATURE_ID, featureId);
        values.put(FIELD_OPERATION, CHANGE_OPERATION_NOT_SYNC);
        values.put(FIELD_ATTACH_ID, NOT_FOUND);
        values.put(FIELD_ATTACH_OPERATION, 0);

        return replace(db, tableName, values);
    }


    public static long setAttachTempFlag(
            String tableName,
            long featureId,
            long attachId)
    {
        return setAttachTempFlag(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, featureId, attachId);
    }

    public static long setAttachTempFlag(SQLiteDatabase db, String tableName,
            long featureId,
            long attachId)
    {
        ContentValues values = new ContentValues();
        values.put(FIELD_FEATURE_ID, featureId);
        values.put(FIELD_OPERATION, CHANGE_OPERATION_ATTACH);
        values.put(FIELD_ATTACH_ID, attachId);
        values.put(FIELD_ATTACH_OPERATION, CHANGE_OPERATION_TEMP);

        return replace(db, tableName, values);
    }


    public static long setAttachNotSyncFlag(
            String tableName,
            long featureId,
            long attachId)
    {
        return setAttachNotSyncFlag(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, featureId, attachId);
    }

    public static long setAttachNotSyncFlag(SQLiteDatabase db, String tableName,
            long featureId,
            long attachId)
    {
        ContentValues values = new ContentValues();
        values.put(FIELD_FEATURE_ID, featureId);
        values.put(FIELD_OPERATION, CHANGE_OPERATION_ATTACH);
        values.put(FIELD_ATTACH_ID, attachId);
        values.put(FIELD_ATTACH_OPERATION, CHANGE_OPERATION_NOT_SYNC);

        return replace(db, tableName, values);
    }


    public static int deleteFeatureTempFlag(
            String tableName,
            long featureId)
    {
        return deleteFeatureTempFlag(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, featureId);
    }

    public static int deleteFeatureTempFlag(SQLiteDatabase db, String tableName,
            long featureId)
    {
        String selection = FIELD_FEATURE_ID + " = " + featureId + " AND " +
                "( 0 != ( " + FIELD_OPERATION + " & " + CHANGE_OPERATION_TEMP + " ) )";

        return delete(db, tableName, selection);
    }


    public static int deleteFeatureNotSyncFlag(
            String tableName,
            long featureId)
    {
        return deleteFeatureNotSyncFlag(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, featureId);
    }

    public static int deleteFeatureNotSyncFlag(SQLiteDatabase db, String tableName,
            long featureId)
    {
        String selection = FIELD_FEATURE_ID + " = " + featureId + " AND " +
                "( 0 != ( " + FIELD_OPERATION + " & " + CHANGE_OPERATION_NOT_SYNC + " ) )";

        return delete(db, tableName, selection);
    }


    public static int deleteAttachTempFlag(
            String tableName,
            long featureId,
            long attachId)
    {
        return deleteAttachTempFlag(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, featureId, attachId);
    }

    public static int deleteAttachTempFlag(SQLiteDatabase db, String tableName,
            long featureId,
            long attachId)
    {
        String selection = FIELD_FEATURE_ID + " = " + featureId + " AND " +
                "( 0 != ( " + FIELD_OPERATION + " & " + CHANGE_OPERATION_ATTACH +
                " ) ) AND " +
                FIELD_ATTACH_ID + " = " + attachId + " AND " +
                "( 0 != ( " + FIELD_ATTACH_OPERATION + " & " + CHANGE_OPERATION_TEMP + " ) )";

        return delete(db, tableName, selection);
    }


    public static int deleteAttachNotSyncFlag(
            String tableName,
            long featureId,
            long attachId)
    {
        return deleteAttachNotSyncFlag(((MapContentProviderHelper) MapBase.getInstance()).getDatabase(false), tableName, featureId, attachId);
    }

    public static int deleteAttachNotSyncFlag(SQLiteDatabase db, String tableName,
            long featureId,
            long attachId)
    {
        String selection = FIELD_FEATURE_ID + " = " + featureId + " AND " +
                "( 0 != ( " + FIELD_OPERATION + " & " + CHANGE_OPERATION_ATTACH +
                " ) ) AND " +
                FIELD_ATTACH_ID + " = " + attachId + " AND " +
                "( 0 != ( " + FIELD_ATTACH_OPERATION + " & " + CHANGE_OPERATION_NOT_SYNC + " ) )";

        return delete(db, tableName, selection);
    }
}

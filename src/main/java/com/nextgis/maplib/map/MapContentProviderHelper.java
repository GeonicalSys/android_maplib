/*
 * Project:  NextGIS Mobile
 * Purpose:  Mobile GIS for Android.
 * Author:   Dmitry Baryshnikov (aka Bishop), bishop.dev@gmail.com
 * Author:   NikitaFeodonit, nfeodonit@yandex.com
 * Author:   Stanislav Petriakov, becomeglory@gmail.com
 * *****************************************************************************
 * Copyright (c) 2012-2016, 2018 NextGIS, info@nextgis.com
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

package com.nextgis.maplib.map;

import com.hypertrack.hyperlog.HyperLog;

import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import com.nextgis.maplib.api.ILayer;
import com.nextgis.maplib.api.INGWLayer;
import com.nextgis.maplib.datasource.DatabaseHelper;
import com.nextgis.maplib.datasource.GeoPoint;
import com.nextgis.maplib.util.Constants;
import com.nextgis.maplib.util.GeoConstants;

import java.io.File;
import java.util.List;

import static com.nextgis.maplib.util.Constants.*;


public class MapContentProviderHelper
        extends MapBase
{
    protected DatabaseHelper mDatabaseHelper;

    protected static final String DBNAME           = "layers";
    protected static final int    DATABASE_VERSION = 6;


    public MapContentProviderHelper(
            Context context,
            File path,
            LayerFactory layerFactory)
    {
        super(context, path, layerFactory);

        /*
         * The database belongs to the map path passed to this instance. Reading the current
         * project preference here allowed an older/background map object to open another
         * project's layers.db after a process restart or project switch.
         */
        File dbFullName = resolveDatabaseFile(path, context.getDatabasePath(DBNAME));

        mDatabaseHelper = new DatabaseHelper(
                context,          // the application context
                dbFullName,       // the name of the database
                null,             // uses the default SQLite cursor
                DATABASE_VERSION  // the version number
        );

        // register events from layers modify in services or other applications
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(NOTIFY_DELETE);
        intentFilter.addAction(NOTIFY_DELETE_ALL);
        intentFilter.addAction(NOTIFY_INSERT);
        intentFilter.addAction(NOTIFY_UPDATE);
        intentFilter.addAction(NOTIFY_UPDATE_ALL);
        intentFilter.addAction(NOTIFY_UPDATE_FIELDS);
        intentFilter.addAction(NOTIFY_FEATURE_ID_CHANGE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(new VectorLayerNotifyReceiver(), intentFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(new VectorLayerNotifyReceiver(), intentFilter);
        }
    }

    static File resolveDatabaseFile(File mapFile, File fallbackDatabaseFile)
    {
        File mapDirectory = mapFile != null ? mapFile.getParentFile() : null;
        return mapDirectory != null
                ? new File(mapDirectory, DBNAME)
                : fallbackDatabaseFile;
    }

    @Override
    public void onUpgrade(SQLiteDatabase sqLiteDatabase, int oldVersion, int newVersion) {
        super.onUpgrade(sqLiteDatabase, oldVersion, newVersion);

        Cursor data;
        boolean tableExists = false;

        try {
            data = sqLiteDatabase.query(TrackLayer.TABLE_TRACKS, null, null, null, null, null, null);
            tableExists = true;
            data.close();
        } catch (Exception ignored) { }

        if (oldVersion <= 2 && tableExists) {
            sqLiteDatabase.execSQL("alter table " + TrackLayer.TABLE_TRACKS + " add column " + TrackLayer.FIELD_COLOR + " integer;");

            GeoPoint point = new GeoPoint();
            ContentValues cv = new ContentValues();
            data = sqLiteDatabase.query(TrackLayer.TABLE_TRACKPOINTS,
                    new String[]{TrackLayer.FIELD_TIMESTAMP, TrackLayer.FIELD_LON, TrackLayer.FIELD_LAT}, null, null, null, null, null);

            if (data != null) {
                if (data.moveToFirst()) {
                    do {
                        point.setCoordinates(data.getDouble(1), data.getDouble(2));
                        point.setCRS(GeoConstants.CRS_WGS84);
                        point.project(GeoConstants.CRS_WEB_MERCATOR);
                        cv.clear();
                        cv.put(TrackLayer.FIELD_LON, point.getX());
                        cv.put(TrackLayer.FIELD_LAT, point.getY());
                        sqLiteDatabase.update(TrackLayer.TABLE_TRACKPOINTS, cv, TrackLayer.FIELD_TIMESTAMP + " = ?", new String[]{data.getLong(0) + ""});
                    } while (data.moveToNext());
                }

                data.close();
            }
        }

        if (oldVersion <= 3 && tableExists) {
            sqLiteDatabase.execSQL("alter table " + TrackLayer.TABLE_TRACKPOINTS + " add column " + TrackLayer.FIELD_SENT + " integer not null default 1;");
            sqLiteDatabase.execSQL("alter table " + TrackLayer.TABLE_TRACKPOINTS + " add column " + TrackLayer.FIELD_ACCURACY + " real;");
            sqLiteDatabase.execSQL("alter table " + TrackLayer.TABLE_TRACKPOINTS + " add column " + TrackLayer.FIELD_SPEED + " real;");
        }

        if (oldVersion <= 4 && tableExists) {
            sqLiteDatabase.execSQL("alter table " + TrackLayer.TABLE_TRACKPOINTS + " add column " + TrackLayer.FIELD_BEARING + " real;");
        }
        if (oldVersion <= 5 && tableExists) {
            sqLiteDatabase.execSQL("ALTER TABLE " + TrackLayer.TABLE_TRACKPOINTS
                    + " ADD COLUMN " + TrackLayer.FIELD_SEGMENT + " INTEGER NOT NULL DEFAULT 0");
        }

    }

    public SQLiteDatabase getDatabase(boolean readOnly)
    {
        if (readOnly) {
            return mDatabaseHelper.getReadableDatabase();
        } else {
            return mDatabaseHelper.getWritableDatabase();
        }
    }


    /**
     * @param pathName
     *         The exact name of the folder which contains a layer. Must be without slashes.
     */
    public static Layer getVectorLayerByPath(
            LayerGroup layerGroup,
            String pathName)
    {
        for (int i = 0; i < layerGroup.getLayerCount(); i++) {
            ILayer layer = layerGroup.getLayer(i);
            if (layer instanceof LayerGroup) {
                LayerGroup inLayerGroup = (LayerGroup) layer;
                Layer out = getVectorLayerByPath(inLayerGroup, pathName);
                if (null != out) {
                    return out;
                }
            } else if (layer instanceof VectorLayer) {
                VectorLayer vectorLayer = (VectorLayer) layer;
                if (pathName.equals(vectorLayer.getPath().getName())) {
                    return vectorLayer;
                }
            } else if (layer instanceof TrackLayer) {
                TrackLayer trackLayer = (TrackLayer) layer;
                if (pathName.contains(TrackLayer.TABLE_TRACKS) ||
                    pathName.contains(TrackLayer.TABLE_TRACKPOINTS)) {
                    return trackLayer;
                }
            }
        }
        return null;
    }


    public static void getLayersByAccount(
            LayerGroup layerGroup,
            String account,
            List<INGWLayer> layerList)
    {
        for (int i = 0; i < layerGroup.getLayerCount(); i++) {
            ILayer layer = layerGroup.getLayer(i);

            if (layer instanceof INGWLayer) {
                INGWLayer ngwLayer = (INGWLayer) layer;
                if (ngwLayer.getAccountName().equals(account)) {
                    layerList.add(ngwLayer);
                }
            }

            if (layer instanceof LayerGroup) {
                getLayersByAccount((LayerGroup) layer, account, layerList);
            }
        }
    }


    public class VectorLayerNotifyReceiver
            extends BroadcastReceiver
    {

        @Override
        public void onReceive(
                Context context,
                Intent intent)
        {
            // extreme logging commented
            //Log.d(TAG, "Receive notify: " + intent.getAction());

            if(!intent.hasExtra(Constants.NOTIFY_LAYER_NAME))
                return;

            ILayer layer = getVectorLayerByPath(MapContentProviderHelper.this,
                    intent.getStringExtra(Constants.NOTIFY_LAYER_NAME));
            if(null == layer)
                return;

            try {
                switch (intent.getAction()) {

                    case Constants.NOTIFY_DELETE:
                        layer.notifyDelete(intent.getLongExtra(FIELD_ID, NOT_FOUND));
                        break;

                    case Constants.NOTIFY_DELETE_ALL:
                        layer.notifyDeleteAll();
                        break;

                    case Constants.NOTIFY_UPDATE:
                    case Constants.NOTIFY_UPDATE_FIELDS:
                        layer.notifyUpdate(
                                intent.getLongExtra(FIELD_ID, NOT_FOUND),
                                intent.getLongExtra(FIELD_OLD_ID, NOT_FOUND),
                                intent.getBooleanExtra(Constants.ATTRIBUTES_ONLY, true));
                        break;

                    case Constants.NOTIFY_UPDATE_ALL:
                        layer.notifyUpdateAll();
                        break;

                    case Constants.NOTIFY_INSERT:
                        layer.notifyInsert(intent.getLongExtra(FIELD_ID, NOT_FOUND));
                        break;
                }
            } catch (RuntimeException e) {
                HyperLog.w(Constants.TAG, "Vector layer notification failed action="
                        + intent.getAction() + " layer=" + layer.getName(), e);
            }
        }
    }
}

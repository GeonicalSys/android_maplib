/*
 * Project:  NextGIS Mobile
 * Purpose:  Mobile GIS for Android.
 * Author:   Dmitry Baryshnikov (aka Bishop), bishop.dev@gmail.com
 * Author:   NikitaFeodonit, nfeodonit@yandex.com
 * Author:   Stanislav Petriakov, becomeglory@gmail.com
 * *****************************************************************************
 * Copyright (c) 2012-2015. NextGIS, info@nextgis.com
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

import android.content.Context;

import com.nextgis.maplib.api.ILayer;
import com.nextgis.maplib.datasource.GeoEnvelope;
import com.nextgis.maplib.util.FileUtil;

import java.io.File;

import static com.nextgis.maplib.util.Constants.NOT_FOUND;


public class MapBase
        extends LayerGroup
{
    protected int mNewId;
    protected static MapBase mInstance = null;
    protected String mFileName;
    private boolean mDirty;

    public MapBase(
            final Context context,
            final File path,
            final LayerFactory layerFactory)
    {
        super(context, path.getParentFile(), layerFactory);
        mNewId = 0;
        mId = path.hashCode() & 0x7FFFFFFF; // hash of path
        mInstance = this;
        mFileName = path.getName();
    }


    /**
     * The identificator generator
     *
     * @return new id
     */
    public int getNewId()
    {
        return mNewId++;
    }


    @Override
    protected void onLayerAdded(ILayer layer)
    {
        //layer.setId(layer.getId());
        //layer.setId(getNewId());
        super.onLayerAdded(layer);
    }


    public static MapBase getInstance()
    {
        if (mInstance == null) {
            throw new IllegalArgumentException(
                    "Impossible to get the instance. This class must be initialized before");
        }
        return mInstance;
    }


    public ILayer getLastLayer()
    {
        if (mLayers.size() == 0) {
            return null;
        }
        return mLayers.get(mLayers.size() - 1);
    }


    @Override
    public boolean delete(boolean keepTrack)
    {
        ILayer trackLayer = null;
        for (ILayer layer : mLayers.values()) {
            if (!(layer instanceof TrackLayer)) {
                layer.setParent(null);
                layer.delete(true);
            } else {
                trackLayer = layer;
            }
        }


        mLayers.clear();
        if (keepTrack && mLayers != null)
            mLayers.put(trackLayer.getId(), trackLayer);

        return FileUtil.deleteRecursive(getFileName());
    }


    @Override
    protected File getFileName()
    {
        return new File(getPath(), mFileName);
    }


    public void moveTo(File newPath)
    {

        if (mPath.equals(newPath)) {
            return;
        }

        // A legacy map root also owns the shared catalog and project registry directory.
        // Move only this map's referenced layers/database, never the enclosing app storage.
        java.util.List<File> owned = new java.util.ArrayList<>();
        java.util.List<File> moved = new java.util.ArrayList<>();
        try {
            if (newPath == null || !save()) throw new java.io.IOException("Cannot save map before moving");
            com.nextgis.maplib.util.UnderlayFiles.directory(newPath);
            for (com.nextgis.maplib.api.ILayer layer : getLayers()) owned.add(layer.getPath());
            for (String name : new String[]{"layers.db", "layers.db-journal", "layers.db-wal", "layers.db-shm"}) {
                File file = new File(mPath, name); if (file.exists()) owned.add(file);
            }
            owned.add(getFileName()); // Publish the map entry point last.
            for (File file : owned) {
                com.nextgis.maplib.util.UnderlayFiles.requireChild(mPath, file);
                File destination = new File(newPath, file.getName());
                com.nextgis.maplib.util.UnderlayFiles.requireChild(newPath, destination);
                if (destination.exists()) throw new java.io.IOException("Map destination is occupied");
            }
            for (File file : owned) {
                if (!file.renameTo(new File(newPath, file.getName()))) throw new java.io.IOException("Cannot move map on this storage");
                moved.add(file);
            }
            mPath = newPath;
        } catch (java.io.IOException error) {
            java.util.Collections.reverse(moved);
            for (File file : moved) {
                if (!new File(newPath, file.getName()).renameTo(file))
                    android.util.Log.e(com.nextgis.maplib.util.Constants.TAG, "Map move rollback incomplete");
            }
            android.util.Log.e(com.nextgis.maplib.util.Constants.TAG, "Map move failed; shared storage preserved", error);
        }
        clearLayers();
        load();
    }

    public GeoEnvelope getFullBounds(){
        if(null != mDisplay){
            return mDisplay.getFullBounds();
        }
        return new GeoEnvelope();
    }

//    public GeoEnvelope getCurrentBounds()
//    {
//        if (mDisplay != null) {
//            return mDisplay.getBounds();
//        }
//        return null;
//    }

    @Override
    public void clearLayers() {
        super.clearLayers();
        mNewId = 0;
    }

    public void setDirty(boolean dirty) {
        mDirty = dirty;
    }

    public boolean isDirty() {
        return mDirty;
    }
}

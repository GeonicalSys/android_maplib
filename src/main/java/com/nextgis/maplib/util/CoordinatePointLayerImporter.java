/*
 * Project: NextGIS Mobile
 * Purpose: Import flattened KML/GPX coordinates into a local point layer.
 */
package com.nextgis.maplib.util;

import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

import com.nextgis.maplib.R;
import com.nextgis.maplib.api.IProgressor;
import com.nextgis.maplib.datasource.Feature;
import com.nextgis.maplib.datasource.Field;
import com.nextgis.maplib.datasource.GeoPoint;
import com.nextgis.maplib.map.VectorLayer;
import com.nextgis.maplib.map.VectorLayerRenderCache;

import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.ParserConfigurationException;

/** Writes {@link CoordinatePointParser} records to an editable local vector layer. */
public final class CoordinatePointLayerImporter {
    private static final int SQL_TRANSACTION_BATCH_SIZE = 250;
    private static final int PROGRESS_POINT_STEP = 50;

    public static final String FIELD_SEQUENCE = "sequence";
    public static final String FIELD_SOURCE_TYPE = "source_type";
    public static final String FIELD_NAME = "name";
    public static final String FIELD_TIME = "time";
    public static final String FIELD_ELEVATION = "elevation";

    private CoordinatePointLayerImporter() {
    }

    public static CoordinatePointParser.ParseResult importFromUri(
            VectorLayer layer,
            Uri uri,
            CoordinatePointParser.Format format,
            IProgressor progressor)
            throws IOException, NGException {
        if (progressor != null) {
            progressor.setIndeterminate(true);
            progressor.setMessage(layer.getContext().getString(R.string.message_opening));
        }

        InputStream inputStream = layer.getContext().getContentResolver().openInputStream(uri);
        if (inputStream == null) {
            throw new NGException(layer.getContext().getString(R.string.error_coordinate_file_open));
        }

        List<Field> fields = createFields(layer);
        layer.create(GeoConstants.GTPoint, fields);
        SQLiteDatabase database = DatabaseContext.getDbForLayer(layer);
        PointWriter writer = new PointWriter(layer, database, progressor);
        CoordinatePointParser.ParseResult result;

        layer.beginBulkImport();
        database.beginTransaction();
        try (InputStream source = inputStream) {
            try {
                result = CoordinatePointParser.parse(source, format, writer);
            } catch (ParserConfigurationException | SAXException exception) {
                if (progressor != null && progressor.isCanceled()) {
                    throw new NGException(layer.getContext().getString(R.string.error_coordinate_file_cancelled));
                }
                throw new NGException(layer.getContext().getString(R.string.error_coordinate_file_invalid));
            }

            if (result.getPointCount() == 0) {
                throw new NGException(layer.getContext().getString(R.string.error_coordinate_file_empty));
            }
            database.setTransactionSuccessful();
        } finally {
            if (database.inTransaction()) {
                database.endTransaction();
            }
            layer.endBulkImport();
        }

        layer.save();
        VectorLayerRenderCache.invalidateOnDataChange(layer);
        layer.notifyLayerChanged();
        return result;
    }

    private static List<Field> createFields(VectorLayer layer) {
        List<Field> fields = new ArrayList<>(5);
        fields.add(new Field(GeoConstants.FTLong, FIELD_SEQUENCE,
                layer.getContext().getString(R.string.coordinate_field_sequence)));
        fields.add(new Field(GeoConstants.FTString, FIELD_SOURCE_TYPE,
                layer.getContext().getString(R.string.coordinate_field_source_type)));
        fields.add(new Field(GeoConstants.FTString, FIELD_NAME,
                layer.getContext().getString(R.string.coordinate_field_name)));
        fields.add(new Field(GeoConstants.FTString, FIELD_TIME,
                layer.getContext().getString(R.string.coordinate_field_time)));
        fields.add(new Field(GeoConstants.FTReal, FIELD_ELEVATION,
                layer.getContext().getString(R.string.coordinate_field_elevation)));
        return fields;
    }

    private static final class PointWriter implements CoordinatePointParser.PointConsumer {
        private final VectorLayer mLayer;
        private final SQLiteDatabase mDatabase;
        private final IProgressor mProgressor;
        private long mPointCount;
        private int mTransactionPointCount;

        PointWriter(VectorLayer layer, SQLiteDatabase database, IProgressor progressor) {
            mLayer = layer;
            mDatabase = database;
            mProgressor = progressor;
        }

        @Override
        public void accept(CoordinatePointParser.PointRecord point) throws SAXException {
            if (mProgressor != null && mProgressor.isCanceled()) {
                throw new SAXException("Import cancelled");
            }

            GeoPoint geometry = new GeoPoint(point.getLongitude(), point.getLatitude());
            geometry.setCRS(GeoConstants.CRS_WGS84);
            geometry.project(GeoConstants.CRS_WEB_MERCATOR);

            Feature feature = new Feature(Constants.NOT_FOUND, mLayer.getFields());
            feature.setGeometry(geometry);
            feature.setFieldValue(FIELD_SEQUENCE, ++mPointCount);
            feature.setFieldValue(FIELD_SOURCE_TYPE, point.getSourceType());
            feature.setFieldValue(FIELD_NAME, point.getName());
            feature.setFieldValue(FIELD_TIME, point.getTime());
            feature.setFieldValue(FIELD_ELEVATION, point.getElevation());
            mLayer.createFeatureBatch(feature, mDatabase, true);

            mTransactionPointCount++;
            if (mTransactionPointCount >= SQL_TRANSACTION_BATCH_SIZE) {
                mDatabase.setTransactionSuccessful();
                mDatabase.endTransaction();
                mDatabase.beginTransaction();
                mTransactionPointCount = 0;
            }
            if (mProgressor != null && mPointCount % PROGRESS_POINT_STEP == 0) {
                mProgressor.setMessage(mLayer.getContext().getString(R.string.process_features)
                        + ": " + mPointCount);
            }
        }
    }
}

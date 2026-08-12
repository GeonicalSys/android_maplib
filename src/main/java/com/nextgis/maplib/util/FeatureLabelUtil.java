/*
 * Project:  NextGIS Mobile
 * Purpose:  Mobile GIS for Android.
 * *****************************************************************************
 * Copyright (c) 2026 NextGIS
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.nextgis.maplib.util;

import com.nextgis.maplib.datasource.Feature;
import com.nextgis.maplib.datasource.Field;

import java.util.List;

/**
 * Resolves the user-facing name of a feature independently from its map-renderer label.
 */
public final class FeatureLabelUtil
{
    private FeatureLabelUtil()
    {
    }


    public static String normalizeFieldName(
            String fieldName,
            List<Field> fields)
    {
        if (fieldName == null || fieldName.length() == 0 ||
                Constants.FIELD_ID.equals(fieldName)) {
            return Constants.FIELD_ID;
        }

        if (fields != null) {
            for (Field field : fields) {
                if (field != null && fieldName.equals(field.getName())) {
                    return fieldName;
                }
            }
        }

        return Constants.FIELD_ID;
    }


    public static String resolve(
            Feature feature,
            String fieldName)
    {
        if (feature == null) {
            return null;
        }

        if (Constants.FIELD_ID.equals(fieldName)) {
            return String.valueOf(feature.getId());
        }

        String value = feature.getFieldValueAsString(fieldName);
        return value == null || value.length() == 0
                ? String.valueOf(feature.getId())
                : value;
    }
}

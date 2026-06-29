/*
 * Lightweight label templates: "${field_name}" placeholders resolved from feature attributes.
 */
package com.nextgis.maplib.display;

import android.text.TextUtils;

import com.nextgis.maplib.datasource.Feature;
import com.nextgis.maplib.util.Constants;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LabelTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)\\}");

    private LabelTemplate() {
    }

    public static boolean hasTemplate(String template) {
        return !TextUtils.isEmpty(template) && template.contains("${");
    }

    public static String resolve(String template, Feature feature) {
        if (TextUtils.isEmpty(template) || feature == null) {
            return template;
        }
        if (!hasTemplate(template)) {
            return template;
        }

        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String fieldName = matcher.group(1).trim();
            String value;
            if (Constants.FIELD_ID.equals(fieldName) || "_id".equals(fieldName)) {
                value = String.valueOf(feature.getId());
            } else {
                value = feature.getFieldValueAsString(fieldName);
                if (value == null) {
                    value = "";
                }
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }
}

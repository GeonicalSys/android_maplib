/*
 * Project:  NextGIS Mobile
 * Purpose:  Mobile GIS for Android.
 * Author:   Dmitry Baryshnikov (aka Bishop), bishop.dev@gmail.com
 * Author:   NikitaFeodonit, nfeodonit@yandex.com
 * Author:   Stanislav Petriakov, becomeglory@gmail.com
 * *****************************************************************************
 * Copyright (c) 2014-2019, 2021 NextGIS, info@nextgis.com
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

import com.nextgis.maplib.BuildConfig;

import java.util.concurrent.TimeUnit;


public interface Constants
{
    String  TAG                = "nextgismobile";
    String  PREFERENCES        = "nextgismobile";
    String  SUPPORT            = "support.bin";
    int     NOT_FOUND          = -1;
    boolean DEBUG_MODE         = BuildConfig.DEBUG;

    /**
     * Disk render cache for vector layers ({@link com.nextgis.maplib.map.VectorLayerRenderCache}).
     * Enabled on the sequential {@code loadLayersToMaplibreMap} path first; parallel prep is separate.
     */
    boolean VECTOR_RENDER_DISK_CACHE_ENABLED = false;

    /**
     * Parallel {@code prepareVectorLayerForMaplibre} in a thread pool during startup.
     * Keep {@code false} until disk cache passes on-device regression (see CUSTOMIZATIONS.md).
     */
    boolean MAP_STARTUP_PARALLEL_VECTOR_PREP = false;

    /**
     * Cold-start UX extras: timing logs in {@code MapDrawable.loadLayersToMaplibreMap}, default progress
     * caption in {@code MapFragment}, placeholder {@code HyperLog.setURL} in app/GISApplication.
     */
    boolean MAP_STARTUP_UX_EXTRAS_ENABLED = VECTOR_RENDER_DISK_CACHE_ENABLED
            || MAP_STARTUP_PARALLEL_VECTOR_PREP;

    /**
     * Foundation flag for Collector layers with {@code layer_origin.render_mode=local_vector_tiles}.
     * Keep disabled until the local MVT/PMTiles provider is implemented and tested; while disabled,
     * MapLibre falls back to the classic GeoJSON source path.
     */
    boolean LOCAL_VECTOR_TILES_ENABLED = true;

    /**
     * @deprecated Use {@link #VECTOR_RENDER_DISK_CACHE_ENABLED} and {@link #MAP_STARTUP_PARALLEL_VECTOR_PREP}.
     */
    @Deprecated
    boolean MAP_STARTUP_OPTIMIZATIONS_ENABLED = MAP_STARTUP_UX_EXTRAS_ENABLED;

    int NGW_v3 = 3;

    /**
     * HTTP parameters
     */
    String MAPLIB_USER_AGENT_PART = "NextGIS-Maplib/" + BuildConfig.VERSION_NAME;

    int    IO_BUFFER_SIZE     = 32 * 1024; // 32k
    int    MAX_CONTENT_LENGTH = 5 * 1024 * 1024; //5Mb

    long MIN_LOCAL_FEATURE_ID = 10000000;
    int  MAX_TILES_COUNT      = 6001;

    /**
     * NGW account type
     */
    String NGW_ACCOUNT_TYPE = "com.nextgis.account";
    String NGW_ACCOUNT_GUEST = "anonymous";

    /**
     * Map parameters
     */
    float MIN_SCROLL_STEP = 5.5f;
    int   MAP_LIMITS_NO   = 1; // no limits to scroll map
    int   MAP_LIMITS_X    = 2; // limit to scroll map by x axis
    int   MAP_LIMITS_Y    = 3; // limit to scroll map by y axis
    int   MAP_LIMITS_XY   = 4; // limit to scroll map by x & y axis

    /**
     * The additional size to off screen drawing from 1 and higher As more than more memory needed
     */

    float OFFSCREEN_EXTRASIZE_RATIO = 1.0f;
    int   DEFAULT_TILE_SIZE         = 256;

    /**
     * thread priorities and delays
     */
    int DEFAULT_DRAW_THREAD_PRIORITY       = android.os.Process.THREAD_PRIORITY_DEFAULT + 11;
    int DEFAULT_DOWNLOAD_THREAD_PRIORITY   = android.os.Process.THREAD_PRIORITY_BACKGROUND + 3;
    int DEFAULT_LOAD_LAYER_THREAD_PRIORITY = Thread.MIN_PRIORITY;
    int DEFAULT_EXECUTION_DELAY            = 650;

    /**
     * tune line string and linear ring simplifier
     */
    double SIMPLIFY_TOENV_AREA_MULTIPLY = 1.5;
    // area multiplier to skip if greater than quad tolerance
    double SIMPLIFY_SKIP_AREA_MULTIPLY  = 5;
    int    SAMPLE_DISTANCE_PX           = 5;


    String CONFIG       = "config.json";
    String LAYER_PREFIX = "layer_";
    String MAP_EXT      = ".ngm";

    /**
     * notifications
     */
    String NOTIFY_DELETE            = "com.nextgis.maplib.notify_delete";
    String NOTIFY_DELETE_ALL        = "com.nextgis.maplib.notify_delete_all";
    String NOTIFY_INSERT            = "com.nextgis.maplib.notify_insert";
    String NOTIFY_UPDATE            = "com.nextgis.maplib.notify_update";
    String NOTIFY_UPDATE_ALL        = "com.nextgis.maplib.notify_update_all";
    String NOTIFY_UPDATE_FIELDS     = "com.nextgis.maplib.notify_update_fields";
    String NOTIFY_FEATURE_ID_CHANGE = "com.nextgis.maplib.notify_change_id";

    String NOTIFY_LAYER_NAME = "layer_name";

    String MESSAGE_INTENT_STYLING = "com.nextgis.malibui.MESSAGE.STYLING";
    String MESSAGE_INTENT_RELOAD = "com.nextgis.malibui.MESSAGE.RELOAD";

    // future change prop raster
//    String MESSAGE_INTENT_STYLING_RASTER = "com.nextgis.malibui.MESSAGE.STYLING.RASTER";

    /**
     * JSON keys
     */
    String JSON_ID_KEY            = "id";
    String JSON_NAME_KEY          = "name";
    String JSON_VISIBILITY_KEY    = "visible";
    String JSON_LEVELS_KEY        = "levels";
    String JSON_LEVEL_KEY         = "level";
    String JSON_TYPE_KEY          = "type";
    String JSON_MAXLEVEL_KEY      = "max_level";
    String JSON_MINLEVEL_KEY      = "min_level";
    String JSON_LAYERS_KEY        = "layers";
    String JSON_LAYER_KEY         = "layer";
    String JSON_PATH_KEY          = "path";
    String JSON_BBOX_MINX_KEY     = "bbox_minx";
    String JSON_BBOX_MINY_KEY     = "bbox_miny";
    String JSON_BBOX_MAXX_KEY     = "bbox_maxx";
    String JSON_BBOX_MAXY_KEY     = "bbox_maxy";
    String JSON_RENDERERPROPS_KEY = "renderer_properties";
    String JSON_STYLE_RULE_KEY    = "style_rule";
    String JSON_WIDTH_KEY         = "width";
    String JSON_COLOR_KEY         = "color";
    String JSON_OUTCOLOR_KEY      = "out_color";
    String JSON_ALPHA_KEY         = "alpha";
    String JSON_OUTALPHA_KEY      = "out_alpha";
    String JSON_CHANGES_KEY       = "changes";
    String JSON_VALUE_KEY         = "value";
    String JSON_SIZE_KEY          = "size";
    String JSON_TEXT_SIZE_KEY     = "text_size";
    String JSON_TEXT_ALIGN_KEY    = "text_alignment";
    String JSON_TEXT_COLOR_KEY    = "text_color";
    String JSON_TEXT_MAX_WIDTH_KEY = "text_max_width";
    String JSON_LABEL_HALO_COLOR_KEY = "text_halo_color";
    String JSON_LABEL_HALO_WIDTH_KEY = "text_halo_width";
    String JSON_LABEL_HALO_BLUR_KEY = "text_halo_blur";
    String JSON_LABEL_SCALE_WITH_ZOOM_KEY = "text_scale_with_zoom";
    String JSON_LABEL_ALLOW_OVERLAP_KEY = "text_allow_overlap";
    String JSON_LABEL_OPTIONAL_KEY = "text_optional";
    String JSON_LABEL_SPACING_KEY = "symbol_spacing";
    String JSON_LABEL_TEMPLATE_KEY = "label_template";
    String JSON_LABEL_MIN_ZOOM_KEY = "label_min_zoom";
    String JSON_LABEL_MAX_ZOOM_KEY = "label_max_zoom";
    String JSON_LABEL_FONT_KEY = "text_font";
    String JSON_LABEL_JUSTIFY_KEY = "text_justify";
    String JSON_LABEL_TRANSFORM_KEY = "text_transform";
    String JSON_LABEL_LETTER_SPACING_KEY = "text_letter_spacing";
    String JSON_LABEL_LINE_HEIGHT_KEY = "text_line_height";
    String JSON_LABEL_PADDING_KEY = "text_padding";
    String JSON_LABEL_KEEP_UPRIGHT_KEY = "text_keep_upright";
    String JSON_LABEL_MAX_ANGLE_KEY = "text_max_angle";
    String JSON_LABEL_ZOOM_STOPS_KEY = "text_zoom_scale_stops";
    String JSON_LINE_LABEL_REPEAT_KEY = "line_label_repeat";
    String JSON_LINE_LABEL_HORIZONTAL_KEY = "line_label_horizontal";
    String JSON_LINE_CAP_KEY = "line_cap";
    String JSON_LINE_JOIN_KEY = "line_join";
    String JSON_LINE_DASH_PRESET_KEY = "dash_preset";
    String JSON_LINE_DASH_ARRAY_KEY = "dash_array";
    String JSON_LINE_OFFSET_KEY = "line_offset";
    String JSON_LINE_GAP_WIDTH_KEY = "line_gap_width";
    String JSON_LINE_OUTLINE_MULTIPLIER_KEY = "line_outline_multiplier";
    String JSON_FILL_PATTERN_KEY = "fill_pattern";
    String JSON_FILL_PATTERN_IMAGE_KEY = "fill_pattern_image";
    String JSON_FILL_TRANSLATE_X_KEY = "fill_translate_x";
    String JSON_FILL_TRANSLATE_Y_KEY = "fill_translate_y";
    String JSON_LAYER_OPACITY_KEY = "layer_opacity";
    String JSON_LABEL_TEXT_OPACITY_KEY = "text_opacity";
    String JSON_LINE_MITER_LIMIT_KEY = "line_miter_limit";
    String JSON_CIRCLE_BLUR_KEY = "circle_blur";
    String JSON_LINE_BLUR_KEY = "line_blur";
    String JSON_RULE_KEY_IGNORE_CASE_KEY = "key_ignore_case";
    String JSON_SCALE_SIZE_WITH_ZOOM_KEY = "scale_size_with_zoom";
    String JSON_SIZE_ZOOM_STOPS_KEY = "size_zoom_scale_stops";
    String JSON_MARKER_ICON_IMAGE_KEY = "marker_icon_image";
    String JSON_MARKER_ICON_SIZE_KEY = "marker_icon_size";
    String JSON_MARKER_ICON_ROTATE_KEY = "marker_icon_rotate";
    String JSON_MARKER_ICON_OFFSET_X_KEY = "marker_icon_offset_x";
    String JSON_MARKER_ICON_OFFSET_Y_KEY = "marker_icon_offset_y";
    String JSON_MARKER_ICON_ANCHOR_KEY = "marker_icon_anchor";
    String JSON_MARKER_ICON_ALLOW_OVERLAP_KEY = "marker_icon_allow_overlap";
    String JSON_MARKER_ICON_IGNORE_PLACEMENT_KEY = "marker_icon_ignore_placement";
    String JSON_DISPLAY_NAME      = "display_name";
    String JSON_RESOURCE_KEY      = "resource";
    String JSON_MESSAGE_KEY       = "message";
    String JSON_EXTENT_KEY        = "extent";
    String JSON_MIN_LAT_KEY       = "minLat";
    String JSON_MAX_LAT_KEY       = "maxLat";
    String JSON_MIN_LON_KEY       = "minLon";
    String JSON_MAX_LON_KEY       = "maxLon";
    String JSON_SUPPORTED_KEY     = "supported";
    String JSON_START_DATE_KEY    = "start_date";
    String JSON_END_DATE_KEY      = "end_date";
    String JSON_USER_ID_KEY       = "nextgis_guid";
    String JSON_SIGNATURE_KEY     = "sign";
//    String JSON_DEFAULT_FORM_ID   = "default_form_id";


    //  intent fields
    String LAYER_ID_KEY = "layer_id";
    String LAYER_NAME = "contrast";
    String FIELD_CONTRAST = "contrast";
    String FIELD_BRIGHTNESS_MIN = "brightnessmin";
    String FIELD_BRIGHTNESS_MAX = "brightnessmax";
    String FIELD_ALPHA = "alpha";


    /**
     * database fields
     */
    String FIELD_ID               = "_id";
    String FIELD_OLD_ID           = "old_id";
    String FIELD_GEOM             = "_geom";
    String FIELD_GEOM_            = "_geom_";
    String FIELD_FEATURE_ID       = "feature_id";
    String FIELD_OPERATION        = "operation";
    String FIELD_ATTACH_ID        = "attach_id";
    String FIELD_ATTACH_OPERATION = "attach_operation";
    String FIELD_ATTACH_DESCRIPTION        = "attach_description";
    String FIELD_ATTACH_DISPLAYNAME        = "attach_displayname";
    String FIELD_ATTACH_MIMETYPE        = "attach_mimetype";


    String ATTRIBUTES_ONLY = "attributes_only";

    /**
     * Layer type
     */
    String CE50 = "CE50";
    String CE90 = "CE90";
    String CE95 = "CE95";
    String CE98 = "CE98";

    /**
     * Layer type
     */
    int LAYERTYPE_REMOTE_TMS   = 1 << 0;
    int LAYERTYPE_NGW_RASTER   = 1 << 1;
    int LAYERTYPE_NGW_VECTOR   = 1 << 2;
    int LAYERTYPE_GROUP        = 1 << 3;
    int LAYERTYPE_LOCAL_VECTOR = 1 << 4;
    int LAYERTYPE_LOCAL_TMS    = 1 << 5;
    int LAYERTYPE_TRACKS       = 1 << 6;
    int LAYERTYPE_LOOKUPTABLE  = 1 << 7;
    int LAYERTYPE_NGW_WEBMAP   = 1 << 8;

    int LAYERTYPE_SYSMAX = 9; // should be the max + 1 of system layer type

    /**
     * File type
     */
    int FILETYPE_PARENT  = 1 << 0;
    int FILETYPE_FOLDER  = 1 << 1;
    int FILETYPE_ZIP     = 1 << 2;
    int FILETYPE_GEOJSON = 1 << 3;
    int FILETYPE_FB      = 1 << 4;
    int FILETYPE_UNKNOWN = 1 << 31;

    /**
     * time constants
     */
    long     ONE_SECOND           = 1000;
    long     ONE_MINUTE           = ONE_SECOND * 60;
    long     ONE_HOUR             = ONE_MINUTE * 60;
    long     ONE_DAY              = ONE_HOUR * 24;
    long     ONE_WEEK             = ONE_DAY * 7;
    long     DEFAULT_TILE_MAX_AGE = ONE_WEEK;
    long     ONE_YEAR             = ONE_DAY * 365;
    int      KEEP_ALIVE_TIME      = 35;
    int      TERMINATE_TIME       = 700;
    TimeUnit KEEP_ALIVE_TIME_UNIT = TimeUnit.MILLISECONDS;

    int  SYNC_NONE           = 1 << 0;
    int  SYNC_GEOMETRY       = 1 << 1;
    int  SYNC_ATTRIBUTES     = 1 << 2;
    int  SYNC_DATA           = SYNC_GEOMETRY | SYNC_ATTRIBUTES;
    int  SYNC_ATTACH         = 1 << 3;
    int  SYNC_ALL            = SYNC_DATA | SYNC_ATTACH;
    long DEFAULT_SYNC_PERIOD = 3600; //1 hour

    String URI_ATTACH  = "attach";
    String URI_CHANGES = "changes";

    // http://stackoverflow.com/a/24055457
    String URI_PARAMETER_LIMIT    = "limit";
    String URI_PARAMETER_TEMP     = "temp";
    String URI_PARAMETER_NOT_SYNC = "not_sync";
    String URI_PARAMETER_ATTACH_NOFILE = "attach_web_located";

    public static final String CHANGES_NAME_POSTFIX      = "_changes";
    public static final String ATTACHMENTS_NAME_POSTFIX      = "_attachments";

    int    CHANGE_OPERATION_TEMP     = 1;
    int    CHANGE_OPERATION_NEW      = 1 << 1; // 2
    int    CHANGE_OPERATION_CHANGED  = 1 << 2; // 4
    int    CHANGE_OPERATION_DELETE   = 1 << 3; // 8
    int    CHANGE_OPERATION_ATTACH   = 1 << 4; // 16
    int    CHANGE_OPERATION_NOT_SYNC = 1 << 5; // 32

    int DRAWING_SEPARATE_THREADS = 9;
    int DRAW_NOTIFY_STEP_PERCENT = 10;
    int DRAW_FINISH_ID = -22;

    String[] VECTOR_FORBIDDEN_FIELDS = {
            "ABORT",
            "ACTION",
            "ADD",
            "AFTER",
            "ALL",
            "ALTER",
            "ANALYZE",
            "AND",
            "AS",
            "ASC",
            "ATTACH",
            "AUTOINCREMENT",
            "BEFORE",
            "BEGIN",
            "BETWEEN",
            "BY",
            "CASCADE",
            "CASE",
            "CAST",
            "CHECK",
            "COLLATE",
            "COLUMN",
            "COMMIT",
            "CONFLICT",
            "CONSTRAINT",
            "CREATE",
            "CROSS",
            "CURRENT_DATE",
            "CURRENT_TIME",
            "CURRENT_TIMESTAMP",
            "DATABASE",
            "DEFAULT",
            "DEFERRABLE",
            "DEFERRED",
            "DELETE",
            "DESC",
            "DETACH",
            "DISTINCT",
            "DROP",
            "EACH",
            "ELSE",
            "END",
            "ESCAPE",
            "EXCEPT",
            "EXCLUSIVE",
            "EXISTS",
            "EXPLAIN",
            "FAIL",
            "FOR",
            "FOREIGN",
            "FROM",
            "FULL",
            "GLOB",
            "GROUP",
            "HAVING",
            "IF",
            "IGNORE",
            "IMMEDIATE",
            "IN",
            "INDEX",
            "INDEXED",
            "INITIALLY",
            "INNER",
            "INSERT",
            "INSTEAD",
            "INTERSECT",
            "INTO",
            "IS",
            "ISNULL",
            "JOIN",
            "KEY",
            "LEFT",
            "LIKE",
            "LIMIT",
            "MATCH",
            "NATURAL",
            "NO",
            "NOT",
            "NOTNULL",
            "NULL",
            "OF",
            "OFFSET",
            "ON",
            "OR",
            "ORDER",
            "OUTER",
            "PLAN",
            "PRAGMA",
            "PRIMARY",
            "QUERY",
            "RAISE",
            "RECURSIVE",
            "REFERENCES",
            "REGEXP",
            "REINDEX",
            "RELEASE",
            "RENAME",
            "REPLACE",
            "RESTRICT",
            "RIGHT",
            "ROLLBACK",
            "ROW",
            "SAVEPOINT",
            "SELECT",
            "SET",
            "TABLE",
            "TEMP",
            "TEMPORARY",
            "THEN",
            "TO",
            "TRANSACTION",
            "TRIGGER",
            "UNION",
            "UNIQUE",
            "UPDATE",
            "USING",
            "VACUUM",
            "VALUES",
            "VIEW",
            "VIRTUAL",
            "WHEN",
            "WHERE",
            "WITH",
            "WITHOUT"};
    char [] VECTOR_FORBIDDEN_CHARS = {':', '@', '#', '%', '^', '&', '*', '!', '$', '(', ')', '+', '-', '?', '=', '/', '\\', '"', '\'', '[', ']', ',', ' '};


    String MESSAGE_ALERT_INTENT = "com.nextgis.malibui.MESSAGE_ALERT";
    String MESSAGE_NOTIFY_INTENT = "com.nextgis.malibui.MESSAGE_NOTIFY";
    String MESSAGE_EXTRA = "message_extra";
    String MESSAGE_TITLE_EXTRA = "message_title_extra";
    String MESSAGE_EXTRA_IS_PARENTFILL = "message_extra_parentfill";
}

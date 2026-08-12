/*
 * SDF marker icons for MapLibre SymbolLayer (icon-color / icon-halo-color tinting).
 */
package com.nextgis.maplib.display;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import org.maplibre.android.maps.Style;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.Property;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.nextgis.maplib.display.SimpleMarkerStyle.MarkerStyleBox;
import static com.nextgis.maplib.display.SimpleMarkerStyle.MarkerStyleCircle;
import static com.nextgis.maplib.display.SimpleMarkerStyle.MarkerStyleCross;
import static com.nextgis.maplib.display.SimpleMarkerStyle.MarkerStyleCrossedBox;
import static com.nextgis.maplib.display.SimpleMarkerStyle.MarkerStyleDiamond;
import static com.nextgis.maplib.display.SimpleMarkerStyle.MarkerStylePoint;
import static com.nextgis.maplib.display.SimpleMarkerStyle.MarkerStyleTriangle;

public final class MarkerIconRegistry {

    public static final String ICON_PREFIX = "ng-marker-";
    private static final int ICON_PX = 64;
    private static final float CENTER = ICON_PX / 2f;
    private static final float SHAPE_R = 14f;
    private static final float SDF_SPREAD = 8f;
    private static final int SDF_SEARCH_RADIUS = 9;

    public static final String PROP_MARKER_TYPE = "filltype";
    public static final String PROP_MARKER_SIZE = "size";
    public static final String PROP_ICON_IMAGE = "iconimage";
    public static final String ASSET_ICON_DIR = "marker_icons";
    private static final Map<String, Bitmap> CUSTOM_ICON_BITMAPS = new LinkedHashMap<>();
    private static final Map<Integer, Bitmap> BUILTIN_MARKER_BITMAPS = new LinkedHashMap<>();
    private static final Set<String> LOADED_ASSET_ICONS = new HashSet<>();

    private MarkerIconRegistry() {
    }

    public static String iconName(int markerType) {
        return ICON_PREFIX + markerType;
    }

    public static void ensureRegistered(Style style) {
        if (style == null) {
            return;
        }
        registerAll(style);
        registerCustom(style);
    }

    public static void ensureRegistered(Style style, AssetManager assetManager) {
        loadIconsFromAssets(assetManager);
        ensureRegistered(style);
    }

    public static List<String> availableAssetIconNames(AssetManager assetManager) {
        List<String> names = new ArrayList<>();
        String[] files = listAssetIconFiles(assetManager);
        for (String file : files) {
            String iconName = iconNameFromAssetFile(file);
            if (iconName != null) {
                names.add(iconName);
            }
        }
        Collections.sort(names);
        return names;
    }

    public static void registerCustomIconBitmap(String imageName, Bitmap bitmap) {
        if (imageName == null || imageName.trim().isEmpty()) {
            return;
        }
        String key = imageName.trim();
        if (bitmap != null) {
            CUSTOM_ICON_BITMAPS.put(key, bitmap);
        } else {
            CUSTOM_ICON_BITMAPS.remove(key);
        }
    }

    public static void registerIconFromAssets(
            AssetManager assetManager,
            String imageName,
            String assetPath) throws IOException {
        try (InputStream stream = assetManager.open(assetPath)) {
            Bitmap bitmap = BitmapFactory.decodeStream(stream);
            if (bitmap == null) {
                throw new IOException("Failed to decode marker bitmap: " + assetPath);
            }
            registerCustomIconBitmap(imageName, bitmap);
        }
    }

    private static void loadIconsFromAssets(AssetManager assetManager) {
        String[] files = listAssetIconFiles(assetManager);
        for (String file : files) {
            String iconName = iconNameFromAssetFile(file);
            if (iconName == null || LOADED_ASSET_ICONS.contains(file)) {
                continue;
            }
            String assetPath = ASSET_ICON_DIR + "/" + file;
            try (InputStream stream = assetManager.open(assetPath)) {
                Bitmap bitmap = BitmapFactory.decodeStream(stream);
                if (bitmap != null) {
                    registerCustomIconBitmap(iconName, bitmap);
                    LOADED_ASSET_ICONS.add(file);
                }
            } catch (IOException ignored) {
                // bad bundled icon should not break map rendering
            }
        }
    }

    private static String[] listAssetIconFiles(AssetManager assetManager) {
        if (assetManager == null) {
            return new String[0];
        }
        try {
            String[] files = assetManager.list(ASSET_ICON_DIR);
            return files != null ? files : new String[0];
        } catch (IOException ignored) {
            return new String[0];
        }
    }

    private static String iconNameFromAssetFile(String file) {
        if (file == null) {
            return null;
        }
        String trimmed = file.trim();
        String lower = trimmed.toLowerCase(Locale.US);
        if (!lower.endsWith(".png")) {
            return null;
        }
        String name = trimmed.substring(0, trimmed.length() - 4).trim();
        return name.isEmpty() ? null : name;
    }

    private static void registerAll(Style style) {
        for (int type : new int[]{
                MarkerStylePoint, MarkerStyleCircle, MarkerStyleDiamond, MarkerStyleCross,
                MarkerStyleTriangle, MarkerStyleBox, 7, MarkerStyleCrossedBox}) {
            String name = iconName(type);
            try {
                style.addImage(name, builtinMarkerBitmap(type), true);
            } catch (IllegalArgumentException ignored) {
                // already registered on this style instance
            }
        }
    }

    private static Bitmap builtinMarkerBitmap(int markerType) {
        Bitmap bitmap = BUILTIN_MARKER_BITMAPS.get(markerType);
        if (bitmap == null) {
            bitmap = createMarkerBitmap(markerType);
            BUILTIN_MARKER_BITMAPS.put(markerType, bitmap);
        }
        return bitmap;
    }

    public static Expression iconImageExpression(int defaultType) {
        return iconImageExpression(defaultType, null);
    }

    public static Expression iconImageExpression(int defaultType, String customIconImage) {
        Expression generatedIcon = generatedIconImageExpression(defaultType);
        Expression defaultIcon = customIconImage != null && !customIconImage.trim().isEmpty()
                ? Expression.literal(customIconImage.trim())
                : generatedIcon;
        return Expression.coalesce(Expression.get(PROP_ICON_IMAGE), defaultIcon);
    }

    public static Expression generatedIconImageExpression(int defaultType) {
        Expression typeExpr = Expression.toNumber(Expression.coalesce(
                Expression.get(PROP_MARKER_TYPE),
                Expression.literal((double) defaultType)));
        return Expression.match(typeExpr,
                Expression.literal(iconName(MarkerStyleCircle)),
                Expression.stop(MarkerStylePoint, Expression.literal(iconName(MarkerStylePoint))),
                Expression.stop(MarkerStyleCircle, Expression.literal(iconName(MarkerStyleCircle))),
                Expression.stop(MarkerStyleDiamond, Expression.literal(iconName(MarkerStyleDiamond))),
                Expression.stop(MarkerStyleCross, Expression.literal(iconName(MarkerStyleCross))),
                Expression.stop(MarkerStyleTriangle, Expression.literal(iconName(MarkerStyleTriangle))),
                Expression.stop(MarkerStyleBox, Expression.literal(iconName(MarkerStyleBox))),
                Expression.stop(7, Expression.literal(iconName(MarkerStyleCircle))),
                Expression.stop(MarkerStyleCrossedBox, Expression.literal(iconName(MarkerStyleCrossedBox))));
    }

    public static Expression customIconImageExpression(String defaultIconImage) {
        if (defaultIconImage != null && !defaultIconImage.trim().isEmpty()) {
            return Expression.coalesce(
                    Expression.get(PROP_ICON_IMAGE),
                    Expression.literal(defaultIconImage.trim()));
        }
        return Expression.get(PROP_ICON_IMAGE);
    }

    public static Expression fillIconSizeExpression(float defaultRadiusPx) {
        return iconSizeForRadiusExpression(Expression.coalesce(
                Expression.get(PROP_MARKER_SIZE),
                Expression.literal((double) defaultRadiusPx)));
    }

    public static Expression iconSizeForRadiusExpression(Expression radiusPx) {
        return Expression.product(
                radiusPx,
                Expression.literal(1.0 / (double) SHAPE_R));
    }

    /** Outer icon-size so on-screen stroke thickness matches circle-stroke-width pixels. */
    public static Expression strokeIconSizeExpression(Expression fillIconSize, Expression strokePx) {
        return Expression.sum(
                fillIconSize,
                Expression.division(strokePx, Expression.literal((double) SHAPE_R)));
    }

    public static boolean useSymbolLayerForMarkerType(int markerType, boolean ruleStyling) {
        return useSymbolLayerForMarkerType(markerType, ruleStyling, null);
    }

    public static boolean useSymbolLayerForMarkerType(
            int markerType,
            boolean ruleStyling,
            String customIconImage) {
        if (customIconImage != null && !customIconImage.trim().isEmpty()) {
            return true;
        }
        if (ruleStyling) {
            return true;
        }
        return markerType != MarkerStyleCircle && markerType != MarkerStylePoint;
    }

    public static String iconAnchorValue(int anchor) {
        switch (anchor) {
            case SimpleMarkerStyle.ICON_ANCHOR_TOP:
                return Property.ICON_ANCHOR_TOP;
            case SimpleMarkerStyle.ICON_ANCHOR_RIGHT:
                return Property.ICON_ANCHOR_RIGHT;
            case SimpleMarkerStyle.ICON_ANCHOR_BOTTOM:
                return Property.ICON_ANCHOR_BOTTOM;
            case SimpleMarkerStyle.ICON_ANCHOR_LEFT:
                return Property.ICON_ANCHOR_LEFT;
            case SimpleMarkerStyle.ICON_ANCHOR_TOP_LEFT:
                return Property.ICON_ANCHOR_TOP_LEFT;
            case SimpleMarkerStyle.ICON_ANCHOR_TOP_RIGHT:
                return Property.ICON_ANCHOR_TOP_RIGHT;
            case SimpleMarkerStyle.ICON_ANCHOR_BOTTOM_RIGHT:
                return Property.ICON_ANCHOR_BOTTOM_RIGHT;
            case SimpleMarkerStyle.ICON_ANCHOR_BOTTOM_LEFT:
                return Property.ICON_ANCHOR_BOTTOM_LEFT;
            case SimpleMarkerStyle.ICON_ANCHOR_CENTER:
            default:
                return Property.ICON_ANCHOR_CENTER;
        }
    }

    private static void registerCustom(Style style) {
        for (Map.Entry<String, Bitmap> entry : CUSTOM_ICON_BITMAPS.entrySet()) {
            try {
                style.addImage(entry.getKey(), entry.getValue(), false);
            } catch (IllegalArgumentException ignored) {
                // already registered on this style instance
            }
        }
    }

    private static Bitmap createMarkerBitmap(int markerType) {
        Bitmap mask = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(mask);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(0xFFFFFFFF);
        fill.setStyle(Paint.Style.FILL);
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setColor(0xFFFFFFFF);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(4f);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);

        switch (markerType) {
            case MarkerStylePoint:
                canvas.drawCircle(CENTER, CENTER, SHAPE_R, fill);
                break;
            case MarkerStyleCircle:
                canvas.drawCircle(CENTER, CENTER, SHAPE_R, fill);
                break;
            case MarkerStyleDiamond: {
                Path path = new Path();
                path.moveTo(CENTER, CENTER - SHAPE_R);
                path.lineTo(CENTER + SHAPE_R, CENTER);
                path.lineTo(CENTER, CENTER + SHAPE_R);
                path.lineTo(CENTER - SHAPE_R, CENTER);
                path.close();
                canvas.drawPath(path, fill);
                break;
            }
            case MarkerStyleTriangle: {
                Path path = new Path();
                path.moveTo(CENTER, CENTER - SHAPE_R);
                path.lineTo(CENTER + SHAPE_R, CENTER + SHAPE_R * 0.75f);
                path.lineTo(CENTER - SHAPE_R, CENTER + SHAPE_R * 0.75f);
                path.close();
                canvas.drawPath(path, fill);
                break;
            }
            case MarkerStyleBox:
            case MarkerStyleCrossedBox: {
                RectF rect = new RectF(
                        CENTER - SHAPE_R, CENTER - SHAPE_R,
                        CENTER + SHAPE_R, CENTER + SHAPE_R);
                canvas.drawRect(rect, fill);
                if (markerType == MarkerStyleCrossedBox) {
                    canvas.drawLine(rect.left, rect.top, rect.right, rect.bottom, stroke);
                    canvas.drawLine(rect.right, rect.top, rect.left, rect.bottom, stroke);
                }
                break;
            }
            case MarkerStyleCross:
                canvas.drawLine(CENTER - SHAPE_R, CENTER, CENTER + SHAPE_R, CENTER, stroke);
                canvas.drawLine(CENTER, CENTER - SHAPE_R, CENTER, CENTER + SHAPE_R, stroke);
                break;
            default:
                canvas.drawCircle(CENTER, CENTER, SHAPE_R, fill);
                break;
        }
        Bitmap sdf = createSdfBitmap(mask);
        mask.recycle();
        return sdf;
    }

    private static Bitmap createSdfBitmap(Bitmap mask) {
        boolean[] inside = new boolean[ICON_PX * ICON_PX];
        for (int y = 0; y < ICON_PX; y++) {
            for (int x = 0; x < ICON_PX; x++) {
                inside[y * ICON_PX + x] = (mask.getPixel(x, y) >>> 24) > 127;
            }
        }

        Bitmap sdf = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888);
        float alphaScale = 127f / SDF_SPREAD;
        for (int y = 0; y < ICON_PX; y++) {
            for (int x = 0; x < ICON_PX; x++) {
                boolean currentInside = inside[y * ICON_PX + x];
                float bestSq = (SDF_SEARCH_RADIUS + 1) * (SDF_SEARCH_RADIUS + 1);
                for (int dy = -SDF_SEARCH_RADIUS; dy <= SDF_SEARCH_RADIUS; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= ICON_PX) {
                        continue;
                    }
                    for (int dx = -SDF_SEARCH_RADIUS; dx <= SDF_SEARCH_RADIUS; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= ICON_PX) {
                            continue;
                        }
                        if (inside[yy * ICON_PX + xx] == currentInside) {
                            continue;
                        }
                        float distSq = dx * dx + dy * dy;
                        if (distSq < bestSq) {
                            bestSq = distSq;
                        }
                    }
                }
                float signedDistance = (float) Math.sqrt(bestSq);
                if (!currentInside) {
                    signedDistance = -signedDistance;
                }
                int alpha = Math.max(0, Math.min(255,
                        Math.round(128f + signedDistance * alphaScale)));
                sdf.setPixel(x, y, (alpha << 24) | 0x00FFFFFF);
            }
        }
        return sdf;
    }
}

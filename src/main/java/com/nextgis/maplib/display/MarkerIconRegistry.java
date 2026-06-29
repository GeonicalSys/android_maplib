/*
 * SDF marker icons for MapLibre SymbolLayer (icon-color / icon-halo-color tinting).
 */
package com.nextgis.maplib.display;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import org.maplibre.android.maps.Style;
import org.maplibre.android.style.expressions.Expression;

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

    public static final String PROP_MARKER_TYPE = "filltype";
    public static final String PROP_MARKER_SIZE = "size";

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
    }

    private static void registerAll(Style style) {
        for (int type : new int[]{
                MarkerStylePoint, MarkerStyleCircle, MarkerStyleDiamond, MarkerStyleCross,
                MarkerStyleTriangle, MarkerStyleBox, 7, MarkerStyleCrossedBox}) {
            String name = iconName(type);
            try {
                style.addImage(name, createMarkerBitmap(type), true);
            } catch (IllegalArgumentException ignored) {
                // already registered on this style instance
            }
        }
    }

    public static Expression iconImageExpression(int defaultType) {
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

    public static Expression fillIconSizeExpression(float defaultRadiusPx) {
        return Expression.product(
                Expression.coalesce(
                        Expression.get(PROP_MARKER_SIZE),
                        Expression.literal((double) defaultRadiusPx)),
                Expression.literal(1.0 / (double) SHAPE_R));
    }

    /** Outer icon-size so on-screen stroke thickness matches circle-stroke-width pixels. */
    public static Expression strokeIconSizeExpression(Expression fillIconSize, Expression strokePx) {
        return Expression.sum(
                fillIconSize,
                Expression.division(strokePx, Expression.literal((double) SHAPE_R)));
    }

    public static boolean useSymbolLayerForMarkerType(int markerType, boolean ruleStyling) {
        if (ruleStyling) {
            return true;
        }
        return markerType != MarkerStyleCircle && markerType != MarkerStylePoint;
    }

    private static Bitmap createMarkerBitmap(int markerType) {
        Bitmap bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(0xFFFFFFFF);
        fill.setStyle(Paint.Style.FILL);
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setColor(0xFFFFFFFF);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(4f);
        stroke.setStrokeCap(Paint.Cap.ROUND);

        switch (markerType) {
            case MarkerStylePoint:
                canvas.drawCircle(CENTER, CENTER, 3f, fill);
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
        return bitmap;
    }
}

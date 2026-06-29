/*
 * Tiled fill patterns for MapLibre FillLayer (transparent gaps show fill-color).
 */
package com.nextgis.maplib.display;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.SparseArray;

import org.maplibre.android.maps.Style;
import org.maplibre.android.style.expressions.Expression;

import java.io.IOException;
import java.io.InputStream;

public final class PolygonPatternRegistry {

    public static final String ICON_PREFIX = "ng-fill-pattern-";
    public static final String PROP_FILL_PATTERN = "fillpattern";

    public static final int FILL_PATTERN_NONE = 0;
    public static final int FILL_PATTERN_HATCH = 1;
    public static final int FILL_PATTERN_CROSS = 2;
    public static final int FILL_PATTERN_DOTS = 3;
    public static final int FILL_PATTERN_BRICK = 4;
    public static final int FILL_PATTERN_FOREST = 5;
    public static final int FILL_PATTERN_MARSH = 6;

    /** All pattern ids including {@link #FILL_PATTERN_NONE}. */
    public static final int[] ALL_PATTERN_IDS = {
            FILL_PATTERN_NONE,
            FILL_PATTERN_HATCH,
            FILL_PATTERN_CROSS,
            FILL_PATTERN_DOTS,
            FILL_PATTERN_BRICK,
            FILL_PATTERN_FOREST,
            FILL_PATTERN_MARSH,
    };

    private static final int TILE_PX = 64;
    private static final SparseArray<Bitmap> CUSTOM_PATTERN_BITMAPS = new SparseArray<>();

    private PolygonPatternRegistry() {
    }

    public static String patternName(int pattern) {
        return ICON_PREFIX + pattern;
    }

    /** Override a built-in pattern with a custom bitmap (e.g. from app assets). */
    public static void registerCustomPatternBitmap(int pattern, Bitmap bitmap) {
        if (bitmap != null) {
            CUSTOM_PATTERN_BITMAPS.put(pattern, bitmap);
        } else {
            CUSTOM_PATTERN_BITMAPS.remove(pattern);
        }
    }

    /** Load pattern PNG from assets, e.g. {@code fill_patterns/forest.png}. */
    public static void registerPatternFromAssets(
            AssetManager assetManager,
            int pattern,
            String assetPath) throws IOException {
        try (InputStream stream = assetManager.open(assetPath)) {
            Bitmap bitmap = BitmapFactory.decodeStream(stream);
            if (bitmap == null) {
                throw new IOException("Failed to decode pattern bitmap: " + assetPath);
            }
            registerCustomPatternBitmap(pattern, bitmap);
        }
    }

    public static void ensureRegistered(Style style) {
        if (style == null) {
            return;
        }
        for (int pattern : ALL_PATTERN_IDS) {
            String name = patternName(pattern);
            try {
                style.addImage(name, createPatternBitmap(pattern), false);
            } catch (IllegalArgumentException ignored) {
                // already registered on this style instance
            }
        }
    }

    public static Expression patternImageExpression(int defaultPattern) {
        Expression patternExpr = Expression.toNumber(Expression.coalesce(
                Expression.get(PROP_FILL_PATTERN),
                Expression.literal((double) defaultPattern)));
        return Expression.switchCase(
                Expression.gt(patternExpr, Expression.literal(0.0)),
                patternImageMatchExpression(patternExpr),
                Expression.literal(patternName(FILL_PATTERN_NONE)));
    }

    /** For rule-style: features with fillpattern &lt;= 0 — no tiled pattern, plain fill-color. */
    public static Expression solidFillFilter(int defaultPattern) {
        Expression patternExpr = Expression.toNumber(Expression.coalesce(
                Expression.get(PROP_FILL_PATTERN),
                Expression.literal((double) defaultPattern)));
        return Expression.lte(patternExpr, Expression.literal(0.0));
    }

    /** For rule-style: features with fillpattern &gt; 0 — hatch / cross / dots / … */
    public static Expression patternedFillFilter(int defaultPattern) {
        Expression patternExpr = Expression.toNumber(Expression.coalesce(
                Expression.get(PROP_FILL_PATTERN),
                Expression.literal((double) defaultPattern)));
        return Expression.gt(patternExpr, Expression.literal(0.0));
    }

    public static Expression patternImageMatchExpression(Expression patternExpr) {
        return Expression.match(patternExpr,
                Expression.literal(patternName(FILL_PATTERN_HATCH)),
                Expression.stop(FILL_PATTERN_HATCH,
                        Expression.literal(patternName(FILL_PATTERN_HATCH))),
                Expression.stop(FILL_PATTERN_CROSS,
                        Expression.literal(patternName(FILL_PATTERN_CROSS))),
                Expression.stop(FILL_PATTERN_DOTS,
                        Expression.literal(patternName(FILL_PATTERN_DOTS))),
                Expression.stop(FILL_PATTERN_BRICK,
                        Expression.literal(patternName(FILL_PATTERN_BRICK))),
                Expression.stop(FILL_PATTERN_FOREST,
                        Expression.literal(patternName(FILL_PATTERN_FOREST))),
                Expression.stop(FILL_PATTERN_MARSH,
                        Expression.literal(patternName(FILL_PATTERN_MARSH))));
    }

    public static Expression patternImageMatchExpression() {
        Expression patternExpr = Expression.toNumber(Expression.coalesce(
                Expression.get(PROP_FILL_PATTERN),
                Expression.literal((double) FILL_PATTERN_NONE)));
        return patternImageMatchExpression(patternExpr);
    }

    /** True when a single FillLayer should carry fill-pattern (non-rule renderer). */
    public static boolean useFillPatternExpression(int defaultPattern, boolean ruleStyling) {
        return !ruleStyling && defaultPattern > FILL_PATTERN_NONE;
    }

    private static Bitmap createPatternBitmap(int pattern) {
        Bitmap custom = CUSTOM_PATTERN_BITMAPS.get(pattern);
        if (custom != null) {
            return custom.copy(custom.getConfig(), false);
        }
        Bitmap bitmap = Bitmap.createBitmap(TILE_PX, TILE_PX, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setColor(0x99000000);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2f);

        switch (pattern) {
            case FILL_PATTERN_HATCH:
                for (int i = -TILE_PX; i < TILE_PX * 2; i += 8) {
                    canvas.drawLine(i, TILE_PX, i + TILE_PX, 0, stroke);
                }
                break;
            case FILL_PATTERN_CROSS:
                stroke.setStrokeWidth(1.5f);
                for (int x = 0; x <= TILE_PX; x += 8) {
                    canvas.drawLine(x, 0, x, TILE_PX, stroke);
                }
                for (int y = 0; y <= TILE_PX; y += 8) {
                    canvas.drawLine(0, y, TILE_PX, y, stroke);
                }
                break;
            case FILL_PATTERN_DOTS:
                Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
                dot.setColor(0x99000000);
                dot.setStyle(Paint.Style.FILL);
                for (int x = 4; x < TILE_PX; x += 8) {
                    for (int y = 4; y < TILE_PX; y += 8) {
                        canvas.drawCircle(x, y, 1.5f, dot);
                    }
                }
                break;
            case FILL_PATTERN_BRICK:
                stroke.setStrokeWidth(1.5f);
                for (int row = 0; row < 4; row++) {
                    int y = 4 + row * 16;
                    int offset = (row % 2 == 0) ? 0 : 8;
                    for (int x = offset; x < TILE_PX; x += 16) {
                        canvas.drawRect(x, y, x + 14, y + 12, stroke);
                    }
                }
                break;
            case FILL_PATTERN_FOREST:
                stroke.setStrokeWidth(1.2f);
                for (int i = -TILE_PX; i < TILE_PX * 2; i += 6) {
                    canvas.drawLine(i, TILE_PX, i + TILE_PX, 0, stroke);
                    canvas.drawLine(i, 0, i + TILE_PX, TILE_PX, stroke);
                }
                break;
            case FILL_PATTERN_MARSH:
                stroke.setStrokeWidth(1.5f);
                for (int y = 6; y < TILE_PX; y += 10) {
                    for (int x = 0; x < TILE_PX; x += 12) {
                        canvas.drawLine(x, y, x + 8, y, stroke);
                    }
                }
                break;
            default:
                break;
        }
        return bitmap;
    }
}

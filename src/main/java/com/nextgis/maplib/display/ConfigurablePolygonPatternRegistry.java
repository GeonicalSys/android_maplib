package com.nextgis.maplib.display;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.LruCache;
import org.maplibre.android.maps.Style;
import java.util.*;

/** Seamless full-colour textures. Integer wave vectors make both tile edges exactly periodic. */
public final class ConfigurablePolygonPatternRegistry {
    public static final int TILE_SIZE = 512;
    public static final String OPACITY_PROPERTY = "polygon_pattern_opacity";
    private static final String PREFIX = "ng-hatch-v1-";
    private static final LruCache<String, Bitmap> BITMAPS = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
    };
    private static final Map<Style, Map<String, Set<String>>> OWNERS = new WeakHashMap<>();
    private ConfigurablePolygonPatternRegistry() { }
    public static boolean applies(SimplePolygonStyle style) {
        return style.getPatternOptions() != null && style.getPatternOptions().isEnabled() && style.getFillPatternImage() == null
                && style.getFillPattern() > PolygonPatternRegistry.FILL_PATTERN_NONE
                && style.getFillPattern() <= PolygonPatternRegistry.FILL_PATTERN_MARSH;
    }
    public static String imageName(SimplePolygonStyle style) {
        PolygonPatternOptions p = style.getPatternOptions();
        return PREFIX + style.getFillPattern() + "_" + p.getAngle() + "_" + p.getSecondAngle()
                + "_" + p.getSpacing() + "_" + p.getWidth() + "_" + p.getColor();
    }
    public static final class Wave {
        public final int x, y;
        public final double period;
        public Wave(float angle, float spacing) { this(angle, spacing, false); }
        private Wave(float angle, float spacing, boolean brick) {
            double radians = Math.toRadians(angle);
            int quantum = brick ? 2 : 1;
            x = quantum * (int) Math.round(-Math.sin(radians) * TILE_SIZE / spacing / quantum);
            y = quantum * (int) Math.round(Math.cos(radians) * TILE_SIZE / spacing / quantum);
            period = TILE_SIZE / Math.hypot(x, y);
        }
        public double phase(double px, double py) { return (x * px + y * py) / TILE_SIZE; }
        public double distance(double px, double py) {
            double phase = phase(px, py);
            return Math.abs(phase - Math.rint(phase)) * period;
        }
    }
    /** Public analytic sample also provides a seam oracle independent of bitmap edge copying. */
    public static int sample(SimplePolygonStyle style, double x, double y) {
        PolygonPatternOptions p = style.getPatternOptions();
        return sample(style.getFillPattern(), p, new Wave(p.getAngle(), p.getSpacing(), style.getFillPattern() == PolygonPatternRegistry.FILL_PATTERN_BRICK),
                new Wave(p.getSecondAngle(), p.getSpacing()), x, y);
    }
    private static int sample(int type, PolygonPatternOptions p, Wave a, Wave b, double x, double y) {
        double half = p.getWidth() / 2;
        double distance = a.distance(x, y);
        if (type == PolygonPatternRegistry.FILL_PATTERN_CROSS || type == PolygonPatternRegistry.FILL_PATTERN_FOREST)
            distance = Math.min(distance, b.distance(x, y));
        else if (type == PolygonPatternRegistry.FILL_PATTERN_DOTS) {
            // Orthogonal wave on the same lattice; the circle never has a tile seam.
            double v = (-a.y * x + a.x * y) / TILE_SIZE;
            distance = Math.hypot(distance, Math.abs(v - Math.rint(v)) * a.period);
        } else if (type == PolygonPatternRegistry.FILL_PATTERN_BRICK) {
            double u = a.phase(x, y), v = (-a.y * x + a.x * y) / TILE_SIZE;
            // Two periods per brick; integer parity is also periodic on the 512px tile.
            double shifted = v / 2 + (Math.floor(u) % 2) * .5;
            distance = Math.min(distance, Math.abs(shifted - Math.rint(shifted)) * a.period * 2);
        } else if (type == PolygonPatternRegistry.FILL_PATTERN_MARSH) {
            double v = (-a.y * x + a.x * y) / TILE_SIZE;
            if (Math.abs(v - Math.rint(v)) > .32) return 0;
        }
        int alpha = (int) Math.round(Math.max(0, Math.min(1, half + .5 - distance)) * 255);
        return alpha == 0 ? 0 : Color.argb(alpha, Color.red(p.getColor()), Color.green(p.getColor()), Color.blue(p.getColor()));
    }
    public static Bitmap bitmap(SimplePolygonStyle style) {
        String key = imageName(style); Bitmap bitmap = BITMAPS.get(key);
        if (bitmap != null) return bitmap;
        PolygonPatternOptions p = style.getPatternOptions();
        Wave a = new Wave(p.getAngle(), p.getSpacing(), style.getFillPattern() == PolygonPatternRegistry.FILL_PATTERN_BRICK), b = new Wave(p.getSecondAngle(), p.getSpacing());
        int[] pixels = new int[TILE_SIZE * TILE_SIZE];
        for (int y = 0; y < TILE_SIZE; y++) for (int x = 0; x < TILE_SIZE; x++)
            pixels[y * TILE_SIZE + x] = sample(style.getFillPattern(), p, a, b, x + .5, y + .5);
        // Keep the 512px wave lattice: changing it would change angle/spacing quantization.
        // Register a smaller motif only after exact ARGB equality across the entire tile.
        PatternTilePeriod.Period repeat = PatternTilePeriod.find(pixels, TILE_SIZE, TILE_SIZE);
        bitmap = Bitmap.createBitmap(pixels, 0, TILE_SIZE, repeat.width, repeat.height,
                Bitmap.Config.ARGB_8888);
        bitmap.setDensity(160); BITMAPS.put(key, bitmap); return bitmap;
    }
    public static void sync(Style style, String owner, Collection<SimplePolygonStyle> polygons) {
        Map<String, Set<String>> owners = OWNERS.computeIfAbsent(style, ignored -> new HashMap<>());
        Set<String> before = new HashSet<>(); for (Set<String> images : owners.values()) before.addAll(images);
        Set<String> used = new HashSet<>();
        for (SimplePolygonStyle polygon : polygons) if (applies(polygon)) {
            String image = imageName(polygon);
            if (used.add(image) && !before.contains(image)) style.addImage(image, bitmap(polygon), false);
        }
        if (used.isEmpty()) owners.remove(owner); else owners.put(owner, used);
        Set<String> after = new HashSet<>(); for (Set<String> images : owners.values()) after.addAll(images);
        before.removeAll(after); for (String image : before) style.removeImage(image);
        if (owners.isEmpty()) OWNERS.remove(style);
    }
    public static void remove(Style style, String owner) { sync(style, owner, Collections.emptyList()); }
}

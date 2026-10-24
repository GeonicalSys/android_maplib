package com.nextgis.maplib.display;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.LruCache;

import com.nextgis.maplib.map.MPLFeaturesUtils;

import org.maplibre.android.maps.Style;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Full-colour point sprites: the glow has no coverage inside the crisp core. */
public final class FireflyMarkerRegistry {
    public static final String IMAGE_PROPERTY = "firefly_image";
    public static final String SCALE_PROPERTY = "firefly_scale";
    private static final String PREFIX = "ng-firefly-";
    private static final int MAX_BITMAP_SIZE = 512;
    private static final LruCache<String, Bitmap> BITMAPS = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
    };
    // Native images are retained only while a logical layer in that Style uses them.
    private static final Map<Style, Map<String, Set<String>>> OWNERS = new WeakHashMap<>();

    private FireflyMarkerRegistry() { }

    public static boolean isFirefly(com.nextgis.maplib.display.Style style) {
        return style instanceof SimpleMarkerStyle
                && ((SimpleMarkerStyle) style).getType() == SimpleMarkerStyle.MarkerStyleFirefly;
    }

    /** Derived solely from persisted marker settings, never from individual feature values. */
    public static final class Sprite {
        public final String imageName;
        public final float coreRadius, glowWidth, softness, radius, iconScale;
        public final int coreColor, glowColor, coreAlpha, glowAlpha, pixels;

        private Sprite(SimpleMarkerStyle style) {
            coreRadius = metric(style.getSize());
            glowWidth = metric(style.getWidth());
            softness = finiteClamp(style.getCircleBlur(), 0, 3);
            // The Gaussian starts at the core edge; all of its support is outside it.
            float extent = softness == 0 ? glowWidth : 3 * sigma();
            radius = Math.max(.5f, coreRadius + extent + 1);
            pixels = Math.min(MAX_BITMAP_SIZE, Math.max(4, (int) Math.ceil(radius * 2 * 4)));
            iconScale = radius * 2 / pixels;
            coreColor = style.getColor() & 0xffffff;
            glowColor = style.getOutColor() & 0xffffff;
            coreAlpha = Math.max(0, Math.min(255, style.getAlpha()));
            glowAlpha = Math.max(0, Math.min(255, style.getOutAlpha()));
            imageName = PREFIX + Integer.toHexString(Float.floatToIntBits(coreRadius)) + "-"
                    + Integer.toHexString(Float.floatToIntBits(glowWidth)) + "-"
                    + Integer.toHexString(Float.floatToIntBits(softness)) + "-"
                    + Integer.toHexString(coreColor) + "-" + coreAlpha + "-"
                    + Integer.toHexString(glowColor) + "-" + glowAlpha;
        }

        private float sigma() { return glowWidth * (.25f + softness / 3f); }

        public Bitmap bitmap() {
            Bitmap cached = BITMAPS.get(imageName);
            if (cached != null) return cached;
            int[] colors = new int[pixels * pixels];
            float sampleScale = 1 / iconScale;
            for (int y = 0; y < pixels; y++) for (int x = 0; x < pixels; x++) {
                double distance = Math.hypot((x + .5) * iconScale - radius,
                        (y + .5) * iconScale - radius);
                float coreCoverage = finiteClamp((float) ((coreRadius - distance) * sampleScale + .5), 0, 1);
                float outside = Math.max(0, (float) distance - coreRadius);
                float glow = 0;
                if (glowWidth > 0) {
                    glow = softness == 0
                            ? finiteClamp((glowWidth - outside) * sampleScale + .5f, 0, 1)
                            : (float) Math.exp(-.5 * outside * outside / (sigma() * sigma()));
                    // Fade the truncated Gaussian over one sample to avoid a visible square edge.
                    glow *= finiteClamp((radius - 1 - (float) distance) * sampleScale + .5f, 0, 1);
                }
                float a = coreCoverage * coreAlpha;
                float b = (1 - coreCoverage) * glow * glowAlpha;
                float alpha = a + b;
                if (alpha > .5f) colors[y * pixels + x] = Color.argb(Math.round(alpha),
                        Math.round((Color.red(coreColor) * a + Color.red(glowColor) * b) / alpha),
                        Math.round((Color.green(coreColor) * a + Color.green(glowColor) * b) / alpha),
                        Math.round((Color.blue(coreColor) * a + Color.blue(glowColor) * b) / alpha));
            }
            Bitmap bitmap = Bitmap.createBitmap(colors, pixels, pixels, Bitmap.Config.ARGB_8888);
            bitmap.setDensity(160); // Native sprite pixelRatio=1; iconScale converts samples to map pixels.
            BITMAPS.put(imageName, bitmap);
            return bitmap;
        }
    }

    public static Sprite sprite(SimpleMarkerStyle style) { return new Sprite(style); }

    private static float metric(float value) {
        return value > 0 && Float.isFinite(value)
                ? finiteClamp(MPLFeaturesUtils.getMPLThinkness(value), 0, 512) : 0;
    }

    private static float finiteClamp(float value, float min, float max) {
        return Float.isFinite(value) ? Math.max(min, Math.min(max, value)) : min;
    }

    /** Main-thread native Style operation, after removing/rebuilding this layer's children. */
    public static void sync(Style style, String owner, Collection<SimpleMarkerStyle> markers) {
        Map<String, Set<String>> owners = OWNERS.computeIfAbsent(style, ignored -> new HashMap<>());
        Set<String> before = new HashSet<>();
        for (Set<String> images : owners.values()) before.addAll(images);
        Set<String> used = new HashSet<>();
        for (SimpleMarkerStyle marker : markers) {
            Sprite sprite = sprite(marker);
            if (used.add(sprite.imageName) && !before.contains(sprite.imageName))
                style.addImage(sprite.imageName, sprite.bitmap(), false);
        }
        if (used.isEmpty()) owners.remove(owner); else owners.put(owner, used);
        Set<String> after = new HashSet<>();
        for (Set<String> images : owners.values()) after.addAll(images);
        before.removeAll(after);
        for (String image : before) style.removeImage(image);
        if (owners.isEmpty()) OWNERS.remove(style);
    }

    public static void remove(Style style, String owner) {
        sync(style, owner, java.util.Collections.emptyList());
    }
}

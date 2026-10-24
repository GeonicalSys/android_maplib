package com.nextgis.maplib.display;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Color;

import com.nextgis.maplib.map.MplFeatureStyleProps;
import com.nextgis.maplib.util.GeoConstants;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.Point;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 36)
public class FireflyMarkerTest {
    private SimpleMarkerStyle marker() {
        SimpleMarkerStyle marker = new SimpleMarkerStyle(Color.WHITE, Color.rgb(255, 102, 133),
                20, SimpleMarkerStyle.MarkerStyleFirefly);
        marker.setWidth(24);
        return marker;
    }

    private int at(FireflyMarkerRegistry.Sprite sprite, float radius) {
        return sprite.bitmap().getPixel(Math.round((sprite.radius + radius) / sprite.iconScale - .5f),
                Math.round(sprite.radius / sprite.iconScale - .5f));
    }

    @Test public void softnessChangesOnlyTheOutsideEvenWithATransparentCore() {
        SimpleMarkerStyle marker = marker();
        int previousPixels = 0;
        for (int softness = 0; softness <= 3; softness++) {
            marker.setCircleBlur(softness);
            FireflyMarkerRegistry.Sprite sprite = FireflyMarkerRegistry.sprite(marker);
            assertEquals(Color.WHITE, at(sprite, sprite.coreRadius - 1));
            assertTrue(Color.alpha(at(sprite, sprite.coreRadius + 1)) > 100);
            assertTrue(Color.green(at(sprite, sprite.coreRadius + 1)) < 130);
            assertEquals(0, Color.alpha(sprite.bitmap().getPixel(0, 0)));
            if (softness > 0) assertTrue(sprite.pixels >= previousPixels);
            previousPixels = sprite.pixels;
            marker.setAlpha(0);
            sprite = FireflyMarkerRegistry.sprite(marker);
            assertEquals("Glow must never fill a transparent core", 0, Color.alpha(at(sprite, sprite.coreRadius - 1)));
            assertTrue(Color.alpha(at(sprite, sprite.coreRadius + 1)) > 100);
            marker.setAlpha(255);
        }
    }

    @Test public void alphaIsIndependentAndZeroGlowWidthReallyDisablesTheGlow() {
        SimpleMarkerStyle marker = marker();
        marker.setCircleBlur(2);
        marker.setAlpha(90);
        marker.setOutAlpha(60);
        FireflyMarkerRegistry.Sprite sprite = FireflyMarkerRegistry.sprite(marker);
        assertEquals(90, Color.alpha(at(sprite, 0)));
        assertTrue(Color.alpha(at(sprite, sprite.coreRadius + 1)) <= 60);
        marker.setOutAlpha(0);
        sprite = FireflyMarkerRegistry.sprite(marker);
        assertEquals(90, Color.alpha(at(sprite, 0)));
        assertEquals(0, Color.alpha(at(sprite, sprite.coreRadius + 1)));
        marker.setOutAlpha(255);
        marker.setWidth(0);
        sprite = FireflyMarkerRegistry.sprite(marker);
        assertEquals(0, Color.alpha(at(sprite, sprite.coreRadius + .75f)));
    }

    @Test public void persistedCategoryCloneAndPropertyRefreshPreserveTheNewTypeWithoutChangingCircleBlur() throws Exception {
        SimpleMarkerStyle marker = marker();
        marker.setCircleBlur(2.75f);
        marker.setAlpha(110);
        marker.setOutAlpha(180);
        marker.setScaleSizeWithZoom(true);
        marker.setSizeZoomScaleStops("12:0.5,16:1,20:2");
        SimpleMarkerStyle restored = new SimpleMarkerStyle();
        restored.fromJSON(marker.toJSON());
        SimpleMarkerStyle clone = restored.clone();
        assertEquals(SimpleMarkerStyle.MarkerStyleFirefly, clone.getType());
        assertEquals(FireflyMarkerRegistry.sprite(marker).imageName, FireflyMarkerRegistry.sprite(clone).imageName);
        assertEquals(marker.getSizeZoomScaleStops(), clone.getSizeZoomScaleStops());
        Feature feature = Feature.fromGeometry(Point.fromLngLat(0, 0));
        MplFeatureStyleProps.apply(clone, feature, GeoConstants.GTPoint);
        assertTrue(feature.hasProperty(FireflyMarkerRegistry.IMAGE_PROPERTY));
        clone.setType(SimpleMarkerStyle.MarkerStyleCircle);
        MplFeatureStyleProps.apply(clone, feature, GeoConstants.GTPoint);
        assertFalse(feature.hasProperty(FireflyMarkerRegistry.IMAGE_PROPERTY));
        assertEquals(2.75f, feature.getNumberProperty(MplFeatureStyleProps.CIRCLE_BLUR).floatValue(), 0);
        clone.setType(SimpleMarkerStyle.MarkerStyleFirefly);
        MplFeatureStyleProps.apply(clone, feature, GeoConstants.GTMultiPoint);
        MplFeatureStyleProps.clear(feature, GeoConstants.GTMultiPoint);
        assertFalse(feature.hasProperty(FireflyMarkerRegistry.SCALE_PROPERTY));
    }

    @Test public void oversizedOrNonFiniteSettingsCannotAllocateAnUnboundedBitmap() {
        SimpleMarkerStyle marker = marker();
        marker.setSize(Float.MAX_VALUE);
        marker.setWidth(Float.MAX_VALUE);
        marker.setCircleBlur(Float.POSITIVE_INFINITY);
        Bitmap bitmap = FireflyMarkerRegistry.sprite(marker).bitmap();
        assertTrue(bitmap.getWidth() <= 512);
        assertTrue(bitmap.getHeight() <= 512);
    }
}

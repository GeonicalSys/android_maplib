package com.nextgis.maplib.display;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Color;
import com.nextgis.maplib.map.MplFeatureStyleProps;
import com.nextgis.maplib.map.mpl.PolygonOutlineLayerFactory;
import com.nextgis.maplib.util.GeoConstants;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.Point;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=36)
public class PolygonStyleOptionsTest {
    private SimplePolygonStyle configured() {
        SimplePolygonStyle style = new SimplePolygonStyle(Color.YELLOW,Color.BLUE);
        style.setFillPattern(PolygonPatternRegistry.FILL_PATTERN_HATCH);
        style.setPatternOptions(new PolygonPatternOptions());
        style.setOutlineOptions(new PolygonOutlineOptions()); return style;
    }
    @Test public void legacyJsonRemainsOptInAndKeepsBundledPixelsAndAlpha() throws Exception {
        SimplePolygonStyle old = new SimplePolygonStyle();
        old.setFill(false);old.setFillPattern(1);
        JSONObject legacy = old.toJSON(); old.fromJSON(legacy);
        assertNull(old.getPatternOptions()); assertNull(old.getOutlineOptions());
        assertFalse(old.toJSON().has(PolygonPatternOptions.JSON_KEY));
        assertEquals(0,old.getPatternOpacity(),0);
        Bitmap before = PolygonPatternRegistry.previewPattern(1,null);
        SimplePolygonStyle copy = new SimplePolygonStyle(); copy.fromJSON(old.toJSON());
        assertTrue(before.sameAs(PolygonPatternRegistry.previewPattern(1,null)));
        assertFalse(ConfigurablePolygonPatternRegistry.applies(copy));
    }
    @Test public void jsonCloneAndExplicitOffRemainIndependentAcrossCategories() throws Exception {
        SimplePolygonStyle other = configured(); other.getPatternOptions().setAngle(27);
        other.getOutlineOptions().setCasing(true);
        SimplePolygonStyle child = (SimplePolygonStyle)FieldStyleRule.mergeRuleWithOther(new SimplePolygonStyle(),other);
        assertEquals(27,child.getPatternOptions().getAngle(),0);
        child.getPatternOptions().setAngle(80); assertEquals(27,other.getPatternOptions().getAngle(),0);
        SimplePolygonStyle explicit = configured(); explicit.getPatternOptions().setEnabled(false); explicit.getOutlineOptions().setEnabled(false);
        SimplePolygonStyle restored = new SimplePolygonStyle(); restored.fromJSON(explicit.toJSON());
        restored = (SimplePolygonStyle)FieldStyleRule.mergeRuleWithOther(restored,other);
        assertFalse(restored.getPatternOptions().isEnabled()); assertFalse(restored.getOutlineOptions().isEnabled());
        SimplePolygonStyle clone = other.clone(); clone.getOutlineOptions().setCasing(false);
        assertTrue(other.getOutlineOptions().isCasing());
    }
    @Test public void allPatternsArePeriodicAtBothEdgesForArbitraryAnglesAndSpacing() {
        SimplePolygonStyle style = configured();
        for (int type=1;type<=6;type++) for (float angle:new float[]{0,17,45,89,135,179})
            for(float step:new float[]{4,13.5f,64}) {
                style.setFillPattern(type); style.getPatternOptions().setAngle(angle); style.getPatternOptions().setSpacing(step);
                for(double[] point:new double[][]{{.5,.5},{-.25,51.5},{88.125,127.75},{511.25,400.875}}) {
                    int pixel = ConfigurablePolygonPatternRegistry.sample(style,point[0],point[1]);
                    for(int axis=0;axis<2;axis++) {
                        int wrapped=ConfigurablePolygonPatternRegistry.sample(style,point[0]+(axis==0?512:0),point[1]+(axis==1?512:0));
                        assertEquals("Periodic alpha type "+type,Color.alpha(pixel),Color.alpha(wrapped));
                        assertEquals(pixel & 0xffffff,wrapped & 0xffffff);
                    }
                }
            }
    }
    @Test public void bitmapColourWidthSpacingAndCrossDirectionActuallyChangeCoverage() {
        SimplePolygonStyle style=configured(); style.getPatternOptions().setColor(Color.RED);
        style.getPatternOptions().setAngle(0); style.getPatternOptions().setSpacing(16); style.getPatternOptions().setWidth(2);
        Bitmap narrow=ConfigurablePolygonPatternRegistry.bitmap(style);
        assertEquals(Color.RED,narrow.getPixel(20,0)); assertEquals(0,Color.alpha(narrow.getPixel(20,8)));
        style.getPatternOptions().setWidth(8); Bitmap wide=ConfigurablePolygonPatternRegistry.bitmap(style);
        assertTrue(Color.alpha(wide.getPixel(20,3))>Color.alpha(narrow.getPixel(20,3)));
        style.setFillPattern(2);style.getPatternOptions().setSecondAngle(90);
        assertTrue(Color.alpha(ConfigurablePolygonPatternRegistry.bitmap(style).getPixel(0,8))>0);
        style.getPatternOptions().setSecondAngle(0);
        assertEquals(0,Color.alpha(ConfigurablePolygonPatternRegistry.bitmap(style).getPixel(0,8)));
    }
    @Test public void patternOpacityIsSeparateFromBackgroundAndFeaturePropsCannotLeak() {
        SimplePolygonStyle style=configured(); style.setFill(false);style.setAlpha(0);style.getPatternOptions().setAlpha(128);
        Feature feature=Feature.fromGeometry(Point.fromLngLat(0,0)); MplFeatureStyleProps.apply(style,feature,GeoConstants.GTPolygon);
        assertEquals(0,feature.getNumberProperty(MplFeatureStyleProps.OPACITY).floatValue(),0);
        assertEquals(128/255f,feature.getNumberProperty(ConfigurablePolygonPatternRegistry.OPACITY_PROPERTY).floatValue(),0);
        assertEquals(ConfigurablePolygonPatternRegistry.imageName(style),feature.getStringProperty(MplFeatureStyleProps.FILL_PATTERN_IMAGE));
        style.setPatternOptions(null);style.setFillPattern(3); MplFeatureStyleProps.apply(style,feature,GeoConstants.GTPolygon);
        assertEquals(PolygonPatternRegistry.patternName(3),feature.getStringProperty(MplFeatureStyleProps.FILL_PATTERN_IMAGE));
        MplFeatureStyleProps.clear(feature,GeoConstants.GTPolygon);
        assertFalse(feature.hasProperty(ConfigurablePolygonPatternRegistry.OPACITY_PROPERTY));assertFalse(feature.hasProperty(PolygonOutlineLayerFactory.KEY));
    }
    @Test public void customImagesKeepTheirExistingOpacityAndTakePrecedence() {
        SimplePolygonStyle style=configured();style.setFillPatternImage("custom");style.setFill(false);
        assertFalse(ConfigurablePolygonPatternRegistry.applies(style));assertEquals(0,style.getPatternOpacity(),0);
    }
    @Test public void unsafeOptionsAreBoundedAndDashFallbackIsFinite() {
        SimplePolygonStyle style=configured();PolygonPatternOptions p=style.getPatternOptions();
        p.setSpacing(Float.MAX_VALUE);p.setWidth(Float.NaN);p.setAngle(Float.POSITIVE_INFINITY);p.setAlpha(-1);
        assertEquals(64,p.getSpacing(),0);assertEquals(2,p.getWidth(),0);assertEquals(45,p.getAngle(),0);assertEquals(0,p.getAlpha());
        assertEquals(512,ConfigurablePolygonPatternRegistry.bitmap(style).getWidth());
        PolygonOutlineOptions o=style.getOutlineOptions();o.setGap(Float.POSITIVE_INFINITY);o.setCasingWidth(-100);
        assertEquals(0,o.getGap(),0);assertEquals(0,o.getCasingWidth(),0);
        assertFalse(MplStyleMapper.isValidDashArray("NaN,2"));assertFalse(MplStyleMapper.isValidDashArray("Infinity,2"));
        assertFalse(MplStyleMapper.isValidDashArray("-1,2"));assertTrue(MplStyleMapper.isValidDashArray("4,2,1,2"));
        o.setType(PolygonOutlineOptions.CUSTOM);o.setDashArray("NaN,2");assertArrayEquals(new Float[]{2f,2f},o.dashes());
    }
    @Test public void cpuTexturesAreSharedByGeometryAndBoundedByBytes() throws Exception {
        SimplePolygonStyle style=configured(); Bitmap before=ConfigurablePolygonPatternRegistry.bitmap(style);
        style.getPatternOptions().setAlpha(42);assertSame(before,ConfigurablePolygonPatternRegistry.bitmap(style));
        for(int i=0;i<10;i++){style.getPatternOptions().setAngle(i*11);ConfigurablePolygonPatternRegistry.bitmap(style);}
        java.lang.reflect.Field field=ConfigurablePolygonPatternRegistry.class.getDeclaredField("BITMAPS");field.setAccessible(true);
        android.util.LruCache<?,?> cache=(android.util.LruCache<?,?>)field.get(null);assertTrue(cache.size()<=4*1024*1024);
    }
}

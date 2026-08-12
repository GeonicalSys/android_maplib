package com.nextgis.maplib.display;

import com.nextgis.maplib.map.MplFeatureStyleProps;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Pure-JVM tests for rule←other unset merge (no Android framework / Paint).
 */
public class FieldStyleRuleMergeTest {

    @Test
    public void labelAttributesFillUnsetFromOther() {
        LabelAttributes other = LabelAttributes.defaults();
        other.setLabelMinZoom(12f);
        other.setLabelMaxZoom(20f);
        other.setTextZoomScaleStops("6:0.35,14:1");
        other.setLabelTemplate("${name}");
        other.setTextAllowOverlap(true);

        LabelAttributes rule = LabelAttributes.defaults();
        rule.fillUnsetFrom(other);

        assertEquals(12f, rule.getLabelMinZoom(), 0.001f);
        assertEquals(20f, rule.getLabelMaxZoom(), 0.001f);
        assertEquals("6:0.35,14:1", rule.getTextZoomScaleStops());
        assertEquals("${name}", rule.getLabelTemplate());
        assertEquals(Boolean.TRUE, rule.getTextAllowOverlap());
    }

    @Test
    public void labelAttributesFillUnsetDoesNotOverwriteSetValues() {
        LabelAttributes other = LabelAttributes.defaults();
        other.setLabelMinZoom(12f);
        other.setLabelMaxZoom(20f);
        other.setTextZoomScaleStops("6:0.35,14:1");

        LabelAttributes rule = LabelAttributes.defaults();
        rule.setLabelMinZoom(14f);
        rule.setTextZoomScaleStops("10:0.5,18:2");
        rule.fillUnsetFrom(other);

        assertEquals(14f, rule.getLabelMinZoom(), 0.001f);
        assertEquals(20f, rule.getLabelMaxZoom(), 0.001f);
        assertEquals("10:0.5,18:2", rule.getTextZoomScaleStops());
    }

    @Test
    public void labelZoomPropNamesMatchContract() {
        assertEquals("labelminzoom", MplFeatureStyleProps.LABEL_MIN_ZOOM);
        assertEquals("labelmaxzoom", MplFeatureStyleProps.LABEL_MAX_ZOOM);
    }

    @Test
    public void mergeRuleWithOtherReturnsRuleWhenOtherNull() {
        Style rule = new StyleStub();
        assertSame(rule, FieldStyleRule.mergeRuleWithOther(rule, null));
    }

    @Test
    public void mergeRuleWithOtherReturnsOtherWhenRuleNull() {
        Style other = new StyleStub();
        assertSame(other, FieldStyleRule.mergeRuleWithOther(null, other));
    }

    @Test
    public void mergeRuleWithOtherFillsSizeStopsFromOther() throws Exception {
        StyleStub other = new StyleStub();
        other.setSizeZoomScaleStops("6:0.35,14:1,18:1.5");

        StyleStub rule = new StyleStub();

        Style effective = FieldStyleRule.mergeRuleWithOther(rule, other);
        assertTrue(effective instanceof StyleStub);
        StyleStub merged = (StyleStub) effective;

        assertEquals("6:0.35,14:1,18:1.5", merged.getSizeZoomScaleStops());
        assertNull(rule.getSizeZoomScaleStops());
    }

    @Test
    public void mergeRuleWithOtherKeepsRuleStopsWhenSet() throws Exception {
        StyleStub other = new StyleStub();
        other.setSizeZoomScaleStops("6:0.35,14:1");

        StyleStub rule = new StyleStub();
        rule.setSizeZoomScaleStops("8:0.5,16:2");

        StyleStub merged = (StyleStub) FieldStyleRule.mergeRuleWithOther(rule, other);
        assertEquals("8:0.5,16:2", merged.getSizeZoomScaleStops());
    }

    @Test
    public void labelAttributesFillUnsetInheritsScaleAndOpacityDefaults() {
        LabelAttributes other = LabelAttributes.defaults();
        other.setTextScaleWithZoom(true);
        other.setTextOpacity(128);
        other.setTextZoomScaleStops("6:0.35,14:1");

        LabelAttributes rule = LabelAttributes.defaults();
        rule.fillUnsetFrom(other);

        assertTrue(rule.isTextScaleWithZoom());
        assertEquals(128, rule.getTextOpacity());
        assertEquals("6:0.35,14:1", rule.getTextZoomScaleStops());
    }

    @Test
    public void labelAttributesFillUnsetKeepsExplicitOpacityAndScaleOffWhenOtherOff() {
        LabelAttributes other = LabelAttributes.defaults();
        other.setTextOpacity(128);

        LabelAttributes rule = LabelAttributes.defaults();
        rule.setTextOpacity(200);
        rule.setTextScaleWithZoom(false);
        rule.fillUnsetFrom(other);

        assertEquals(200, rule.getTextOpacity());
        assertTrue(!rule.isTextScaleWithZoom());
    }

    @Test
    public void mergeRuleWithOtherInheritsScaleSizeWithZoom() throws Exception {
        StyleStub other = new StyleStub();
        other.setScaleSizeWithZoom(true);
        other.setSizeZoomScaleStops("6:0.35,14:1");

        StyleStub rule = new StyleStub();
        StyleStub merged = (StyleStub) FieldStyleRule.mergeRuleWithOther(rule, other);

        assertTrue(merged.isScaleSizeWithZoom());
        assertEquals("6:0.35,14:1", merged.getSizeZoomScaleStops());
    }

    private static final class StyleStub extends Style {
        @Override
        public StyleStub clone() throws CloneNotSupportedException {
            return (StyleStub) super.clone();
        }

        @Override
        public String getField() {
            return null;
        }

        @Override
        public void onDraw(com.nextgis.maplib.datasource.GeoGeometry geoGeometry,
                GISDisplay display) {
        }
    }
}

package com.nextgis.maplib.util;

import com.nextgis.maplib.display.LabelAttributes;
import com.nextgis.maplib.display.MplStyleMapper;
import com.nextgis.maplib.display.SimpleFeatureRenderer;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StyleJsonAdapterTest {

    @Test
    public void labelAttributesRoundTripExtendedKeys() throws Exception {
        LabelAttributes attrs = LabelAttributes.defaults();
        attrs.setTextHaloBlur(2.5f);
        attrs.setTextOpacity(120);
        attrs.setTextScaleWithZoom(true);
        attrs.setTextZoomScaleStops("6:0.4,14:1,18:1.8");
        attrs.setTextAllowOverlap(true);
        attrs.setTextOptional(false);
        attrs.setSymbolSpacing(42f);
        attrs.setTextMaxWidth(11f);
        attrs.setTextFont("Open Sans Regular, Arial Unicode MS Regular");
        attrs.setTextJustify("center");
        attrs.setTextTransform("uppercase");
        attrs.setTextLetterSpacing(0.08f);
        attrs.setTextLineHeight(1.5f);
        attrs.setTextPadding(5f);
        attrs.setTextKeepUpright(false);
        attrs.setTextMaxAngle(30f);

        LabelAttributes copy = LabelAttributes.defaults();
        copy.fromJSON(attrs.toJSON());

        assertEquals(2.5f, copy.getTextHaloBlur(), 0.0001f);
        assertEquals(120, copy.getTextOpacity());
        assertTrue(copy.isTextScaleWithZoom());
        assertEquals("6:0.4,14:1,18:1.8", copy.getTextZoomScaleStops());
        assertEquals(Boolean.TRUE, copy.getTextAllowOverlap());
        assertFalse(copy.isTextOptional());
        assertEquals(42f, copy.getSymbolSpacing(), 0.0001f);
        assertEquals(11f, copy.getTextMaxWidth(), 0.0001f);
        assertEquals("Open Sans Regular, Arial Unicode MS Regular", copy.getTextFont());
        assertArrayEquals(
                new String[]{"Open Sans Regular", "Arial Unicode MS Regular"},
                copy.getTextFontStack());
        assertEquals("center", copy.getTextJustify());
        assertEquals("uppercase", copy.getTextTransform());
        assertEquals(0.08f, copy.getTextLetterSpacing(), 0.0001f);
        assertEquals(1.5f, copy.getTextLineHeight(), 0.0001f);
        assertEquals(5f, copy.getTextPadding(), 0.0001f);
        assertEquals(Boolean.FALSE, copy.getTextKeepUpright());
        assertEquals(30f, copy.getTextMaxAngle(), 0.0001f);
    }

    @Test
    public void ngwAdapterMapsStyleAliasesAndFractionalOpacity() throws Exception {
        JSONObject style = new JSONObject()
                .put("opacity", 0.5)
                .put("stroke_opacity", 0.25)
                .put("text_opacity", 0.75)
                .put("font", "Open Sans Bold")
                .put("icon", "well-icon")
                .put("pattern_image", "sand-pattern");
        JSONObject renderer = new JSONObject()
                .put(Constants.JSON_NAME_KEY, "simple")
                .put(SimpleFeatureRenderer.JSON_STYLE_KEY, style);

        NgwLayerConfigAdapter.adaptRenderer(renderer);
        JSONObject adapted = renderer.getJSONObject(SimpleFeatureRenderer.JSON_STYLE_KEY);

        assertEquals("SimpleFeatureRenderer", renderer.getString(Constants.JSON_NAME_KEY));
        assertEquals(128, adapted.getInt(Constants.JSON_ALPHA_KEY));
        assertEquals(64, adapted.getInt(Constants.JSON_OUTALPHA_KEY));
        assertEquals(191, adapted.getInt(Constants.JSON_LABEL_TEXT_OPACITY_KEY));
        assertEquals("Open Sans Bold", adapted.getString(Constants.JSON_LABEL_FONT_KEY));
        assertEquals("well-icon", adapted.getString(Constants.JSON_MARKER_ICON_IMAGE_KEY));
        assertEquals("sand-pattern", adapted.getString(Constants.JSON_FILL_PATTERN_IMAGE_KEY));
    }

    @Test
    public void customDashArrayFallsBackWhenInvalid() {
        assertArrayEquals(
                new Float[]{8f, 4f, 1f, 4f},
                MplStyleMapper.dashArray("8,4,1,4", MplStyleMapper.DASH_PRESET_SHORT));
        assertArrayEquals(
                MplStyleMapper.dashArray(MplStyleMapper.DASH_PRESET_LONG),
                MplStyleMapper.dashArray("bad", MplStyleMapper.DASH_PRESET_LONG));
    }
}

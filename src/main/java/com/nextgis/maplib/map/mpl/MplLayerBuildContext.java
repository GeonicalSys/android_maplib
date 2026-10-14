package com.nextgis.maplib.map.mpl;

import android.content.res.AssetManager;

import com.nextgis.maplib.api.ILayer;
import com.nextgis.maplib.display.FieldStyleRule;
import com.nextgis.maplib.display.LabelAttributes;
import com.nextgis.maplib.display.MplStyleMapper;
import com.nextgis.maplib.display.RuleFeatureRenderer;
import com.nextgis.maplib.map.VectorLayer;
import com.nextgis.maplib.map.MplFeatureStyleProps;
import org.maplibre.android.style.expressions.Expression;

import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.Layer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Inputs shared by MapLibre vector layer factories. */
public final class MplLayerBuildContext {
    public Expression scaleSize(Expression base, Expression enabled, boolean defaultEnabled, String defaultStops) {
        return scale(base, enabled, defaultEnabled, defaultStops, false);
    }
    public Expression scaleText(Expression base, Expression enabled, boolean defaultEnabled, String defaultStops) {
        return scale(base, enabled, defaultEnabled, defaultStops, true);
    }
    private Expression scale(Expression base, Expression enabled, boolean defaultEnabled, String defaultStops, boolean text) {
        List<String> curves = new ArrayList<>();
        if (ruleStyling && iLayer instanceof VectorLayer) {
            RuleFeatureRenderer renderer = (RuleFeatureRenderer) ((VectorLayer) iLayer).getRenderer();
            if (renderer.getStyleRule() instanceof FieldStyleRule) {
                FieldStyleRule rules = (FieldStyleRule) renderer.getStyleRule();
                for (String key : rules.getStyleRules().keySet()) {
                    com.nextgis.maplib.display.Style effective = rules.resolveEffectiveStyle(key, renderer.getStyle());
                    if (effective == null) continue;
                    curves.add(text ? LabelAttributes.fromStyle(effective).getTextZoomScaleStops() : effective.getSizeZoomScaleStops());
                }
            }
        }
        return MplStyleMapper.ruleZoomScaleExpression(base, enabled, defaultEnabled, defaultStops,
                text ? MplFeatureStyleProps.TEXT_ZOOM_STOPS : MplFeatureStyleProps.SIZE_ZOOM_STOPS, curves);
    }

    public final int layerId;
    public final int layerType;
    public final Style mapStyle;
    public final boolean changeLayer;
    public final String layerPath;
    public final String namePrefix;
    public final float layerOpacityFactor;
    public final MplLayerStyleVars vars;
    public final AssetManager assetManager;
    public final boolean ruleStyling;
    public final ILayer iLayer;
    public final Map<Integer, Layer> layersHashMap;
    public final Map<Integer, Layer> layersHashMap2;
    public final Map<Integer, List<Layer>> layersHashMapLineDash;

    public MplLayerBuildContext(
            int layerId,
            int layerType,
            Style mapStyle,
            boolean changeLayer,
            String layerPath,
            String namePrefix,
            float layerOpacityFactor,
            MplLayerStyleVars vars,
            AssetManager assetManager,
            boolean ruleStyling,
            ILayer iLayer,
            Map<Integer, Layer> layersHashMap,
            Map<Integer, Layer> layersHashMap2,
            Map<Integer, List<Layer>> layersHashMapLineDash) {
        this.layerId = layerId;
        this.layerType = layerType;
        this.mapStyle = mapStyle;
        this.changeLayer = changeLayer;
        this.layerPath = layerPath;
        this.namePrefix = namePrefix;
        this.layerOpacityFactor = layerOpacityFactor;
        this.vars = vars;
        this.assetManager = assetManager;
        this.ruleStyling = ruleStyling;
        this.iLayer = iLayer;
        this.layersHashMap = layersHashMap;
        this.layersHashMap2 = layersHashMap2;
        this.layersHashMapLineDash = layersHashMapLineDash;
    }
}

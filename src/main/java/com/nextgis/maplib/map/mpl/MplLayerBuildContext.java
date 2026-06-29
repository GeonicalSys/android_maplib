package com.nextgis.maplib.map.mpl;

import com.nextgis.maplib.api.ILayer;

import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.Layer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Inputs shared by MapLibre vector layer factories. */
public final class MplLayerBuildContext {

    public final int layerId;
    public final int layerType;
    public final Style mapStyle;
    public final boolean changeLayer;
    public final String layerPath;
    public final String namePrefix;
    public final float layerOpacityFactor;
    public final MplLayerStyleVars vars;
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
        this.ruleStyling = ruleStyling;
        this.iLayer = iLayer;
        this.layersHashMap = layersHashMap;
        this.layersHashMap2 = layersHashMap2;
        this.layersHashMapLineDash = layersHashMapLineDash;
    }
}

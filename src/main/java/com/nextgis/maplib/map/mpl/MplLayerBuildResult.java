package com.nextgis.maplib.map.mpl;

import org.maplibre.android.style.layers.FillLayer;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.SymbolLayer;

import java.util.ArrayList;
import java.util.List;

/** Layers produced by a geometry-specific factory. */
public final class MplLayerBuildResult {

    public Layer mainLayer;
    public Layer outlineLayer;
    public final List<LineLayer> dashLayers = new ArrayList<>();
    public FillLayer patternFillLayer;
    public SymbolLayer markerIconLayer;
}

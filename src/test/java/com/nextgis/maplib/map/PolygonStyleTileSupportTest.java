package com.nextgis.maplib.map;
import com.nextgis.maplib.display.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class PolygonStyleTileSupportTest {
    @Test public void advancedPolygonsUseClassicFallbackButLegacyAndExplicitOffRemainSupported() {
        SimplePolygonStyle polygon=new SimplePolygonStyle();assertTrue(LocalVectorTileProvider.isSupportedPolygonStyle(polygon));
        polygon.setPatternOptions(new PolygonPatternOptions());assertFalse(LocalVectorTileProvider.isSupportedPolygonStyle(polygon));
        polygon.getPatternOptions().setEnabled(false);assertTrue(LocalVectorTileProvider.isSupportedPolygonStyle(polygon));
        polygon.setOutlineOptions(new PolygonOutlineOptions());assertFalse(LocalVectorTileProvider.isSupportedPolygonStyle(polygon));
        polygon.getOutlineOptions().setEnabled(false);assertTrue(LocalVectorTileProvider.isSupportedPolygonStyle(polygon));
    }
}

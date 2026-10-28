package com.nextgis.maplib.map;

import com.nextgis.maplib.datasource.Feature;
import com.nextgis.maplib.datasource.GeoPoint;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

/** Independent decoded identities: successful results must not thin a large simple-point corpus. */
public class LocalVectorTileFullContentTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    @Test public void completeEncodedAndCachedTileKeepsEveryIdentityBeyondTheFormer2048Cutoff() throws Exception {
        List<Feature> features = new ArrayList<>();
        for (int id = 1; id <= 4097; id++) {
            Feature feature = new Feature(); feature.setId(id);
            feature.setGeometry(new GeoPoint(id % 100 + 1, id / 100 + 1)); features.add(feature);
        }
        byte[] tile = LocalVectorTileEncoder.encodePointTile(features,
                new LocalVectorTileEncoder.TileBounds(0, 0, 128, 128), 7, null, null);
        LocalVectorTileDiskCache cache = new LocalVectorTileDiskCache(folder.getRoot());
        String key = LocalVectorTileDiskCache.key("/owned/layer", 1, "style", "complete", 12, 2, 3);
        cache.put(key, tile, 128 * RenderMemoryPolicy.MIB);
        byte[] cached = cache.read(key, 4 * RenderMemoryPolicy.MIB); assertArrayEquals(tile, cached);
        Set<Long> ids = new HashSet<>();
        for (LocalVectorTileLabelsTest.Record record : LocalVectorTileLabelsTest.decode(cached).records) ids.add(record.id);
        assertEquals(4097, ids.size());
        for (long id = 1; id <= 4097; id++) assertTrue("Missing source identity " + id, ids.contains(id));
        assertEquals(4097, features.size());
    }
}

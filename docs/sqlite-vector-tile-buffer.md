---
title: SQLite native tile buffer parity
module_id: maplib
last_verified: 2026-10-28
related_code:
  - src/main/java/com/nextgis/maplib/map/NativeVectorTileEncoder.java
  - src/main/java/com/nextgis/maplib/map/NativeVectorTileClipper.java
  - src/main/java/com/nextgis/maplib/map/SqliteVectorTileProvider.java
  - src/main/java/com/nextgis/maplib/map/MPLFeaturesUtils.java
  - src/main/java/com/nextgis/maplib/map/mpl/PolygonLayerFactory.java
  - src/main/java/com/nextgis/maplib/display/SimplePolygonStyle.java
  - src/test/java/com/nextgis/maplib/map/NativeVectorTileBufferParityTest.java
  - src/test/java/com/nextgis/maplib/map/NativeVectorTileClipperTest.java
---

# Preserve the classic source's128px margin

The ordinary classic fallback in `MPLFeaturesUtils.createSourceForLayer` constructs
`GeoJsonSource` from its FeatureCollection without `GeoJsonOptions`. MapDrawable
does not override those options. The unchanged official Android13.7.1 SDK uses
[a128px default buffer](https://github.com/maplibre/maplibre-native/blob/android-v13.7.1/include/mln/style/sources/geojson_source.hpp#L23)
with [512px tiles and8192 extent](https://github.com/maplibre/maplibre-native/blob/android-v13.7.1/include/mln/util/constants.hpp#L13).
Its [GeoJSONVT setup](https://github.com/maplibre/maplibre-native/blob/android-v13.7.1/src/mln/style/sources/geojson_source_impl.cpp#L100)
multiplies the buffer by8192/512, producing2048 extent units beyond each tile edge.

The direct SQLite encoder previously queried and clipped only one eighth of a
tile beyond each edge:64px/1024extent. This could discard an otherwise supported
object before a style moved its visible output into the tile. In particular,
SimplePolygonStyle's fill translation is passed to the native fill-translate
property without a clamp or conversion. A polygon90style pixels outside an edge
with110px translation can produce visible output20px inside that edge. The64px
candidate envelope never retrieved it, whereas the classic128px margin did.

`NativeVectorTileEncoder.Bounds` now uses128/512 for all four query boundaries.
Both `SqliteVectorTileProvider`'s RTree envelope and `NativeVectorTileClipper`'s
full-precision intersection consume these same boundaries. Tile coordinates,
8192extent, complete candidate processing, original geometry, multipart/holes,
properties, source ownership and cancellation are unchanged. No simplification,
LOD, feature cutoff or layer disabling is introduced. Bounds for polygon anchor
ownership remain half-open tile ownership, independent of the geometry buffer.

The technical backend regression uses strict geometry-envelope filtering, rather
than returning every fixture regardless of the query. Four polygons90px beyond
different tile edges must reach the independently decoded PBF; a hole and an
original collinear vertex survive. The decoded centres with110px translations
must lie20px inside their corresponding edges. A separate polygon crossing the
128px boundary must clip at exactly-2048extent; a farther polygon must never be
read. All original WKT remains unchanged. These are unit expectations, not a
native renderer or real-project performance result.

The existing disconnected-concave-clip regression retains its original U geometry
and independently expected output: two arms at x[0,2]/[4,6], y[2.75,5.25], area5
each/10 total. Its base tile Y bounds are now[19/6,29/6], so the128/512 expansion
produces that same reviewed effective clip window. This preserves and strengthens
the original area/validity/full-original expectations instead of accepting the
larger area12 produced by the old base bounds after the deliberate buffer change.

This restores the classic baseline; it does not establish sufficient reach for
arbitrarily large user translations, wide symbols or long/offset labels. Their
full style-dependent reach remains a separate design/acceptance question. The
enlarged envelope has1.44times the previous area, so uniformly distributed data
may produce more candidates; this correctness fix is not a claimed memory
reduction. Existing bounded workers/complete-tile retry and disk-cache policy
remain, without a hard total-process/native/GPU allocation bound. Derived cache
keys include a random process session, so updating/restarting cannot reuse the
previous process's64px tiles. No saved project/schema/style migration is needed.

Native edge/translation rendering and physical787/2GiB measurements still need
their own independent acceptance. Passing this unit alone cannot certify them.

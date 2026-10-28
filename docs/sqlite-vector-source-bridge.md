---
title: SQLite native vector source lifetime
module_id: maplib
last_verified: 2026-10-28
related_code:
  - src/main/java/com/nextgis/maplib/map/SqliteVectorSourceBridge.kt
  - src/main/java/com/nextgis/maplib/map/SqliteVectorTileBackend.java
  - src/main/java/com/nextgis/maplib/map/SqliteVectorTileRequest.java
  - src/main/java/com/nextgis/maplib/map/SqliteSourceRetirement.java
  - src/main/java/com/nextgis/maplib/map/SqliteVectorLayerController.java
  - src/main/java/com/nextgis/maplib/map/MPLFeaturesUtils.java
  - src/main/java/com/nextgis/maplib/map/MapDrawable.java
  - src/main/java/com/nextgis/maplib/map/VisibleLineLabelController.java
  - src/test/java/com/nextgis/maplib/map/SqliteVectorSourceBridgeTest.kt
---

# SQLite → native MVT bridge

`SqliteVectorSourceBridge` owns exactly one source generation and the coroutine
scope supplied to the unchanged official MapLibre OpenGL SDK13.7.1
`CustomVectorSource`. Its provider returns `TileData.Mvt` from the Java backend.
There is no HTTP server and no whole-project GeoJSON load in this bridge.
“Direct” means bounded SQLite tile reads followed by PBF transfer to the SDK;
JNI copies the byte array into native storage, so this is not zero-copy.

`MapDrawable.getSqliteVectorDiagnostics(layerId)` is a pure, fixed-size Java
snapshot for source-delivery investigation. `source_registered=false` means no
current controller entry; an existing entry adds11 fixed scalar fields:
requested, io_successful, returned_to_sdk, cancelled, retries, failures,
last_failure_class, last_cancel_kind, active, closed and owner_job_active.
No SDK calls, DB reads, paths, coordinates, geometry, business values or arbitrary
exception messages are included. Counters are independent atomic observations,
not one transactional instant. `active` reaches zero after in-flight work unwinds;
it can remain positive briefly after terminal cancellation.

`requested` counts entry into the SDK provider coroutine, not the private native
fetch callback. `io_successful` counts complete backend.load returns, including
bytes later rejected by a current/cancel guard. `returned_to_sdk` counts the final
guarded provider return attempt; it is not evidence of JNI/native acceptance,
MVT parsing, geometry buckets or rendered pixels. SDK requests canceled before
the provider body starts cannot increment requested. Retry does not create a
second requested count; active failures record only bounded exception class.
Cancellation reason is a fixed enum; known generic internal stale-style/request
messages are matched to enum values without publishing their message text.
Manual backend PBF decoding does not increment these provider-entry counters.

The logical layer path/ID and actual SDK source ID are distinct. Each streamed
generation uses its own native source ID. SDK13.7.1 identifies render sources by
ID/type, but its CustomVectorSource impl comparison checks zoom range and only
the presence of a loader, not loader identity. Removing/readding the same source
ID before a renderer update can therefore keep the previous tile pyramid/PBF.
The native lifecycle test exposed this after category/Other color reversal:
the Java provider was replaced while native properties still held old colors.

`SqliteVectorLayerController.nativeSourceId(style, logicalPath)` resolves the
current actual SDK ID, falling back to the logical path for classic sources;
`isStreaming` identifies this route. MPLFeaturesUtils supplies the resolved ID
to MplLayerBuildContext's existing source constructor parameter (`layerPath` in
that factory-only context). All geometry, dash, pattern, icon, outline and rule
children thereby use the same current native source. Polygon/point labels use
that ID; line auxiliary labels remain on `logicalPath + "-text"` GeoJSON.
The source-layer binding pass visits the actual native ID. DB ownership,
feature/layer IDs, user selection and saved state retain logical identity.

Every newly added MVT base/outline/pattern/icon/dash layer and direct MVT
point/polygon label receives `source-layer=features` before its first native
`addLayer`. Rule geometry/label, polygon contour and Firefly children inherit
the base layer's nonempty source-layer before add. Classic null/empty stays
unset. The late binding pass remains responsible for MVT geometry/label filters
and offset expressions, rather than being the first initialization of source-layer.
SDK13.7.1 Fill/Line layout comparisons omit sourceLayer, so late mutation alone
cannot guarantee reparsing an already observed layer. This initialization
invariant does not by itself prove the cause of a native missing-bucket failure;
actual PBF contents and native IDs/pixels still need independent verification.

Explicit classic `createSourceForLayer` first calls `detachForClassic` to cancel
and remove a prior streamed source plus its children. This applies to fixture
classic binding and unsupported-renderer fallback. It must not leave a canceled
streamed source behind or silently attach classic factories to an old actual ID.
Full native style reversal, content replacement, visibility, edit and fallback
remain acceptance gates after this fix; Java provider replacement alone cannot
prove that the renderer replaced its old cached tiles.

The Java constructor accepts source ID, `SqliteVectorTileBackend`, a
`BooleanSupplier` proving current source ownership, and optional min/max zoom
(default0/18, valid0..25). Construct and call `getSource()` on the UI thread.
The owner supplier must read thread-safe Java identity/generation state only;
it must never query SDK style/source/native objects on IO threads. The backend
captures owner, source/content/style epochs and edited-ID exclusion in one
immutable request, and validates it on every load/retry.

Before removing this source, replacing its style or project, or destroying the
MapView, call `closeBeforeDetach()` on UI. It atomically marks the generation
closed, cancels its `SupervisorJob`, then closes the backend once. Backend close
must be idempotent and nonblocking; in-flight IO owns its resources and releases
them in `finally`. Do not wait for IO on UI. The closed bridge is terminal: a new
generation needs a new backend/bridge/source. Its canceled scope also rejects
late SDK fetch callbacks that arrive after the SDK clears its active-job map.

Project replacement is stronger than Activity recreation.
`MapDrawable.retireSqliteVectorSources()` is terminal and safe from any thread,
including inside the application's synchronized map-switch boundary. It marks
the retirement fence first, cancels registered sessions/providers immediately,
and invalidates epochs without consulting `app.getMap()` or waiting for Main.
The bridge's `cancelPendingWork()` is the corresponding any-thread operation:
it touches only Java/coroutine state, never an SDK object. A race-safe
`SqliteSourceRetirement` registration also closes a source created concurrently
with retirement. Only releasing controller wrapper/filter references is posted
to Main. `GISApplication.resetMap/closeMapObj` retire the old drawable before
dropping it; sync-workspace maps and recording owners are not changed.

Ordinary UI `closeSqliteVectorSourcesBeforeDetach()` does not retire the whole
project and permits a new bridge after Activity recreation. A retired controller
cannot bind again. Delayed bind requests must additionally prove exact canonical
model identity (`getLayerById(id) == model`), so a deleted/replaced layer cannot
reappear from an old callback. Temporary hide refreshes the source after content
version changes; feature-ID remap moves the exclusion only for the matching
active edited feature. Root host integration owns Save/Cancel/native validation.

Requests start and finish on `Dispatchers.Main.immediate`. Blocking snapshot
and load work uses `runInterruptible` on one shared `Dispatchers.IO` dispatcher
limited to two workers across all sources. The backend owns its existing
admission/byte budget; the bridge does not acquire a second gate. Each current
predicate includes the individual SDK request Job, parent Job, closed state,
source owner and immutable backend stamp. Canceling one tile therefore does
not close other tiles or the source. A final current check on Main precedes
the SDK handoff without another suspension.

MapLibre13.7.1 sends ordinary provider Exceptions to `nativeSetTileError` and
rethrows `CancellationException`. Consequently, a late IO exception after
close, cancellation or an epoch change is converted to cancellation. Genuine
active failures keep their original exception. Only the typed backend
`RetryableTileException` is retried: stack unwinding releases IO/resources
before a cancellable Main delay100..2000ms. The immutable request is reused;
if it becomes stale the request is canceled. Resource pressure never causes
the bridge to invent an empty or partial successful PBF.

The bridge does not simplify geometry, remove layers, alter saved-state formats,
or implement the once-per-session memory warning; those contracts belong to the
backend/host and existing memory guard. Two active workers do not establish a
hard bound on native heap, GPU allocations or SDK tile retention. This new
route needs separate native lifecycle/style/label/picking/edit tests before
claiming complete map acceptance or2GiB safety. JVM lifecycle doubles are not
that acceptance.

Exact upstream witnesses:

- [CustomVectorSource13.7.1](https://github.com/maplibre/maplibre-native/blob/android-v13.7.1/platform/android/MapLibreAndroid/src/main/java/org/maplibre/android/style/sources/CustomVectorSource.kt): supplied scope, per-tile jobs, cancellation/error handling and removal callback.
- [JNI source13.7.1](https://github.com/maplibre/maplibre-native/blob/android-v13.7.1/platform/android/MapLibreAndroid/src/cpp/style/sources/custom_vector_source.cpp): byte copying and native peer lifetime.
- [Native custom vector tile13.7.1](https://github.com/maplibre/maplibre-native/blob/android-v13.7.1/src/mln/tile/custom_vector_tile.cpp): MVT tile data instead of a global GeoJSONVT index.

AGP9.1 built-in Kotlin is disabled by the existing app-wide setting. This module
uses the already configured Kotlin2.2.10 plugin and JVM21, matching maplibui,
plus cached coroutines-android1.10.2. SDK coordinates and artifact stay unchanged.
No SDK classes/FQCNs are replaced and no native fork is built.

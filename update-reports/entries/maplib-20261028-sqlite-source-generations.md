---
id: maplib-20261028-sqlite-source-generations
area: Полнота отрисовки и смена стиля SQLite слоёв
kind: internal
at: 2026-10-28T04:57:00+01:00
title: Отдельный native source ID каждого поколения SQLite слоя
why: Native проверка изменения стиля нашла старые цвета после замены Java provider из-за повторного использования source ID и удержания прежней tile pyramid SDK13.7.1
covers:
  - src/main/java/com/nextgis/maplib/map/SqliteVectorLayerController.java
  - src/main/java/com/nextgis/maplib/map/MapDrawable.java
  - src/main/java/com/nextgis/maplib/map/MPLFeaturesUtils.java
  - src/main/java/com/nextgis/maplib/map/VisibleLineLabelController.java
  - src/main/java/com/nextgis/maplib/map/RuleGeometryLayouts.java
  - src/main/java/com/nextgis/maplib/map/RuleLabelLayouts.java
  - src/main/java/com/nextgis/maplib/map/mpl/PolygonOutlineLayerFactory.java
  - src/main/java/com/nextgis/maplib/map/mpl/FireflyLayerFactory.java
  - docs/sqlite-vector-source-bridge.md
---

MOB-04, actual native failure software-sqlite-related02: первый lifecycle case
NativeSqliteVectorRenderingTest.contentStyleAndVisibilityChangesRetireLateProvidersWithoutRebuildingGlobalFeatures
упал на строке107 после изменения стиля. Initial129polygonIDs,3labels, paints,
content notification replacement и отмена прежнего provider прошли, но после
category/Other reversal новые native features сохраняли старые REDcategory и
GREENother. Factory fallback уже соответствовал новому REDother. Failure JSON/PNG
сохранены в evidence/software-sqlite-related02/sqlite-evidence/
related-0-NativeSqliteVectorRenderingTest-contentStyleAndVisibilityChang-2b472d62055a.
Проверка неизменных цветов после content-only replacement сама по себе не могла
выявить удержание старого PBF; style reversal даёт независимое ожидание.

Exact SDK13.7.1 causal chain:

- [style_diff.cpp52–55](https://github.com/maplibre/maplibre-native/blob/android-v13.7.1/src/mln/renderer/style_diff.cpp#L52) сравнивает sources по ID/type. Remove/add с тем же ID до renderer frame становится changed, а не removed+added.
- [render_orchestrator.cpp366–381](https://github.com/maplibre/maplibre-native/blob/android-v13.7.1/src/mln/renderer/render_orchestrator.cpp#L366) удаляет/создаёт render source только для removed/added.
- [custom_vector_source_impl.cpp16–17](https://github.com/maplibre/maplibre-native/blob/android-v13.7.1/src/mln/style/sources/custom_vector_source_impl.cpp#L16) сравнивает zoomRange и только bool(loaderRef), не identity loader.
- [render_custom_vector_source.cpp26–33](https://github.com/maplibre/maplibre-native/blob/android-v13.7.1/src/mln/renderer/sources/render_custom_vector_source.cpp#L26) не очищает tilePyramid при таком равенстве; [CustomVectorTile](https://github.com/maplibre/maplibre-native/blob/android-v13.7.1/src/mln/tile/custom_vector_tile.cpp#L14) сохраняет прежний loader. Инвалидация нового loader старые тайлы не затрагивает.

Каждое новое поколение получает отдельный actual native source ID с epoch.
Controller хранит явную logical→native связь и удаляет actual source/children
до следующего bind. MapDrawable readiness/source lookup и viewport line-label
snapshot используют актуальный ID. Stable logical path, layer/feature IDs,
DB/schema/style JSON и исходная полная геометрия не меняются.

MPLFeaturesUtils разрешает actual ID до фабрик. Existing MplLayerBuildContext
source parameter передаёт его всем point/line/polygon/dash/contour/firefly/pattern
constructors; RuleGeometry/RuleLabel children копируют source ID базового слоя.
Point/polygon labels читают тот же MVT source; line auxiliary GeoJSON сохраняет
logicalPath-text. Source-layer binding обходится по actual ID. Перед явным
classic source creation detachForClassic закрывает streamed bridge и снимает
его children/source: classic fixtures и unsupported fallback не оставляют старый
стриминговый источник. Точные public SDK классы,13.7.1 dependency/native.so
остаются прежними, SDK fork/замена FQCN не вводились.

До MPL edits выполнен whole-contour plan
sqlite-generation-factories-dependency-plan01.json: UNKNOWN,
REFRESHED_IN_MEMORY. Controller/MapDrawable/line-controller изменения принадлежат
parent integration в том же общем task; factory source closure вручную проверена
по всем ctx.layerPath constructors и base.getSourceId child copies. Semantic
source-generation/detach links описаны здесь; parent обновляет app/canonical
registries. Foreign desktop checkout не менялся; новых desktop/NGW formats нет.
Delivery order сохраняется maplib→maplibui→app pointers, saved-state migration
не требуется. Первоначальный failed native run сохраняется без перезаписи.

На source freeze новая generation реализация ещё не запускалась native/Gradle
этим агентом. Root выполняет combined units/build и повтор обязательного
native style/content/visibility/edit/fallback case. Это не гарантирует RAM/OOM
или2GiB приёмку. Source/card не выпускались, commit/push не выполнялись.
Новый discovery plan удерживается как evidence наQ, около33.5MiB, до общего
закрытия расследования; SDK/runtime/download/эмуляторы агент не создавал.

Повтор software-sqlite-related03 сохранил новый FAIL после style reversal,
строка108: actual source уже sqlite-native-4, Symbol query даёт3Point, а Fill
даёт0IDs; PNG чёрный. Backend styled-original witness подтверждает новый
GREEN31/RED62/RED99, но сам по себе не удостоверяет polygon content фактического
переданного PBF. Initial/content steps прошли. Следовательно, source generation
устранил возможность повторного использования прежнего source cache, но пока
не доказал полноту native geometry replacement и не назван завершённым fix.
Исходный03FAIL/JSON/PNG остаётся сохранённым.

Дополнительно исправлен обязательный initialization contract:
`source-layer=features` задаётся base/outline/pattern/icon/dash и прямым MVT
point/polygon labels ДО первого native addLayer. RuleGeometry/RuleLabel и
PolygonOutline/Firefly children наследуют nonempty base source-layer до add.
Classic null/empty не переопределяется. Point/Line/Polygon factories сами не
добавляют результат в style, поэтому ранняя настройка сосредоточена в MPL,
без лишней смены их API. Source generation и geometry/label filters сохраняются.

Это самостоятельный подтверждённый дефект порядка настройки, не доказательство
единственной причины03FAIL: [Fill Impl layout comparison](https://github.com/maplibre/maplibre-native/blob/android-v13.7.1/src/mln/style/layers/fill_layer_impl.cpp#L6)
и [Line Impl](https://github.com/maplibre/maplibre-native/blob/android-v13.7.1/src/mln/style/layers/line_layer_impl.cpp#L6)
не включают sourceLayer, тогда как [GeometryTileWorker468–470](https://github.com/maplibre/maplibre-native/blob/android-v13.7.1/src/mln/tile/geometry_tile_worker.cpp#L468)
пропускает geometry layout при отсутствии такого MVT layer. Поздний setter сам
по себе не гарантирует reparse. При этом generic RenderLayer.transition
обновляет source baseImpl, а новый TilePyramid вызывает setLayers для новых
тайлов; утверждение, что stable LayerID неизбежно хранит старый source, не
подтверждено. Render-frame barrier и SDK fork этим batch не вводились.

До пяти source edits выполнен whole-contour plan
sqlite-preadd-source-layer-dependency-plan01.json: UNKNOWN,
REFRESHED_IN_MEMORY. Новые edges MPL.inheritNativeSourceLayer→four child
factories и pre-add source-layer contract записаны здесь/в local doc; canonical
index/app registry остаются общей parent closure. Parent запускает compile,
units и новый native case после freeze. Эти проверки здесь NOT RUN; ожидается
независимый PBF/native geometry witness. Дополнительный read-only discovery
plan остаётся в Q evidence до общего закрытия incident; bulk downloads нет.

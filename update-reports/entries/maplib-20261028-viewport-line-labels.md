---
id: maplib-20261028-viewport-line-labels
area: Подписи линий и память карты
kind: user
at: 2026-10-28T02:23:27+01:00
title: Подписи линий из SQLite для текущего экрана без полной коллекции слоя
why: Demand-driven native источник обычного вектора больше не предоставляет GeoJsonSource и полный Java список, необходимые прежнему контроллеру подписей
covers:
  - src/main/java/com/nextgis/maplib/map/VisibleLineLabelController.java
  - src/main/java/com/nextgis/maplib/map/VisibleLineLabelQuery.java
  - src/test/java/com/nextgis/maplib/map/VisibleLineLabelQueryTest.java
  - docs/manifest.yaml
---

MOB-04; maplib61 перед app82. Это часть SQLite/native migration, не отдельное
включение backend и не изменение публичного редактора. Root владеет
MapDrawable/MPLFeaturesUtils/editor интеграцией, отдельная backend-card —
SqliteVectorTileProvider/native bridge и feature/style encoding.

VisibleLineLabelController сохраняет прежний classic GeoJSON путь. Для
streamed LineString/MultiLineString он получает provider текущего слоя через
MapDrawable.isSqliteVectorLayer/getSqliteVectorProvider. Main CustomVectorSource
не приводится к GeoJsonSource и не читается через querySourceFeatures.
Только существующий отдельный GeoJSON source подписей содержит небольшие
видимые label points. Root integration обязана создать его с прежним ID и
сохранить style/layout/visibility/zoom contracts.

SDK source/camera/projection/visibility читаются на UI. IO worker рассчитывает
Mercator3857 bbox всех четырёх углов экрана, разделяет dateline на два узких
окна и вызывает provider.readVisibleLines(List<GeoEnvelope>,BooleanSupplier).
Provider возвращает только viewport candidates: полные исходные styled линии,
не native clipped tiles и не список всего слоя. Центр камеры задаёт unwrap;
поворот/наклон обрабатывает прежний VisibleLineLabelLayout по настоящему
четырёхугольнику, не по bbox. Геометрия, части, координаты, FID, полный текст
и используемые style properties не упрощаются и не изменяются.

Reader и layout получают cancellation supplier. Перед/после DB чтения и при
расчёте проверяются camera generation, closed state, layer content version и
provider.isCurrent. UI apply дополнительно требует тех же application map,
MapLibreMap/MapView/Style, layer identity/visibility, main/label source objects
и provider identity. Устаревший job не публикует label points. Существующие
coalescing/busy и один worker сохраняются; новых timer/parallel queues нет.

Consumer closure: MapDrawable lifecycle/source callbacks → controller →
provider viewport RTree/SQLite read → прежний VisibleLineLabelLayout →
MPLFeaturesUtils.setNativeLabelGeoJson → существующие SymbolLayer groups.
Provider обязан исключать текущий edited FID и инвалидироваться при data/style/
owner/exclusion generation; controller это не заменяет. Polygon/MultiPolygon,
Point/MultiPoint anchors/labels принадлежат backend/style интеграции этой же
задачи и не реализуются новым line helper. Track/Walk/selection/editor APIs,
source SQLite, sync/outbox, draft, saved visibility/style и schema прежние.
Нет persist migration, geometry simplification или arbitrary-ID thinning.

До edits выполнен full-contour read-only plan3paths:
sqlite-line-label-impact01.json в evidence задачи. UNKNOWN/PLUGIN_ONLY,
REFRESHED_IN_MEMORY; source basis — наблюдаемые live working trees корня и
пяти Android repositories, не native acceptance или сохранённый final index.
Чужой dirty desktop/semantic registry не редактируется; final source index
и центральный контракт сохраняет основной task после всей source batch.

Добавлены8 независимых технических unit checks: численные Mercator bounds
повёрнутого экрана; два узких dateline окна и wrapped longitude; camera-wide
arc; полнота LineString/MultiLineString/подписи/style; точное quad clipping;
cancel до DB/после изменения данных; dateline FID dedupe; invalid projection
и конечные polar bounds. Tests/build пока NOT RUN до общей backend/API source
handoff. Native label/style/DB полнота и память на устройстве требуют отдельных
Related/Full gates; карта787 остаётся read-only memory/render evidence.
Публикация/merge/Git/server действия этой правкой не выполнялись.

Хранение: только компактный impact receipt наQ; дополнительных runtime/
download копий и native captures нет. Receipt сохраняется с active evidence
до общей доставки и финального source-basis handoff.

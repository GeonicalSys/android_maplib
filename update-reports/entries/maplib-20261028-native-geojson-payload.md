---
id: maplib-20261028-native-geojson-payload
area: Память и отрисовка карты
kind: user
at: 2026-10-28T00:55:00+01:00
title: Уменьшение повторяемых native GeoJSON атрибутов без изменения геометрии
why: Профиль787 подтвердил сотни MiB property-map копий внутри GeoJSONVT; полные style и label наборы передавались даже туда, где native factories их не используют
covers:
  - src/main/java/com/nextgis/maplib/map/NativeFeaturePayload.java
  - src/main/java/com/nextgis/maplib/map/MPLFeaturesUtils.java
  - src/main/java/com/nextgis/maplib/map/MapDrawable.java
  - src/main/java/com/nextgis/maplib/map/VisibleLineLabelController.java
  - src/main/java/com/nextgis/maplib/map/MLP/PointEditClass.java
  - src/main/java/com/nextgis/maplib/map/MLP/MultiPointEditClass.java
  - src/main/java/com/nextgis/maplib/map/MLP/LineEditClass.java
  - src/main/java/com/nextgis/maplib/map/MLP/MultiLineEditClass.java
  - src/main/java/com/nextgis/maplib/map/MLP/PolygonEditClass.java
  - src/main/java/com/nextgis/maplib/map/MLP/MultiPolygonEditClass.java
  - src/main/java/com/nextgis/maplib/util/MapRuntimeDiagnostics.java
  - src/test/java/com/nextgis/maplib/map/NativeFeaturePayloadTest.java
  - src/test/java/com/nextgis/maplib/util/MapRuntimeDiagnosticsTest.java
  - docs/README.md
  - docs/manifest.yaml
---

MOB-04, текущие maplib61/app82. В квалифицированном heap03 главным владельцем
retained allocations оказался GeoJSONVT index: около673MiB из907MiB,
включая не менее564MiB property hash maps. Эти числа относятся к выборке
allocator, а не ко всему RSS процесса. Большой payload уже существовал в223;
точный триггер усиления падений223→224 остаётся неподтверждённым.

NativeFeaturePayload копирует только сохраняемые поля за один проход.
Rich Java features редактора не изменяются; все части/отверстия исходной
геометрии, bbox, строковые/GeoJSON IDs, неизвестные вложенные поля и явные
false/null остаются. Удалены только доказанно неиспользуемые native поля:
literal label/layout настройки, literal dash arrays, translation/collision
настройки существующих layout groups. Polygon geometry освобождена от label
настроек; native polygon label points создаются только для непустой подписи
и не несут geometry paint. Point source сохраняет нужные label+paint поля;
Line source сохраняет label inputs для querySourceFeatures fallback.
Custom zoom curves, label zoom gates, layout/dash keys и фактические paint значения
остаются. Для точного uniform SimpleFeatureRenderer/SimplePolygonStyle
совпадающие overrides заменяются теми же factory defaults. Без контекста,
для rules или несовпадающего override применяется консервативный вариант.

Компактизация включена в initial/hot source creation, reload/restyle и
logical-vector ID/edit/save/hide/show native handoffs. Saved style JSON,
SQLite, sync/outbox, видимость и source geometry не меняются. Публичный
convertToPointFeatures сохраняет прежний anchor API. Старые rich URI-файлы
не обходят проекцию; USE_MAPLIBRE_NATIVE_GEOJSON_URI остаётся false.
Transport counters журналируются отдельно от memory attribution: layer ID,
роль и количества объектов/свойств/непустых подписей, без координат/значений.
Журнал сохраняет прежний предел8наблюдений; line camera-idle не спамит HyperLog.

Независимые unit expectations перечисляют literal нужные/лишние ключи, а не
повторяют helper. Проверяются все6geometry kinds, geometry/bbox/IDs/unknowns,
неизменность original, glyph/paint zoom gates, sparse equal defaults и
реальный VisibleLineLabelLayout поверх native-query fallback. App fixture
проверяет compact source readiness и возвращает прежние rich features;
native GPU/style/layout/selection assertions сохраняются.

Consumer closure: MPLFeaturesUtils и MapDrawable передают geometry payload;
VisibleLineLabelController — label payload; maplibui editor/preview и app
используют прежние rich features и фабрики. Схема сохранения не меняется;
desktop/PostgreSQL/NGW не получают нового формата. Root task обновляет app
registry/docs и source index; canonical desktop checkout занят чужой dirty
задачей, поэтому его реестры здесь не редактировались.

Это не eviction внутреннего GeoJSONVT index и не гарантия отсутствия OOM.
Unit/build результаты, полный native style smoke и A/B787 записываются
отдельно; до их завершения экономия RSS и2GiB приёмка не объявляются PASS.
Merge/release/publication этой правкой не выполнялись.

Локальный полный `:maplib:testDebugUnitTest`:651tests,0failures/errors/skips,
25s. Включены NativeFeaturePayloadTest8, VisibleLineLabelLayoutTest8,
MapRuntimeDiagnosticsTest13 и прежние pattern tests16. Main sources
скомпилированы. Квитанция с точными hashes и результатами:
Q:/Codex/Scratch/mobile-crash-20261027-01a17d5e/evidence/native-payload-units04.json.
App AndroidTest compile и native GPU/A-B787 здесь не выполнялись. Новых bulk
downloads/копий нет; компактные logs/receipt сохраняются с расследованием,
outputs используют существующие shared Gradle build junctions наQ.

Read-only source review нашёл дополнительный bypass:6MLP constructors при входе
в editor повторно передавали весь rich background list в logical-layer source.
Все6writes теперь используют тот же compact handoff. Удаление редактируемого
объекта из background list, selected/tool/marker/vertex sources, исходные
attributes/geometry, Save/Cancel/Undo и durable drafts сохранены. Constructor
не получает layer owner, поэтому использует консервативную проекцию без
uniform sparsity; sparse owner API можно расширить отдельно. Этот follow-up
инвалидирует прежние source hashes и native APK/приёмку до новой сборки.

До edits выполнен read-only plan по всем6пути с whole-contour in-memory
discovery: dependency-plan-mlp-native-payload01.json в evidence текущего Qtask.
Все6пути PLUGIN_ONLY, итог UNKNOWN/path-contract-unmapped; semantic closure
проверено вручную в обе стороны (MapDrawable → createEditObject → constructors
→ native projection, обратно к rich Java cache и editor consumers). Нет capped,
missing/unreadable roots; geo.json имеет прежнее parser warning. Неполнота
модели не названа PASS. Новые синтаксические caller edges сохраняются root
task при source-index closure; чужой dirty desktop реестр здесь не менялся.

После6MLP edits полный maplib unit набор повторён:651/651PASS,0errors/failures/
skips,26s; main sources с новыми вызовами скомпилированы. Итоговая квитанция
native-payload-units05.json включает hashes всех6MLP files. Native constructor
исполнение и Save/Cancel/GPU приёмка этим unit прогоном не подменяются.
Exact-path guard:35cardsPASS,0uncovered/errors, свидетельство
native-payload-report-coverage01.json. Текущий diff Point/MultiPoint — только
замена native handoff; прежний ANR0e709 этих файлов не менял и здесь не смешан
с независимой логикой ANR. Native APK и его source receipt нужно пересобрать.

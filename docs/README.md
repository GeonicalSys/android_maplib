---
title: maplib — GIS model, storage, NGW и MapLibre
module_id: maplib
last_verified: 2026-07-24
---

# maplib — GIS model, storage, NGW и MapLibre

## Назначение

Нижняя библиотека проекта: GIS layer/data model, локальное хранение, NGW
protocol/sync decisions, MapLibre style/rendering и shared application APIs.

## Критичные области

- `MapDrawable`, `MPLFeaturesUtils`, `VectorLayerRenderCache` — rendering;
- `user-location-layer` остаётся служебным верхним overlay независимо от порядка пользовательских слоёв;
- `LayerContentProvider` разрешает активный `IGISApplication.getMap()` на каждой операции и не
  маршрутизирует треки/объекты через карту предыдущего Collector workspace;
- `MaplibreMapInteraction`, `IGISApplication` — API верхних слоёв;
- `Connection`, `SyncAdapter`, `NGWSyncService` — NGW;
- `NGWResourceUrl`, `ResourceGroup.loadTargetResource` — разбор URL и точечное
  получение NGW-ресурса без загрузки всего дерева;
- `CollectorProjectItem`, `CollectorProjectMetadata`,
  `CollectorProjectCompositionSync` — normalized Collector composition;
- `NGWRasterLayer` хранит style identity, отдельный parent extent id и
  project-origin metadata для server-rendered Collector styles;
- `NGWVectorLayer` применяет для managed Collector-слоя проектный editable-флаг
  вместе с исходящим направлением sync; обычные слои сохраняют общий
  `is_editable` gate;
- Collector insertion сохраняет «Мои треки» последним во внутреннем
  `LayerGroup`, то есть наверху UI-списка;
- `LayerConfigUtil` — server/local render and origin config.

## Ограничения

- Нет imports из `maplibui`/`app`.
- LayerGroup index `0` — bottom.
- Track start/end flag layers не включаются.
- Collector resource type не удаляется без продуктового решения.
- Collector style support ограничена уже известными `Connection` классами
  `qgis_vector_style` и `qgis_raster_style`; новые resource classes не
  добавляются в `Connection.java` без отдельного решения.
- URL ресурса принимает только HTTP(S), не содержит credentials/fragment и
  заканчивается на `/resource/<positive-id>`; server path до `/resource` сохраняется.
- Изменение public interface требует compile/manifest updates consumers.
- Debug `BuildConfig.VERSION_NAME` должен совпадать с Lisa Debug, release — с
  Lisa/Belka Release. На AGP 9.x library `buildTypes.versionName` не
  используется; variant coupling проверяет root script
  `tools/verify-apk-version-matrix.ps1`.

## Диагностика

- Неверный style order: проверить model order, sibling anchor и момент создания
  MapLibre style.
- Курсор перекрывается треком/вектором: проверить, что `user-location-layer`
  последний в live style после cold/lite/hot reload.
- «После restart стало правильно»: проверить hot-add/deferred reload contract.
- Пустой список треков после project switch: проверить строку
  `LayerContentProvider bound to active map path=...` и соответствие пути активному workspace;
- NGW config/data issue: отделить config parsing от feature sync decision.
- Импорт по URL: сначала проверить parser/server/account, затем response code,
  тип ресурса и `data.read`/`data.write` permissions.
- Collector composition: проверить stable remote IDs/project metadata до UI.
- Пропавший style из Collector: проверить resource `cls`, style remote id,
  parent extent id и authenticated render tile URL; style не должен попадать в
  edit pipeline.
- «Нет редактируемых слоёв» в Collector: сверить item `editable`,
  `managed_by_project` и исходящее направление sync.

## Проверки

Начать с `:maplib:testDebugUnitTest`; затем выбрать device smoke из manifest и
central registry.

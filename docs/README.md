---
title: maplib — GIS model, storage, NGW и MapLibre
module_id: maplib
last_verified: 2026-07-19
---

# maplib — GIS model, storage, NGW и MapLibre

## Назначение

Нижняя библиотека проекта: GIS layer/data model, локальное хранение, NGW
protocol/sync decisions, MapLibre style/rendering и shared application APIs.

## Критичные области

- `MapDrawable`, `MPLFeaturesUtils`, `VectorLayerRenderCache` — rendering;
- `MaplibreMapInteraction`, `IGISApplication` — API верхних слоёв;
- `Connection`, `SyncAdapter`, `NGWSyncService` — NGW;
- `CollectorProjectMetadata`, `CollectorProjectCompositionSync` — Collector;
- `LayerConfigUtil` — server/local render and origin config.

## Ограничения

- Нет imports из `maplibui`/`app`.
- LayerGroup index `0` — bottom.
- Track start/end flag layers не включаются.
- Collector resource type не удаляется без продуктового решения.
- Изменение public interface требует compile/manifest updates consumers.

## Диагностика

- Неверный style order: проверить model order, sibling anchor и момент создания
  MapLibre style.
- «После restart стало правильно»: проверить hot-add/deferred reload contract.
- NGW config/data issue: отделить config parsing от feature sync decision.
- Collector composition: проверить stable remote IDs/project metadata до UI.

## Проверки

Начать с `:maplib:testDebugUnitTest`; затем выбрать device smoke из manifest и
central registry.

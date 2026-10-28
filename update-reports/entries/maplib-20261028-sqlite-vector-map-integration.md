---
id: maplib-20261028-sqlite-vector-map-integration
area: Память карты и редактирование
kind: internal
at: 2026-10-28T05:20:00+01:00
title: Чтение обычных векторов из SQLite по тайлам без стартовой GeoJSON копии
why: Удержание внутренних GeoJSONVT tiles не устраняется одним уменьшением свойств; обычные редактируемые слои также должны читать только необходимые оригиналы
covers:
  - src/main/java/com/nextgis/maplib/map/SqliteVectorLayerController.java
  - src/main/java/com/nextgis/maplib/map/MapDrawable.java
  - src/main/java/com/nextgis/maplib/map/MPLFeaturesUtils.java
---

MOB-04, библиотека PR61 перед app82. Ordinary Simple/Field Rule renderers всех
шести типов геометрии используют SQLite/RTree → полный MVT8192 → штатный
CustomVectorSource13.7.1. Cold/hot/lite/style/data load не создают общий rich
Java FeatureCollection или GeoJSON source; совместимая запись cache пуста.
Raster/tracks и малые selection/edit/cursor sources сохраняют свои форматы.
Неизвестные расширенные renderers сохраняют прежний compatibility path;
нельзя выдавать эту границу за поддержку всех произвольных расширений.

Все прежние factories paint/layout/rules/pattern/icon используют те же свойства
и порядок, но получают source-layer `features`. Polygon Fill/Line принимают
только Polygon, Symbol — только inline Point anchors. Это сохраняет rule filter
и устраняет подтверждённые native query Point hits в FillLayer с теми же FID.
Не допускается просто выбросить эти hits из теста: native oracle проверяет тип
каждого raw hit до дедупликации допустимых clipped polygon fragments.

Редактор получает по одному полному styled DB original, а не clipped/quantized
тайл. Малый edit list/selected/vertex GeoJSON остаётся локальным; background
исключает текущий FID native filter. Cancel/Save возвращают SQLite source,
ID remap переносит transient exclusion, delete/undo сохраняет остальные IDs.
Подписи polygon не восстанавливают глобальную коллекцию после edit. Изначальные
координаты, атрибуты, таблицы, sync/outbox/draft, saved styles и visibility не
мигрируют и не упрощаются.

Source generation process-unique; owner/style/content/data epochs проверяются
до результата. Scope отменяется до remove/style replacement/destroy. Actual
provider predicate отличает уже подключённый SQLite source от capability:
classic bind тестов/compatibility не становится streaming по одному типу слоя.
Поздний callback не может подключить удалённую модель или затереть новый host.
Project-retirement отдельный terminal any-thread API нужен до смены активного
проекта; consuming GISApplication integration имеет собственную карточку.

Зависимости обнаружены по всем доступным owner sources и обратным цепочкам
NGW/Collector/desktop. Формат и семантика producer resources не меняются.
Генерируемый desktop source index/curated registry остаётся follow-up владельца
чужого checkout codex/roadmap-admission; UNKNOWN не является отсутствием связи.
Общий native acceptance, длительный787 benchmark и2GiB gate ещё не закрыты.
Первый native software01 сохранил FAIL и raw JSON/PNG с корректными129PolygonID
и тремя ошибочно попавшими в Fill Point anchors; после исправления нужен повтор.
Сборка или JVM PASS не доказывают скорость/полноту отображения/устранение OOM.

---
title: maplib — GIS model, storage, NGW и MapLibre
module_id: maplib
last_verified: 2026-08-15
---

# maplib — GIS model, storage, NGW и MapLibre

## Назначение

Нижняя библиотека проекта: GIS layer/data model, локальное хранение, NGW
protocol/sync decisions, MapLibre style/rendering и shared application APIs.

## Критичные области

- `MapDrawable`, `MPLFeaturesUtils`, `VectorLayerRenderCache` — rendering;
- после batch fill `MapDrawable` проверяет, что для каждого видимого vector layer
  в новом MapLibre style существуют source и style layer; при неполном apply
  выполняется один полный повтор, а completion callback до этого не публикуется;
  локальное включение ранее невидимого слоя сверяет тот же live style и при
  отсутствии source/layer загружает данные независимо от старого process-кэша;
- `LocalVectorTileProvider` / `LocalVectorTileEncoder` — ленивые MVT для
  read-only polygon/multipolygon и простого `GTPoint` с кругом и подписью;
- `MapDrawable.finishCreateNewFeature` допускает отсутствие временной edit-сессии
  после cold form recovery; если новый id отсутствует в process-local GeoJSON,
  `reloadFeatureToMaplibre` перечитывает данные слоя, а не только стили;
- `FieldStyleRule` / `MplFeatureStyleProps` — rule-based стили: layer defaults и
  merge unset ← «прочие (по умолчанию)» (зум подписей, stops, scale flags,
  opacity); per-feature `labelminzoom`/`labelmaxzoom` через text-opacity gate;
- `user-location-layer` остаётся служебным верхним overlay независимо от порядка пользовательских слоёв;
- `LayerContentProvider` разрешает активный `IGISApplication.getMap()` на каждой операции и не
  маршрутизирует треки/объекты через карту предыдущего Collector workspace;
- `MaplibreMapInteraction`, `IGISApplication` — API верхних слоёв;
- `Connection`, `SyncAdapter`, `NGWSyncService` — NGW; временно упавшие pull
  векторных слоёв повторяются отдельным проходом после остальных слоёв, а
  process-wide sync state обновляется адаптером напрямую, не только broadcast;
- `NGWResourceUrl`, `ResourceGroup.loadTargetResource` — разбор URL и точечное
  получение NGW-ресурса без загрузки всего дерева;
- `CollectorProjectItem`, `CollectorProjectMetadata`,
  `CollectorProjectCompositionSync` — normalized Collector composition;
- `NGWRasterLayer` хранит style identity, отдельный parent extent id и
  project-origin metadata для server-rendered Collector styles;
- `NGWVectorLayer` применяет для managed Collector-слоя проектный editable-флаг
  вместе с исходящим направлением sync; обычные слои сохраняют общий
  `is_editable` gate; managed HTTP 404 не меняет тип слоя, а полная NGW identity
  сохраняется отдельно для восстановления частичной конфигурации;
- новый обычный `VectorLayer` из ручного создания или локального файла по
  умолчанию редактируем; `GeoJSONUtil` принимает WGS 84 без `crs`, `CRS84`,
  распространённые URN/OGC URL для EPSG:4326 и поддерживаемые записи EPSG:3857;
  bulk-write PRAGMA настраиваются до начала SQLite-транзакции и внутри неё не
  повторяются, что обязательно для Android 16;
- `CoordinatePointParser` потоково читает KML `coordinates` и GPX
  `wpt`/`rtept`/`trkpt`; `CoordinatePointLayerImporter` создаёт из них один
  редактируемый точечный слой WGS 84 с порядком, источником и доступными
  name/time/elevation, используя SQLite-транзакции не более 250 точек;
- `VectorLayer.fromJSON()` может восстановить R-tree без сохранения
  недочитанного конфига подкласса;
- `VectorLayer.feature_label_field` хранит поле отображаемого имени объекта
  отдельно от renderer label и единообразно обслуживает identify/UI;
- `MultiPolygonGeometryRepair` проверяет и исправляет невалидную топологию
  `GeoMultiPolygon` через JTS, сохраняя один feature, CRS и полигональные части;
  при отсутствии CRS контейнера восстанавливает его из дочерней геометрии;
- `LocationTrackFilter` и Android-независимый `LocationTrackFilterCore`
  обслуживают трек и обход одним accuracy-aware pipeline: движение до 160 км/ч,
  проверка качества/возраста/дистанции/ускорения и буфер из трёх точек для
  одиночных выбросов;
- `LocationProviderArbiter` оставляет Network резервным источником, но не смешивает
  его точки со свежим пригодным GPS-потоком: fallback возвращается через 12 секунд;
- `StakeoutGeometryTarget` один раз индексирует приватную Web Mercator-копию
  точки/линии/границы полигона, а каждый fix возвращает ближайшую WGS84-точку,
  эллипсоидальное расстояние и азимут; `StakeoutGuidancePolicy` выбирает
  дистанционную звуковую зону с гистерезисом для GPS/mock precision fix;
- `GpsEventSource` даёт owner-based high-frequency lease для foreground-выноса,
  не меняя сохранённые пользовательские параметры обычного местоположения;
- `LayerGroup.createLayerStorage()` атомарно резервирует UUID-каталог; параллельные
  задачи одной Collector-партии не могут разделить SQLite-таблицу. Первый
  неуспешный batch insert аварийно завершает и откатывает неполный слой;
- Collector insertion сохраняет «Мои треки» последним во внутреннем
  `LayerGroup`, то есть наверху UI-списка;
- `LayerConfigUtil` — server/local render and origin config.

## Ограничения

- Нет imports из `maplibui`/`app`.
- LayerGroup index `0` — bottom.
- Точечный `local_vector_tiles` поддерживает только простой круговой marker и
  подпись из одного поля/фиксированного текста; rule/icon/template/editable
  варианты используют classic fallback.
- Автоматический topology repair разрешён только для точного типа слоя
  `GTMultiPolygon`; простой `GTPolygon` и линейные типы не обрабатываются.
- Track start/end flag layers не включаются.
- GPS-фильтр не имеет профилей движения: рабочий distance-cap равен `55 м/с`
  с запасом над 160 км/ч, а reported speed свыше `100 м/с` считается мусором.
  Разрыв более 30 секунд обязан выгрузить валидный буфер до сброса состояния.
- При включённых GPS и Network пригодный GPS имеет приоритет; Network остаётся
  стартовым резервом и снова принимается через 12 секунд без пригодного GPS.
- Вынос поддерживает только Point/MultiPoint, LineString/MultiLineString и
  Polygon/MultiPolygon в EPSG:4326/3857. Для полигона расстояние всегда идёт до
  ближайшей внешней или внутренней границы, даже если fix находится внутри.
- Collector resource type не удаляется без продуктового решения.
- Collector style support ограничена уже известными `Connection` классами
  `qgis_vector_style` и `qgis_raster_style`; новые resource classes не
  добавляются в `Connection.java` без отдельного решения.
- URL ресурса принимает только HTTP(S), не содержит credentials/fragment и
  заканчивается на `/resource/<positive-id>`; server path до `/resource` сохраняется.
- Локальный GeoJSON поддерживает только WGS 84/EPSG:4326 и Web Mercator/EPSG:3857;
  другие системы координат отклоняются без попытки угадать преобразование.
- Упрощённый импорт KML/GPX считает координаты WGS 84 и не сохраняет исходные
  линии, полигоны, route/track topology, стили, ExtendedData, вложения, KMZ или
  KML `gx:Track`: каждый найденный поддерживаемый tuple становится точкой.
- Изменение public interface требует compile/manifest updates consumers.
- Debug `BuildConfig.VERSION_NAME` должен совпадать с Lisa Debug, release — с
  Lisa/Belka Release. На AGP 9.x library `buildTypes.versionName` не
  используется; variant coupling проверяет root script
  `tools/verify-apk-version-matrix.ps1`.

## Диагностика

- Неверный style order: проверить model order, sibling anchor и момент создания
  MapLibre style.
- Rule-based подписи игнорируют зум/opacity/scale: проверить, что слойные
  дефолты взяты из «прочих», props после merge, и SymbolLayer min/max сброшены;
  пустой зум/scale=false/opacity=255 в категории наследуются из other.
- Zoom-stops «не действуют»: кривая слоя общая из other; нужен флаг scale
  (на other или унаследованный); data-driven scale — outer switchCase.
- Курсор перекрывается треком/вектором: проверить, что `user-location-layer`
  последний в live style после cold/lite/hot reload.
- Объект после cold form recovery выбирается, но не виден до restart: проверить
  `MapLibre feature missing after form Save` и следующий полный data reload слоя.
- Мультиполигон не сохранился: проверить безопасный HyperLog-код
  `MultiPolygon geometry repair failed`; исходная геометрия должна остаться в
  редакторе, а координаты в журнал не записываются. Причина
  `converted repair is empty or invalid` после ручного MapLibre-редактирования
  является регрессией CRS.
- «После restart стало правильно»: проверить hot-add/deferred reload contract.
- Объекты выбираются, но видимый слой пуст после batch fill: найти
  `MapLibre post-load verification`; отсутствие source/style layer должно
  вызвать один полный reload, а не считаться успешным завершением.
- Слой был невидим при import и не появился после локального включения: проверить
  `MapLibre visibility enable requires data reload`; наличие записи в
  `sourceFeaturesHashMap` не заменяет source/layer в текущем live style.
- Пустой список треков после project switch: проверить строку
  `LayerContentProvider bound to active map path=...` и соответствие пути активному workspace;
- Трек/обход замер на скорости: проверить `LocationTrackFilter` причины вместе с
  `provider`, затем итоговые `filterPassed/filterDropped/filterChordDropped/filterGaps`
  и `networkSuppressed`.
  Для валидного движения до 160 км/ч не должно быть каскада `drop:speed_dist`.
- Неверное расстояние выноса: проверить CRS исходной геометрии и ближайшую точку
  `StakeoutGeometryTarget`; пользователю нельзя выдавать плоское расстояние 3857.
- NGW config/data issue: отделить config parsing от feature sync decision.
- `ExternalDatabaseError`/HTTP 5xx на feature pull: проверить журнал
  `deferred transient retry`; один успешный второй проход является ожидаемым
  восстановлением, а исчерпание очереди даёт сообщение о временной
  недоступности сервера или внешней БД.
- Импорт по URL: сначала проверить parser/server/account, затем response code,
  тип ресурса и `data.read`/`data.write` permissions.
- Локальный GeoJSON ошибочно отклонён как неподдерживаемый: проверить значение
  `crs.properties.name`; WGS 84 может быть без `crs`, как `CRS84`, `EPSG:4326`,
  EPSG URN или OGC definition URL. Другой EPSG-код действительно не поддержан.
- Импорт дошёл до SQLite, но сообщил `Safety level may not be changed inside a
  transaction`: проверить, что `DatabaseContext.getDbForLayer()` вызван до
  `beginTransaction()`, а внутри цикла используется уже полученный `dbTx`.
- Созданный вручную слой нельзя редактировать: проверить сохранённый
  `is_editable`; новый обычный `VectorLayer` должен записывать `true`.
- Collector composition: проверить stable remote IDs/project metadata,
  `localPhysicalLayers`, `repairOrigin` и `identityConflict` до UI; при
  неоднозначной паре `account + remoteId` apply должен быть пропущен.
- Пропавший style из Collector: проверить resource `cls`, style remote id,
  parent extent id и authenticated render tile URL; style не должен попадать в
  edit pipeline.
- «Нет редактируемых слоёв» в Collector: сверить item `editable`,
  `managed_by_project` и исходящее направление sync.

## Проверки

Начать с `:maplib:testDebugUnitTest`; затем выбрать device smoke из manifest и
central registry.

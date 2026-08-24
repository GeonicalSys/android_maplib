---
title: maplib — GIS model, storage, NGW и MapLibre
module_id: maplib
last_verified: 2026-08-24
---

# maplib — GIS model, storage, NGW и MapLibre

## Назначение

Нижняя библиотека проекта: GIS layer/data model, локальное хранение, NGW
protocol/sync decisions, MapLibre style/rendering и shared application APIs.
Для выпуска `3.1.2.16` диагностический release `BuildConfig.VERSION_NAME` равен
`3.1.2.16`; отдельный Lisa Debug использует `3.1.2.11`. Оба значения проверяются
вместе с соответствующим APK consuming app.

## Критичные области

- `MapDrawable`, `MPLFeaturesUtils`, `VectorLayerRenderCache` — rendering;
- MapLibre Android `13.0.2` подключён через явный OpenGL-артефакт
  `android-sdk-opengl`; generic `android-sdk` этой версии использует Vulkan и
  не совместим с частью устройств без рабочего Vulkan-драйвера;
- горячее обновление style применяет вычисляемые свойства только к независимому
  snapshot из `VectorLayerRenderCache` в общей последовательной очереди; worker
  не изменяет опубликованные в MapLibre `Feature.properties`;
- после batch fill `MapDrawable` проверяет, что для каждого видимого vector layer
  в новом MapLibre style существуют source и style layer; при неполном apply
  выполняется один полный повтор, а completion callback до этого не публикуется;
  локальное включение ранее невидимого слоя сверяет тот же live style и при
  отсутствии source/layer загружает данные независимо от старого process-кэша;
- полный style reload освобождает прежний GeoJSON snapshot до подготовки нового,
  а после `setStyle` удаляет ссылки на detached MapLibre source/layer wrappers;
  это ограничивает пиковую Java/native память больших Collector-проектов и не
  сохраняет данные удалённых слоёв между reload;
- `LocalVectorTileProvider` / `LocalVectorTileEncoder` — ленивые MVT для
  read-only polygon/multipolygon и простого `GTPoint` с кругом и подписью;
  loopback-сервер допускает не более двух одновременных сборок и 16 ожидающих
  запросов, сериализует тяжёлые тайлы одного слоя, отменяет очередь предыдущего
  поколения карты и отвечает `503` при перегрузке либо остатке heap менее 64 МБ;
- `LayerIdentifyPolicy` оставляет выключенные классические слои вне identify,
  но разрешает просмотр локальных атрибутов выключенного слоя, настроенного на
  `local_vector_tiles`, не включая его отрисовку;
- `MapDrawable.finishCreateNewFeature` допускает отсутствие временной edit-сессии
  после cold form recovery; если новый id отсутствует в process-local GeoJSON,
  `reloadFeatureToMaplibre` перечитывает данные слоя, а не только стили;
- `MapDrawable.onTouch` фиксирует живые `MapLibreMap`, `MapView` и host context в
  начале события и отбрасывает поздний gesture после `onDestroyView`, не вызывая
  identify/edit API уничтоженного native renderer;
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
- инкрементальный NGW pull работает как bulk-операция: построчные
  insert/update/delete broadcast подавлены, после всех SQLite-изменений R-tree
  перестраивается и карта перезагружается один раз;
- `NGWResourceUrl`, `ResourceGroup.loadTargetResource` — разбор URL и точечное
  получение NGW-ресурса без загрузки всего дерева;
- `CollectorProjectItem`, `CollectorProjectMetadata`,
  `CollectorProjectCompositionSync` — normalized Collector composition;
- `NGWRasterLayer` хранит style identity, отдельный parent extent id и
  project-origin metadata для server-rendered Collector styles;
- `NGWVectorLayer` применяет для managed Collector-слоя проектный editable-флаг
  вместе с исходящим направлением sync; обычные слои сохраняют общий
  `is_editable` gate; возможность изменить направление sync использует тот же
  owning policy и не зависит от текущего направления, чтобы слой можно было
  вернуть из server-only в двусторонний режим; managed HTTP 404 не меняет тип
  слоя, а полная NGW identity сохраняется отдельно для восстановления частичной
  конфигурации; первый сбой чтения или записи объекта при полном fill сохраняет
  в HyperLog имя слоя, remote id, нулевой индекс объекта в исходном массиве,
  класс/сообщение ошибки и ограниченный стек без координат и значений полей;
- при schema/config/SQLite mismatch `NGWVectorLayer` передаёт через
  `IGISApplication.scheduleNgwLayerRebuildAfterSchemaMismatch()` устойчивый
  fingerprint причины, чтобы UI-orchestrator мог ограничить повтор тяжёлого
  rebuild без смешивания разных причин;
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
- `GeometryRTree` сериализует публичные операции чтения/изменения, а
  `VectorLayer` не принимает feature-notify во время bulk/rebuild. Ошибка
  отдельного receiver логируется и не завершает главный Android-поток;
- `VectorLayer.feature_label_field` хранит поле отображаемого имени объекта
  отдельно от renderer label и единообразно обслуживает identify/UI;
- `MultiPolygonGeometryRepair` проверяет и исправляет невалидную топологию
  `GeoMultiPolygon` через JTS, сохраняя один feature, CRS и полигональные части;
  при отсутствии CRS контейнера восстанавливает его из дочерней геометрии, а
  контур короче трёх различных точек возвращает отдельным результатом до repair;
- LineString/Polygon и их Multi-варианты принимают один стартовый узел и
  последующие tap-вставки после выбранной вершины; midpoint-вставка доступна и
  линейке. Выбранная вершина красная, следующая внутри той же части/кольца и
  соединяющий сегмент оранжевые; у открытого конца линии направления нет, а
  замкнутое кольцо указывает с последней вершины на первую. GeoJSON-конвертер
  явно замыкает кольца при восстановлении скетча;
- WKT round-trip `GeoPolygon` и `GeoMultiPolygon` разбирают кольца и отдельные
  polygon members по уровню скобок, не превращая внешнее кольцо в дублирующую
  внутреннюю дырку и не теряя следующие части мультиполигона;
- холодное продолжение обхода не заменяет MapLibre edit feature геометрией без
  служебных свойств: заливка и красный контур восстанавливаются из одного source,
  а скрытый vertex cache после Stop снова публикует редактируемые вершины;
  общий edit fill включается только для Polygon/MultiPolygon и явно снимается при
  восстановлении LineString/MultiLineString; привязка ждёт, пока все edit sources
  принадлежат текущему style, а общий entrypoint редактирования не разыменовывает
  отсутствующие или оставшиеся от заменённого style source;
- `LocationUtil.formatAreaHectares()` переводит площадь линейки из квадратных
  метров в гектары и сохраняет читаемую точность для площадей меньше гектара;
  `MapDrawable` публикует и восстанавливает геометрию активного MapLibre
  `MeasurmentLine`, чтобы app/maplibui-панель Undo/Redo работала с реально
  отображаемыми точками, а не с legacy overlay;
  редактор MultiPolygon отклоняет добавление второй части, не изменяя уже
  существующие многосоставные геометрии и отверстия при их загрузке;
- `LocationTrackFilter` и Android-независимый `LocationTrackFilterCore`
  обслуживают трек и обход одним accuracy-aware pipeline: движение до 160 км/ч,
  проверка качества/возраста/дистанции/ускорения и буфер из трёх точек для
  одиночных выбросов;
- `LocationProviderArbiter` оставляет Network резервным источником, но не смешивает
  его точки со свежим пригодным GPS-потоком: fallback возвращается через 12 секунд;
- выход `LocationTrackFilter` остаётся только набором принятых координат; звуковой
  health-контроль в `maplibui` использует отдельный поток пригодных фиксов без
  порога перемещения и не зависит от выдачи фильтра или insert/commit. Поэтому
  неподвижное устройство продолжает сигнализировать об активной записи, а
  прекращение свежих координат гасит сигнал; сам `maplib` не выбирает audio
  stream, alarm-stream/vibration fallback принадлежит владельцу service в
  `maplibui`; отзыв Android location permission
  обрабатывает владелец foreground-service в `maplibui`;
- `StakeoutGeometryTarget` один раз индексирует приватную Web Mercator-копию
  точки/линии/границы полигона, а каждый fix возвращает ближайшую WGS84-точку,
  эллипсоидальное расстояние и азимут; `StakeoutGuidancePolicy` выбирает
  дистанционную звуковую зону с гистерезисом для GPS/mock precision fix;
- `GpsEventSource` даёт owner-based high-frequency lease для foreground-выноса,
  не меняя сохранённые пользовательские параметры обычного местоположения;
- `LayerGroup.createLayerStorage()` атомарно резервирует UUID-каталог; параллельные
  задачи одной Collector-партии не могут разделить SQLite-таблицу. Первый
  неуспешный batch insert аварийно завершает и откатывает неполный слой;
- `DatabaseContext` разрешает `layers.db` по родительской цепочке самого слоя,
  а `MapContentProviderHelper` открывает БД рядом со своим map-файлом. Фоновый
  fill, переживший смену активного проекта или процесса, не может продолжить
  транзакцию в БД другого workspace;
- `NgwFeatureGeometryValidator` проверяет полученные от NGW Polygon и каждый
  member MultiPolygon через JTS `IsValidOp`. Это сохраняет прежнюю семантику
  коллекции, но убирает квадратичный перебор пар сегментов на контурах с
  десятками тысяч координат;
- Collector insertion сохраняет «Мои треки» последним во внутреннем
  `LayerGroup`, то есть наверху UI-списка;
- `LayerConfigUtil` — server/local render and origin config.
- raster MBTiles validation/storage: `MbTilesInfo` проверяет SQLite schema,
  metadata, image format и integrity, `TMSLayer` публикует файл только после
  sync + atomic rename, а `MapDrawable` подключает его через `mbtiles:///`;
- legacy tile conversion math: OSM row переводится в TMS/MBTiles, bounds
  вычисляются в Web Mercator tile matrix, raster format определяется по magic
  bytes без декодирования каждого изображения.

## Ограничения

- Нет imports из `maplibui`/`app`.
- MapLibre backend должен оставаться согласованным с `maplibui` и `app`:
  `org.maplibre.gl:android-sdk-opengl:13.0.2` во всех трёх модулях.
- LayerGroup index `0` — bottom.
- MBTiles local TMS path принимает только raster PNG/JPEG/WEBP с обязательными
  `tiles`/`metadata`; vector MBTiles и повреждённая SQLite отклоняются.
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
- Crash `No Vulkan compatible GPU found` означает возврат generic
  `org.maplibre.gl:android-sdk` либо Vulkan-артефакта; production использует
  `android-sdk-opengl` и не должен инициализировать Vulkan surface.
- Rule-based подписи игнорируют зум/opacity/scale: проверить, что слойные
  дефолты взяты из «прочих», props после merge, и SymbolLayer min/max сброшены;
  пустой зум/scale=false/opacity=255 в категории наследуются из other.
- Zoom-stops «не действуют»: кривая слоя общая из other; нужен флаг scale
  (на other или унаследованный); data-driven scale — outer switchCase.
- Курсор перекрывается треком/вектором: проверить, что `user-location-layer`
  последний в live style после cold/lite/hot reload.
- Объект после cold form recovery выбирается, но не виден до restart: проверить
  `MapLibre feature missing after form Save` и следующий полный data reload слоя.
- После успешного Save объект остаётся выбранным или видны edit sources: app host
  обязан сначала завершить `cancelFeatureEdit(false)`, затем перейти в normal mode
  и вызвать view unselect; `MapDrawable` не владеет политикой нижних панелей.
- Crash из `MapDrawable.onTouch` после `MapLibreMapView.onDestroy`: проверить, что
  host очистил map/view ссылки, а touch guard завершил событие до
  `queryRenderedFeatures`.
- Мультиполигон не сохранился: проверить безопасный HyperLog-код
  `MultiPolygon geometry repair failed`; исходная геометрия должна остаться в
  редакторе, а координаты в журнал не записываются. Причина
  `converted repair is empty or invalid` после ручного MapLibre-редактирования
  является регрессией CRS.
- Импорт NGW-слоя остановился на объекте: найти `NGW feature fill failed` и
  сопоставить `layer`, `res` и нулевой `featureIndex`; журнал содержит класс,
  сообщение и ограниченный стек, но не геометрию и не значения полей.
- «После restart стало правильно»: проверить hot-add/deferred reload contract.
- Объекты выбираются, но видимый слой пуст после batch fill: найти
  `MapLibre post-load verification`; отсутствие source/style layer должно
  вызвать один полный reload, а не считаться успешным завершением.
- Слой был невидим при import и не появился после локального включения: проверить
  `MapLibre visibility enable requires data reload`; наличие записи в
  `sourceFeaturesHashMap` не заменяет source/layer в текущем live style.
- Выключенный `local_vector_tiles` не попал в identify: проверить сохранённый
  `layer_origin.render_mode` и `LayerIdentifyPolicy`; видимость слоя не должна
  включаться ради чтения атрибутов.
- После панорамирования/перезагрузок карты растут тормоза или возникает OOM в
  `LocalVectorTileProvider.buildTile`: worker должен называться только
  `LocalVectorTileWorker-1/2`; трёхзначный номер `pool-*-thread-*` означает
  возврат неограниченного пула. Проверить также throttled-счётчик и heap headroom.
- Пустой список треков после project switch: проверить строку
  `LayerContentProvider bound to active map path=...` и соответствие пути активному workspace;
- Трек/обход замер на скорости: проверить `LocationTrackFilter` причины вместе с
  `provider`, затем итоговые `filterPassed/filterDropped/filterChordDropped/filterGaps`
  и `networkSuppressed`.
  Для валидного движения до 160 км/ч не должно быть каскада `drop:speed_dist`.
- Курсор карты движется, но у завершённого трека `raw=0` и `filterInput=0`:
  фильтр вообще не получил координат; проверять фактический запуск
  `TrackerService`, а не ослаблять accuracy/speed ограничения.
- Неверное расстояние выноса: проверить CRS исходной геометрии и ближайшую точку
  `StakeoutGeometryTarget`; пользователю нельзя выдавать плоское расстояние 3857.
- NGW config/data issue: отделить config parsing от feature sync decision.
- Crash `notify_insert → GeometryRTree.chooseLeaf → GeoEnvelope.width`: проверить,
  что incremental pull вошёл в bulk-режим, в журнале нет построчных notify, а
  после pull есть ровно одна строка `spatial cache rebuilt` с числом строк SQLite.
- `LinkedTreeMap` из `reloadVectorLayerStyleProps`: style worker не должен брать
  live `sourceFeaturesHashMap`; допустим только независимый render-cache snapshot
  либо полный data reload при cache miss.
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
- NGW показывает небольшое число объектов, но полный fill зависает на
  MultiPolygon: считать координаты/части, а не только features; проверка серверной
  геометрии должна идти через `NgwFeatureGeometryValidator`, без legacy
  `GeoLinearRing.intersects()`.
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
- Направление самопроизвольно стало server-only после просмотра свойств слоя:
  проверить, что UI игнорирует начальный callback выбора и использует
  `isSyncDirectionConfigurable()`, а не общий `isEditable()`.

## Проверки

Начать с `:maplib:testDebugUnitTest`; затем выбрать device smoke из manifest и
central registry.

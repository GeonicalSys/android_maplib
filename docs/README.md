---
title: maplib — GIS model, storage, NGW и MapLibre
module_id: maplib
last_verified: 2026-10-28
---

# maplib — GIS model, storage, NGW и MapLibre

## Горячая запись PBF и отменяемый допуск к IO — MOB-04, 28 октября2026

`LocalVectorTileDiskCache` сохраняет прежние ключи и полные PBF, byte/entry и
1024-file bounds, временный файл/атомарный rename и SQLite как источник данных.
Общий индекс canonical root запрашивается при каждой операции, поэтому HTTP
экземпляры на каждый запрос и старые providers после вытеснения root ledger
не расходятся в учёте. Registry ограничен8roots, каждый inventory держит не
более1025metadata до trim. Метаданные/recency хранятся в памяти, сортированный
набор обслуживается инкрементально; обычный put не читает и не сортирует каталог.
Первичное чтение и обнаруженное изменение каталога требуют reconciliation;
проверка имени без повторной regex compilation, stat метаданных на файл один раз.
Ожидаются только штатные same-process writers этого private namespace;
удаление cache ОС безопасно, произвольный внешний конкурентный writer не обещан.

Native provider передаёт дешёвый current guard внутрь put: проверки до/после
lock, перед write/rename и после публикации не меняют source/content/style keys.
HTTP получает новый общий индекс через прежний3-argument API и сохраняет
свои внешние current/key проверки; socket probe1мс не вводится в cache lock.
При IO/iterator failure кэш остаётся optional miss, готовый полный тайл не
отклоняется только из-за недоступного производного файла. Никакой новой
долговременной cache policy, миграции проекта или background thread нет.

`SqliteVectorTileSession` получает общий отменяемый допуск2 до отправки в shared
IO dispatcher. Отменённые ожидающие SDK jobs не создают blocking payload каждый.
Lease освобождается на IO после настоящего unwind и до Main, а predispatch cancel
имеет идемпотентный fallback. Retry не держит permit; некооперативная активная
работа сохраняет permit до выхода. SDK определяет нужные tiles, включая parent/
fade/adjacent; age/zoom/density не используются для потери текущего результата.
Pending SDK jobs и отдельные HTTP/line-label queues этим не ограничиваются.

Новые25 cache/7 admission tests и прежние content/lifecycle/gate проверки —
техническая приёмка, не новый измеритель и не реальные smartphone timings.
Владелец просит только обычный APK, сам установит его на телефон; ADB/native/
Full/публикация в этом шаге не выполняются. Карточка:
`maplib-20261028-tile-cache-queue`; central contract:
`INV-LOCAL-TILE-CACHE-ADMISSION`.

## Подробный локальный сбор ресурсов — opt-in, 2026-10-28

`RenderResourceProfiler` по умолчанию выключен. Приватный app adapter явно
вызывает `start(sessionId, version, build, revision, Sink)`; `Sink.offer` обязан
только неблокирующе принять неизменяемую запись. Библиотека не создаёт файлов,
не вызывает SDK, не делает новых SQL/memory reads и не управляет renderer/cache
policy. Публичный `event(kind, numericFields)` принимает ограниченные числовые
метрики и boolean, отбрасывает строки, координатные/URI/path/object-ID поля,
невалидные числа и лишние поля. Сбор не добавляется в Sentry-журнал.

Каждый `MapRuntimeDiagnostics.SqliteScope` получает независимый token, включая
legacy-unobserved запросы: прежние cap16/cap32/10s throttle остаются прежними,
но не ограничивают новый учёт. Хранятся до 256 слоёв и 512 подробных active
tokens. Остальные запросы продолжают увеличивать общие и overflow-агрегаты;
потеря подробных записей, отказ/ошибка sink и неизвестные слои явны.
Requests, outcomes, work counts и суммы фаз учитываются до передачи raw записи
и не зависят от заполненности очереди app writer. Никакая ошибка диагностики
не должна превращать тайл в failure или успешный неполный результат.

Wall clock — `SystemClock.elapsedRealtimeNanos`, включая suspend; CPU clock —
`Debug.threadCpuTimeNanos` только между точками одного `Process.myTid`.
Main→IO→Main интервалы имеют известное wall time и неизвестный CPU, а не
разность часов разных потоков. Суммы elapsed разных запросов перекрываются
и не являются временем сцены; histograms фаз описывают завершённые segments,
а не p95 всего запроса. CPU включает overhead диагностических clocks/accounting.
Process/SDK memory samples не являются удержанной памятью конкретного слоя.

`stop(reason)` выключает только наблюдение и возвращает авторитетную bounded
summary; `snapshot()` даёт такой же background checkpoint. App сохраняет summary
отдельно от raw очереди. Незавершённые scopes остаются `active_at_snapshot`,
не объявляются cancelled/finished, и после stop не меняют готовую summary.
Summary содержит полные global totals/histograms, compact numeric rows всех
отслеживаемых слоёв и точные phase arrays для top32 по CPU/elapsed; число слоёв
без phase detail явно указано. Индивидуальные raw requests содержат все phase
arrays, если очередь их сохранила. Это ограничение детализации не теряет общий
знаменатель. Долговременный кеш и алгоритмы/данные карты этим шагом не меняются.

## Ограниченное чтение SQLite originals — MOB-04, 2026-10-28

Final allmaplib gate:750PASS/132classes/0fail/error/skip59s, runtime unchanged;
71focused — поднабор. Independent dense1403/11.7z strict full-scene gate дал
843d32.45–34.09s→6039 13.68–15.65s→D10110.33–12.27s, late map ROI MAE0.
One processcold run/build/diskretained, не FPS/все стили/2–3s target. TrackLayer
saved/active snapshots остаются полным GeoJSON и вне ordinary-vector tile path;
длинные треки и их RAM не измерены.

После actual tablet phase evidence добавлен `SqliteVectorFeatureReader`:
attributes-only cursor на32 прежних eligible IDs с CASE order и один ленивый
compiled BLOB statement на visit. На API28+ отдельный geometry-only cursor
держит не более32 полных BLOB до16KiB в явном1MiB CursorWindow; API26/27,
большие BLOB и safely unsupported driver используют прежний полный PFD путь.
512KiB — предел small payload, не общий прирост RAM: SQLite sorter buffers,
Java buffer и decoded original учитываются отдельно. Сохранены все typed
поля/NULL, оригинальные geometry, hidden/excluded masks, порядок и generation
guards. Cursor/statement закрываются при отмене/ошибке; missing/extra IDs или
bad BLOB не дают успешного partial tile/cache. Direct/line-label visitors
получают совместимый default Access seam; editor/sync API не меняются.
Нет global features/GeoJSON cache или simplification. Typed phase boundaries
сохранены для сравнения с private843d. Source RED/GREEN и native границы —
[карточка](../update-reports/entries/maplib-20261028-sqlite-batched-original-reads.md).

Small-BLOB window — отдельная невыпущенная доработка после actual6039 read
evidence:3051 originals/2,076,156B/PBF356,630B сохранились, BLOB stage всё ещё
занял1458.11ms. Nested CASE по typeof/length не материализует большой BLOB или
TEXT в window; исходные bytes не обрезаются. Отмена между queries, exact
ID/order/length/cardinality и cleanup errors проверяются до успеха всего tile.
Mid-window unsupported driver переводит только текущий и оставшиеся IDs на
PFD, без повторной доставки. Query/prefill/refill/getBlob относятся к BLOB_READ;
inline_blobs/pfd_blobs считают полные buffers перед decode, BLOB_BYTES остаётся
логическим объёмом потреблённых originals, не объёмом prefetch при отмене.
Ordinary D101 normalall3 PASS62s/matrixPASS/71focused PASS. ActualT40/API30
сохранил1350inline+2PFD/1352consumed originals в10terminal representatives,
без read/window/SQLite/fatal markers. Same9counter cohorts121/110features
сохранили BLOB/PBF/label counts: BLOB83.57→13.28ms и134.11→15.37ms против6039;
163line originals42.24→8.59ms. Heavy3051 detail D101 отсутствует в snapshots,
процесс-глобальные суммы/пики неизвестны; IOqueue всё ещё до3.374s в observed
scope. Full native paint/labels/PSS/newextent проверяются отдельно.
[Новая карточка](../update-reports/entries/maplib-20261028-sqlite-small-blob-window-reads.md).

## Локальные SQLite phase timings — MOB-04, 2026-10-28

Для разбора задержки полного содержимого на T40 Plus/API30 добавлен отдельный
`MapRuntimeDiagnostics.SqliteScope`. Локальный logcat tag `LISA-SqlitePhase`
разделяет ожидание IO/слоя/gate/cache, RTree query, чтение атрибутов/BLOB,
декодирование original geometry, style/clip/anchors/PBF/cache write и возврат
на Main. Записи содержат только числовые layer/epoch/content IDs, zoom без
tile X/Y, длительности и counts; URI, координаты, значения полей и exception
message не передаются. Это не новый sender событий GlitchTip.

Наблюдаются максимум16 активных scopes и32 layer summaries; один observer
использует существующий bounded writer. Text log — не чаще10s/слой; suppressed
финал получает отложенный flush. Потеря наблюдений при saturation учитывается
для слоёв с snapshot; unobserved-only layer без первого snapshot пока может
не опубликовать counters, поэтому reported-layer суммы не process-global.
Завершённые inactive summaries переиспользуются после throttle interval.
Main не читает память/БД/MapLibre ради diagnostics. Ошибки clock/writer/watch
не меняют исходные bytes, cancellation или currentness checks.

`returned_to_sdk` означает только Java callback return, не native acceptance
и не готовность всех объектов/подписей. `provider_returned`, `cancelled`,
`failed` и активное ожидание различаются. Старый HTTP queue counter не выдаётся
за очередь SQLite. Focused39 PASS и ordinary all3 build57s/version matrix PASS
дали private843d. В нём один startup tile3051 объектов занял9.909s, включая
5.281s attributes и1.548s BLOB; другие tiles ждали IO до3.432s. Это подтверждённый
application read hot path на11.7, не готовность native/full contents или закрытие
reported12.1 delay. Новая bounded-read правка ещё требует своего tablet route.
См. [точный scope](../update-reports/entries/maplib-20261028-sqlite-phase-diagnostics.md).

## SQLite tile buffer parity — MOB-04, 2026-10-28

Candidate query и full-precision clipping нового native SQLite route используют
128px/512px tile, как actual classic GeoJsonSource official13.7.1; прежний64px
запас мог терять translated edge polygons. Две strict-backend/unit protobuf
регрессии сохраняют full vertices/holes/props на всех четырёх краях. Это
восстановление полноты, не memory reduction; candidate envelope больше, а
произвольные большие style offsets и native edge pixels отдельно не приняты.
См. [точный контракт/SDK proof](sqlite-vector-tile-buffer.md).

## Потоковые viewport line labels — MOB-04, 2026-10-28

Production line-label worker использует `visitVisibleLines` и подготовленный
`OriginalPlanner`: каждый full DB original сразу превращается в label candidate,
не собираясь в List всей видимой геометрии. Candidates содержат только props/id
и Point anchor; Label не удерживает исходный Feature. Mercator viewport готовится
один раз, exact clipping/axial direction/nearest anchor/upright/dateline/FID
семантика сохраняется. DB координаты и source geometry не изменяются.
Compatibility `readVisibleLines` и fragment-merging List planner оставлены;
streaming принимает полные originals, не native tile fragments.
Shared gate/lease и camera/data/owner/UI fences сохранены. Новые6tests проверяют
в том числе ownership без GC timing; compilation/native/performance требуют
fresh общей проверки, прежний26PASS checkpoint её не заменяет.
См. [точный scope](../update-reports/entries/maplib-20261028-streamed-line-labels.md).

## SQLite native vector tiles — MOB-04, 2026-10-28

`SqliteVectorTileProvider` обслуживает полные PBF для CustomVectorSource прямо
из RTree/SQLite, без общего startup GeoJSON и коллекции всех styled features.
Public Backend/Request фиксируют canonical owner/source/content/style/excludedFID
generation; `close()` terminal и nonblocking. Native bridge владеет Main/IO
coroutine delivery, backend — единственным shared с HTTP MVT gate, полными
derived PBF и отменой. `isCurrent()` и feature loops используют дешёвые epochs;
renderer JSON проверяется на границах load. Free-only policy не меняется.
`queryForNativeRender` не принимает перестраиваемый/отменённый неполный RTree:
IDs читаются под spatial writer lock, COUNT validation один раз на tree/data/size
generation выполняется вне lock. Weak marker не удерживает старое дерево.
After-commit data generation защищает до UI broadcast; при неготовности bridge
повторяет запрос вместо successful partial PBF. COUNT не доказывает правильность
всех IDs в историческом индексе с тем же количеством записей.

Generic encoder использует native extent8192, scalar paint/rule/label/layout
properties и text/icon offset pairs через public `NativeVectorTilePropertyCodec`
X/Y constants; factories должны собрать typed arrays `semiliteral` expressions.
Unknown nested/nonfinite payload вызывает typed unsupported failure, не тихую
потерю данных. JSON null становится absence только в известном coalesce
renderer contract. Metadata-only `canServe` проверяет все шесть geometry types,
exact Simple/Field Rule renderers и standard effective styles, без data scans.

Clipper сохраняет multipart/holes/disconnected pieces, не добавляя simplification
или чтения geom_zoom. Polygon label использует прежний classic anchor до clip
и один half-open owner tile. Main geometry/inline polygon anchors имеют
`features` source layer, `_label_anchor` выделяет label points. Line labels
читают viewport originals; `loadOriginalFeature`/`readOriginalFeature` дают
полную DB/styled geometry редактору. ORDER=FID стабилен между tiles. Host
владеет fast editedFID filters, content/style source recreation и fallback.

Прежний encoder остаётся compatibility foundation; его Rule-property loss
не следует включать для поддерживаемых слоёв вместо нового route. Раздел
compact GeoJSON ниже описывает сохранённый compatibility path. Новая схема
прошёл focused26JVM/Robolectric tests и main/test compile, но ещё требует
native styles/edit/full-content и benchmark cold/
warm/pan/pinch на том же787: скорость и2GiB/OOM безопасность не объявлены PASS.
См. [exact scope/ограничения](../update-reports/entries/maplib-20261028-sqlite-native-vector-tiles.md).

## Компактный native GeoJSON — MOB-04, 2026-10-28

Профиль проекта787 подтвердил основное удержание в копиях property maps
внутреннего GeoJSONVT index, а не необходимость упрощать исходную геометрию.
`NativeFeaturePayload` строит отдельные native-копии за один проход, сохраняя
rich Java Feature для редактора, исходные geometry/bbox/IDs и неизвестные поля.
Он не копирует поля, которые native factories уже задают константами через
layout groups. Polygon geometry не несёт подписи; отдельные label points
содержат только используемые label properties/IDs/unknowns и создаются лишь
при непустой подписи. Point sources сохраняют совместные paint/label поля,
а Line sources — label inputs для существующего fallback querySourceFeatures.
Custom zoom curves, layout keys, label zoom gates и явные false/пустые override
сохраняются. Исходные SQLite данные, style JSON, sync, видимость и геометрия
не меняются; миграции сохранённых пользовательских данных нет.

Только при точном SimpleFeatureRenderer/SimplePolygonStyle контексте одинаковые
с factory defaults значения заменяются теми же native fallback. Без контекста
или для rules применяется консервативная проекция. Все logical-vector native
handoffs (cold/hot load, style/data reload, ID/edit/save/hide/show) используют
один путь. Все6MLP edit constructors тоже передают оставшийся background list
через compact projection; selected/tool/vertex features остаются rich.
В этом совместимом constructor API нет layer owner, поэтому используется
консервативный geometry payload, без uniform-default sparsity. Публичный
geometry anchor helper не меняет семантику. Styled URI
не может обойти проекцию; cache flag остаётся false. Отладочные transport counts
пишут только layer ID, роль source, количества features/properties/nonempty labels;
координаты и бизнес-значения не включаются, журнал ограничен прежними8записями.
Initial load дополнительно оставляет компактные локальные HyperLog строки;
camera-idle line updates не создают такие строки.

Это исправляет подтверждённое дублирование payload, но не вводит eviction во
внутренний GeoJSONVT index и не доказывает причину различия223→224. Фактическую
экономию процесса, все GPU styles/labels/picking и приёмку на2GiB необходимо
проверить отдельно. Unit regression: NativeFeaturePayloadTest; consuming
VectorStyleRenderingTest сохраняет прежние rich Java/GPU assertions, а native
readiness проверяет транспортную проекцию вместо намеренно удалённых полей.

## Экономия кэшей и диагностика памяти — MOB-04, 2026-10-27

`RenderMemoryGuard` принимает решения по текущей свободной системной памяти,
её системному порогу, Java headroom и OS low-memory state. Номинальная RAM и
Android low-RAM class определяют только обычный disk cache и резерв32/64MiB;
сами по себе они не вызывают предупреждение, экономию кэша или сериализацию.
Абсолютные used/native/RSS остаются числовой диагностикой без порога реакции.
Полные builds сериализуются при свободном system остатке не выше512MiB
либо системного порога с резервом, при малом Java headroom или OS low-memory.
Неизвестный счётчик не считается нулём. Это **не** верхний предел native расхода.
Вызов SDK `onLowMemory` и отключение межмасштабного кэша не гарантируют очистку
всех внутренних GeoJSONVT структур. Подтверждённый профилем перерасход payload
описан выше; runtime экономия и приёмка2GiB проверяются отдельно.
Высокое устойчивое плато расхода само по себе не доказывает утечку: нужно
сравнивать одинаковые действия и удерживаемые allocations после возврата камеры.

Обычные слои и MVT используют полную исходную геометрию и весь подходящий набор
объектов. Отбора ID, новых LOD, скрытия/снятия регистрации слоёв и переписывания
SQLite/draft/visibility в этой защите нет. При действительном дефиците ресурсов
тайл откладывается через retryable503; неполный тайл никогда не становится200
или записью кэша. Штатные два builder сохраняются; при текущем дефиците
свободной системной либо Java памяти один builder занимает оба fair permits.
Переполнение очереди диагностируется отдельно: само по себе оно не доказывает
нехватку памяти и не вызывает memory warning или очистку кэшей.
Ответ503 сохраняет возможность повтора, однако восстановление тайла самим SDK
после освобождения памяти ещё требует native проверки. Успешный unit test
ответа не доказывает этот переход.

Для local MVT отключён coarse prefetch. Полные derived PBF read-only слоёв могут
попадать в private app cache:32MiB для слабого устройства,128MiB для остальных,
не больше1024 файлов и4MiB на файл. Ключ включает процесс/поколение карты,
canonical layer, content version, стиль и tile coordinates; это не межпроцессный
кэш и не файл подкачки native heap. Потеря кэша не удаляет исходные данные.

Callback очистки проверяет владельца map/view/host и lifecycle epoch. Pause и
Destroy прекращают watcher; Retry пересматривает только кэш и не перестраивает
все GeoJSON sources. Верхний app показывает одно автоматическое уведомление
на процесс. Ограниченных слоёв нет: `restrictions()` возвращает пустой набор.

MapRuntime хранит bounded source-stage before/after и периодические RSS/native/
system-free counters в прежнем журнале16KiB, включая доступную RAM, Java headroom,
системный порог/low-memory, резерв свободной памяти и текущий tile work limit.
Они показывают наблюдаемые изменения,
а не доказанное владение асинхронными native allocation. Настоящие координаты,
геометрии и атрибуты в эти наблюдения не попадают. Native приёмка памяти,
полноты карты и предупреждения остаётся отдельным обязательным gate.
Компактный native payload дополняет эту защиту: полный rich Java снимок
остаётся у редактора, guard не подменяет его частичной выборкой.
Карточки: [экономия и диагностика](../update-reports/entries/maplib-20261027-render-memory-budget.md),
[native payload](../update-reports/entries/maplib-20261028-native-geojson-payload.md).

## Пустые handles и загрузка индекса — 2026-10-27

MapDrawable не обращается к native renderer для пустого marker collection и
vertex-layer без editingObject. Заполненные handles, линейка и обычный picking
сохраняются. GeometryRTree читает прежний бинарный формат через буфер 64 KiB;
legacy persistence regression проверяет ID/координаты/поиск и точные байты.
Новый snapshot повторного startup/render_query сохраняется и внутри текстового
rate limit: ограничение сообщений не оставляет на диске прежний зависший запрос.
Это адресная работа по MOB-02, не доказательство устранения всех native crash.
Разбор и native-проверки — consuming docs/reference/mobile-crash-224-2026-10-27.md.

TrackColorPalette назначает насыщенный цвет один раз при insert. TrackMapStyle
владеет общими проходами свечения/линии/выделения; MapDrawable передаёт metadata
сегментов и поднимает Point/MultiPoint группы над треками. См. consuming
`docs/architecture/track-controls.md`; native rendering проверяется в приложении.

По выбранному пользователем образцу обычный трек сохраняет два уровня:
сердцевина 1.4 px с RGB-яркостью 27% исходного цвета и свечение исходного
оттенка 6.5 px / blur 3 / opacity .83. `track_core_color` вычисляется при
формировании сегмента; сохранённый `track_color`, БД и число проходов не меняются.

## Штриховки, контуры и живой образец

PolygonPatternOptions/PolygonOutlineOptions сохраняют необязательные параметры и explicit off. ConfigurablePolygonPatternRegistry строит бесшовные цветные текстуры с независимой alpha, LRU 4 MiB и native ownership. PolygonOutlineLayerFactory группирует константные dash/cap/join; новые стили MVT используют classic fallback. Контракт — consuming docs/architecture/polygon-style-controls.md.

`PatternTilePeriod` уменьшает исходную512×512ARGB решётку только при точном
совпадении повторяемого прямоугольного мотива во всех пикселях. Сохраняются
округление, плотность160, цвет/alpha, идентификаторы и владение изображениями;
если меньшего периода нет, остаётся512×512. Геометрия и style JSON прежние.
[Карточка](../update-reports/entries/maplib-20261027-pattern-exact-motif.md).

## «Светлячок»

SimpleMarkerStyle type=9 сохраняет прежний JSON и имеет чёткое ядро с внешним
ореолом. FireflyMarkerRegistry строит ограниченные RGBA sprites и учитывает
владельцев native images; FireflyLayerFactory добавляет обычные rendering
children с общим picking/visibility/zoom. PointLayerFactory исключает новый тип
из прежнего circle/SDF прохода. Прозрачное ядро не заполняется свечением.
Подробности, ограничения и проверки — consuming docs/architecture/point-firefly.md.

Diagnostic VERSION_NAME: release 3.1.2.30, debug 3.1.2.23. При подготовке
локального Lisa Release версия проверяется вместе с APK через consuming
`tools/verify-apk-version-matrix.ps1 -LisaOnly`; публикация отдельно требует Full.

## Копирование, сдвиг и поворот

GeometryTransform и FeatureCopyPolicy владеют независимыми копиями, совместимостью single/multi без потери частей, совпадением имён/типов полей и rigid transforms всех частей/отверстий. GeometryTransformGesture потребляет весь touch stream, не меняет камеру и завершает один Undo на жест. Default previewGeometryTransform сохраняет совместимость остальных hosts. Полный контракт — consuming docs/architecture/feature-copy-transform.md.


## Настройка сектора точности компаса

В «Настройки → Местоположение» есть чекбокс «Показывать сектор точности
компаса» (`show_compass_accuracy_sector`, default false). Выбор сохраняется;
MapFragment применяет его при возврате на карту через
`MapDrawable.setCompassAccuracySectorVisible(boolean)`. Заливка и оба
контура переключаются вместе, выбранная видимость сохраняется при reload.
Расчёт и стили сектора сохранены для доработки. Стрелка направления, круг GPS
и синяя точка 5 dp с белой каймой 2 dp независимы от чекбокса.
Related `cursor` проверяет default off, включение/выключение после resume,
hot-add/lite reload, реальные rendered features и русскую подпись чекбокса
в обеих темах при крупном интерфейсе.

## GPS diagnostic episodes

Snapshot v3 records continuous reason_duration_ms, diagnostic_tick_gap_ms and
device_sleep_gap_ms in addition to the bounded history. No-fix/acquisition/filter
wait remain local regardless of length or recurrence. Callback stalls need 30 s
of that continuous reason; provider silence needs 60 s, invalid/stale/display
processing needs 15 s. Earlier reception waiting cannot trigger an immediate
callback-stall warning. Retry, recording and filter ownership remain unchanged.
Source integration regressions seed actual prior callback/status silence before
expecting a report; ordinary acquisition is not used as a delivery-fault fixture.

Snapshot v3 separates reception loss, callback delivery and processing; bounded
24-sample history and correlated recovery/cancel preserve the episode UUID.
Reception gaps stay local; actual delivery/processing faults are rate limited. Only sustained
fresh used-in-fix statuses without callbacks allow one same-listener re-registration.
Recorders, Network, precision leases, wake lock, filtering and segment rules remain owned
by their existing controllers. See consuming root docs/architecture/location-pipeline.md.

## Общая история треков и проверки обычных стилей

SharedTrackStore owns one private track database and durable spool per installation.
Legacy read-only migration preserves all registered histories and rollback sources;
the source filename is resolved through DatabaseContext as `layers.db`. Completed
history missed by the first rollout is retried beside existing shared tracks.
PendingTrackPoints migration reads atomic backup/new variants without modifying
source files; transaction failure and lost acknowledgements remain retryable.
MapDrawable/TrackLayer use shared snapshots. LabelAttributes preserves explicit
optional presence, including false/default, and inherits all unset label settings.
Contracts: consuming root docs/architecture/shared-tracks.md and classic-style-verification.md.

## Готовые изменения и подтверждение полного проекта

NGWVectorLayer публикует owning-map onLocalChangesCommitted только после commit,
включая снятие TEMP/NOT_SYNC после Save. Нижняя библиотека не зависит от scheduler
maplibui. Готовая выборка FeatureChanges исключает отдельные Save-флаги всего
feature/attachment; остальные записи не отправляются до сохранения. Rollback
не вызывает callback. ProjectSyncState хранит отдельный full completion clock
canonical workspace; библиотечный account adapter не пишет общий UI timestamp,
а при queue-owned lifecycle передаёт право подтверждения верхней очереди.
Tracked URL читает часы своего проекта, с since=0 для неизвестного подтверждения.
Регрессии: LocalChangesCommitTest и ProjectSyncStateTest, native consumer tests.

## Автоматический background lease и display-only Network

`GpsEventSource.acquireBackgroundUpdates/releaseBackgroundUpdates` добавляют
независимого владельца общего GPS/Network и partial wake lock. Снятие map listener
при таком владельце не прерывает acquisition или фильтр; release не трогает
recorders и precision leases. `getLastDisplayLocation` удерживает последнюю
отображённую сетевую точку с исходным timestamp/accuracy и округлённым возрастом.
`getLastKnownLocation`, запись и точные инструменты сохраняют прежнюю свежесть;
скрытая сетевая точка старше последнего GPS не используется после потери GPS.
`GpsRecoveryPolicy` различает local delay и remote escalation, сохраняя episode
identity/history и парный итог. Точные пороги и ограниченный retry описаны выше.
Consuming app доставляет snapshot через свой diagnostics transport.
Регрессии: GpsDisplayRetentionTest и GpsRecoveryPolicyTest.
Явный выход consuming app снимает только его автоматические leases; recording
listeners Track/Walk остаются независимыми и удерживают acquisition/wake lock.
Minimize и explicit close различает app controller; API нижней библиотеки прежний.


## Назначение

MapRuntimeDiagnostics отслеживает длительные touch queries и local tile work
без вызовов SDK/SQLite из watchdog: trace stages, heap/queue/workers, cached
frame/lifecycle/scene state, bounded caller Java stack. Observer работает отдельно
от bounded writer, быстрые успехи не логируются, повторы считают suppression.
Private journal до 16 KiB связывается с событием по времени и сохраняет identity
прежнего процесса. Local 503 содержит точную причину queue_full/heap_headroom
и request id; ограничения ресурсов и содержимое тайлов не меняются.
Контракт: consuming root docs/architecture/error-reporting.md.

`LocalLogInitializer` выполняет один local-only HyperLog initialize на процесс,
мигрируя устаревший `HyperLog/URL` до чтения библиотекой. Локальная база и формат
сохраняются; последующие app/GIS вызовы не запускают подготовку remote uploads.
`LayerFormHashUtil` канонизирует form/meta JSON: порядок ключей/пробелы не меняют
hash, но значения, идентичности, правила и порядок массивов остаются значимыми.
Это сохраняет snapshot/download/staging guard без ложных mismatches между NGW workers.

TrackSendSettings хранит независимое намерение отправки трека: default=true,
однократная миграция старой выключенной галочки и сохранение последующего opt-out.
Готовность сервера и отметки не выводятся из этой галочки: maplibui отдельно
подтверждает регистрацию пары сервер/UID; app учитывает оба условия.
Контракт: consuming root docs/architecture/ngw-sync-and-storage.md.

CascadingLists.initialSelections восстанавливает допустимые цепочки родителей
по значению категории стиля, используя стабильные keys и AND-фильтры. Метод
не меняет текущий сеанс и возвращает все различимые цепочки без выбора первой.
PolygonPatternRegistry.previewPattern переиспользует штатные pixels для legend.
Контракт: consuming root docs/architecture/feature-type-creation.md.

`forms/ConditionalRequiredRules` разбирает независимый NGFP `lisa_form_rules`:
v1/v2 условная обязательность и v2 видимость поля/элемента по scalar значениям,
all/any/not, равенству, спискам значений и пустоте. Обе коллекции делят лимиты;
неизвестные версии, ключи и неоднозначные цели отклоняются. Parser ограничивает размер/глубину и не исполняет код.
Контракт: consuming root `docs/architecture/conditional-form-rules.md`.

`forms/CascadingLists` разбирает NGFP `lisa_form_dependencies`: общие таблицы,
устойчивые key/value/label, AND-фильтры и ациклический граф произвольной глубины.
Восстанавливает состояние без автоматического выбора и проверяет принадлежность;
исторические значения допускаются только с неизменными исходными предками.
Контракт: consuming root `docs/architecture/cascading-form-lists.md`.

Нижняя библиотека проекта: GIS layer/data model, локальное хранение, NGW
protocol/sync decisions, MapLibre style/rendering и shared application APIs.
Для подготовленного выпуска `3.1.2.29` диагностический release `BuildConfig.VERSION_NAME` равен
`3.1.2.29`; отдельный Lisa Debug использует `3.1.2.23`. Оба значения проверяются
вместе с соответствующим APK consuming app.

## Критичные области

- Field.isRequired() хранит штатный NGW feature_layer.fields[].required в
  локальном JSON и Parcel; старое описание без флага означает false.
  Изменение обязательности или подписи чинит только metadata после проверки
  физической схемы. SQLite, объекты и outbox не пересоздаются; fingerprint
  разрушительного rebuild не зависит от обязательности. Проверку пользовательского
  ввода выполняет maplibui, а не низкоуровневые операции синхронизации.

- `LocationPowerPolicy` отделяет глобальное ограничение GPS в Battery Saver
  от Doze exemption приложения: предупреждает для system GPS при mode 1/2/4,
  для API 26–27 — при включённом Battery Saver; mode 0/3 и native external
  GNSS исключены. GPS health пишет powerSave/locationPowerSaveMode/batteryExempt/deviceIdle.

- `GnssDeviceScanner` передаёт nullable RSSI в `GnssDevice`: BLE из ScanResult,
  Classic из discovery только для сопряжённых адресов. `GnssDeviceScanResults`
  обновляет по transport/id, сохраняя порядок строк и разделяя одинаковые имена.
  RSSI живёт только в текущем поиске. Stop/new scan отсекает прежние callbacks;
  имя и id подключения не содержат уровень сигнала.

- Общий GNSS-поток и sampler сохраняют поддержку ходьбы и автомобиля.
  Режим «Пешеход» ограничивается только consumer `maplibui.TrackerService`
  до sampler/queue; пропущенная поездка использует существующий номер сегмента.
  Курсор карты и обход не наследуют ограничение скорости трека; схема БД не меняется.

- GPS-triggered обновление текущего трека и явный reload истории читают SQLite
  в фоне из захваченной карты, не через переключаемый ContentProvider и не через
  mutable cache `TrackLayer`. `CoalescingRefresh` оставляет одну активную и одну
  отложенную задачу для каждого вида; публикация проверяет карту/view/style.
  Сегменты, фильтр видимости, исключение текущего трека из истории и отсутствие
  start/end flags сохранены.
- `Feature.equalsData` строит временный индекс имён полей за один проход,
  поддерживая перестановку колонок и прежние правила null/number/date; индекс
  не кешируется между вызовами, поскольку имена `Field` изменяемы. Поиск имени
  не обращается по индексу к linked schema.
- Snapshot-соединения, включая HTTPS redirect, имеют connect timeout 45 с и
  read inactivity timeout 180 с в штатной работе. Явная отмена повышает
  generation активных sync-session, закрывает только зарегистрированные
  read-only GET и проверяется между стадиями и объектами, поэтому зависшее
  чтение не ждёт timeout. Уже отправленный POST/PUT/DELETE не разрывается:
  завершается один запрос, после чего следующий change record не начинается.
  `NgwSyncTrace` пишет attempt/stage/elapsed и не чаще раза в 10 с прогресс без
  содержимого объектов. `DatabaseContext` больше не отключает SQLite journal
  и synchronous; целостность bulk apply важнее прежней PRAGMA-оптимизации.
- `NgwSnapshotCheckpoint` после commit на час запоминает только полностью
  объяснённый невалидными отсутствующими геометриями count mismatch `SYNC_NONE`.
  Workspace/account/server/resource/filter/schema и оба count должны совпасть;
  старый локальный объект не считается отсутствующим. Ручная перепроверка
  `SyncAdapter.EXTRA_RECHECK_SKIPPED` обходит checkpoint.
- Sync state принадлежит работающим потокам адаптера; чужой early finish,
  delayed broadcast и повторное создание Service не снимают чужую активность.
- `MapDrawable`, `MPLFeaturesUtils`, `VectorLayerRenderCache` — rendering;
- Для `map_bg=light` `MapDrawable` создаёт нижний MapLibre `BackgroundLayer`
  со сплошным `background-color: #FFFFFF`. При смене режима слой пересоздаётся,
  чтобы прежний `background-pattern` не перекрывал цвет; neutral и dark
  продолжают использовать штатные растровые узоры.
- MapLibre Android `13.7.1` подключён через явный OpenGL-артефакт
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
  кварталов содержат один внутренний label anchor исходного объекта в том же
  `features` source-layer. Фрагменты полигонов не несут подпись; служебная Point
  запись с `_label_anchor=true` и исходным ID попадает только в один тайл без
  учёта buffer. Используются прежние VectorSource, FillLayer, LineLayer и
  SymbolLayer; отдельный объектный слой/GeoJsonSource не создаётся.
  `LocalVectorTileLabelAnchor` учитывает отверстия, выбирает наибольшую пригодную
  часть MultiPolygon и проверяет точку после округления в сетку MVT. Геометрии
  без представимой внутренней точки на данном зуме не получают подпись.
  Общие границы соседних тайлов вычисляются одной формулой без epsilon-overlap;
  повторяющиеся IDs пространственного индекса обрабатываются один раз на тайл.
  Тайл внутри квартала не требует пересечения с его границей: bbox-кандидаты
  передаются непосредственно отсечению, иначе при увеличении масштаба исчезает
  и заливка, и внутренняя подпись.

- Поддерживаемые геометрии локальных MVT:
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
- `MPLFeaturesUtils.convertToPointFeatures` ставит якорь подписи внутри
  Polygon, учитывая отверстия; для MultiPolygon выбирает наибольшую пригодную
  часть. Неполная или вырожденная геометрия без внутренней точки не получает
  якорь, а свойства подписи сохраняются;
- `user-location-layer` остаётся служебным верхним overlay независимо от порядка
  пользовательских слоёв; геодезический круг `user-location-accuracy`, сектор
  направления `user-location-heading` и его контуры `user-location-heading-halo` /
  `user-location-heading-outline` лежат непосредственно под ним (роль Polygon
  `accuracy`/`heading`); непостоянные `azimuth-measurement-*` line/point layers
  для измерения свободных точек восстанавливаются при full/lite reload строго под ним,
  не входят в `LayerGroup`, не сохраняются как прикладные данные и передают drag
  редактируемых концов линии host-экрану;
- `LayerContentProvider` разрешает активный `IGISApplication.getMap()` на каждой операции и не
  маршрутизирует треки/объекты через карту предыдущего Collector workspace;
- `MaplibreMapInteraction`, `IGISApplication` — API верхних слоёв;
- `Connection`, `SyncAdapter`, `NGWSyncService` — NGW; временно упавшие pull
  векторных слоёв повторяются отдельным проходом после остальных слоёв, а
  process-wide sync state обновляется адаптером напрямую, не только broadcast;
  `NgwSyncProgress` считает сессию всех account, вес листа по локальным правкам
  и внутри слоя push/TUS, байты snapshot и apply; неизвестный остаток держит
  долю, deferred retry не закрывает слой, composition/fill в шкалу не входят;
  destroy bound service не блокирует Android main thread ожиданием worker;
- schema preflight сравнивает authoritative `resource.cls`/geometry/fields,
  serialized config и physical SQLite affinities: metadata-only drift чинится
  без refill, а legacy config без типа не считается PostGIS;
- full untracked NGW response сначала пишется в app-owned temporary JSON, затем
  дважды потоково читается для backup/delete plan и одной SQLite-транзакции;
  отдельная отсутствующая или невалидная геометрия пропускается с сохранением
  remote ID и прежней локальной копии, а остальные объекты продолжают apply;
  pending local edits отправляются до remote pull;
- server attachment metadata сверяется с `FeatureAttachments`, а не с
  необязательными локальными файлами/META: metadata-only pull не создаёт backup,
  и новые server features сразу получают online metadata без скачивания байтов;
- инкрементальный NGW pull работает как одна атомарная bulk-операция:
  построчные insert/update/delete broadcast подавлены, interruption откатывает
  общую SQLite-транзакцию, а после commit R-tree перестраивается и карта
  перезагружается один раз. Каталоги вложений удалённых объектов очищаются
  только после commit, поэтому rollback не восстанавливает строку без её файлов;
- reload выключенного vector layer освобождает прежний GeoJSON, не читая всю
  таблицу; включение использует существующий on-demand reload contract;
- `NGWResourceUrl`, `ResourceGroup.loadTargetResource` — разбор URL и точечное
  получение NGW-ресурса без загрузки всего дерева;
- `LisaCatalog` / `LisaCatalogLookup` — поиск группы `keyname=lisa` и список
  `collector_project` внутри неё;
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
  opt-in фильтр `resmeta.items.district` ищет вхождение ключа в поле `district`
  через `fld_district__like=%value%` (список районов через запятую с пробелом,
  не точное равенство); для `SYNC_NONE` сравнивается отфильтрованный server
  count с локальным `COUNT(*)`, и при расхождении (если сервер не пуст) слой
  пересобирается полным snapshot;
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
- `Table.save/load` использует `AtomicFile` для `default.ngm` и layer configs:
  после process death читается последняя полная старая либо новая версия JSON;
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
- `GpsEventSource` владеет общим потоком позиции и отдельным GNSS-only выходом
  записи. Карта получает свежий GPS (чип или mock) и Network только без GPS,
  метрический круг accuracy и
  геодезический сектор точности компаса (`UserLocationGeometry`) длиной 56
  логических пикселей при текущем zoom. Заливка сектора 0.24 дополнена белым
  контуром 2.8 px с синим контуром 1.2 px поверх него. «Орбита-кольцо»
  оставляет прозрачное окно диаметром 21 dp с синей точкой 5 dp с белой обводкой 2 dp в центре.
  Синий обод имеет внешний радиус 13 dp: он утолщён на 1 dp наружу за счёт
  белого обода, чей внешний радиус 15.2 dp и тонкая тёмная кромка сохранены.
  Заливки accuracy и компаса также освобождают окно; внутренний контур сектора
  скрыт под непрозрачным ободом. Треугольник остаётся 16 × 14 dp.
  Общий canvas 72 dp центрирован на фиксе
  и содержит треугольник при любом повороте. Компас приоритетен
  даже на остановке, при его недоступности — курс движущегося фикса; без обоих
  направлений остаётся круг. Legacy type=stand/go сохранён, has_direction
  выбирает ресурс, bearing — вычисленное направление. Host обновляет сектор
  и треугольник без нового GPS, в том числе при expiry компаса и смене камеры.
  GPS bearing не создаёт сектор компаса. Источник очищает устаревшую
  позицию по монотонному времени, в том числе после сна.
  `ExternalGnssFixPolicy` отбрасывает заглушку GPS Connector без extras и чип
  при живом mock или native NMEA; сам NMEA не считается чипом. `gnss_input=external` читает NMEA напрямую (Bluetooth Classic/LE,
  USB, TCP/IP) без Mock Location и не закрывает транспорт из-за снятия
  слушателей карты; `NmeaParser` собирает GGA/GST/GSA/RMC и `#BESTPOSA`.
  Двоичный ComNav CNB (`AA 44 12`, как у PiGoLite) даёт координаты из BESTPOSB
  (сообщение 42); `$`/`#` внутри кадра не начинают NMEA. `unlogall` не пишется;
  при отсутствии ASCII сессия может по одной команде добавить `log gpgga/gst/gsa/rmc`.
  Для Pigo с service `3A20` transport выбирает write `3A21` / notify `3A22`.
  Тихому приёмнику добавляется только BESTPOSB; живой binary stream не
  перенастраивается. CCCD и каждый write подтверждаются, ошибки/таймаут
  запускают reconnect без crash при отозванном permission. Старые callbacks
  не затрагивают новый transport, качество сбрасывается через 8 с тишины.
  Профиль/запись/ошибки и сессия проверяются `BluetoothLeProfileTest`,
  `BluetoothLeTransportTest`, `ExternalGnssSessionTest` на API 26/36;
  реальный холодный старт Pigo/PiRat остаётся device smoke.
  `AdaptiveLocationFilterCore` сглаживает шум чипа, удерживает остановку, проверяет
  выбросы и учитывает автомобильные повороты; mock и native NMEA пишутся как есть.
  `LocationRecordingSampler`
  прореживает только уже проверенные точки (для mock и native NMEA не грубее 2 с / 1 м).
  База v6 и `TrackLayer.getTracks()` сохраняют многосегментные линии, Canvas/MapLibre не соединяют разрывы.
  Исходные GNSS/mock фиксы выноса доступны через `addRawListener`.
  Подробный контракт: [текущая позиция и запись GPS](../../docs/architecture/location-pipeline.md).
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
  `MapDrawable` подключает его напрямую через `mbtiles:///`, без display proxy
  и преобразования тайлов;
- legacy tile conversion math: OSM row переводится в TMS/MBTiles, bounds
  вычисляются в Web Mercator tile matrix, raster format определяется по magic
  bytes без декодирования каждого изображения.

## Ограничения

- Нет imports из `maplibui`/`app`.
- MapLibre backend должен оставаться согласованным с `maplibui` и `app`:
  `org.maplibre.gl:android-sdk-opengl:13.7.1` во всех трёх модулях.
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
- Network разрешён только если нет свежего GPS и источник не `gnss_input=external`; трек и обход сохраняют GNSS чипа, mock приёмника или native NMEA.
  Разрыв пригодного потока более 8 секунд разделяет трек на сегменты.
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
  только отсутствующие ключи наследуются из other; явно заданные false/255/default сохраняются.
- Zoom-stops «не действуют»: проверить effective category curve и флаг scale
  (на other или в категории); обычный GeoJSON renderer объединяет stops разных
  category curves в top-level interpolate и выбирает multiplier по props.
- Курсор перекрывается треком/вектором: проверить, что `user-location-layer`
  последний в live style после cold/lite/hot reload, а под ним
  `user-location-heading-outline`, `user-location-heading-halo`,
  `user-location-heading` и `user-location-accuracy`.
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
  `provider` и причины фильтра; у сервиса счётчик accepted относится к выходу общего GNSS-потока.
  Для валидного движения до 160 км/ч не должно быть каскада `drop:speed_dist`.
- Курсор карты движется, но у завершённого трека `accepted=0`:
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

## GPS: фон и уточнение стоянок

GPS-подписка записи сохраняется при скрытии/возврате карты. Источник удерживает
partial wake lock, пока активен хотя бы один recorder или внешняя NMEA-сессия,
независимо от звука.
Акселерометр 25 Гц дополняет GNSS-проверку стоянок; при отсутствии свежих сенсорных
событий используется состояние «неизвестно». Согласованное движение автомобиля
может опровергнуть неподвижность телефона в держателе. Уточнение стоянки через
`takeStationaryCorrection` изменяет последнюю свою вершину, а не дописывает линию.
Диагностика `GPS health` позволяет сравнить сырые интервалы и accuracy со включённым
и выключенным экраном. См. [контракт GPS](../../docs/architecture/location-pipeline.md).

Взятие телефона в руки не подтверждает ходьбу. Фильтр требует направления,
выхода за два радиуса accuracy и достоверной speed либо более длинного тренда.
Sampler держит до 120 секунд / 512 неподтверждённых GNSS точек и после подтверждения
восстанавливает начало с временами и поворотами; остановка/разрыв не выгружает
сомнительный буфер. Regression fixture содержит обезличенный ADB-замер стоящего A54.

## Независимый preview и подтверждение ходьбы

`MapDrawable.showWalkPreview` принимает полную геометрию сервиса отдельно от
foreground-редактора. `WalkPreviewGeometry` восстанавливает CRS приватной копии
перед WGS84-проекцией и отображает также незавершённые кольца. Источник переживает
полную и облегчённую загрузку style. Резервирование слоя доступно через default
hook `IGISApplication.isLayerReservedForWalk` и учитывается перед удалением.

`LocationMotionFilter` выдаёт текущий display candidate отдельно от stop anchor.
Короткое окно для accuracy до 5 м и медианные части 12-секундного окна для
accuracy до 12 м допускают обычную неопределённость speed и ранний поворот.
`stationary`/`departureMs` в диагностике различают ожидание фильтра и отсутствие
GNSS. Пороговые значения и пределы: [GPS pipeline](../../docs/architecture/location-pipeline.md).


## Общее хранилище подложек

SharedUnderlayCatalog/SharedUnderlayStore владеют общими NGRc/MBTiles и тонкими shared_underlay_id ссылками. NgrcArchive читает ZIP двумя потоковыми проходами и считает тайлы для прогресса импорта; RasterMbtilesWriter пишет одну базу без дерева файлов. Display name документа не берётся из SAF id вида `msf:308`. Legacy migration — rename с журналом, хеширование старых MBTiles отложено. UnderlayWorkspaceIndex меняет закрытые карты без MapBase singleton. Контракт и recovery: [shared-underlays](../../docs/architecture/shared-underlays.md).

При объединении одинакового NGRc в разных форматах `Asset.referenceConfig`
переносит тип, уровни тайлов и bounds целевого payload, сохраняя проектные
имя, видимость и пределы масштаба. Та же операция используется при подключении;
у нового слоя пределы масштаба и provenance берутся из ассета. Перед загрузкой
`repairReference` исправляет ссылки старых сборок с несовпадающим `tms_type`.
Регрессии проверяют оба направления NGRc/MBTiles, возобновление move/redirect,
закрытые проекты и недоступный целевой payload.

Потоковый `NgrcArchive` принимает исходные `Mapnik.json` и `Mapnik/z/x/y.jpg`
наряду с `config.json` и `.tile`, включая PNG/JPEG/WebP и разный регистр.
Проверки пути, схемы, полного archive hash и неоднозначной конфигурации
сохраняются. При ошибке либо отмене импорта worker вызывает удаление слоя;
`MapDrawable.deleteLayerByID` переносит удаление native style и связанных
реестров на main thread, сохраняя синхронный вызов из UI. Это устраняет
`CalledFromWorkerThreadException` в cleanup; ошибка самого импорта остаётся
доступна диагностике.

## Надёжность локальной записи — 2026-10-03

Изменение feature и NGW outbox фиксируются одной транзакцией в базе карты,
которой принадлежит слой. Ошибка чтения outbox не означает отсутствие правок.
Уведомления, удаление файлов feature/NGW-вложений и поколение данных публикуются
после commit. `FeatureSaveJournal` закрепляет повторный insert за UUID операции;
`PendingTrackPoints` хранит ограниченную упорядоченную очередь и подтверждает
точку только после идемпотентной записи в owning `TrackLayer`.

`MapTapGesture` отделяет микросмещение короткого тапа от drag/long press/pinch;
`MaplibreMapInteraction.isTapPlacementActive()` включает согласованный допуск
для инструментов. `AuthInterceptorNG` читает неизменяемый снимок реквизитов,
сопоставляет origin, префикс сервера и точный resource id.
Обход связного списка при WKT-сериализации последовательный; формат не изменён.

Реальный SQLite проверяется Robolectric на API26/36 и native AndroidTest из app.
Новые storage/API contracts требуют совместимых maplibui/app commits; порядок
merge: maplib → maplibui → app. См.
[контракт хранения](../../docs/architecture/ngw-sync-and-storage.md),
[восстановление](../../docs/architecture/crash-recovery.md) и
[аудит](../../docs/reference/mobile-reliability-audit.md).

## Project scripts

Владеет форматом пакета/ссылки, private project cache, isolated QuickJS JNI/service и read-only host broker. `gis.query` и `time.monthWindow` встроены; spatial/расчётные/table API пока не реализованы.

[Архитектура](../../docs/architecture/project-scripts.md),
[руководство](../../docs/guides/project-scripts-user-guide.md).

## Изоляция общей синхронизации

Настройка sync_all_projects включена по умолчанию. ProjectSyncRunner сериализует
project/account passes, а SyncWorkspaceSession связывает owning map с каждым
callback, provider URI и service ticket до фактического завершения. Закрытая карта
не активируется; открытая форма/черновик и preferences остаются прежними.
Полный контракт: consuming root docs/architecture/ngw-sync-and-storage.md;
пользовательская инструкция: docs/guides/project-synchronization-user-guide.md.
Перед изменениями читать оба документа. Нельзя заменить изоляцию временным
переключением глобальной карты или prefs, либо закрыть БД по timeout при живом child.

## Профиль полной приёмки consumer

Opt-in verification APK app имеет отдельные IDs/storage и использует существующий
maplib без изменения GIS/sync API. Полная приёмка consumer выполняет maplib unit
tests, simulated location/track cases на эмуляторе и статические сценарии на
смартфоне: [автотесты](../../docs/runbooks/automated-tests.md).

Startup наблюдается тем же MapRuntimeDiagnostics observer через beginStartup.
Приложение задаёт только фиксированные phase names. Предыдущий private journal
читается до первой новой записи и остаётся доступным delayed AppExitInfo;
startup/query/tile записи вместе ограничены 16 KiB с удалением старейшей.
Новых зависимостей на app/maplibui, чтения GIS из watchdog и изменений тайлов нет.
Unit checks покрывают blocked startup, late ANR после перезаписи файла и размер
общего journal; native consuming-app check использует настоящий SDK AnrV2Hint.


## Учёт доработок

Карточки и автоматический контроль исходных путей: [реестр обновлений](../update-reports/README.md).
Перед push выполняется локальный hook; в новом клоне требуется его установка.

SharedTrackStore schema 2 назначает насыщенные постоянные цвета прежним SQL NULL
при upgrade и позднем импорте. Все явные цвета/metadata/points сохраняются;
ошибка откатывает и цвета, и версию. TrackMapStyle: core 2/glow 6/selection 4.

LineString/MultiLineString labels use one whole point-anchored block for the current visible extent. VisibleLineLabelLayout clips loaded geometry and computes its predominant direction; VisibleLineLabelController coalesces camera/source changes without SQLite or per-frame layout. See consuming docs/architecture/visible-line-labels.md.

## Полевые линии и mixed hit candidates

queryTrackIds больше не исключает трек при попадании в точку. App объединяет
track IDs с векторными кандидатами; сегменты одного трека не дублируются.
LayerDisplayPriority распознаёт линейную геометрию с именем «Полевые линейные»
или field_lines. MapDrawable располагает body/labels этих групп над треками
и под Point/MultiPoint, включая derived label sources и вложенные logical
слои. Full/lite/style/reorder восстанавливают тот же порядок; already-correct
stack не перестраивается. Схема, API payload и число проходов не меняются.
Consuming Related track-candidates: 4/4 native PASS. Контракт — consuming
архитектура track-controls.md. Runtime unit/build и полный набор отдельны.

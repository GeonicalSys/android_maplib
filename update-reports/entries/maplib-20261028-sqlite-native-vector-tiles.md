---
id: maplib-20261028-sqlite-native-vector-tiles
area: Память и отрисовка обычных векторов
kind: internal
at: 2026-10-28T03:15:00+01:00
title: Полные SQLite тайлы вместо общего стартового GeoJSON снимка
why: Квалифицированный профиль787 показал крупное удержание GeoJSONVT property maps; компактный payload не ограничивает внутренний индекс пройденных тайлов
covers:
  - src/main/java/com/nextgis/maplib/map/SqliteVectorTileBackend.java
  - src/main/java/com/nextgis/maplib/map/SqliteVectorTileRequest.java
  - src/main/java/com/nextgis/maplib/map/SqliteVectorTileProvider.java
  - src/main/java/com/nextgis/maplib/map/NativeVectorTileEncoder.java
  - src/main/java/com/nextgis/maplib/map/NativeVectorTilePropertyCodec.java
  - src/main/java/com/nextgis/maplib/map/NativeVectorTileClipper.java
  - src/main/java/com/nextgis/maplib/map/RenderTileWorkLimiter.java
  - src/main/java/com/nextgis/maplib/map/RenderTileWorkGate.java
  - src/main/java/com/nextgis/maplib/map/LocalVectorTileServer.java
  - src/main/java/com/nextgis/maplib/map/VectorLayer.java
  - src/test/java/com/nextgis/maplib/map/SqliteVectorTileRequestTest.java
  - src/test/java/com/nextgis/maplib/map/SqliteVectorTileProviderTest.java
  - src/test/java/com/nextgis/maplib/map/NativeVectorTilePropertyCodecTest.java
  - src/test/java/com/nextgis/maplib/map/NativeVectorTileEncoderTest.java
  - src/test/java/com/nextgis/maplib/map/NativeVectorTileClipperTest.java
  - src/test/java/com/nextgis/maplib/map/RenderTileWorkGateTest.java
  - src/test/java/com/nextgis/maplib/map/SqliteVectorSpatialQueryTest.java
---

MOB-04, maplib61 перед app82. Это backend нового CustomVectorSource route;
bridge, native factories/source-layer и host/editor migration доставляет тот
же общий task отдельными адресными карточками. Git/merge/release не выполнялись.

Provider одной immutable generation читает RTree candidates и полные SQLite
originals через getFeature(id), не вызывая getFeatures или общий startup
createFeatureListFromLayer. Преобразование каждого объекта временное; после
записи его PBF commands полный geometry/property object не удерживается общей
коллекцией. loadOriginalFeature возвращает полный datasource.Feature,
readOriginalFeature — полный styled GeoJSON для selection/edit overlays.
readVisibleLines получает только viewport candidates и возвращает full,
unclipped rich lines прежнему VisibleLineLabelLayout. SQLite/поля/геометрия,
sync/outbox/visibility/drafts не изменяются; saved-state миграции нет.

API Backend.snapshot/isCurrent/load принимает immutable Request с canonical
owner, source/content/style epochs, optional edited ID и z/x/y. Close terminal,
idempotent, без ожидания IO и закрытия чужой DB. Job/owner/source/content fences
проверяются до/после и каждые32 объекта. Полный renderer JSON сравнивается
на входе и выходе load, не в feature loop; root обязан менять source epoch
на style/data/lifecycle boundaries. Cache key включает process/source owner
и content/style/exclusion/tile identity. Это process-local derived PBF,
не подкачка native heap. Лимиты32/128MiB,1024 files/4MiB entry ограничивают
только disk cache: большой полный тайл возвращается целиком без кэширования.

Один process-shared fair gate используется backend, visible-line reads и
прежним HTTP MVT. Два обычных permits и фактическое free-memory ограничение
сохраняются; номинальная RAM и used/RSS не включают slowdown/warning.
Fixed64 lock stripes не создают растущий global map. Новый cancellable wait
проверяет job каждые50ms и возвращает permits при отмене/exception;
старый blocking API/fairness сохранён. Pressure даёт RetryableTileException
с100–2000ms delay для bridge backoff после освобождения IO/permits. Ошибка
или отмена не публикует и не записывает частичный/пустой успешный тайл.

Старый encoder выдавал только IDs/order/signature и не сохранял Rule paint
payload. Новый generic encoder сохраняет все переданные scalar properties:
paint/opacity, rule layout keys, signature/label gates и строковые zoom curves.
Text/icon offset pairs передаются через __ng_text_offset_x/y и
__ng_icon_offset_x/y; host factories восстанавливают typed arrays через
SDK semiliteral expressions для всех соответствующих children.
MVT не представляет JSON null: известные coalesce consumers получают absence,
а has(null) parity не заявляется. Неожиданные nested properties, неправильные
pairs и nonfinite numbers дают явный UnsupportedTileException для host fallback;
они не stringify/drop молча. Полные DB/rich originals сохраняют исходные поля.

Metadata-only canServe ограничен шестью геометриями, exact Simple/Field Rule
renderers и стандартными marker/line/polygon style families, включая effective
category/default styles. Unknown extensions остаются явным host fallback.
Для поддерживаемых Rule layers новый route должен предшествовать старому
local-vector-tiles flag; прежний ограниченный encoder не объявлен исправленным.

Point/MultiPoint, Line/MultiLine и Polygon/MultiPolygon кодируются с полными
multipart commands/rings/holes. JTS rectangular intersection сохраняет
disconnected polygon pieces; simplification/LOD/geom_zoom columns не добавлены.
Полностью внутренние объекты обходят overlay. Extent8192 совпадает с native
SDK13.7.1; legacy encoder4096 сохранён отдельно. Polygon anchor считается
прежним classic4326 helper до clipping, затем принадлежит одному half-open
tile. Geometry paint и label-only props разделены; ORDER стабилен по FID,
а не по меняющемуся порядку кандидатов тайла. Editing использует full original,
никогда clipped/quantized feature; host temporary FID filter не пишет DB.

Добавлены23 адресных test methods: независимое protobuf декодирование всех
шести видов/holes/multipart/native grid и scalar Rule payload; offsets/types/
unknown/null failures;4097 complete IDs и warm PBF без повторных DB reads;
bounded style serialization; cancellation/content/style/owner/close fences
без partial cache; viewport full lines; один polygon label owner из4tiles;
cancel/throwing gate supplier без permit leaks и4 actual SQLite/RTree checks.
Финальные focused26tests (включая3 прежних gate tests) прошли29s,
0failures/errors/skips: Request2/Provider6/Codec3/Encoder2/Clipper3/Gate6/
SpatialQuery4. Exact7suiteXML/cases и17source hashes сохранены в
sqlite-native-backend-units03.json/units03-xml/log. Первая попытка остановилась
на неверном test qualifier константы; во второй26run/3fixtureFAIL setup использовал
старый createFeature API с nonnull empty getItem list. Исправлены только test
constant/setup (owned technical SQLite rows + actual rebuild), исходный API
не менялся; неуспешные log/JSON/XML сохранены отдельно. Эти unit/main-compile
свидетельства не принимают native GPU/A-B787/Full. Draw speed — отдельный cold/warm/
pan/pinch benchmark того же проекта/масштаба; отсутствие деградации пока не
установлено. Устранение GeoJSONVT index не является hard native-memory cap
или гарантией отсутствия всех OOM;2GiB/Related/Full остаются native gates.

Spatial follow-up закрывает реальную возможность снять частичный индекс:
queryForNativeRender синхронизирует IDs с rebuild/cache writer lock и проверяет
готовность до/после. SQL COUNT выполняется вне spatial lock, чтобы не создать
обратное ожидание с bulk SQL transaction; результат кэшируется по weak tree
identity/data generation/size, а не пересчитывается на каждом тайле. После
cancel/exception индекс не помечается ready и не сохраняется как валидный;
новый пустой SQL table0 даёт законный empty snapshot. DataGeneration после
commit инвалидирует provider раньше отложенного UI broadcast. Четыре tests
проверяют actual SQLite rebuild, blocked reader, typed retry вместо partial
PBF, сохранность прежнего RTREE при cancel, независимые SQL IDs и commit fence.
Проверка COUNT не доказывает отсутствие исторического same-count wrong-ID
index: это отдельная непроверенная возможность, не полный oracle/авторемонт.

Read-only whole-contour pre-edit plan: sqlite-native-backend-impact01.json,
16paths; post-own-source plan sqlite-native-backend-impact02.json,18paths;
до spatial API — sqlite-native-spatial-impact01.json,6paths. Все сохраняют
UNKNOWN/path-contract-unmapped/REFRESHED_IN_MEMORY. Root/bridge host edits
выполнялись отдельно; final whole-task source-index closure остаётся обязательным.
Чужой dirty desktop index не изменяется; root сохраняет semantic app ↔ bridge
↔ backend ↔ native factory/editor/line-controller links и cross-owner delivery
dependency. Producers NGW/desktop/SQLite schema не меняются. Полнота исторического RTree,
малформированные source geometries, dateline/world copies, все реальные styles
и whole-user-workflow acceptance проверяются отдельно, не следуют из unit roster.

Хранение: новые исходники/карточка остаются в Git workspace; только compact
impact/test receipts в активном Q evidence. Новых runtime/download/native captures
этот backend этап не создаёт. Evidence хранится до общей доставки расследования.

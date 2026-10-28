---
id: maplib-20261028-sqlite-batched-original-reads
area: Скорость чтения карты
kind: internal
at: 2026-10-28T11:23:47+01:00
title: Ограниченные порции атрибутов и один BLOB statement для полного SQLite тайла
why: На T40 Plus_ROW/API30 startup scope15 проекта1403 занял9908.73ms для3051 объектов;5281.16ms ушли на атрибуты и1547.78ms на BLOB, остальные запросы ждали IO до3431.57ms
covers:
  - src/main/java/com/nextgis/maplib/map/SqliteVectorFeatureReader.java
  - src/main/java/com/nextgis/maplib/map/SqliteVectorTileProvider.java
  - src/main/java/com/nextgis/maplib/map/VectorLayer.java
  - src/test/java/com/nextgis/maplib/map/SqliteVectorFeatureReaderTest.java
  - src/test/java/com/nextgis/maplib/map/SqliteVectorTileProviderTest.java
---

MOB-04, та же own maplib61→app82 цепочка; private843d — сохранённый исходный
диагностический APK, не эта оптимизация. Фазы подтвердили конкретный startup
read hot path на integer tile z11/current scene11.7. Это не закрывает reported
3–10s на12.1: проверка владельца требует резкого17→12 и полностью новой области.

`SqliteVectorFeatureReader` получает только прежние eligible candidate IDs,
по32 на attributes-only cursor. SQL CASE сохраняет точный исходный порядок;
ID/cardinality сверяются до успешного завершения. Геометрия не попадает в
CursorWindow: один полный original BLOB читается/декодируется перед visitor,
тем же descriptor/readBlob/fromBlob путём. Один visit-owned statement лениво
компилируется при первом BLOB и переиспользуется bindLong, не разделяется
между потоками. Cursor/descriptor/statement закрываются при success/error/cancel;
secondary close failure не заменяет исходную ошибку. Реальный SQLiteException
не проглатывается как null cursor. Missing/extra/reordered IDs и missing/bad BLOB
останавливают весь тайл, не создавая успешный partial PBF или disk cache.

Provider и visible-line visitor используют совместимый default Access seam;
editor/sync `getFeature` APIs остаются прежними. Lazy ID filtering сохраняет
hidden/excluded masks и порядок; owner/content/style/current guards проверяются
при проходе и между rows. Gates, IO workers, source epochs, cache keys, geometry,
style/anchor algorithms и free-only policy не изменены. Нет all-layer features,
GeoJSON копии, глобального LRU, thinning, simplification или потери полей/NULL.
Fixed phase enums/boundaries остаются: attributes query/materialization,
BLOB read, full decode, style/clip/labels/PBF/cache/Main. Counts прежних объектов
и BLOB bytes можно сравнивать с843d; SDK return по-прежнему не native ready.

До edits выполнен ONE root per-change plan: `sqlite-batch-read-impact01.json`,
exit0/UNKNOWN/REFRESHED_IN_MEMORY,50,921,014B stdout обработан в RAM,
source_context сохранён. Foreign canonical root codex/roadmap-admission не
изменён; owner follow-up на generated source index обязателен до Git closure.
Семантический read contract сохранён в own app registries. Schema, Collector/NGW
producer resources, saved projects и data/draft/sync migration не меняются;
maplibui/app binary consumers получают прежние provider bytes/contracts.

Независимые технические ожидания записаны до оптимизации: semantics-preserving
extraction прежнего per-ID цикла дала RED9 tests/2 expected failures:
65 attrs queries вместо3 и5 statement opens вместо1; остальные7 прошли.
Это source extraction, не новый запуск baseline APK. Log, JUnit и RED source
сохранены отдельно. Первый GREEN55 tests PASS24s (reader11/provider9/bridge13/
spatial4/diagnostics18),0fail/error/skip. Промежуточный GREEN02 PASS17s:56 tests
(reader12/provider9/bridge13/spatial4/diagnostics18), включая full multipart,
hole/3001 vertices/>32KiB BLOB exact bytes;0fail/error/skip. До freeze найден
guard-order риск: resource admission был после decode. Checked beforeRead
перенёс current/free-only guard перед query/полным original read, включая
default Access и legacy ветку. Независимая regression подтверждает0queries/
0BLOB/0statement при первом отказе и cleanup при отказе следующего row.
Final GREEN03 PASS20s:57 tests (reader13/provider9/bridge13/spatial4/diagnostics18),
0fail/error/skip. Independent read-only review обновлённого helper/wiring не
нашёл блокеров: CASE binds/order, typedNULL, cardinality, read/cleanup/current
и восстановленная free-only cadence проверены; ID-only direct query не обходит
NGW row-policy. Native BLOB descriptor/speed/content acceptance ещё pending.
Технические doubles не названы business/native acceptance. Позднее ordinary6039
normalall3PASS62s/matrixPASS и actualAPI30 scope3051originals/2076156B/PBF356630
проверили физический PFD read path. Strict dense1403/11.7z video gate дал
13.68–15.65s против843d32.45–34.09s с positive last-upper-pink-strip gate;
late mapROI MAE0. Это1processcoldrun/build/diskretained, не FPS/все объекты/
стили/новая область. Последующая отдельная small-BLOB карточка содержитD101;
не приписывать её новым behavior старомуID. Full/2GiB/merge/публикация не приняты.

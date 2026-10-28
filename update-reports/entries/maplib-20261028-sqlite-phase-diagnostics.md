---
id: maplib-20261028-sqlite-phase-diagnostics
area: Диагностика скорости отрисовки
kind: internal
at: 2026-10-28T10:09:26+01:00
title: Ограниченные локальные фазовые замеры SQLite отрисовки на планшете
why: На T40 Plus_ROW/API30 владелец наблюдает3–10s до полного кадра около12.1z; private app storage недоступен извне, а прежние counters не разделяют очередь, SQLite, geometry и Main delivery
covers:
  - src/main/java/com/nextgis/maplib/util/MapRuntimeDiagnostics.java
  - src/main/java/com/nextgis/maplib/map/SqliteVectorTileProvider.java
  - src/main/java/com/nextgis/maplib/map/SqliteVectorTileBackend.java
  - src/main/java/com/nextgis/maplib/map/SqliteVectorSourceBridge.kt
  - src/main/java/com/nextgis/maplib/map/SqliteVectorLayerController.java
  - src/main/java/com/nextgis/maplib/map/VectorLayer.java
  - src/test/java/com/nextgis/maplib/util/MapRuntimeDiagnosticsTest.java
  - src/test/java/com/nextgis/maplib/map/SqliteVectorTileProviderTest.java
  - src/test/java/com/nextgis/maplib/map/SqliteVectorSourceBridgeTest.kt
---

MOB-04, собственная существующая maplib61→app82 цепочка. Это диагностическое
улучшение перед установлением причины, не исправление скорости или OOM.
Фактически открытый на планшете проект — Collector1403, не787; быстрый телефон
не является его приёмкой. Права/ACL/scoped storage устройства не меняются.

Typed `MapRuntimeDiagnostics.SqliteScope` отмечает IO queue, layer lock,
cache read, gate wait, index query, attributes/BLOB read, full geometry decode,
style, clip, polygon/line labels, PBF encode/finish, cache write и Main dispatch.
Существующие SQLite reads и данные не дублируются. `useNewLargeDataload=true`
разделяет attributes cursor и raw BLOB descriptor; old fallback не выдаётся за
отдельно измеренное декодирование. Bridge передаёт scope через совместимый
default Backend overload, controller — только diagnostic layer ID.

`LISA-SqlitePhase` — локальный logcat через существующий bounded writer, без
нового Sentry/event sender. Фиксированные enum arrays не принимают произвольные
phase/error strings. В выводе layer/source/content IDs, zoom без tile X/Y,
counts/bytes и длительности; нет URI, координат, feature attrs или error.message.
На Main нет дополнительных memory/DB/native SDK чтений и suspension. Фаза
Main dispatch начинается на IO до resume; последний current guard сохранён.
`returned_to_sdk` не подтверждает native delivery/rendering/content-ready.

Один observer обслуживает максимум16 активных scopes и32 layer summaries.
Text log ограничен10s/слой, окончание после suppressed промежуточной записи
получает отложенный flush. Cumulative provider-return/SDK-return/cancel/failure
counts разделены. Saturation явно сообщает unobserved scopes/layer requests;
inactive завершённые summaries переиспользуются только после throttle interval.
Observer снимается после завершения scopes/отложенных flush. Clock/writer/watch
failure не меняет исходную операцию и освобождает слот наблюдения.

Сохранены прежние geometry/attributes/IDs, clipping/anchor алгоритмы, gates,
кэши, free-only policy, cancellation и source epochs. Ни simplification,
прореживания, отключения слоёв, SQL/business writes, новых heap captures или
произвольного увеличения workers нет. Причина3–10s остаётся неподтверждённой;
следующий шаг — private normal build и повтор на том же планшете/проекте.

До edits выполнен один root read-only dependency plan по точным source/test/
docs/card paths: `sqlite-phase-diagnostics-impact01.json`,8,065B; analysis
UNKNOWN, source index REFRESHED_IN_MEMORY, source_context сохранён. Полный
38,778,434B stdout обработан только в RAM. Foreign desktop canonical index/
semantic registry остаётся follow-up владельца codex/roadmap-admission; это
ограничение не названо отсутствием NGW/Collector/desktop consumers. Формат
producer resources, saved projects/data/draft/sync не меняется.

Добавлены focused regressions phase/privacy/throttle/limits/overflow/failure
cleanup и совместимости backend/bridge bytes. Согласованный focused Gradle
PASS25s:39 tests (18 diagnostics,8 provider,13 bridge),0 failure/error/skipped;
полный log и JUnit counts сохранены в Q evidence. Центральные app docs,
dependencies/invariant/impact/smoke/config registries описывают этот контракт.
Maplibui/docs excluded: его callers/lifecycle/API не менялись, default overload
сохраняет совместимость. VectorLayer write/sync/Collector producer formats и
saved state не менялись; native write/Collector/desktop runs не подменены units.
Native/tablet/Full результат и performance fix не заявляются. Версии, merge и
публикация не менялись.

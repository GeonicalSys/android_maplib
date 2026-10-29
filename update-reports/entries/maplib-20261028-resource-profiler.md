---
id: maplib-20261028-resource-profiler
area: Диагностика ресурсов карты
kind: internal
at: 2026-10-28T20:16:28+01:00
title: Подробный opt-in учёт времени и объёмов работы карты без потери общего знаменателя
why: Владелец будет вручную менять масштабы и плотность карты на T40; legacy cap16/32/throttle не даёт полного рейтинга, process memory не разделяет владельцев, а wall ожидание очереди нельзя считать CPU
covers:
  - src/main/java/com/nextgis/maplib/util/RenderResourceProfiler.java
  - src/main/java/com/nextgis/maplib/util/MapRuntimeDiagnostics.java
  - src/test/java/com/nextgis/maplib/util/RenderResourceProfilerTest.java
---

MOB-04, новая диагностическая задача после слияния предыдущей app82/maplib61.
Владелец явно исключил долговременный кеш: требуется найти затраты холодной
подготовки и отображения, а не скрыть их повторным использованием результата.
Новая собственная ветка `codex/mobile-resource-profiling`; library → app adapter
и его bounded local writer → приватная диагностическая установка. Публикация
APK, Full и ранее отложенный длительный полевой запуск этим не объявляются
выполненными; установка/короткая проверка сбора имеют отдельные свидетельства.

`RenderResourceProfiler` default-OFF, не имеет file/SDK/DB/memory-sampler ownership.
Общий учёт не зависит от legacy наблюдателя и приёма raw записи writer-очередью.
Все accepted scopes увеличивают requests/outcomes/count/phase totals, включая
превышение 512 подробных active scopes; после 256 layer buckets используется
явный overflow bucket. Raw loss, sink failures, clock failures и layer overflow
не скрываются под полным результатом. Передаваемые массивы/структуры — отдельные
snapshots, библиотека не меняет их после передачи в `Sink.offer`.

`SqliteScope` маршрутизирует identity/phase/count/finish в новый token даже при
legacy NONE, не меняя прежних фаз/транспортов/current guards. Same-TID CPU clock
отделён от BOOTTIME wall time. Переходы Main/IO, регрессия/недоступность clocks
имеют явную неполноту. Stop не отменяет задачи renderer; активные scopes остаются
незавершёнными. Возвращённая summary должна сохраняться app отдельно от raw
очереди, чтобы отказ записи не уничтожил рейтинг потребителей.

Summary ограничена: полные global totals/histograms; compact numeric rows всех
256 слоёв; полные phase arrays top32 по CPU/elapsed и explicit omitted count.
Raw requests дают индивидуальные phase arrays для всех сохранённых подробных
scopes. Сумма длительностей перекрывающихся запросов не является сценой, а
phase histogram измеряет segments. PSS/native/SDK дельты не выдаются за точное
владение памятью слоя. Качество, геометрия, подписи, producer/NGW/SQLite schema,
сохранённые проекты, source epochs и cache keys не изменяются.

Адресные технические ожидания: OFF не создаёт tokens; cross-TID CPU не вычитается;
заполненная/бросающая очередь не теряет aggregates и не роняет renderer; double
finish не дублирует итог; active/layer overflow сохраняют знаменатель; stop
оставляет unfinished; metadata не принимает координаты, строки и mutable
numbers; фактические 96 scopes проходят через legacy cap16; worst-value footer
при 256 слоях остаётся меньше 192 КиБ. Это source tests, не измеренный overhead,
не native acceptance и не устранение причины низкой скорости/OOM.

Новый app adapter владеет включением, bounded writer/export, SDK frame/process
memory samples и correlation. Он не должен добавлять крупные записи в Sentry.
Перед commit source root обязан проверить существующий whole-contour plan и
сохранённый source_context: чужой desktop checkout остаётся недоступным для
записи, UNKNOWN/canonical index follow-up не подменяются end-to-end PASS.

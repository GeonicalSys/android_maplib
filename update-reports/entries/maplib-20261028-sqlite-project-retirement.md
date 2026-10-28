---
id: maplib-20261028-sqlite-project-retirement
area: Владение проектом и отмена чтения карты
kind: internal
at: 2026-10-28T04:32:00+01:00
title: Немедленная terminal отмена SQLite источников при замене проекта
why: Новый SQLite provider сохранял корректного старого DB owner и мог продолжать чтение или retry после closeMapObj до уничтожения Fragment
covers:
  - src/main/java/com/nextgis/maplib/map/SqliteSourceRetirement.java
  - src/main/java/com/nextgis/maplib/map/SqliteVectorLayerController.java
  - src/main/java/com/nextgis/maplib/map/SqliteVectorSourceBridge.kt
  - src/main/java/com/nextgis/maplib/map/MapDrawable.java
  - src/test/java/com/nextgis/maplib/map/SqliteSourceRetirementTest.java
  - src/test/java/com/nextgis/maplib/map/SqliteVectorSourceBridgeTest.kt
  - docs/sqlite-vector-source-bridge.md
---

MOB-04, адресный lifecycle follow-up нового SQLite native route. Any-thread
MapDrawable.retireSqliteVectorSources устанавливает terminal retirement fence,
закрывает зарегистрированные coroutine sessions/providers и инвалидирует epochs.
Только освобождение UI-owned references переносится на Main. Вызов не обращается
к SDK/app.getMap и не ждёт Main, поэтому допустим внутри synchronized app map
monitor. Обычный UI reset при Activity recreation не делает проект terminal.

SqliteSourceRetirement регистрирует отмену гонкоустойчиво: источник, создаваемый
одновременно с retirement, тоже немедленно отменяется. Registration закрывается
ровно один раз при remove/style reset/retire; новая generation после retirement
запрещена. Bridge.cancelPendingWork закрывает только Java/coroutine state.
SDK getter/detach остаются UI-owned. При source binding проверяется точное
тождество canonical model; callback удалённого/заменённого layer не восстанавливает
старый источник. Локальные edit follow-ups: hide пересоздаёт источник после
content version change; ID remap переносит exclusion только для совпадающего
active edited feature. Геометрия и сохранённый формат не меняются.

До edits: workspace preflight Fetch OK, текущий mobile/maplib OWN dirty;
maplibui master clean/fresh/no-open-PR, actor geoglyth, существующий pre-push hook.
В maplibui создана связанная собственная codex/mobile-crash-224. Whole-contour
plan sqlite-retirement-dependency-plan01.json: UNKNOWN, source index
REFRESHED_IN_MEMORY. Связь GISApplication→retire→controller→bridge→backend
прослежена обратно к Collector activate/delete/reset и прямо к pending tile jobs.
Desktop/NGW не получают новый schema contract; чужой desktop registry не менялся.
Parent task сохраняет canonical/app registry links и согласует delivery order:
maplib → maplibui → app submodule pointers. Ни один компонент не выпускался.

Созданы5 pure ownership tests (late registration, repeated close, normal source
replacement,200 register/retire races, reentrant registration и failed-close
isolation) и дополнительный bridge test: retirement из-под application monitor
завершается, пока Main блокирован, а IO guard уже false. На момент передачи
batch tests/Gradle/native/ADB NOT RUN. Прежние10 bridge tests проверяют SDK-free
session core, не public SDK constructor/UI guard/native JNI или реальные host
detach paths. Native project switch/delete/recreate и edit Save/Cancel нужны
отдельно; hard RAM/OOM/2GiB гарантий нет.

Новый полный dependency plan удерживается как evidence расследования наQ
(около33MiB), без runtime/SDK downloads. Дополнительных bulk temporary данных
не создавалось; план и будущие компактные unit receipts удерживаются до общего
закрытия memory incident, после чего их хранение пересматривается владельцем.

Общий fullunits02 после source handoff скомпилировал production и708tests:
706PASS,2FAIL только bridge test oracles (DEBUG Thread.name suffix и copied
IOException outer identity). Оба oracle исправлены с сохранением требований
Main/IO разделения, original cause и изоляции peer request. Production файлы
в этом test follow-up не менялись; повтор parent запускает отдельно, FAIL
не переписан и не объявлен PASS до нового результата.

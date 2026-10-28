---
id: maplib-20261028-sqlite-bridge-witness
area: Диагностика передачи SQLite тайлов в SDK
kind: internal
at: 2026-10-28T05:38:00+01:00
title: Ограниченный снимок стадий provider без бизнес-данных
why: Native software04 получал корректные polygon PBF вручную, но новый native source не показывал объекты; требовалось отличить отсутствие SDK запросов, отмену, IO и возврат в SDK
covers:
  - src/main/java/com/nextgis/maplib/map/SqliteVectorSourceBridge.kt
  - src/main/java/com/nextgis/maplib/map/SqliteVectorLayerController.java
  - src/main/java/com/nextgis/maplib/map/MapDrawable.java
  - src/test/java/com/nextgis/maplib/map/SqliteVectorSourceBridgeTest.kt
  - docs/sqlite-vector-source-bridge.md
---

MOB-04, диагностическое улучшение, не исправление неподтверждённой native причины.
software-sqlite-related04 завершился FAIL после style change: текущий provider
manual load выдавал4decoded PBF по15KiB с79/80Polygon, features/extent8192,
ожидаемыми GREEN31/RED62/RED99 и opacity1. Attached/focused видимый map view
оставался живым, но новые Fill/Line/Symbol queries давали0. Runtime trace
не доказал доставку этих PBF через штатный SDK provider. Original FAIL/JSON/PNG
и225KB logcat сохранены; logcat содержит только1336lines/4.3s вокруг финального
FAIL, без TestRunner started, поэтому отсутствие ранних SDK ошибок не заявлено.

SqliteVectorSourceBridge/session теперь ведёт constant-memory atomic counters:
provider entry requested, завершённый backend IO, guarded provider return,
cancel/retry/failure, bounded failure class, fixed cancellation enum, active,
closed и owner-job state. Controller/MapDrawable отдают pure Map<String,Object>
по getSqliteVectorDiagnostics(int), максимум12 scalar fields с source_registered.
Снимок не вызывает SDK/DB/app.getMap, не ждёт UI и не содержит path/coords/
geometry/properties/error messages. Обычная coroutine scheduling, IO admission,
backoff, source identities и lifecycle guards сохраняются; добавлены только
счётчики и их snapshot. Known stale-style/request CancellationException тексты
сопоставляются с fixed enum, произвольное сообщение никогда не публикуется.

Границы: requested начинается только в provider body; callback SDK в уже
отменённый scope может не попасть в него. IOsuccessful включает поздние байты,
которые guard отбросил. returned_to_sdk означает guarded return attempt, НЕ
native accepted/delivered/parsed/rendered. После close active может быть>0 до
завершения in-flight work; затем finally обнуляет его. Counters наблюдаются
независимо, не одним atomic transaction. Manual load/PBF witness не подменяет
эти стадии. Native acceptance остаётся самостоятельной и не ослабляется.

Согласован consuming test-only hook policy agent в NativeSqliteVectorRenderingTest:
failure JSON сохраняет JSONObject нового public snapshot. Root отдельно усиливает
versioned runner bounded UID-scoped full-start logcat. SDK fetch/cancel/private
activeJobs/JNI не подменяются; SDK/native dependency остаётся13.7.1 без fork.

Tests теперь12: существующие success/cancel/retry/peer failure/terminal retire
проверяют соответствующие counts и active0 после unwind. Отдельная regression
сохраняет owner scope и peer request после cooperative stale-style cancellation;
privacy expectation перечисляет fixed schema и запрещает error message в values.
Coroutine DEBUG остаётся включённым; Thread identity и bounded original-cause
oracle сохраняются. На source freeze compile/tests/ADB этим агентом NOT RUN;
root запускает combined checks и новый native evidence run. Диагностика не
объявляется устранением missing rendering, OOM или2GiB проблемы.

До edits выполнен whole-contour plan sqlite-bridge-witness-dependency-plan01.json:
UNKNOWN, source index REFRESHED_IN_MEMORY. Новые semantic edges provider-stage
counter→controller snapshot→consuming failure JSON описаны здесь/local doc;
parent обновляет canonical/app registries. Business/data formats и desktop/NGW
не меняются, миграции saved-state нет. Foreign desktop checkout не менялся.
Delivery вместе с maplib source и consuming app, публикация не выполнена.
Новый discovery plan удерживается наQ с memory incident evidence до общего
закрытия; runtime/SDK/download и новых bulk copies агент не создавал.

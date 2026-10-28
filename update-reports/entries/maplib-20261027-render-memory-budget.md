---
id: maplib-20261027-render-memory-budget
area: Память и отрисовка карты
kind: user
at: 2026-10-27T20:15:00+01:00
title: Экономия кэшей и диагностика памяти без упрощения объектов
why: Native std::bad_alloc и RSS до нескольких GiB требуют измерять весь процесс, снижать лишнюю работу и сохранять полную геометрию; одного Java headroom недостаточно
covers:
  - src/main/java/com/nextgis/maplib/map/LocalVectorTileProvider.java
  - src/main/java/com/nextgis/maplib/map/LocalVectorTileServer.java
  - src/main/java/com/nextgis/maplib/map/MPLFeaturesUtils.java
  - src/main/java/com/nextgis/maplib/map/MapDrawable.java
  - src/main/java/com/nextgis/maplib/map/MaplibreMapInteraction.java
  - src/main/java/com/nextgis/maplib/map/VectorLayer.java
  - src/main/java/com/nextgis/maplib/map/RenderMemoryPolicy.java
  - src/main/java/com/nextgis/maplib/map/RenderMemoryGuard.java
  - src/main/java/com/nextgis/maplib/map/RenderTileWorkGate.java
  - src/main/java/com/nextgis/maplib/map/LocalVectorTileDiskCache.java
  - src/main/java/com/nextgis/maplib/util/MapRuntimeDiagnostics.java
  - src/main/java/com/nextgis/maplib/util/RenderProcessMemory.java
  - src/test/java/com/nextgis/maplib/map/RenderMemoryPolicyTest.java
  - src/test/java/com/nextgis/maplib/map/RenderMemoryGuardTest.java
  - src/test/java/com/nextgis/maplib/map/RenderTileWorkGateTest.java
  - src/test/java/com/nextgis/maplib/map/LocalVectorTileFullContentTest.java
  - src/test/java/com/nextgis/maplib/map/LocalVectorTileDiskCacheTest.java
  - src/test/java/com/nextgis/maplib/util/MapRuntimeDiagnosticsTest.java
  - src/test/java/com/nextgis/maplib/util/RenderProcessMemoryTest.java
---

MOB-04; библиотека61 перед app82. Не выпущено, native приёмка не завершена.

Защита пересмотрена после read-only review: нет произвольного отбора ID,
упрощения геометрии, послойных запретов или фоновой подмены GeoJSON снимков.
Все успешные classic/MVT результаты содержат полную подходящую геометрию.
Системный дефицит означает явный retryable503 с возможностью повтора, а не неполный200.
Обычные два worker сохраняются; только текущий дефицит свободной системной или
Java памяти либо OS low-memory включает fair сериализацию. Номинальная RAM и
low-RAM class задают обычный disk cache и резерв32/64MiB, но сами по себе не
включают предупреждение или замедление. Used/native/RSS не являются триггерами.
Переполнение очереди диагностируется отдельно, но само по себе не означает
дефицита памяти и не вызывает memory warning или очистку кэша. App владеет уведомлением один
раз на процесс; список ограниченных слоёв пуст.

Кэш derived полных PBF ограничен32/128MiB,1024 файлами и4MiB на запись,
разделён по process/map/content/style generation. Межпроцессного reuse и
подкачки произвольного native heap нет. Coarse prefetch выключен.
Очищается только кэш текущего владельца map/view/host/lifecycle; Retry не
пересоздаёт все sources и не удваивает пик полной загрузки. Видимость модели,
исходные SQLite данные, редактирование/draft, sync/outbox не меняются.

BLOB чтение закрывает SQLiteStatement/PFD и избегает второго полного byte-array
при известном размере. Частые heading/GPS обновления не обходят весь native
стек слоёв; структурные изменения сохраняют прежнюю проверку порядка.
Журнал16KiB дополняют bounded source-stage и периодические RSS/native/system
counters, включая available RAM, Java headroom, системный порог/low-memory,
free reserve и tile work limit, с сохранением исходной identity после restart. Это наблюдения,
не точная атрибуция native ownership.

Ограничение: фиксированного лимита used/native/RSS нет. Реакция определяется
свободным остатком: сериализация и cache economy при system free<=512MiB либо
порога с резервом, малом Java headroom или OS low-memory; неизвестный счётчик
не подменяется нулём. SDK onLowMemory/cache-off не доказаны очисткой GeoJSONVT internal
cache. Последующий квалифицированный профиль787 установил крупное удержание
property maps; отдельное исправление —
[NativeFeaturePayload](maplib-20261028-native-geojson-payload.md). Оно сохраняет
полный rich Java снимок и исходную геометрию и совместимо с cache-only guard.
Внутренний GeoJSONVT index по-прежнему не имеет доказанного здесь eviction.
Итоговый расход и устройства2GiB требуют native приёмки. Восстановление SDK
после503 при возвращении памяти не проверено одним фактом retryable ответа.
Высокое плато не равно доказанной утечке; точный триггер223→224 неизвестен.
Никакое отсутствие повтора или unit PASS не объявляется устранением исходного
std::bad_alloc.

Проверки: combined `:maplib:testDebugUnitTest` завершён успешно за35s:
643 tests,0 failures/errors/skips, включая policy6/cache4/full-content1/
concurrency3, source/RSS diagnostics и отдельные pattern regressions.
Квитанции `memory-cache-review-units01.log/json` сохранены внеGit в evidence
текущей задачи. Этот643-test checkpoint предшествует последней free-only policy;
он не подтверждает её текущее исполнение. Последующая651-test проверка после
MLP payload handoffs тоже предшествует последней policy. Новые source/native
проверки фиксируются отдельно. Это проверка source/Java, не устройства.
Source/Native/публикация различаются. Protected ANR/RTree исправления0e70971
сохранены. Центральные registry и приёмку верхнего app ведёт основная задача.

Последний queue-warning follow-up проверяет отдельно: при4GiB свободной RAM
повторный queue_full не включает warning/economy, при реальном малом свободном
остатке задержка уведомляет один раз, а устройство2GiB с достаточным остатком
сохраняет два builder без warning. MapRuntimeDiagnosticsTest явно сохраняет
новые free-headroom поля при restart, исключая старые process-budget keys,
координаты и database path. Final-path read-only plan:
evidence/free-memory-notice-impact01.json,3пути, UNKNOWN/PLUGIN_ONLY,
REFRESHED_IN_MEMORY; он дополняет38-path dependency-plan-app-memory-docs02.json.
Чужой dirty desktop source index не менялся; синтаксические/межрепозиторные
links остаются delivery follow-up, а не доказанным end-to-endPASS.

Последние focused unit проверки прошли после free-only/queue fix:
23/23,0failures/errors/skips,11s: Guard2,Policy8,MapRuntime13. Actual XML и
hashes сохранены в evidence/free-memory-notice-units01.json/log. Это адресный
JVM/Robolectric PASS, не новый full651/nativeGPU/2GiB/Full результат.

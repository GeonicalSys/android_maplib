---
id: maplib-20261114-android-20261114-recovery-sync-without-map
area: Разработка
kind: internal
at: '2026-11-14T09:14:02.561011+00:00'
title: Проверка блокировки синхронизации не требует уже созданной карты
why: Согласованные завершённые изменения рабочей сессии
covers:
- src/main/java/com/nextgis/maplib/util/ProjectRecoveryGuard.java
- update-reports/entries/maplib-20261114-android-20261114-recovery-sync-without-map.md
---

Проверка блокировки синхронизации не требует уже созданной карты

Решения и зависимости:

- isSyncBlocked and isHeld on a layer now resolve the owning map, the sync-scoped map, or the already initialized active map. A layer with no project is not treated as blocked and no longer calls MapBase.getInstance, which threw before any map existed. Hold and delivery files remain fail-closed when a map directory exists. Repeated Robolectric opens still hit SQLITE_CANTOPEN while setting WAL on this Windows host; that runtime failure is not declared a passing native gate.

Полный журнал, входные хэши и результаты проверок: сессия mobile-geo-ux-rel-01a1d22d, задача android-20261114-recovery-sync-without-map. Неизвестные связи и отдельные native/GUI проверки не объявляются пройденными.

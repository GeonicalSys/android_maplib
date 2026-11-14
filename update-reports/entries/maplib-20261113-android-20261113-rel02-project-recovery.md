---
id: maplib-20261113-android-20261113-rel02-project-recovery
area: Восстановление данных
kind: internal
at: '2026-11-13T18:22:24.470627+00:00'
title: 'REL-02: сохранена начальная основа карантина и архивирования; интеграция продолжается'
why: Согласованные завершённые изменения рабочей сессии
covers:
- src/main/java/com/nextgis/maplib/map/NGWVectorLayer.java
- src/main/java/com/nextgis/maplib/map/SharedTrackStore.java
- src/main/java/com/nextgis/maplib/util/ProjectRecoveryGuard.java
- src/main/java/com/nextgis/maplib/util/WorkspaceSqliteSnapshot.java
- update-reports/entries/maplib-20261113-android-20261113-rel02-project-recovery.md
---

REL-02: сохранена начальная основа карантина и архивирования; интеграция продолжается

Решения и зависимости:

- SOURCE STAGE ONLY: maplib/maplibui Java/Kotlin compile PASS (rel02/source-01.log). REL-02 feature remains incomplete: recovery UI, full fixtures, large-BLOB snapshot support, external form/asset closure, archive metadata validation and final emulator acceptance are still required. No claim of completed recovery or runtime PASS. Native blocked by available host RAM below 6 GiB.
- Current user added an independent roadmap cleanup on 13 November. Preserve this source-stage attribution, perform prose-only maintenance under a new task ID, then resume REL-02 integration and the other requested mobile features. No source changes are discarded.

Полный журнал, входные хэши и результаты проверок: сессия mobile-geo-ux-rel-01a1d22d, задача android-20261113-rel02-project-recovery. Неизвестные связи и отдельные native/GUI проверки не объявляются пройденными.

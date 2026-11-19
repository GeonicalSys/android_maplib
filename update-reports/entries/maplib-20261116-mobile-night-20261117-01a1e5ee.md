---
id: maplib-20261116-mobile-night-20261117-01a1e5ee
area: Мобильное приложение
kind: internal
at: '2026-11-17T03:49:58.690070+00:00'
title: PERF-01, MVT-01, AUTH-01 and SCRIPT-UI-01 overnight implementation and targeted
  physical-device verification
why: Согласованные завершённые изменения рабочей сессии
covers:
- src/main/java/com/nextgis/maplib/map/MapDrawable.java
- src/main/java/com/nextgis/maplib/map/VectorLayer.java
- update-reports/entries/maplib-20261116-mobile-night-20261117-01a1e5ee.md
- update-reports/entries/maplib-20261117-mvt-committed-freshness.md
---

PERF-01, MVT-01, AUTH-01 and SCRIPT-UI-01 overnight implementation and targeted physical-device verification

Решения и зависимости:

- Implement the four authorized overnight roadmap items on the idle verified session; preserve all Desktop and shared AGENTS work
- MVT-01 native RED: committed insertion followed by SQLite-source attachment leaves a nonempty legacy Java feature collection on A56/API37. No stale-pixel diagnosis inferred.
- A committed DELETE must notify rendering even when the independent legacy spatial-cache entry is absent. A missing cache item does not mean the database row was not deleted.
- AUTH-01 and SCRIPT-UI-01 have passed targeted physical checks on the current source candidate; retain exact original failures and test-oracle corrections separately.
- Retain native findings, correct the asynchronous export oracle and host-only upload/setup transport, and keep broader acceptance boundaries explicit.
- Finish the mobile night stage at owner request; preserve implemented AUTH/SCRIPT/MVT, failed PERF findings, updated roadmap and exact pending device cleanup; leave shared Desktop session active.
- Correct only unsupported roadmap enum spellings before task end; preserve partial product scope and failed/unknown runtime acceptance.

Полный журнал, входные хэши и результаты проверок: сессия karniilp-01a1e5c4, задача mobile-night-20261117-01a1e5ee. Неизвестные связи и отдельные native/GUI проверки не объявляются пройденными.

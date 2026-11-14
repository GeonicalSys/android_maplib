---
id: maplib-20261114-android-20261114-recovery-delivery-owner
area: Восстановление и защита от дублей
kind: user
at: '2026-11-14T02:32:56.382862+00:00'
title: Единственная отправляющая копия восстановленного проекта
why: Согласованные завершённые изменения рабочей сессии
covers:
- src/main/java/com/nextgis/maplib/map/NGWVectorLayer.java
- src/main/java/com/nextgis/maplib/util/ProjectRecoveryGuard.java
- src/test/java/com/nextgis/maplib/util/ProjectRecoveryReviewTest.java
- update-reports/entries/maplib-20261114-android-20261114-recovery-delivery-owner.md
---

Единственная отправляющая копия восстановленного проекта

Решения и зависимости:

- Read affected Guard, review, worker, UI, receipt APIs and coordinator. Complete context-01 delivered; model reading is distinct. Added durable recovery delivery ownership under the exclusive lease after explicit UI choice. Source and related copies retain all data and queue; sync is paused. New restores preserve ancestry and only remove a validated inherited marker in new staging. Known sibling ACK for the same immutable creation UUID requires server reconciliation, never stale retry approval. Active track/walk/stakeout are untouched and review is deferred while active.
- Android local recovery contract only; no NGW server/schema/form rule change. Cross-device/absent sibling outcomes remain manual/UNKNOWN. No exactly-once backend claim.
- Compile App and native tests PASS; selected maplib JVM results retained in unit-xml. Native extended partial-form and source/copy ACK scenarios are pending the final emulator run. Full/phone/production signed acceptance NOT_RUN.

Полный журнал, входные хэши и результаты проверок: сессия mobile-geo-ux-rel-01a1d22d, задача android-20261114-recovery-delivery-owner. Неизвестные связи и отдельные native/GUI проверки не объявляются пройденными.

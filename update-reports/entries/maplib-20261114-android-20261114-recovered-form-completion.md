---
id: maplib-20261114-android-20261114-recovered-form-completion
area: Восстановление полевых правок
kind: user
at: '2026-11-14T01:49:07.694630+00:00'
title: Проверяемое завершение формы в восстановленной копии проекта
why: Согласованные завершённые изменения рабочей сессии
covers:
- src/main/java/com/nextgis/maplib/map/NGWVectorLayer.java
- src/main/java/com/nextgis/maplib/util/FeatureSaveJournal.java
- src/main/java/com/nextgis/maplib/util/ProjectRecoveryGuard.java
- src/test/java/com/nextgis/maplib/util/ProjectRecoveryReviewTest.java
- update-reports/entries/maplib-20261114-android-20261114-recovered-form-completion.md
---

Проверяемое завершение формы в восстановленной копии проекта

Решения и зависимости:

- A full-form completion receipt is written after all attribute/photo/signature writes, before checkpoint clear; it stores immutable Save UUID, row and a streaming digest of exact queued operation identities. Recovery marker captures the completed-receipt AUTOINCREMENT high-water mark and exact copied old holds. Only a later full Save matching the current queue can clear those copied holds; active/current owners, later partial edits, another row/project and pre-restore receipts remain protected. New completed-save metadata is copied by the existing full SQLite snapshot. Old public feature-only reconciliation/retry methods retain their creation-only operation scope.
- 44 targeted JVM tests PASS, libraries/App/native test classes compile PASS. New JVM discriminators cover pre-restore/retired receipts, later mutation invalidation, current owner and sibling row preservation. Native actual photo Save and archived partial-form recovery cases have been extended; final emulator run is pending the following source/version/document closure stage.

Полный журнал, входные хэши и результаты проверок: сессия mobile-geo-ux-rel-01a1d22d, задача android-20261114-recovered-form-completion. Неизвестные связи и отдельные native/GUI проверки не объявляются пройденными.

---
id: maplib-20261113-android-20261114-changes-centre
area: Изменения и синхронизация
kind: user
at: '2026-11-14T00:46:11.763173+00:00'
title: 'UX-02: центр конкретных изменений, достоверных квитанций и безопасного повтора'
why: Согласованные завершённые изменения рабочей сессии
covers:
- src/main/java/com/nextgis/maplib/map/NGWVectorLayer.java
- src/main/java/com/nextgis/maplib/map/VectorLayer.java
- src/main/java/com/nextgis/maplib/util/FeatureChanges.java
- src/main/java/com/nextgis/maplib/util/ProjectRecoveryGuard.java
- src/main/java/com/nextgis/maplib/util/UploadAttachmentRemap.java
- src/main/java/com/nextgis/maplib/util/UploadOperationJournal.java
- src/test/java/com/nextgis/maplib/map/NGWAttachmentUploadAckTest.java
- src/test/java/com/nextgis/maplib/map/NGWVectorLayerUploadAckTest.java
- src/test/java/com/nextgis/maplib/util/ProjectRecoveryReviewTest.java
- src/test/java/com/nextgis/maplib/util/UploadOperationJournalTest.java
- update-reports/entries/maplib-20261113-android-20261114-changes-centre.md
---

UX-02: центр конкретных изменений, достоверных квитанций и безопасного повтора

Решения и зависимости:

- Read-only centre pages read saved configs and OPEN_READONLY SQLite directly; they must not load or migrate background maps. Mutating review resolves the registered project and exact layer path, reserves identity then transitions the same exclusive lease to a normal scoped DATA_SYNC owner so callbacks can drain. UUID, target and current queue membership are rechecked by Core. Active tracking, walk and stakeout must keep running; the UI defers conflicting review actions rather than stopping owners.
- Core-unit-02: 38 targeted JVM checks PASS, including object/attachment receipts, lost response, invalid attachment IDs, later metadata, orphan/corrupt input and local retry after accepted association. The earlier core-unit-01 had one invalid coalescing stimulus: production addChange appends a new row, so that test legitimately drained both edits in one pass. Corrected the stimulus to use the public coalescing API and added a separate appended-row test. Failed logs/XML retained. No new native/GUI result is claimed yet.
- Changes centre reads current queue and retired receipt metadata without loading/switching background maps; explicit reconciliation resolves trusted registry UID under exclusive lease transitioned to scoped DATA_SYNC. All saved-copy pending rows require exact UUID review before hold release. Restored target checks archived canonical path, resource/type and account/server/login hashes. Attachment reconciliation GET compares metadata and streaming SHA/length; no mutation HTTP during review. Active track/walk/stakeout is never stopped and defers mutating review.
- Native-07 PASS: 19 distinct emulator cases, including all 3 actual changes-centre scenarios, 4 recovery archive/quarantine cases, 5 local delivery cases, form recovery and 6 reliable changes/restart cases. Core 42 JVM tests PASS. Emulator/ANGLE/settings cleaned; no device/publication/Full claim.

Полный журнал, входные хэши и результаты проверок: сессия mobile-geo-ux-rel-01a1d22d, задача android-20261114-changes-centre. Неизвестные связи и отдельные native/GUI проверки не объявляются пройденными.

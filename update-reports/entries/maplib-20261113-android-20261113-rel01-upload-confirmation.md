---
id: maplib-20261113-android-20261113-rel01-upload-confirmation
area: Надёжность синхронизации
kind: user
at: '2026-11-13T17:48:05.956426+00:00'
title: 'REL-01: достоверный ACK и восстановление неопределённой отправки'
why: Согласованные завершённые изменения рабочей сессии
covers:
- src/main/java/com/nextgis/maplib/map/NGWVectorLayer.java
- src/main/java/com/nextgis/maplib/util/FeatureSaveJournal.java
- src/main/java/com/nextgis/maplib/util/UploadIdRemap.java
- src/main/java/com/nextgis/maplib/util/UploadOperationJournal.java
- src/test/java/com/nextgis/maplib/map/NGWVectorLayerUploadAckTest.java
- src/test/java/com/nextgis/maplib/util/UploadOperationJournalTest.java
- update-reports/entries/maplib-20261113-android-20261113-rel01-upload-confirmation.md
---

REL-01: достоверный ACK и восстановление неопределённой отправки

Решения и зависимости:

- REL-01: durable per-workspace mutation receipts; strict server IDs; uncertain POST is held for explicit server-read reconciliation. Remap/ACK preserves later changes and other workspaces. Phone/publication deferred by current user; active GPS work modes retain their owners.
- Owning-database upload receipts fence uncertain feature POST; form Save holds prevent remap or upload before attachment copy/terminal draft handling. LocalChangesWorker and full sync continue to use sendLocalChanges; full project clock remains independent.
- No accepted server idempotency/soft-delete/author contract is introduced. A death after remote acceptance but before saving the response is uncertain; UI must use exact server-read reconciliation or explicit duplicate-aware retry. Backward downgrade/replay by an older APK is not verified. Physical phone and publication remain deferred.
- Current source: maplib UploadOperationJournalTest 6 + NGWVectorLayerUploadAckTest 7 PASS (unit-06.log). Consumer Java/Kotlin and AndroidTest compilation passed. Native attempt native-01: APK build PASS, emulator NOT_RUN at resource admission (available 5.83 GiB, required 6 GiB; no low-memory signal, commit 44.8%). No physical phone. Native/source final acceptance remains pending in the final verification stage after the four feature implementations.
- At finalization document REL-01 SQLite receipts, strict ID/atomic remap/ACK, non-replay of uncertain POST, owner-scoped form holds, exact server-read reconciliation and explicit retry. Carry actual native outcomes separately; preserve source-only and old-APK downgrade limits.

Полный журнал, входные хэши и результаты проверок: сессия mobile-geo-ux-rel-01a1d22d, задача android-20261113-rel01-upload-confirmation. Неизвестные связи и отдельные native/GUI проверки не объявляются пройденными.

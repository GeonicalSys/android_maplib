---
id: maplib-20261113-android-20261114-reliability-native-fixes
area: Надёжность мобильной отправки и восстановления
kind: user
at: '2026-11-13T22:38:53.362616+00:00'
title: 'REL-01/REL-02: исправления по эмулятору и завершение native-проверок'
why: Согласованные завершённые изменения рабочей сессии
covers:
- src/main/java/com/nextgis/maplib/map/NGWVectorLayer.java
- src/main/java/com/nextgis/maplib/util/FeatureChanges.java
- src/main/java/com/nextgis/maplib/util/UploadOperationJournal.java
- src/main/java/com/nextgis/maplib/util/WorkspaceSqliteSnapshot.java
- src/test/java/com/nextgis/maplib/map/NGWVectorLayerUploadAckTest.java
- src/test/java/com/nextgis/maplib/util/FeatureChangesOperationIdentityTest.java
- src/test/java/com/nextgis/maplib/util/UploadOperationJournalTest.java
- update-reports/entries/maplib-20261113-android-20261114-reliability-native-fixes.md
---

REL-01/REL-02: исправления по эмулятору и завершение native-проверок

Решения и зависимости:

- REL-01 native regression fixed with immutable per-mutation queue UUIDs. Numeric queue-ID reuse cannot select an old receipt; coalesced later CHANGED edits survive an older create ACK. Legacy unbound creations remain uncertain, receipt migration preserves history, and 408/409/425 do not authorize automatic POST replay. Missing stale form selections resolve only through the exact form save UUID and do not write replacement checkpoints.
- Confirmed Android-only saved-state and archive closure. Queue UUID and acknowledged token snapshots are copied with the full SQLite archive; generated columns participate in rowid-shadow detection. No NGW published field, Web form rule or server API was changed. Source links reviewed against calls/imports; discovery UNKNOWN groups remain open.
- Independent expectations: no duplicate POST on uncertain result; exact bytes/IDs/sequence/generated rowids and application R-tree survive archive restore; source can remain corrupt and active project never switches. Corrupt/wrong-project/schema/space archives never register or replace data. Original malformed draft bytes export unchanged; old operation cannot clear a newer draft. 27 JVM tests and App/native compilation PASS. Native-04 retains 12 REL-01/LocalChanges/FormSave PASS plus 3 recovery PASS and one invalid fixture failure. Native-06: all 4 recovery cases PASS, including actual create/restore UI and corrupt-source DB; emulator cleanup verified. Evidence: Q:/Codex/Scratch/mobile-reliability-01a1d22d/evidence/native-fixes and native-04/native-06.
- This completes the scoped correction/checkpoint, not the four roadmap features. UX-02 and MOB-REL-03 still require implementation; archive shared-underlay and UI font/active-mode matrices require further coverage. Existing 3 documentation source-drift tripwires remain pending semantic review. Native-02 had an OS-confirmed ANR without a retained main-thread stack; it did not recur in native-04, so its root cause remains UNKNOWN. Native-03 was NOT_RUN due to host-memory admission, not product failure. No physical phone, Full, merge, deployment or publication acceptance is claimed.
- Context-02 full registered delivery succeeded for the complete task scope after a one-file query correctly omitted App/MaplibUI rules. Delivery and model reading kept separate; nearest policies and affected source/consumer paths were reviewed. Execution authority comes from the user request, not execution_permission=false context records. Publisher preflight is fresh, clean, 0/0, identity geoglyth and no open PRs; generic Android preflight dirty rows are the already owned session, not discarded changes.

Полный журнал, входные хэши и результаты проверок: сессия mobile-geo-ux-rel-01a1d22d, задача android-20261114-reliability-native-fixes. Неизвестные связи и отдельные native/GUI проверки не объявляются пройденными.

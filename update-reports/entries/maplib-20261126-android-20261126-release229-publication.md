---
id: maplib-20261126-android-20261126-release229-publication
area: Android publication
kind: user
at: '2026-11-26T09:38:36.812420+00:00'
title: Verify and publish Android release 229 for LISA and BELKA
why: Согласованные завершённые изменения рабочей сессии
covers:
- agent-system/knowledge/documentation/after-commit-ready-change-selection-and-full-clock-ownership.json
- agent-system/knowledge/documentation/hidden-vector-reload-resource-url-and-catalogue-traversal.json
- agent-system/knowledge/documentation/module-readme-frontmatter-and-title.json
- build.gradle
- docs/README.md
- src/main/java/com/nextgis/maplib/map/MapContentProviderHelper.java
- src/main/java/com/nextgis/maplib/map/NGWVectorLayer.java
- src/main/java/com/nextgis/maplib/util/LisaHistoryFields.java
- src/main/java/com/nextgis/maplib/util/NgwFeatureData.java
- src/main/java/com/nextgis/maplib/util/NgwFeatureTransaction.java
- src/main/java/com/nextgis/maplib/util/UploadTransactionJournal.java
- src/test/java/com/nextgis/maplib/map/MapContentProviderHelperTest.java
- src/test/java/com/nextgis/maplib/map/NGWVectorLayerHistoryFieldsTest.java
- src/test/java/com/nextgis/maplib/map/NGWVectorLayerUploadAckTest.java
- src/test/java/com/nextgis/maplib/util/NgwFeatureDataTest.java
- src/test/java/com/nextgis/maplib/util/NgwFeatureTransactionTest.java
- src/test/java/com/nextgis/maplib/util/UploadTransactionJournalTest.java
- update-reports/entries/maplib-20261126-android-20261126-release229-publication.md
- update-reports/entries/maplib-20261126-history-uploads.md
- update-reports/entries/maplib-20261126-legacy-recovery-229.md
- update-reports/entries/maplib-20261126-unbound-layer-account-filter.md
---

Verify and publish Android release 229 for LISA and BELKA

Решения и зависимости:

- Only the two reviewed mobile owners were committed and pushed. The Desktop index and unrelated work are preserved. Android native acceptance is scoped to the user's emulator-only instruction for release 229.
- Published both stable flavors as version 3.1.2.35 (229). Public manifests, history and both downloadable APK hashes match the accepted signed artifacts. Previous release notes remain included. User-scoped emulator acceptance and owned scratch cleanup are recorded.

Полный журнал, входные хэши и результаты проверок: сессия 2026-11-25, задача android-20261126-release229-publication. Неизвестные связи и отдельные native/GUI проверки не объявляются пройденными.

---
id: maplib-20261115-rel03-resource-safety
area: Мобильное приложение — REL-03
kind: user
at: '2026-11-15T16:11:49.987765+00:00'
title: Полная синхронизация ограничивает память и временный диск
why: Не материализовать крупные ID-наборы и не изменять данные без безопасного backup
covers:
- agent-system/knowledge/documentation/module-readme-frontmatter-and-title.json
- agent-system/knowledge/documentation/rel03-resource-bounded-full-snapshot.json
- agent-system/knowledge/maplib-module-manifest/manifest-key-components-ngwfeaturebudgetreader-rel03.json
- agent-system/knowledge/maplib-module-manifest/manifest-key-components-ngwfullsnapshotindex-rel03.json
- agent-system/knowledge/maplib-module-manifest/manifest-key-components-ngwsyncresourcebudget-rel03.json
- agent-system/knowledge/maplib-module-manifest/manifest-key-components-ngwvectorlayer-66f4602770.json
- agent-system/knowledge/maplib-module-manifest/maplib-module-manifest.json
- agent-system/provenance/structured-views.json
- docs/README.md
- docs/manifest.yaml
- src/main/java/com/nextgis/maplib/map/NGWVectorLayer.java
- src/main/java/com/nextgis/maplib/map/NgwFullSnapshotIndex.java
- src/main/java/com/nextgis/maplib/util/NgwFeatureBudgetReader.java
- src/main/java/com/nextgis/maplib/util/NgwSyncResourceBudget.java
- src/test/java/com/nextgis/maplib/map/NGWVectorLayerFullSnapshotResourcesTest.java
- src/test/java/com/nextgis/maplib/map/NgwFullSnapshotIndexTest.java
- src/test/java/com/nextgis/maplib/util/NgwSyncResourceBudgetTest.java
- update-reports/entries/maplib-20261115-rel03-resource-safety.md
---

ID/backup/delete/cleanup план на дисковой SQLite, cursor traversal, ограничение remote feature до WKT и локальной строки до hydration, actual-byte disk reserve, bounded64KiB error body. >512 destructive IDs идут через прежний whole-layer backup gate. Staging/index cleanup при каждом terminal path. Реальные technical SQLite tests сохраняют предыдущую базу/outbox/attachment при сбое и проверяют безопасный retry.

REL-03, по прямому поручению владельца. Только относящиеся к изменению проверки; общая регрессия, Full, планшет и публикация отложены. FORM-01 не возобновлялась. Изменения сохраняются локально; merge/push/release не выполняются. Parent pointers не переводятся на неслитые библиотечные ветки.

Изменённый контракт: частный поток полного NGW snapshot и частный журнал Android обхода. Публичный IGISApplication backup API, HTTP206 admission, SQLite/outbox atomicity, point-owner lock, другие части/отверстия геометрии и исходные категории формы сохранены. Новые owner/sequence/root keys и журнал format1 читаются поверх прежних full-WKT/part-only настроек; существующий UUID/sequence0 — миграционная база. Журнал внутри owning map входит в прежний проектный архив. Android-only: Web, Desktop/Qt, NGW publication metadata и save scripts не меняются; syntactic/semantic closure прослежена до app preview, worker, backup и архивирования. Новые подтверждённые связи внесены в own dependency/config/invariant/module records. Неподтверждённые динамические ссылки не объявляются покрытыми.

Standalone route: прежняя общая сессия CLOSED; Desktop содержит чужие изменения lab-regression, они не редактируются и не включаются. Android owners начали с clean/synchronized pinned heads, fetch и open-PR проверки проведены, hooks установлены. Собственная предыдущая сверка дорожной карты сохранена отдельным локальным docs commit86d9c5ca. Источники библиотек на своих codex/rel03-resource-safety branches.

Контекст: live owners `.`, App, maplib, maplibui; source baseline и input digests сохранены в evidence. Перед записью подтверждены 525 страниц manifest/content, независимые required IDs/fields и CURRENT (generation e873ac51b8ac025a4eecf43c8696965d7a2f800e9736bd40c58ac4a02540ff87). Зарегистрированная доставка отделена от чтения релевантных тел. Глобальный full_context_complete=false и прежние UNKNOWN/foreign observations сохраняются; это не полная семантическая приёмка экосистемы. Финальная source-frame сверка записывается отдельно.

Адресные результаты и границы: см. Q:/Codex/Scratch/rel03-resource-safety-01a1d9ac/evidence/ и final-result.json. XML, исходные неуспешные попытки, APK/input hashes и cleanup receipts сохраняются. Injected SQLite/space failure проверяет отказ/rollback, не физическое заполнение пользовательского диска. Source/JVM/emulator checks не являются физической матрицей, общим Full или выпуском. Поведение при физическом power cut и реальные low-storage/long-day устройства остаются для финальной приёмки.

Итог адресных проверок: 66/66 unit (maplib39, maplibui27; 9 классов), ноль ошибок/пропусков. LisaRelease и BelkaRelease source compile PASS с kotlin.incremental=false. Native Related: 7 различных сценариев/12 запусков PASS на Android36 Google APIs r7, Emulator37.2.13 GuestAngle/Lavapipe/WHPX, 2 CPU/2560MiB. Реальные process death, kernel reboot и отзыв GPS permissions восстановили принятый журнал; три прежние сервисные GPS-регрессии PASS. Owned VM/Job/console завершены, Job remaining0, shared ADB не перезапускался. Предыдущие host admission/environment отказы сохранены как NOT_RUN native, не скрыты.

Исходный native fingerprint: 4e69ebc6ad5e761dcab7f365c0a9281f6801dfc75c25d0d2f4dd5d258bce838a. Последующие правки ограничены knowledge/documentation/contract status; runtime/test bytes проверяются native-doc-closeout-delta.json. Этот Related receipt не объявляется актуальным Full/publication gate. Обязательная финальная сверка frame/graph/context и unknowns фиксируется в context-final-receipt.json и final-result.json; временные базы удаляются после закрытия, компактные доказательства сохраняются.

Доставка после адресной приёмки: владелец15.11.2026 прямо поручил commit/push/merge текущей работы и проверенных общих файлов. Ранние ограничения «локально/без merge» выше описывают предыдущий этап, новым поручением они заменены только для доставки; release/deployment/Full остаются отдельно. Библиотеки сливаются Merge Commit, App — Squash Merge после обновления pins до слитых commit. Runtime/test bytes с исходной квитанцией не меняются. Связанный общий реестр: lisa-20261115-rel03-resource-contract; общий Desktop/Web набор сохраняется в собственных репозиториях.

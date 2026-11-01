---
id: maplib-20261101-class-metadata-repair
area: Надёжность синхронизации NGW
kind: user
at: "2026-11-01T14:59:26+04:00"
title: Исправление сохранённого класса слоя без повторного скачивания данных
why: Известный vector/PostGIS class drift не вызывал уже предусмотренное исправление metadata.
covers:
- src/main/java/com/nextgis/maplib/util/NGWLayerSchemaCompat.java
- src/test/java/com/nextgis/maplib/map/NGWVectorLayerClassMetadataRepairTest.java
- docs/README.md
---

В исходниках исправлен normal preflight: если NGW сообщает другой известный
vector/PostGIS класс, а геометрия и физическая схема уже совпадают, исправляется
сохранённая metadata без refill. Пустой, неизвестный и неподдержанный класс
не получают новой политики; необходимость настоящего schema rebuild сохраняется.

Регрессии вызывают actual comparison и private refresh/verified helper, подменяя
только transport, physical-schema check и save ACK. Проверены оба направления,
равные и устаревшие поля, unchanged-description shortcut, первый/второй save(false)
и повтор; account/remote_id и sync-настройки сохраняются. Успешный первый field save
не объявляется atomic rollback при неуспешном class save.

Те же frozen14 tests: исходный код — 6 ожидаемых assertion FAIL/8 PASS/0 errors/skips;
после исправления — 14 PASS/0 failures/errors/skips. Служебная классификация RED
сначала не приняла два JUnit ComparisonFailure; оригинал и точное пояснение
сохранены без изменения тестов. Offline consumer Java/Kotlin compile для Core/UI
Debug/Release и App LisaDebug/BelkaRelease прошёл, 192-task graph совпал с dry-run,
без native/packaging/sign/device tasks. Все Jobs завершены штатно.

Реальные NGW/SQLite/устройство/AVD, выпуск и APK не запускались. Технические
результаты не устанавливают текущий полевой инцидент или deployment acceptance.

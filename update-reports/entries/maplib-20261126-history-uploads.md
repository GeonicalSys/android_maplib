---
id: maplib-20261126-history-uploads
area: Отправка изменений NGW
kind: user
at: '2026-11-26T00:00:00+04:00'
title: Служебные поля истории ЛИСы остаются под управлением сервера
why: Создание и изменение PostGIS-объекта передавало служебные audit-значения клиента и могло быть отклонено защитой истории.
covers:
- src/main/java/com/nextgis/maplib/map/NGWVectorLayer.java
- src/main/java/com/nextgis/maplib/util/LisaHistoryFields.java
- src/test/java/com/nextgis/maplib/map/NGWVectorLayerHistoryFieldsTest.java
- src/test/java/com/nextgis/maplib/map/NGWVectorLayerUploadAckTest.java
---

Новый create/update payload для подтверждённого PostGIS-слоя ЛИСы не содержит
`created_at`, `created_by`, `updated_at`, `updated_by`, `deleted_at`, `deleted_by`
и `deletion_operation_id`. Их значения определяет сервер. Фильтрация ограничена
известными таблицами `dynamic`, подтверждёнными class/resource/account/server
и ожидаемыми типами audit-полей. Только совпадения имён недостаточно.

Это отдельная первая доработка: исправление сериализации служебной истории.
Обратная совместимость старой структуры, ручная сверка legacy226 и transport
reviewed creation описаны следующей карточкой `maplib-20261126-legacy-recovery-229`;
они не выдаются за исходный scope этого исправления.

Обычные числовые бизнес-поля и существующее преобразование numeric SQLite NULL
в0 не изменены. Другие серверы/таблицы не получают такого исключения. Сохранённые
uncertain receipts не переписываются и не переотправляются автоматически.
При явной сверке из JSON-копий исключаются только подтверждённые служебные поля;
бизнес-значения и вся геометрия продолжают участвовать в сравнении.

Потребители: обычная синхронизация NGW и прямой LocalChangesWorker через общий
`sendLocalChanges`; authoritative metadata проверяется перед новой upload pass,
а proof не переживает её завершение. Новая сериализация влияет на создаваемые
snapshots, а не изменяет прежнее доказательство возможной отправки. Desktop
стандартизация/PostGIS history определяют типы и владение полями; Web читает тот же
NGW resource, но не исполняет Android save scripts. Реальные schema/permissions
заказчика не менялись и не считаются принятыми исходными тестами.

Проверки: первоначальный combined JVM receipt более поздней recovery-доработки
содержал Core90 + App8 =98 PASS до последних review-изменений. Итоговый JVM06
для замороженных production/test исходников229 завершился PASS: Core96 + App8
=104; сохранённая квитанция `evidence/jvm-migration-06/receipt.json` находится
в task-owned Q evidence. Последующие правки документации не меняют этот проверенный
код, но не устанавливают native acceptance. Emulator/native/normal APK проверки
и публикация остаются pending. Ранние emulator fixtures не заменяют реальный
NGW/PostGIS acceptance и не скрывают startup ANR других групп.

Публикация и merge этой карточкой не заявляются. Не добавлены клиентские
credentials, реальные данные, SQL дампы или приватные runtime artifacts.

На итоговой сборке 229 прошли адресные проверки фильтра и отправки со старой структурой на эмуляторе. Обе подписанные сборки содержат проверенный код исключения служебных полей.

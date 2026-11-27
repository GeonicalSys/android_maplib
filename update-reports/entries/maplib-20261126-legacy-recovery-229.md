---
id: maplib-20261126-legacy-recovery-229
area: Восстановление накопленной очереди NGW
kind: user
at: '2026-11-26T00:00:01+04:00'
title: Старая очередь сохраняет точки и различает сверку, повтор и известное подтверждение
why: Старые структуры audit-полей и очередь226 без доказательства сетевой попытки нельзя безопасно обновить одним повторным POST или удалением локальных объектов.
covers:
- build.gradle
- src/main/java/com/nextgis/maplib/map/NGWVectorLayer.java
- src/main/java/com/nextgis/maplib/util/LisaHistoryFields.java
- src/main/java/com/nextgis/maplib/util/NgwFeatureData.java
- src/main/java/com/nextgis/maplib/util/NgwFeatureTransaction.java
- src/main/java/com/nextgis/maplib/util/UploadTransactionJournal.java
- src/test/java/com/nextgis/maplib/map/NGWVectorLayerHistoryFieldsTest.java
- src/test/java/com/nextgis/maplib/map/NGWVectorLayerUploadAckTest.java
- src/test/java/com/nextgis/maplib/util/NgwFeatureDataTest.java
- src/test/java/com/nextgis/maplib/util/NgwFeatureTransactionTest.java
- src/test/java/com/nextgis/maplib/util/UploadTransactionJournalTest.java
- agent-system/knowledge/documentation/after-commit-ready-change-selection-and-full-clock-ownership.json
- agent-system/knowledge/documentation/module-readme-frontmatter-and-title.json
- docs/README.md
---

После обновления APK накопленные данные со старой локальной структурой из четырёх
audit-полей могут отправляться до перезагрузки конфигурации. Фильтр первой карточки
`maplib-20261126-history-uploads` расширен для совместимой частичной структуры;
новый authoritative metadata proof всё равно обязателен. Бизнес-поля и обычный
numeric NULL→0 не меняются. Release-кандидат:229/3.1.2.35, Debug218/3.1.2.23.

Для настоящей226 пять колонок старой очереди не различают «никогда не отправляли»
и «POST принят, ответ потерян». App226 source pin указывает на maplib
`3437353f1bddee7379d7f81003a7e89f24d2467c`; UUID/upload receipt появились позже,
в `fa4af75ddf745037ecb45d1727e1b8a3a55ef734`. Тесты старых четырёх полей с уже
современным UUID сами по себе не доказывают миграцию очереди226. Не выполняется
массовая генерация разрешений на повтор по совпадающим атрибутам или геометрии.

Оператор явно сверяет сохранённую creation с исходным NGW. Совпадения в ограниченной
выборке помогают выбору ID; отсутствие совпадений, RLS-видимость или similarity
не доказывают, что объект не создавался. Строгое сравнение учитывает все части,
кольца, Z/M и бизнес-значения. Отдельное явное решение о привязке существующего ID
сохраняет нынешние локальные данные новым CHANGED UUID до ACCEPTED/remap, включая
случай редактирования после старой попытки. Frozen ACK create его не поглощает.
Коллизии строк, вложений, файлов, другой target и активная форма блокируют операцию;
слияние, удаление исходной точки и HTTP mutation при ручной привязке не выполняются.

Только подтверждённая новая reviewed creation допускает NGW Transaction API.
Opt-in записывается атомарно с upload snapshot; старый plain SENDING без такого
доказательства остаётся защищённым. Поддержка сервера/metadata проверяется заново:
сохранённая версия слоя не служит актуальным доказательством, а отказ допуска
не приводит к plainPOST fallback. WKT snapshot фиксируется в3857, после чего WKB
при необходимости готовится в SRS ресурса, отличном от storage SRID PostGIS.
Source hash, target, типы, SRS, operation, tid и commit boundary сохраняются;
HTTP подготовки выполняется вне SQLite transaction. Обычные новые create прежние.

Потеря allocation может оставить только пустой server transaction; тот же PUT
повторяет неизменный operation/sequence. После durable commit_started разрешён
только GET результатов того же tid, без нового commit/tid или plainPOST.
Из primary NGW source следует раздельный commit внешнего PostGIS и центрального
NGW receipt; это граница, не проверенная аварией реального сервера. NotCommitted
после неоднозначного ответа оставляет UNKNOWN, а не разрешает повторную вставку.
Валидный положительный commit response сохраняется до GET fid и запрещает
NeverSent/reset даже при неизвестном ID. GET с подтверждённым fid позволяет
завершить прежний локальный ACK. Известные receipt/Tx ID нельзя подменить при review.

Разрешённый оператором reset архивирует все прежние Tx columns и исходные
payload/state/reason/boundary/ACK tokens receipt, затем удаляет только техническую
current Tx row в той же SQLite transaction, что RETRY_ALLOWED. COMMITTED proof,
known fid, активная отправка или изменившийся context не разрешают reset.
Новая очередь и поздние изменения сохраняются; workspace, form/TEMP/NOT_SYNC,
backup/remap ownership и отдельные full completion clocks не обходятся.

Initial verification: Core90 + App8 =98 JVM PASS до последних review-изменений.
Итоговый JVM06 для замороженных production/test исходников229: Core96 + App8
=104 PASS, квитанция `evidence/jvm-migration-06/receipt.json` сохранена в task-owned
Q evidence. Последующие docs-only изменения не считаются новым runtime proof.
Emulator Related/native, normal APK DEX/signature/version и публикация ещё pending;
этот текст не является общим PASS кандидата229. Пользователь разрешил
для этого выпуска только emulator acceptance вместо physical-device gate;
это не отменяет остальные обязательные проверки и не меняет общую release policy.
Fixture fault models не устанавливают live NGW/PostGIS acceptance. Итоговые receipts
будут внесены после owning runner; production phone/DB этим изменением не правились.

Обратная связь: Desktop etalon/стандартизация владеют field types и server history,
Android — локальной очередью/сверкой, Web потребляет опубликованный NGW resource.
Конкретные опубликованные customer schema/permissions и скрытые RLS строки остаются
runtime boundary. Приватные настройки, credentials, базы и клиентские логи в Git
не включены. Эта карточка не заявляет merge, выпуск или успешное восстановление
каждого реального устройства до выполнения соответствующих проверок.

Итоговый проход `jvm-migration-08` подтвердил 99 случаев Core и 9 случаев App. Полное покрытие 164 сценариев на эмуляторе подтверждено в `native-release229-03-verified-cohorts`, включая фактический перезапуск, потерю ответа и повторное чтение транзакции. Один нестабильный сценарий обхода прошёл повтор на той же сборке. Подпись, версии и код в двух Release APK проверены. Принятие данными рабочего WebGIS остаётся отдельной проверкой.

---
id: maplib-20261028-tile-cache-queue
area: Производительность подготовки тайлов
kind: user
at: 2026-10-28T22:30:00+01:00
title: Запись тайла без повторного просмотра каталога и отменяемый допуск к IO
why: Ручной T40 trial выявил дорогой scan/regex/sort/stat каталога на каждом put и большой backlog отменённых SDK запросов; владелец поручил убрать эти корневые расходы и собрать обычный APK без замерщика
covers:
  - src/main/java/com/nextgis/maplib/map/LocalVectorTileDiskCache.java
  - src/main/java/com/nextgis/maplib/map/SqliteVectorSourceBridge.kt
  - src/main/java/com/nextgis/maplib/map/SqliteVectorTileProvider.java
  - src/test/java/com/nextgis/maplib/map/LocalVectorTileDiskCacheTest.java
  - src/test/java/com/nextgis/maplib/map/SqliteVectorTileAdmissionTest.kt
---

MOB-04, прямое продолжение текущих собственных Draft PR62/84 после анализа
ручного T40-прогона. Предыдущие профили — основание выбора, не замер скорости
этой реализации. Новых profiler/device/Full запусков, установки, публикации и
слияния пользователь не просил; готовится обычный APK для его смартфона.

Изменяется только обслуживание существующих производных PBF и допуск штатных
SDK Job к блокирующей IO работе. Состав и полнота тайлов, объекты/геометрии/
подписи/scale gates, SQLite originals, owner/source/content/style fences,
редактирование/синхронизация и cache identity сохраняются. Новый долговременный
кэш, прореживание и эвристическое удаление нужных родительских тайлов не вводятся.

Кэш: ограниченный общий индекс по canonical root, актуальный lookup при каждой
операции; учёт размера/числа и recency в памяти. Первичная inventory и редкая
reconciliation при обнаруженных внешних изменениях заменяют scan/sort на каждом
put. Сохраняются byte/file/entry budgets, optional miss, полная атомарная PBF
публикация и безопасный отказ при filesystem failure. Native передаёт дешёвый
current guard внутрь записи; HTTP сохраняет прежние внешние проверки current/key
и совместимый трёхпараметрический API, чтобы socket peer probe с timeout1мс не
вызывался для каждого файла под общей блокировкой. Его отдельная очередь и
глубокая peer cancellation этим шагом не меняются. Внешний конкурентный writer не
считается поддержанным: все штатные cache writers одного процесса используют
общий ledger, удаление cache Android безопасно.

Очередь: общий отменяемый допуск2 перед shared IO dispatcher. Отменённые SDK
ожидания не создают blocking payload на каждый старый запрос. Permit живёт до
фактического unwind IO, освобождается на worker до Main и идемпотентно при отмене
до начала. SDK определяет актуальные запросы; число pending SDK jobs этим не
ограничивается, некооперативные активные bodies сохраняют permit до выхода.
Глобальный memory work gate остаётся отдельным механизмом resource headroom.

Обязательные проверки: filesystem byte/file bounds через реальные Q temp files,
нулевой повторный inventory на hot puts, общие экземпляры/registry eviction,
ошибки write/rename/delete, cache loss и cancellation; queued cancellation,
noncooperative unwind, Main blocked, predispatch cancel, retry/failure guards;
существующие complete PBF/geometry/label/lifecycle regressions и compile consumers.
Фактические результаты и source/APK provenance дописываются после выполнения.

Source closure: read-only whole-contour plan до правки, отдельный HTTP consumer
seed; семантические связи сохраняются в центральных app registries. Общий desktop
checkout чужой и dirty, generated canonical index write остаётся UNKNOWN/follow-up;
его исходники/контракты не редактируются. SQLite/NGW/desktop schema не меняются.

Фактическая проверка:70 JVM tests/9classes PASS50s, включая25cache и7newadmission;0fail/error/skip. Normal LisaDebug/LisaRelease/BelkaRelease PASS1m50s; version matrix PASS, app/maplib versions218/3.1.2.23 debug и224/3.1.2.30 release совпали. Source fingerprint1658runtime/buildfiles1ea9ac886867116d6e3d5c6707e83b74f6caad7644410e563441cdc37952de3d до/после сборки неизменен. LisaAPK70580085B/SHA256 b0f55cda77d4bc3965d00e12a316654f9a7de98c9eab060ded7e56337bb67e37, обычный signer, DEBUG/FIELD_VERIFICATION/PRIVATE_RESOURCE_PROFILING false, profileable element отсутствует. Установка, замерщик, native/Full и публикация не выполнялись.

Whole-contour pre-change checks exit0; final refresh дважды INVALID из-за меняющихся/несогласованных контрактных документов чужого desktop checkout (standart_profiles/AGENTS.md и geonical-docs scenario-dependencies/local-ci/mobile-client). Это НЕ PASS. Runtime mobile inputs заморожены и совпали до/после; новые semantic contracts сохранены в app registries. Canonical generated index write/check остаётся UNKNOWN/follow-up владельцу root; чужие файлы не замораживались/не переписывались. Поставка — только явно запрошенный private candidate, не сквозная release acceptance.

После самостоятельной проверки APK владелец сообщил: «Почти моментально отрисовывается всё». Это его положительный субъективный отзыв; численные замеры, точная device/APK attestation и полнота объектов/подписей не подтверждены этим сообщением. Владелец явно отложил формальную проверку на потом. Агент дополнительных тестов/замерщика/установки не запускал.

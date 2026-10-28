---
id: maplib-20261028-sqlite-vector-bridge
area: Память и жизненный цикл карты
kind: internal
at: 2026-10-28T02:29:00+01:00
title: Отменяемый мост SQLite MVT к штатному CustomVectorSource
why: Для чтения обычных векторных слоёв по тайлам нужен мост без общего GeoJSONVT индекса и без поздних native callbacks после закрытия источника
covers:
  - build.gradle
  - src/main/java/com/nextgis/maplib/map/SqliteVectorSourceBridge.kt
  - src/test/java/com/nextgis/maplib/map/SqliteVectorSourceBridgeTest.kt
  - docs/sqlite-vector-source-bridge.md
---

MOB-04. Новый SqliteVectorSourceBridge передаёт полный MVT из отдельного Java
backend в штатный CustomVectorSource SDK OpenGL13.7.1. Он не создаёт HTTP server
или общий GeoJSON снимок. JNI копирует PBF; zero-copy и гарантированный предел
native RAM не заявляются. SDK/native библиотека и её классы не подменяются.

Каждое поколение source владеет Main.immediate scope и SupervisorJob.
closeBeforeDetach вызывается UI владельцем до remove/style/project/destroy:
closed flag, отмена scope, единственный nonblocking backend.close. Blocking IO
выполняется interruptibly на общем dispatcher с2workers. Backend сохраняет
своё единственное admission gate. Guard включает source owner, immutable
content/style/source stamp и Job конкретного тайла. После отмены/смены stamp
поздний успех или IOException не превращается в native setTileData/setTileError.

Только RetryableTileException вызывает отменяемое ожидание100..2000ms и повтор
с тем же immutable request. IO/permits освобождены до ожидания; неполный или
выдуманный пустой успешный тайл при нехватке ресурсов не возвращается. Настоящие
активные ошибки не подавляются; сбой одного запроса не отменяет остальные.

Совместимость: min/max source zoom0..25; публичный bridge API вызывается с UI,
owner supplier читает только thread-safe Java state. Host обязан закрыть bridge
до native detach и создать новое поколение для повторного attach. Геометрия,
rules/styles/labels, выделение/редактор, исходные SQLite/schema/sync форматы здесь
не изменяются. Их tile backend и host integration проверяются отдельными
карточками и native gates; JVM doubles не удостоверяют полноту карты.

Для первого Kotlin source module подключён существующий Kotlin plugin2.2.10
и JVM21, как в maplibui; coroutines-android1.10.2 уже есть в shared cache.
Новый runtime/SDK/download не создавался. Блокирующих работ/ADB/build/Git этот
ограниченный implementation batch не запускал.

До edits выполнены preflight и whole-contour dependency plan
sqlite-bridge-dependency-plan01.json: UNKNOWN, source index REFRESHED_IN_MEMORY.
Неизвестное покрытие не названо PASS. Syntactic links bridge→backend/SDK и
семантика owner-before-detach/typed retry описаны здесь и в local doc; parent
task обновляет app registry и canonical source index при общей delivery closure.
Desktop checkout принадлежит другой задаче и его registry здесь не менялся.
Saved-state migration отсутствует; desktop/NGW producers не получают нового
формата. Взаимные зависимости backend/host deliver вместе до включения route.

Focused tests созданы для IO/Main dispatch, terminal close, late IO error,
индивидуальной отмены, retry без удержания IO, смены content/owner, сохранения
активных ошибок и общего лимита2workers между source generations. На момент
карточки они ещё не запущены: compile/test handoff согласуется с parent task,
native lifecycle/style/labels/edit/GPU и2GiB acceptance остаются NOT EXECUTED.
Merge/release/publication не выполнялись. Новых bulk temporary данных нет;
dependency plan и компактные receipts удерживаются в Q evidence расследования.

Первый общий unit прогон после lifecycle integration:708executed,706PASS/2FAIL;
оба FAIL относятся к диагностическим деталям этих tests, а не к проверенному
IO dispatch: coroutine DEBUG добавляет `@coroutine#...` к Thread.name и может
создавать IOException copy. Oracle исправлен: реальные Thread identities и
принадлежность owned IO executor, точный Main Thread и отсутствие IO на Main;
тип/сообщение IOException и оригинальный instance в bounded16 cause chain.
Никакие debug flags или production bridge не изменены. Exact coroutines1.10.2
[ExceptionsConstructor](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/kotlinx-coroutines-core/jvm/src/internal/ExceptionsConstructor.kt)
создаёт copy с оригиналом в cause; StackTraceRecovery вызывает этот путь.
Исходный FAIL сохранён parent task, повторная проверка ожидается отдельно.

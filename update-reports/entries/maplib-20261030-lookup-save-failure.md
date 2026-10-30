---
id: maplib-20261030-lookup-save-failure
area: Надёжность синхронизации NGW
kind: user
at: '2026-10-30T07:59:34.596633+00:00'
title: Неудачное сохранение справочника отмечает проход ошибкой
why: Две ветви обновления игнорировалиfalse отsave, поэтому проход мог выглядеть завершённым.
covers:
- src/main/java/com/nextgis/maplib/map/NGWLookupTable.java
- AGENTS.md
- docs/README.md
- docs/manifest.yaml
---

Если обновлённый справочник не удалось сохранить, текущий проход синхронизации
получает ошибку. Ранее обе ветви NGWLookupTable.sync игнорировали false отsave,
и результат прохода мог оставаться чистым. Изменение подготовлено в Core Draft #63;
установленное приложение получит его после обычного выпуска.

В двух существующих местах добавляется один numIoExceptions к уже имеющемуся
SyncResult. Map owner и новая память, порядок clear/putAll, один save, исключения
RuntimeException/Error и equal-next no-write сохранены. Ничего не откатывает память,
не делает компенсирующую запись и не исправляет retry/disk durability. Последующий
равный ответ может не повторить save — AUDIT-TODO-NGW-LOOKUP-CACHE-PERSISTENCE открыт.

ProjectSyncRunner требует !part.hasError для pair ACK и полного успеха для project
clock. IO от этого прохода препятствует их подтверждению; ранее успешный push/другой
account не откатывается. Это source-backed consumer, не исполненная приёмка очереди
или диска. Queued generic incomplete message отличается от legacy IO fallback;
новый сетевой текст или immediate retry не обещаны.

App15 actual public-sync technical methods исполнялись внутри shared19:
3 named assertion RED/12controls →15PASS/0errors/skips, XML2.298с; Core4 дают19 всего,
не19+15 и не15+старые9. Проверяются обе ветви/существующие IO+другие counters,
map identity/новая память/одна запись/throws/equal-next/foreign controls. Save double
не является физическим AtomicFile/disk/restart witness. Синтаксически корректный, но неполный200,
samehash cache readiness/shared deletion/account backup27 не исправлялись.

Source c0a4b7f682ae18a2e34a93580abc1371ee3de4c8a1ce83acd95dad54e542af25;
inverse ровно двух expressions восстанавливает8ca98f59 целиком. Test7d697ccd baseline
и GREEN одинаков. Belka source0/39с/set100/orderdifferent/7PIDstop отделён от первого
compile-dry nativeunknown/NOT_PASS и recovery. Старые9/19/22/21/12 не rerun,
UI39/142/19/F61 неизменён; результаты новых metadata checks записаны в соседней
Core206 карточке, включая прежние clock/Wiki NOT_PASS. Общего PASS нет.

[Полная точная public recipe и ограничения](maplib-20261030-full-snapshot-http206.md)
живут в соседней новой Core карточке. Source Draft доставка не означает merge,
App pointer, APK release, deploy или native acceptance; библиотечный Merge Commit
должен предшествовать scoped App pointer после merge.

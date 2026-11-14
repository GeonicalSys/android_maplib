---
id: maplib-20261113-android-20261113-rel02-recovery-integration
area: Сохранность мобильных проектов
kind: internal
at: '2026-11-13T20:30:42.098645+00:00'
title: 'REL-02: сохранён компилируемый этап архива и экрана восстановления; native-проверки
  продолжаются отдельно'
why: Согласованные завершённые изменения рабочей сессии
covers:
- src/main/java/com/nextgis/maplib/map/MapContentProviderHelper.java
- src/main/java/com/nextgis/maplib/util/WorkspaceSqliteSnapshot.java
- src/test/java/com/nextgis/maplib/util/WorkspaceSqliteSnapshotTest.java
- update-reports/entries/maplib-20261113-android-20261113-rel02-recovery-integration.md
---

REL-02: сохранён компилируемый этап архива и экрана восстановления; native-проверки продолжаются отдельно

Решения и зависимости:

- Копия SQLite переносит полные значения через bounded CursorWindow, сохраняет native storage types, внутренние rowid, sequence, schema, application_id и encoding. Android metadata создаётся/восстанавливается отдельно от собственного bootstrap. 4 адресных JVM теста PASS; SQLite JVM не содержит rtree, поэтому пространственная проверка остаётся для реального эмулятора.
- Архив охватывает сохранённый workspace: map/layer config, NGFP form/meta sidecars (LayerUtil.formFiles; LayerFillService/GISApplication сохраняют их в layer path), проектные script ZIP/reference и БД с outbox/receipt/hold. Общие immutable подложки включаются по ссылкам; глобальные namespaces других проектов исключены в legacy map root. Глобальная активная запись/SharedTrackStore не переносится и не останавливается; recovery-origin блокирует повторную миграцию старой истории. Неоконченная глобальная форма остаётся отдельным журналом/карантином; архив не выдаётся за экспорт всей памяти приложения.
- snapshot-unit-02: 4 PASS / 0 failures / 0 errors. Проверены 6 MiB+ BLOB и 3 MiB+ TEXT по независимым SHA, ID/int64/NULL/empty/REAL, rowid, WITHOUT ROWID, sequence, FK/index/view/trigger, application_id, отмена и отказ без транзакции. JVM SQLite не имеет rtree; проверка настоящего rtree НЕ выполнена и перенесена на эмулятор. compile-01: MaplibUI Java/Kotlin и App LisaDebug Kotlin/Java PASS, 87 tasks, без assemble/устройства.
- Добавлен экран из настроек проекта: архив/экспорт, проверка и восстановление LOCAL-копии, экспорт исходников/повтор карантина. Собственная отмена прерывает только собственный worker и ждёт его unwind; активные трек/обход/вынос не останавливаются. Исходный проект не активируется/переключается автоматически, новая очередь held до UX-02 сверки. Версия схемы читается статически, чтобы restore не зависел от открытия повреждённой исходной БД. При общем clear черновика другого workspace исходник сначала карантинируется.
- ЭТО ЗАВЕРШЁННЫЙ SOURCE CHECKPOINT, НЕ ЗАВЕРШЕНИЕ REL-02/всего пакета. Следующим этапом: написать/запустить ProjectRecoveryNativeTest и зарегистрировать runner/plan; проверить большой BLOB/rtree, archive hash/CRC/identity/version/disk/cancel, отдельную LOCAL копию, оригинал/активный проект, формы/подложки/вложения/outbox, карантин/тип prefs/ошибку сохранения/экспорт, UI real-window и рабочие режимы. Проверить и обновить после смыслового review 3 прежних UI tripwire замечания; local/central docs/semantic contracts, asset closure и source graph ещё требуют финализации. UX-02 согласует held очереди, в том числе NEW после старого снимка; старый canonical workspace path и archive SHA сохранены в recovery origin. Физические устройства/Full/подпись/merge/выпуск NOT_RUN. MOB-REL-03 и UX-02 пока не реализованы.

Полный журнал, входные хэши и результаты проверок: сессия mobile-geo-ux-rel-01a1d22d, задача android-20261113-rel02-recovery-integration. Неизвестные связи и отдельные native/GUI проверки не объявляются пройденными.

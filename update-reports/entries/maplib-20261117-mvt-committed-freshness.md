---
id: maplib-20261117-mvt-committed-freshness
area: Мобильная карта — MVT-01
kind: user
at: '2026-11-17T07:30:00+04:00'
title: Сохранять пустой streaming-список и обновлять карту после committed delete
why: Ночной native-прогон воспроизвёл удержание полного списка и след удалённого объекта
covers:
- src/main/java/com/nextgis/maplib/map/MapDrawable.java
- src/main/java/com/nextgis/maplib/map/VectorLayer.java
- update-reports/entries/maplib-20261117-mvt-committed-freshness.md
---

Streaming bind заменяет прежний полный Java-список пустым совместимым списком.
Отложенный classic reload повторно проверяет текущий путь перед чтением,
после чтения и перед публикацией на Main, поэтому не заполняет список после
перехода на SQLite. После committed delete render-инвалидация и notification
выполняются также при отсутствии объекта в отдельном spatial cache; прежние
условия сохранения metadata/cache сохраняются.

Native ожидание независимо от production encoder: реальные SQLite insert,
update и delete, исходная геометрия/FID, подпись, identify и пиксели уже видимой
области без pan/restart; A→B→A→B с одинаковыми настоящими layer ID/FID.
Сценарий PASS на A56/API37, T40/API30 и CPU/API36. Последний CPU Related
прошёл также все 10 прежних SQLite rendering cases. Адресный Java unit-набор
Core/UI: 92 PASS. Admission, edit, источники данных и сохранённые ID не менялись.

Сохраняются отрицательные прогоны: промежуточный content/style case превышал
30 с и затем прошёл на неизменённом Core, причина не доказана. Тяжёлая карта
смартфона может не сообщать fully-rendered кадр за 120 с, хотя независимая
проверка видимой сцены проходит; это остаётся открытым PERF-01.
Общий Full, sync→visible-frame, полевая матрица, merge и выпуск не выполнены.
Изменения остаются в собственной общей рабочей ветке без commit/push.

Точные source/APK bases, неуспешные попытки и receipts:
`Q:/Codex/Scratch/mobile-night-20261117-01a1e5ee/retained`.
Семантическая связь: committed VectorLayer delete → render content epoch →
MapDrawable/SQLite native source; classic async publication должна соблюдать
текущий streaming admission. Канонические архитектурные записи согласуются
при финализации общей сессии, без вмешательства в Desktop.

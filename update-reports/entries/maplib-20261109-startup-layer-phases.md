---
id: maplib-20261109-startup-layer-phases
area: Мобильное приложение
kind: internal
at: '2026-11-09T15:14:16+04:00'
title: Подробные стадии загрузки слоёв
why: Различить файловый ввод и запрос SQLite в отчёте о зависании
covers:
  - agent-system/knowledge/documentation/startup-diagnostic-observer-and-bounded-original-journal.json
  - src/main/java/com/nextgis/maplib/map/Table.java
  - src/main/java/com/nextgis/maplib/map/VectorLayer.java
  - src/main/java/com/nextgis/maplib/util/MapRuntimeDiagnostics.java
  - src/test/java/com/nextgis/maplib/util/MapRuntimeDiagnosticsTest.java
---

При GlitchTip №46, №67 и №122 независимый наблюдатель получает текущую стадию и накопленные времена. Операции вложены в существующую трассу старта. Тест проверяет наблюдение во время SQLite и восстановление вложенной стадии после выхода.

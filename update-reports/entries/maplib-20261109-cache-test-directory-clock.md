---
id: maplib-20261109-cache-test-directory-clock
area: Мобильное приложение
kind: internal
at: '2026-11-09T23:32:17+04:00'
title: Стабилизировать проверку повторных обходов кэша тайлов
why: NTFS могла обновить отметку времени папки после завершения тестовой записи
covers:
  - src/main/java/com/nextgis/maplib/map/LocalVectorTileDiskCache.java
  - src/test/java/com/nextgis/maplib/map/LocalVectorTileDiskCacheTest.java
---

Проверка горячей записи использует реальные файлы и управляемую отметку времени
папки. Она требует один исходный обход и ровно одну проверку целевого файла
на каждую новую запись. Проверка внешних изменений использует отметку
реальной папки, как и код приложения.

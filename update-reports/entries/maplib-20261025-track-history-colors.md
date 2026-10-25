---
id: maplib-20261025-track-history-colors
area: Мобильное приложение
kind: user
at: 2026-10-25T17:25:00+01:00
title: Тонкая линия трека и постоянные цвета прежней истории
why: Широкие линии закрывали карту, а прежние треки без индивидуального цвета не различались
covers:
  - src/main/java/com/nextgis/maplib/map/TrackMapStyle.java
  - src/main/java/com/nextgis/maplib/map/TrackColorPalette.java
  - src/main/java/com/nextgis/maplib/map/SharedTrackStore.java
  - src/test/java/com/nextgis/maplib/map/LegacyTrackColorsTest.java
  - src/test/java/com/nextgis/maplib/map/SharedTrackStoreTest.java
---

Чёткая линия уменьшена с 4 до 2, свечение с 10 до 6, выделение с 7 до 4.
Общая база обновляется до schema 2: прежние NULL цвета получают сохранённые
насыщенные оттенки с полным циклом 12 цветов до повторения. Вручную выбранные
цвета не меняются. Та же транзакционная обработка работает для позднего импорта
старого проекта. ID, имена, видимость, точки, сегменты и sent не меняются;
при ошибке откатываются и раскраска, и версия базы, повтор безопасен.

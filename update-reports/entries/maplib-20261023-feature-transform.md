---
id: maplib-20261023-feature-transform
area: Мобильное приложение
kind: user
at: 2026-10-23T19:23:46+02:00
title: Цельные геометрические преобразования жестами
why: Сдвигать и поворачивать объект целиком с сохранением его структуры
covers:
  - src/main/java/com/nextgis/maplib/util/GeometryTransform.java
  - src/main/java/com/nextgis/maplib/map/GeometryTransformGesture.java
  - src/main/java/com/nextgis/maplib/map/MapDrawable.java
  - src/main/java/com/nextgis/maplib/map/MaplibreMapInteraction.java
  - src/test/java/com/nextgis/maplib/util/GeometryTransformTest.java
---

Projected transforms сохраняют CRS, части и отверстия, не меняя камеру/масштаб. Preview checkpoint не создаёт Undo шаги; завершённый жест создаёт один, CANCEL возвращает точные предыдущие координаты. Default host callback совместим с существующими consumers. Поставка через maplibui #43 и app #77.

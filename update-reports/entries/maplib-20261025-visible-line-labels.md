---
id: maplib-20261025-visible-line-labels
area: Мобильное приложение
kind: user
at: 2026-10-25T18:25:00+01:00
title: Одна цельная подпись по направлению видимой части линии
why: На изгибах текст распадался по буквам; направление всего объекта не подходит для текущего экстента
covers:
  - src/main/java/com/nextgis/maplib/map/VisibleLineLabelLayout.java
  - src/main/java/com/nextgis/maplib/map/VisibleLineLabelController.java
  - src/main/java/com/nextgis/maplib/map/RuleLabelLayouts.java
  - src/main/java/com/nextgis/maplib/map/MPLFeaturesUtils.java
  - src/main/java/com/nextgis/maplib/map/MapDrawable.java
  - src/test/java/com/nextgis/maplib/map/VisibleLineLabelLayoutTest.java
---

Текст LineString/MultiLineString рисуется единым прямым блоком в отдельном
точечном источнике. Для каждого SQL ID остаётся одна подпись на видимую часть.
Направление — взвешенное по длине преобладающее направление видимых сегментов;
точка привязки остаётся на видимой линии ближе к центру экрана. Подпись может
пересекать несколько сегментов и не требует прямого участка длиной со слово.

Расчёт клиппирует истинный четырёхугольник экрана с учётом поворота, учитывает
переход долготы через 180°, отменяет устаревшие задания и сохраняет исходные
геометрию/ID/стили. Используются уже загруженные данные, без нового чтения SQLite;
работа идёт в фоне после остановки камеры. Native сохраняет font/halo/цвет,
collision/zoom/category; settled orientation учитывает keep-upright. Старые
repeat/curvature параметры остаются в JSON, но одна прямая подпись на текущий
экстент — новый явно выбранный пользователем контракт.

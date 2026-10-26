---
id: maplib-20261025-mixed-track-hit-candidates
area: Мобильное приложение
kind: user
at: 2026-10-25T22:15:00Z
title: Треки участвуют в выборе вместе с точечными объектами
why: При пересечении точечный объект исключал трек из hit candidates
covers:
  - src/main/java/com/nextgis/maplib/map/MapDrawable.java
---

queryTrackIds возвращает все совпавшие видимые сеансы и объединяет их сегменты
по ID даже при попадании в точку. Приоритет рисования точек не фильтрует
результат выбора. Consuming app объединяет эти ID с векторными кандидатами;
native регрессия и полный набор выполняются в app PR #78. База и схема треков
не меняются. Зависимость: библиотека #60 Merge Commit → remote pin app #78.

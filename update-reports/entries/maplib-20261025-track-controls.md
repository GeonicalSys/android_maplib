---
id: maplib-20261025-track-controls
area: Мобильное приложение
kind: user
at: 2026-10-25T11:30:00+01:00
title: Цветные треки со свечением и точечные слои поверх линий
why: Треки должны различаться по цвету и не закрывать точечные объекты
covers:
  - src/main/java/com/nextgis/maplib/map/TrackColorPalette.java
  - src/main/java/com/nextgis/maplib/map/TrackMapStyle.java
  - src/main/java/com/nextgis/maplib/map/TrackLayer.java
  - src/main/java/com/nextgis/maplib/map/MPLFeaturesUtils.java
  - src/main/java/com/nextgis/maplib/map/MapDrawable.java
  - src/test/java/com/nextgis/maplib/map/TrackColorPaletteTest.java
---

Новые треки получают сохранённый насыщенный случайный цвет. Существующие и
вручную выбранные цвета сохраняются. Цвет каждого сеанса передаётся в общие
MapLibre sources и рисуется чёткой линией с мягким свечением; выбор выделяет все
его сегменты. Число style layers не растёт с числом треков.

Настоящие Point/MultiPoint слои с ореолами и подписями подняты над треками и
остальными данными с сохранением порядка между собой. Временные инструменты
и GPS выше. Тап по видимой точке сохраняет существующий выбор объектов.
Подготовка GeoJSON/тайлов, server coordinate stream и start/end flags не меняются.
Контракт и фактические проверки находятся в consuming app документации.

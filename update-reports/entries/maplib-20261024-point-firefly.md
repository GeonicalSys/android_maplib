---
id: maplib-20261024-point-firefly
area: Мобильное приложение
kind: user
at: 2026-10-24T00:06:05+02:00
title: Точки получили отдельный стиль с чётким ядром и внешним свечением
why: Прежнее размытие затрагивало середину точки и не позволяло получить чёткий внутренний край
covers:
  - src/main/java/com/nextgis/maplib/display/FireflyMarkerRegistry.java
  - src/main/java/com/nextgis/maplib/display/SimpleMarkerStyle.java
  - src/main/java/com/nextgis/maplib/display/GISDisplay.java
  - src/main/java/com/nextgis/maplib/map/MplFeatureStyleProps.java
  - src/main/java/com/nextgis/maplib/map/MPLFeaturesUtils.java
  - src/main/java/com/nextgis/maplib/map/MapDrawable.java
  - src/main/java/com/nextgis/maplib/map/mpl/FireflyLayerFactory.java
  - src/main/java/com/nextgis/maplib/map/mpl/PointLayerFactory.java
  - src/test/java/com/nextgis/maplib/display/FireflyMarkerTest.java
  - src/test/java/com/nextgis/maplib/map/LocalVectorTileProviderTest.java
---

У «Светлячка» середина остаётся чёткой, а свечение убывает только наружу.
Цвет и непрозрачность ядра и свечения независимы. Прозрачная середина
не заполняется ореолом. Стиль сохраняется для категорий и масштабируется
вместе с точкой; прежнее размытие других типов сохранено.
При смене стиля уже загруженные упрощённые точечные слои переходят
к обычной отрисовке, чтобы сразу показать выбранный символ.

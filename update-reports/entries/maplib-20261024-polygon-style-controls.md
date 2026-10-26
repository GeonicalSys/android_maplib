---
id: maplib-20261024-polygon-style-controls
area: Мобильное приложение
kind: user
at: 2026-10-24T13:27:26+02:00
title: Штриховка и контур полигона получили независимые настройки
why: Штриховку нельзя было оставить без фона или настроить под карту, а контур был только сплошным
covers:
  - src/main/java/com/nextgis/maplib/display/PolygonPatternOptions.java
  - src/main/java/com/nextgis/maplib/display/PolygonOutlineOptions.java
  - src/main/java/com/nextgis/maplib/display/ConfigurablePolygonPatternRegistry.java
  - src/main/java/com/nextgis/maplib/display/SimplePolygonStyle.java
  - src/main/java/com/nextgis/maplib/display/FieldStyleRule.java
  - src/main/java/com/nextgis/maplib/display/MplStyleMapper.java
  - src/main/java/com/nextgis/maplib/map/MplFeatureStyleProps.java
  - src/main/java/com/nextgis/maplib/map/MPLFeaturesUtils.java
  - src/main/java/com/nextgis/maplib/map/MapDrawable.java
  - src/main/java/com/nextgis/maplib/map/LocalVectorTileProvider.java
  - src/main/java/com/nextgis/maplib/map/mpl/MplLayerStyleVars.java
  - src/main/java/com/nextgis/maplib/map/mpl/PolygonLayerFactory.java
  - src/main/java/com/nextgis/maplib/map/mpl/PolygonOutlineLayerFactory.java
  - src/test/java/com/nextgis/maplib/display/PolygonStyleOptionsTest.java
  - src/test/java/com/nextgis/maplib/map/PolygonStyleTileSupportTest.java
---

Добавлены угол и второе направление, шаг, толщина, цвет и независимая непрозрачность встроенных штриховок. Контур поддерживает пунктир, точки, штрихпунктир и свой рисунок, соединения, просвет, смещение и дополнительную сплошную обводку. Параметры сохраняются в категориях. Прежние стили сохраняют свои узоры и вид; для новых возможностей упрощённый слой автоматически переходит к обычной отрисовке.

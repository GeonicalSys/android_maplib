---
id: maplib-20261109-layer-snapshot
area: Мобильное приложение
kind: internal
at: '2026-11-09T14:06:14+04:00'
title: Согласованный снимок слоёв карты
why: Устранить гонку между фоновой загрузкой слоёв и списком
covers:
  - agent-system/knowledge/documentation/rendering-order-and-opengl-dependency-diagnosis.json
  - src/main/java/com/nextgis/maplib/map/LayerGroup.java
  - src/main/java/com/nextgis/maplib/map/MapBase.java
---

Снимок прямых детей строится под монитором группы. Перемещение по двум слоям разрешает позицию цели в текущем порядке под тем же монитором. Существующие вызовы перемещения по позиции используют прежний метод. Порядок сериализации карты сохраняется. Регрессия GlitchTip №144 проверяется в приложении с обеими библиотеками.

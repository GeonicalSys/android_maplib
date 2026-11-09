---
id: maplib-20261109-live-style-layer-visibility
area: Мобильное приложение
kind: internal
at: '2026-11-09T17:38:36+04:00'
title: Видимость в текущем стиле карты
why: После переустановки элементов стиля контур мог остаться на карте
covers:
  - src/main/java/com/nextgis/maplib/map/MapDrawable.java
  - agent-system/knowledge/documentation/independent-pattern-and-outline-settings-and-native-image-ownership.json
---

Видимость основных элементов применяется к текущему стилю по ID, включая варианты контура и подписи.

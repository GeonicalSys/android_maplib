---
id: maplib-20261109-map-editor-without-style
area: Мобильное приложение
kind: internal
at: '2026-11-09T15:14:16+04:00'
title: Отмена правки при пересоздании карты
why: Устранить обращение к недоступному стилю при восстановлении экрана
covers:
  - agent-system/knowledge/documentation/rendering-order-and-opengl-dependency-diagnosis.json
  - src/main/java/com/nextgis/maplib/map/MapDrawable.java
---

Отмена сбрасывает состояние редактирования и возвращает видимость записи SQLite. Рисование выделения и запуск линейки требуют готового стиля. Регрессии GlitchTip №129 и №130 запускаются в потребляющем приложении.

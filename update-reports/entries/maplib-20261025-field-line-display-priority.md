---
id: maplib-20261025-field-line-display-priority
area: Мобильное приложение
kind: user
at: 2026-10-25T22:20:00Z
title: Полевые линейные под точками и над остальными данными
why: Полевые линии должны оставаться видимыми поверх треков и прочих слоёв
covers:
  - src/main/java/com/nextgis/maplib/map/MapDrawable.java
  - src/main/java/com/nextgis/maplib/map/LayerDisplayPriority.java
  - src/test/java/com/nextgis/maplib/map/LayerDisplayPriorityTest.java
---

Линейный слой «Полевые линейные» / field_lines вместе со своими проходами
и подписями поднимается над треками и обычными данными, но остаётся под всеми
точечными слоями. Правило использует тип линии и точное имя без учёта регистра
и крайних пробелов; посторонние линии и полигоны не получают этот приоритет.
Порядок поддерживается при full/lite/style/reorder, включая logical layers
в группах; инструменты и GPS сохраняют свои верхние позиции.
Unit и consuming app native проверяют тип/имя, тела/подписи и перезагрузки.

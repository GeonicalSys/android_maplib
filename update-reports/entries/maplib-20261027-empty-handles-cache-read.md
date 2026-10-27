---
id: maplib-20261027-empty-handles-cache-read
area: Мобильное приложение
kind: user
at: 2026-10-27T14:00:00+01:00
title: Устранение лишних запросов карты и мелких чтений пространственного индекса
why: Подтверждён ANR в пустом marker hit-test на Samsung SM-X115 и медленная загрузка GeometryRTree
covers:
  - src/main/java/com/nextgis/maplib/map/MapDrawable.java
  - src/main/java/com/nextgis/maplib/datasource/GeometryRTree.java
  - src/test/java/com/nextgis/maplib/datasource/GeometryRTreePersistenceTest.java
  - src/main/java/com/nextgis/maplib/util/MapRuntimeDiagnostics.java
  - src/test/java/com/nextgis/maplib/util/MapRuntimeDiagnosticsTest.java
---

MOB-02. Пустой marker collection и отсутствующий объект редактирования не
запускают native marker/vertex query на каждом ACTION_DOWN. Запросы заполненных
редакторских handles и обычный выбор объектов сохраняют прежний путь.

GeometryRTree читает существующий бинарный файл через буфер 64 KiB; поток
закрывается и при IOException. Формат, ID, координаты, поиск и синхронизация
индекса сохраняются. Независимый legacy-format fixture проверяет 6000 элементов,
включая несколько узлов и буферов, точный поиск и byte-identical сохранение.

Это не доказательство устранения всех ANR/native crash и не ускорение,
подтверждённое на пользовательском проекте. Проблемные данные/устройство не
изменялись. Native-проверки и разбор находятся в consuming app PR.

Адресный native прогон выявил потерю нового journal snapshot при ограничении
повторного текста. Для медленных startup/render_query новое состояние теперь
сохраняется независимо от текстового rate limit; быстрые операции по-прежнему
молчат. Регрессия проверяет повторное зависание в прежнем десятисекундном окне.

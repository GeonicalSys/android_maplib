---
id: maplib-20261126-unbound-layer-account-filter
area: Разделение карты и обмена данными
kind: user
at: '2026-11-26T12:00:00+04:00'
title: Слой без учётной записи остаётся на карте и пропускается при обмене
why: При открытии карты с таким слоем поиск слоёв учётной записи приводил к исключению
covers:
  - src/main/java/com/nextgis/maplib/map/MapContentProviderHelper.java
  - src/main/java/com/nextgis/maplib/map/NGWVectorLayer.java
  - src/test/java/com/nextgis/maplib/map/MapContentProviderHelperTest.java
  - src/test/java/com/nextgis/maplib/map/NGWVectorLayerUploadAckTest.java
  - agent-system/knowledge/documentation/hidden-vector-reload-resource-url-and-catalogue-traversal.json
  - docs/README.md
---

Слои без учётной записи допустимы в составе карты и вложенных групп. Поиск
для обмена выбирает слои только по заданному имени учётной записи. Векторный
слой с отсутствующим или пустым именем пропускает чтение и отправку даже при
прямом вызове. Фоновая отправка учитывает отсутствие удалённого назначения.
Локальная редактируемость и накопленные правки сохраняются.

Оба сценария Perf01 предыдущего прогона на эмуляторе завершились исключением
при открытии карты до начала измерений. Добавлены проверки обхода вложенной
группы и сохранения правок в SQLite без сетевой отправки. Итоговая приёмка
229 на эмуляторе и публикация пока ожидаются.

Адресные проверки подтвердили пропуск удалённого обмена и сохранение правок без учётной записи. Обе проверки открытия и отображения карты Perf01 прошли на эмуляторе в `native-release229-03`.

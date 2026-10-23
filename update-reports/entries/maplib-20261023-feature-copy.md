---
id: maplib-20261023-feature-copy
area: Мобильное приложение
kind: user
at: 2026-10-23T19:23:46+02:00
title: Совместимость геометрии и атрибутов при копировании
why: Переносить объект между слоями без потери частей или неверного сопоставления полей
covers:
  - src/main/java/com/nextgis/maplib/util/FeatureCopyPolicy.java
  - src/test/java/com/nextgis/maplib/util/FeatureCopyPolicyTest.java
---

FeatureCopyPolicy разрешает только потеребезопасные single/multi преобразования в одном семействе. Атрибуты совпадают по key и типу, с безопасным расширением FTInteger; identity не копируется. Связанные PR: maplibui #43 и app #77.

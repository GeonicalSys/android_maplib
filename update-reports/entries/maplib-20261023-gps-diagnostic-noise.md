---
id: maplib-20261023-gps-diagnostic-noise
area: Мобильное приложение
kind: user
at: 2026-10-23T12:32:23+02:00
title: Устранены ложные предупреждения о зависании GPS при поиске спутников
why: В багрепортах остаются непрерывные сбои доставки и обработки, а нормальное ожидание решения сохраняется локально
covers:
  - src/main/java/com/nextgis/maplib/location/GpsRecoveryPolicy.java
  - src/main/java/com/nextgis/maplib/location/GpsEventSource.java
  - src/test/java/com/nextgis/maplib/location/GpsRecoveryPolicyTest.java
  - src/test/java/com/nextgis/maplib/location/GpsDisplayRetentionTest.java
  - docs/manifest.yaml
---

Время прежнего no-fix не превращается в длительность сбоя callbacks. Callback
stall должен непрерывно длиться 30 s; provider silence — 60 s. No-fix/acquisition/
filter wait остаются локально. Snapshot v3 содержит длительность причины и
интервалы проверок/сна устройства; условия повторной регистрации и запись треков
сохранены. Связанная app проверка и последующий APK выпуск выполняются отдельно.

---
id: maplib-20261014-shared-tracks
area: Мобильное приложение
kind: internal
at: 2026-10-14T22:33:22+02:00
at_source_commit: cc74f8c48aea8dc7e99cc135769b70c240edf9f0
title: Общее хранилище и миграция треков
why: Проектные базы разделяли одну историю устройства
---

SharedTrackStore хранит историю и spool вне проектов. Транзакционный импорт сохраняет sent, segments, UUID и pending tail; provenance и completion marker предотвращают дубли и reimport удалённой истории.

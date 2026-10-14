---
id: maplib-20261014-shared-tracks
area: Мобильное приложение
kind: internal
at: 2026-10-14
title: Общее хранилище и миграция треков
why: Проектные базы разделяли одну историю устройства
---

SharedTrackStore хранит историю и spool вне проектов. Транзакционный импорт сохраняет sent, segments, UUID и pending tail; provenance и completion marker предотвращают дубли и reimport удалённой истории.

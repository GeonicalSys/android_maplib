---
id: maplib-20261102-git-eol
area: Сопровождение
kind: internal
at: '2026-11-02T12:00:00+04:00'
title: Общие правила переносов строк Git
why: Сделать текстовые checkout и экспорт независимыми от Windows core.autocrlf
covers:
  - .gitattributes
---

Общий блок LF и CRLF для Windows-скриптов согласован с пятью остальными
репозиториями ЛИСА. Бинарные ресурсы сохранены; исключение официального
QuickJS `-text -whitespace` оставлено после общего блока. Java/C++ API,
данные и runtime не меняются. Дополнительных текстовых blob для нормализации
нет; пустой normalization commit не создаётся. Связанный app PR MOB-DEV-01.

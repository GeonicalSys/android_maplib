---
id: maplib-20261014-classic-style-tests
area: Мобильное приложение
kind: internal
at: 2026-10-14
title: Проверки обычных стилей и исправление подписей по правилу
why: Часть optional настроек не наследовалась или теряла явные значения после сохранения
---

Добавлена native матрица обычных SQL/GeoJSON слоёв, включая rule-style, подписи,
геометрии, marker shapes/icons, line options и polygon patterns. Исправлены
наследование optional label settings и explicit false/default serialization,
zoom категорий и передача native constant настроек подписей/icon/line/fill через
фильтрованные rendering children вместо неподдерживаемых feature expressions.
Исправлены category zoom curves, custom dash arrays и сброс native dashes при
возврате к сплошной линии. Configured vector-tile слои исключены; их поддержка
не расширялась.

Отложенные и фоновые снимки отрисовки проверяют актуальность renderer, поэтому
старые rule props не возвращают прежние настройки после их изменения.
Если стиль изменился во время hot add, загрузка повторяется с актуальным стилем,
чтобы источник слоя не потерялся до создания объекта.
Матрица пикселей выявила исчезновение тонкого маркера-креста; исправлено
кодирование SDF-границы встроенных значков под порог native MapLibre.
Исправлена передача category font stack: native MapLibre требует литералы для
поиска шрифта. Проверены пиксели кириллицы четырьмя встроенными начертаниями.

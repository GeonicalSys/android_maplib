---
id: maplib-20261028-sqlite-vector-map-integration
area: Память карты и редактирование
kind: internal
at: 2026-10-28T05:20:00+01:00
title: Чтение обычных векторов из SQLite по тайлам без стартовой GeoJSON копии
why: Удержание внутренних GeoJSONVT tiles не устраняется одним уменьшением свойств; обычные редактируемые слои также должны читать только необходимые оригиналы
covers:
  - src/main/java/com/nextgis/maplib/map/SqliteVectorLayerController.java
  - src/main/java/com/nextgis/maplib/map/MapDrawable.java
  - src/main/java/com/nextgis/maplib/map/MPLFeaturesUtils.java
---

MOB-04, библиотека PR61 перед app82. Ordinary Simple/Field Rule renderers всех
шести типов геометрии используют SQLite/RTree → полный MVT8192 → штатный
CustomVectorSource13.7.1. Cold/hot/lite/style/data load не создают общий rich
Java FeatureCollection или GeoJSON source; совместимая запись cache пуста.
Raster/tracks и малые selection/edit/cursor sources сохраняют свои форматы.
Неизвестные расширенные renderers сохраняют прежний compatibility path;
нельзя выдавать эту границу за поддержку всех произвольных расширений.

Все прежние factories paint/layout/rules/pattern/icon используют те же свойства
и порядок, но получают source-layer `features`. Polygon Fill/Line принимают
только Polygon, Symbol — только inline Point anchors. Это сохраняет rule filter
и устраняет подтверждённые native query Point hits в FillLayer с теми же FID.
Не допускается просто выбросить эти hits из теста: native oracle проверяет тип
каждого raw hit до дедупликации допустимых clipped polygon fragments.

Редактор получает по одному полному styled DB original, а не clipped/quantized
тайл. Малый edit list/selected/vertex GeoJSON остаётся локальным; background
исключает текущий FID native filter. Cancel/Save возвращают SQLite source,
ID remap переносит transient exclusion, delete/undo сохраняет остальные IDs.
Подписи polygon не восстанавливают глобальную коллекцию после edit. Изначальные
координаты, атрибуты, таблицы, sync/outbox/draft, saved styles и visibility не
мигрируют и не упрощаются.

Source generation process-unique; owner/style/content/data epochs проверяются
до результата. Scope отменяется до remove/style replacement/destroy. Actual
provider predicate отличает уже подключённый SQLite source от capability:
classic bind тестов/compatibility не становится streaming по одному типу слоя.
Поздний callback не может подключить удалённую модель или затереть новый host.
Project-retirement отдельный terminal any-thread API нужен до смены активного
проекта; consuming GISApplication integration имеет собственную карточку.

Зависимости обнаружены по всем доступным owner sources и обратным цепочкам
NGW/Collector/desktop. Формат и семантика producer resources не меняются.
Генерируемый desktop source index/curated registry остаётся follow-up владельца
чужого checkout codex/roadmap-admission; UNKNOWN не является отсутствием связи.
Общий native acceptance, длительный787 benchmark и2GiB gate ещё не закрыты.
Первый native software01 сохранил FAIL и raw JSON/PNG с корректными129PolygonID
и тремя ошибочно попавшими в Fill Point anchors; после исправления нужен повтор.
Сборка или JVM PASS не доказывают скорость/полноту отображения/устранение OOM.

### Приватная обычная сборка и реальные замеры787,28Oct2026

По новому поручению владельца собраны обычные Debug/Lisa Release/Belka Release
без verification/CI flags: build02 PASS83s, version matrix всех трёх PASS.
Измеряемая основа — app dbbd453f77f3, maplib8d415880f957, maplibui edc189cc41bd;
runtime fingerprint `1b0c09ce75f18f34896958d774145de0cdc1a439d501b6faf7262b3fe8eefb85`
до/после сборки одинаков. SHA-256 приватного Lisa Release:
`ada0f5b806bc3d98a67037bd9e6020a01dbe55bb62455f4468d0292c3fbc0cb2`.
Runtime source не менялся; это новая проверка прежнего кода, не новое поведение.

Первый B diagnostic на реальном SM-A566B/проекте787 завершил300s:
idle60/pan90/zoom90/idle60,67 pan+1 компенсация,55 zoom+5 компенсаций,
total RSS460.7–614.3MiB. Он выполнен до включения владельцем подписей выделов,
поэтому не является строгим A/B или доказательством отсутствия OOM.
Проверка конфигураций/кода подтверждает SQLite gate для11 видимых ordinary
vectors;7 native ID прямо видны в журнале,3 line-label overlay отдельно.
Четыре main source ID не перечислены, вся visual/label parity этим не принята.

Отдельная A-B-B-A серия обычных baselinecab9/SQLite APK с одинаковыми
подписями выделов и камерой реального активного объекта PK3688 дала168 samples:
A1/B1/B2/A2 retry по42 samples/6 действий/210s. Среднее двух run-level statistics
A→B: late60 RSS958.12→659.66MiB (−31.15%), sampled peak1043.82→792.76MiB
(−24.05%); Graphics late60 вырос219.48→230.91MiB. Это не pooled median/CI.
Memory проходы выполняются без heapprofd/видеозаписи; content-ready latency
проверяется отдельно. A1 сохранил42 samples и6 нажатий за210s, затем финальный
UI XML export отказал из-за idle ожидания. Общий FAIL и исходные evidence
сохранены; это не установленный вылет приложения. Original A2 partial с13 samples
и2 action entries после отказа ADB daemon5037 сохранён/исключён из статистики,
completed retry отдельный. Exact camera UNKNOWN, phone scene medians A1/B1/B2
примерно0.2–0.5s перекрываются, ускорение не установлено. Для f809 numeric20px
legacy CENTER/generic TOP различие остаётся; полной pixel/label parity нет.

Первичный read-only DB snapshot включает все44 таблицы/80,351 строку без
исключений геометрий/атрибутов/attachments/changes; повторный provenance scan
совпал. После серии весь DB SHA8a201c148f530bb382bc1154e51e75687eac51fe6f0db3d2067078346b435843
не изменился,16 layer configs совпали с user labels-ON basis. Tablet Collector1403
с reported3–10s около12.1 не принят телефонными данными/двумя warm11.7 pans;
требуется sharp17→12 и полностью новая область. Свидетельства:
Q:/Codex/Scratch/mobile-crash-20261027-01a17d5e/evidence/resume-20261028/;
детали — consuming app docs/reference/mobile-crash-224-2026-10-27.md.
Прежние1043 JVM/161 Python PASS относятся к предыдущему source этапу, не
переименованы в currentD101/native run. Full, физические2GiB/SM-X115 и оставшиеся
software/native проверки открыты. Merge/публикация не выполнялись.

Последний privateD101 после read-hotpath доработок: normalall3PASS62s,
versionmatrixPASS,71focused и final ALLmaplib750PASS/132classes/0fail/error/skip59s
при unchangedsourcefp62de8740…59ec91; independent review без blockers.
ActualAPI30 window/PFD witness и strict dense1403/11.7z fullscene video gate
сохранены отдельно:843d32.45–34.09s→6039 13.68–15.65s→D10110.33–12.27s,
late mapROI MAE0 с обоими. Один processcoldrun/build/diskretained, не FPS/
все стили/2–3s target. Phase SDKreturn не выдан за native ready.

Current787 corpus/config позже изменились:2TMSOFF/LVPCON/pointbbox,+10rows,
10existingattrs changed, existinggeometry0differences; actorUNKNOWN.
Новаяcab9→D101 пара на этом CURRENTbasis:42+42rawsamples/6+6taps/210s каждый,
RSSlate820.42→643.06MiB(-21.62%), sampledpeak896.19→751.16(-16.18%).
Graphicslate вырос143.36→210.14MiB(+46.58%). Это одна последовательная пара,
не pooledold31.15% ABBA/CI/drawspeed; privateexactcameraUNKNOWN. TrackLayer
saved/active fullGeoJSON остаётся исключён из ordinaryVectorLayer tile path;
trackRAM/длинная сессия не измерены. Full/2GiB/OOMfixed/merge/publication не приняты.

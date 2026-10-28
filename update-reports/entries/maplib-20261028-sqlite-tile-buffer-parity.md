---
id: maplib-20261028-sqlite-tile-buffer-parity
area: Потоковые SQLite векторы и границы тайлов
kind: internal
at: 2026-10-28T07:16:00+01:00
title: Классический запас128px при чтении и clipping SQLite тайлов
why: Новый64px query/clip запас был меньше фактического128px default прежнего GeoJsonSource и мог терять объекты до применения fill translation
covers:
  - src/main/java/com/nextgis/maplib/map/NativeVectorTileEncoder.java
  - src/test/java/com/nextgis/maplib/map/NativeVectorTileBufferParityTest.java
  - src/test/java/com/nextgis/maplib/map/NativeVectorTileClipperTest.java
  - docs/sqlite-vector-tile-buffer.md
  - docs/README.md
  - docs/manifest.yaml
---

MOB-04, самостоятельный source-confirmed parity defect, не объяснение падений
из-за памяти и не доказанная причина отдельного custom-PNG failure Native06.
В actual classic createSourceForLayer нет GeoJsonOptions: official13.7.1 defaults
512px tile/128px buffer, native GeoJSONVT получает2048extent при8192extent.
SQLite Bounds использовал только1/8tile=64px. Style fillTranslate поступает в
native как style pixels без ограничения. Polygon90px вне края с translate110px
должен появляться20px внутри, но меньший query envelope его не читал.

Все четыре Bounds query boundaries теперь используют128/512. Provider уже
применяет их к RTree candidates, а clipper — к full-precision intersection,
поэтому отдельные production provider/clipper изменения не нужны. Геометрия
не упрощается, holes/multipart, все исходные вершины, свойства/ID и полные tiles
сохраняются; feature cutoff/layer disabling/новых data notifications нет.
Saved SQLite/schema/style JSON не меняются; process-session cache key исключает
повторное использование тайлов предыдущего процесса после APK update/restart.

Две новые Robolectric36 regressions проверяют strict candidate-envelope backend
и independently decoded protobuf. Четыре polygon90px за разными краями проходят
до native tile coordinates, hole и collinear vertex сохраняются, typed Rule props
не теряются, реальные decoded centres после110px translation находятся20px
внутри. Отдельный crossing128polygon clip-ится точно на-2048extent; полностью
внешний polygon не читается. Original WKT совпадает целиком до/после. Test backend
не содержит business input, не подменяет SQLite/RTree/native renderer acceptance.

Baseline128px не гарантирует произвольные большие offsets/translations/labels.
Envelope area увеличивается в1.44раза относительно64px, что может увеличить
candidate count/работу на равномерной плотности. Это восстановление полноты,
не обещание снижения памяти или отсутствия OOM. Конкурентность/сериализация,
полный PBF и cancel/source fences прежние; dense single-tile bytes/native/GPU
пока не имеют жёсткого общего budget. Physical787/2GiB и native edge+translate
pixel acceptance отдельно, PASS здесь не объявлены.

Before edit workspace preflight Fetch OK/own ahead0-behind0; maplib чистый на
30561bf, app own WIP parent сохранён, foreign desktop codex/roadmap-admission
не менялся. Read-only whole-contour plan sqlite-tile-buffer-parity-dependency-plan01.json
exit0/UNKNOWN: SDK default→classic source→style translation→RTree query→clip→PBF
и reverse MapDrawable/factories trace выполнены. Desktop/NGW/Collector producers,
layer schema/resource format и raster route не меняются. Semantic constraint
сохранён в local doc; parent владеет canonical/app registry follow-up и delivery.
Root обязан refresh/check generated source index перед commit; UNKNOWN не PASS.

Build10 actual XML подтвердил обе новые buffer regressions PASS. Общий run
остался FAIL: прежний concave clip test задавал baseY[3,5] и ожидал площадь10,
что неявно зависело от старого64px расширения. С новым128px margin effective
clipY стал[2.5,5.5] и дал площадь12. Test сохраняет прежнюю original U geometry,
раздельность2members и ожидаемую площадь10: baseY[19/6,29/6] даёт прежнее
independent effective clipY[2.75,5.25]. Дополнительно проверяются точные envelopes
обеих полос x[0,2]/[4,6], area5 каждая, validity и весь original WKT. Area oracle
не переписан на фактические12, production clipper не менялся. Expanded whole-
contour plan02 exit0 добавил этот точный test path до правки.

Source frozen и diff-check PASS; этим агентом Gradle/ADB/build/native не запускались.
Parent выполняет повторные combined unit/compile checks. Exact SDK proof/ограничения:
[tile-buffer contract](../../docs/sqlite-vector-tile-buffer.md).
Root Build11 PASS51s:715maplib/93maplibui/235app tests,1043 total, no failures,
errors or skips. This includes both strict buffer tests and the preserved concave
output regression. Docs04 and145Python tool tests PASS. Native07 is only a
custom-symbol diagnostic and cannot certify translated edge pixels; physical
performance/memory and full native acceptance remain separate and unverified.
Два dependency plans (около63MiB) сохранены в Q incident evidence до общего
closure; новых SDK/runtime/download/DB environments не создано.

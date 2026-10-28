---
id: maplib-20261028-streamed-line-labels
area: Память видимых линейных подписей
kind: internal
at: 2026-10-28T04:38:00+01:00
title: Потоковое размещение подписей без всех полных линий viewport в памяти
why: SQLite reader собирал List полных геометрий, а каждый готовый Label дополнительно удерживал original Feature
covers:
  - src/main/java/com/nextgis/maplib/map/SqliteVectorTileProvider.java
  - src/main/java/com/nextgis/maplib/map/VisibleLineLabelLayout.java
  - src/main/java/com/nextgis/maplib/map/VisibleLineLabelQuery.java
  - src/main/java/com/nextgis/maplib/map/VisibleLineLabelController.java
  - src/test/java/com/nextgis/maplib/map/SqliteVectorTileProviderTest.java
  - src/test/java/com/nextgis/maplib/map/VisibleLineLabelStreamingTest.java
---

MOB-04, самостоятельная memory доработка после SQLite backend checkpoint;
прежняя viewport-line карточка не расширяется новым поведением.

Production цепочка: Controller → Query.planStreaming →
Provider.visitVisibleLines(envelopes,current,visitor). IO читает один полный
SQLite original и сразу отдаёт planner перед чтением следующего. List всех
видимых полных features больше не создаётся. Candidate IDs по RTree/dateline
dedup остаются metadata collections; полные геометрии ограничены одним текущим
объектом/его преобразованием, а не суммой viewport. Label хранит только Point
anchor, style properties и optional id, не original Feature/LineString.

Viewport готовится один раз. Прежние exact rotated-quad clipping, axial direction,
nearest-visible anchor, upright/dateline и один label на FID сохраняются.
MultiLineString обрабатывается целиком. Streaming принимает ПОЛНЫЕ DB originals;
native fragments продолжают использовать прежний List planner с geometry
merge/dedup. Public readVisibleLines остаётся совместимым collector, production
Controller его не вызывает. Late UI property clone сохранён, source props
не получают ANGLE; SQL/editor coordinates/visibility/sync/drafts/schema не
меняются, миграции нет.

Сохраняются shared admission lease, free-memory policy, source/owner/content/
data-generation fences и style comparison только на границах чтения. Visitor
cancel/exception освобождает lease через finally; current проверяется между
объектами и каждые1024 vertices при scan. Query/Controller сохраняют прежние
camera/provider/source/style/map/view/layer/UI guards и отбрасывают весь stale
результат. UI/SDK вызовов на worker не добавлено.

Добавлены6 tests: delivery до следующего DB read/cancel; full-original
streaming/classic parity и независимые angle/anchor/style ожидания;128 originals
по4097вершин с проверкой reachable candidate ownership без GC timing; late UI
props; cancel до DB/при scan/перед publish; dateline dedup. Прежние8 layout и8
query regressions сохранены. На handoff compilation/tests NOT RUN по root scope;
общую проверку запускает root. Прежний26PASS backend receipt предшествует этим
Provider/Layout изменениям. Native labels/pan, memory и performance на реальном
проекте требуют новых измерений; hard cap/no-OOM этим не доказываются.

Pre-edit whole-contour read-only plan: streamed-line-label-impact01.json,
11paths, UNKNOWN/path-contract-unmapped/REFRESHED_IN_MEMORY. Final source-index
closure новых visitor/callback links остаётся у root; foreign dirty desktop
owner здесь не записывается. Portable NGW/desktop label configuration и producer
формат не меняются. Новые scoped contracts сохранены в local/module/architecture
docs; ambiguous/dynamic consumers не объявлены отсутствующими.

Gradle/ADB/Git/server/publication из этой batch не запускались. Новых runtimes,
downloads/native captures нет; compact evidence наQ хранится до общей доставки.

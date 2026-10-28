---
id: maplib-20261028-sqlite-small-blob-window-reads
area: Скорость чтения карты
kind: internal
at: 2026-10-28T13:02:53+01:00
title: Полные малые SQLite геометрии через отдельный ограниченный cursor
why: После attributes batching actual T40 scope сохранил3051 originals и356630B PBF, но BLOB stage всё ещё занял1458.11ms для2076156B
covers:
  - src/main/java/com/nextgis/maplib/map/SqliteVectorFeatureReader.java
  - src/main/java/com/nextgis/maplib/util/MapRuntimeDiagnostics.java
  - src/test/java/com/nextgis/maplib/map/SqliteVectorFeatureReaderTest.java
  - src/test/java/com/nextgis/maplib/util/MapRuntimeDiagnosticsTest.java
---

MOB-04, existing own maplib61→app82. Durable private6039 — предыдущий batch/PFD
baseline;843d — предыдущая диагностика. Новый source uncommitted/SOURCE_DIRTY,
не public/HEAD release. Никакой2–3s/full-scene/OOM/2GiB гарантии нет.

ONE root per-change plan выполнен до edits: sqlite-small-blob-window-impact01,
exit0/UNKNOWN/REFRESHED_IN_MEMORY;50,259,420B stdout обработаны в RAM,
source_context сохранён. Foreign canonical root не изменён; после внешнего
merge он main clean, но canonical generated index остаётся owner follow-up
до Git delivery с own mobile live basis. Семантика записана в own app registries.
Collector/NGW/PostgreSQL producer/schema, saved projects, drafts и sync migration
не меняются; reverse producers исключены по неизменным `_id`/`_geom`/field types.
Direct tile и visible-line consumers сохраняют provider bytes/style/epochs;
editor/sync API и совместимый Access default не меняются.

Attrs-only32-ID cursor/projection остаётся прежним. На API28+ второй,
geometry-only cursor в exact CASE order выбирает `_id`, typeof, BLOB length и
полный `_geom` только при BLOB<=16384B. Nested lazy CASE исключает large BLOB
и TEXT payload materialization без substr/hex/cast/обрезки. Явный1MiB
CursorWindow содержит не более32 small BLOB, payload<=512KiB; это не общий
RAM budget: sorter buffers, Java getBlob и decoded geometry отдельные.
API26/27 и большие originals идут через один lazy visit-owned statement/PFD
с полными исходными bytes, без simplification/потери объектов/слоёв.

Ownership передаётся cursor только после успешного setWindow, перед любым
move/getCount; до передачи новое окно закрывается отдельно. Оба cursors,
statement и каждый descriptor закрываются success/cancel/error. Current/free-only
cadence сохраняется, включая guard между attrs и geometry query. Exact
ID/order/count/type/NULL/length проверки не позволяют partial tile/cache.
Safely unsupported window возвращает null после cleanup. Mid-window capability
failure закрывает cursor и переводит только текущий и оставшиеся IDs на PFD,
не повторяя visitor. SQLite/IO/OOM/schema/resource/cleanup failures не становятся
fallback success; исходные и suppressed ошибки сохраняются.

Geometry query/getCount/prefill/refill/getBlob и PFD относятся к прежнему
BLOB_READ; attrs materialization остаётся SQL_ATTRIBUTES. Fixed counters
inline_blobs/pfd_blobs считают полные buffers перед decode; BLOB_BYTES сохраняет
прежний логический объём consumed originals. При отмене window может prefetch
до32 small BLOB; counts не претендуют на physical IO/all prefetched bytes.
No URI/coordinates/business attrs/extra diagnostic reads; throttling/cap unchanged.
Known cap16 unobserved-only layer denominator gap остаётся открытым отдельно.

До GREEN независимые technical expectations дали RED34tests/2expected failures:
65small originals требовали65PFD вместо0, boundary набор1/16383/16384/16385/3MiB —
5PFD вместо2. Остальные32PASS; RED source/log/JUnit сохранены. Более ранний
RED01 остановился на test compilation и не назван expected RED. Дополнены
API26/27 PFD-only и API30 real SQLite nested CASE/hugeTEXT/NULL/full-byte tests,
guard/cancel/cardinality/cleanup/three-resource ownership и no-duplicate fallback.
Focused GREEN01 PASS71tests/0fail/error/skip за1m58s: Reader26/Provider9/
Bridge13/Spatial4/Diagnostics19. Full source fingerprint
62de8740ff56ee0abd76b880000a6049f2825d0bfe9d118f8c94e975c659ec91;
log, result JSON и5JUnit XML сохранены. Independent read-only stable-source
review PASS без blockers, отдельно прочитаны5XML/71PASS и совпали hashes всех
четырёх source/test файлов. Оба guard/lifecycle замечания закрыты адресными
regressions; Provider/VectorLayer unchanged от reviewed a62 baseline.
Technical doubles не business/native acceptance. Ordinary PRIVATE D101 normal
all3 PASS62s/versionmatrix all3 PASS; fp62de8740…59ec91 до/после,7runtime/5tests/
review/5HEAD/diffSHA неизменны. Exclusive private APK70563701B, same signing,
224/3.1.2.30, без instrumentation/profileable/debuggable. ZIP16K иELF64PASS;
прежний32bitMapLibre4096alignment gap сохранён,16nativeSO byte-identical с6039/
843/ADA; все прежние APK/hash сохранены. Это uncommitted SOURCE_DIRTY.

Actual T40/API30 exactD101 cold1403 collection:18validJSON,8native+2line terminal
representatives1352consumed originals/1021500B,1350inline+2PFD.833-object scope
выполнил831inline+2PFD/749736B/PBF109310, фактически проверив оба Android paths.
RetainedPIDlog не содержит read/window/SQLite/fatal markers; published failures/
retry/cancel0. Same9logical-counter cohorts с6039:121features/140772B/PBF22217
BLOB83.57→13.28ms;110features/41376B/78labels/PBF16476134.11→15.37ms;
163line originals/47484B42.24→8.59ms. Совпадение counts не cryptographic bytes
equality/tileidentity.110-feature elapsed вырос791.51→3775.86ms с IOqueue6.17→
3374.41ms, поэтому каждый tile быстрее не заявлен.3051 terminal detail D101
не опубликован из-за snapshot/throttle replacement:1458→80ms для другого833scope
не используется как same-cohort comparison. Reported52requests/33unobserved —
не process-global denominator, unknown unobserved-only layers остаются.
Read-path witness теперь physical Android, не только JVM. Реальный
T40/API30 sharp17→12/fully new extent, полный кадр/labels/PSS ещё отдельный gate;
Java provider/SDK return не означает native content ready. Git/merge/publication
во время этого source шага не выполнялись.

Final full maplib gate:750PASS/132classes/0fail/error/skip59s при том же
sourcefp62de8740…59ec91;71focused — поднабор. Независимое видео одной strict
dense1403/11.7z сцены (включая последнюю верхнюю розовую полосу) дало full-gate
32.45–34.09s843d→13.68–15.65s6039→10.33–12.27sD101. Late map ROI вне HUD/
controls MAE0 с обоими предыдущими. Это1processcold run/build при retained
OS/diskcache, не статистическая серия/все объекты/стили/масштабы и не2–3s target.
Producer phase не выдаётся за fullscene time; sharp17→12/newextent отдельный gate.
TrackLayer не входит в VectorLayer path: его saved/active GeoJSON snapshots
сохранены, RAM длинных треков не измерена. Больше source оптимизаций в этой
итерации нет; публикация/merge/Full/2GiB/OOM fixed не заявлены.

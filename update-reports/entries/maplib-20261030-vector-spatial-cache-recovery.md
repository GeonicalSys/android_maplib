---
id: maplib-20261030-vector-spatial-cache-recovery
area: Импорт и отрисовка векторных слоёв
kind: user
at: '2026-10-30T15:14:23.128017+00:00'
title: Автоматически готовить и восстанавливать кэш импортированных слоёв
why: Свежий непустой index мог остаться неготовым; missing-file load сохранял старый count после удаления nodes,
  а same-count empty-root файл переживал новый load. Нужны отдельные lifecycle, persisted recovery и native acceptance
  границы.
covers:
- src/main/java/com/nextgis/maplib/map/VectorLayer.java
- src/main/java/com/nextgis/maplib/datasource/GeometryRTree.java
- src/test/java/com/nextgis/maplib/map/LocalVectorBulkCacheReadinessTest.java
- src/test/java/com/nextgis/maplib/datasource/GeometryRTreeRecoveryTest.java
- AGENTS.md
- docs/README.md
- docs/manifest.yaml
---


## Завершение и восстановление векторного spatial cache — 30 октября2026

Обычный local/NGW vector импорт использует один VectorLayer lifecycle. После
успешного create только проверенные zero SQL rows + zero live cache становятся
готовой начальной точкой: count читается вне spatial lock, затем проверяются тот же
cache object, data generation и нулевой size. Начатый bulk по-прежнему блокирует
native reads. Готовность не устанавливается из finally и не выводится из одного
совпадения ненулевых counts; failed/cancelled/nonempty/unknown-count случаи не
получают новую гарантию commit/rollback или произвольного save после ошибки.

GeometryRTree.clear удаляет nodes и size и помечает публичное изменение dirty:
notifyDeleteAll → clear → save должен записать пустой cache в тот же путь. Load
использует отдельный clean reset. Missing file даёт настоящий empty index; IOException,
header/actual Entry cardinality mismatch или неверный child kind полностью сбрасывают
прочитанный prefix. Счётчик проверяется обходом уже загруженных nodes без второго
getAll/ID list. Валидный прежний binary layout, IDs/envelopes/search и clean same-path
save сохраняются. Владелец VectorLayer при расхождении с SQL восстанавливает derived
index из originals; частично загруженный subclass config при этом не сохраняется.

Причина важна для нескольких входов: GPX/KML CoordinatePointLayerImporter и GeoJSON
batch; initial NGW download через оба resource picker и Collector/form task; config
fromJSON и properties getCount повторно читают index. Старый clear мог оставить
header countN при уже пустом root; последующий save и новый load сохраняли равенство
counts и пропускали repair. Native query и обычный hit-test имеют разные readiness
gates. Смена стиля/видимости не строит spatial tree; manual rebuild создаёт новое
дерево и extents из исходных ID/geometry BLOB, но не переписывает LOD geometry.

Пользователь подтвердил: invisible local/NGW layers переживали restart, style edit
и visibility toggle; помогал только manual rebuild. Эти исходные сведения сохранены
как независимые условия регрессии. Повтор после нового исправления на устройстве
не исполнен; точный installed version30/source commit и native результат для GPX20wpt
и server import ещё не установлены. Это source-backed цепочка, не уже принятый
post-fix результат телефона. Проверка header/cardinality
не доказывает произвольную same-count ID/envelope целостность или disk power-failure
durability. Private GPX names/coordinates не входят в Git и не становятся runtime
dependency. Единый открытый риск: AUDIT-TODO-VECTOR-SPATIAL-CACHE-RECOVERY.

Техническая проверка: Core29 фактически исполнен: исходный код —15 assertion FAIL/14 control PASS, после двух исправлений —29 PASS/0 failures/errors/skips; те же пять наборов и все Core test bytes/oracles сохранены. Это23 новые проверки (12 lifecycle +11 RTree) и6 прежних контролей (legacy1, concurrency1, spatial4):16 Robolectric API28 с технической SQLite и13 plain JUnit. Финальный запуск:5 XML/5.653s, Gradle exit0/BUILD40s (наблюдалось42.537s),105 входов неизменны во время запуска,10 собственных процессов завершены и отсутствие подтверждено. Финальная consumer source compile Belka: Gradle exit0, BUILD SUCCESSFUL in 1m 23s, 86.8108238s observed, 100 source-only tasks,120 inputs unchanged/all12 owned processes naturally stopped. Java/Kotlin source compilation; no APK/AAB/AAR/dex/NDK/install/publish or native Android/GPU/device acceptance. Receipt Q:\Codex\Scratch\lisa-ecosystem-depth-audit-20261029\evidence\resume-20261031-source-checkpoint\belka-resume-compile\final-result-source-stop.json SHA256 be7f54baa369662c2cd44a509c745fbc099f357a0df91c9a30a7d69a12008d1c. Это не55 PASS; App legacy25 PASS/1 path assertion не воспроизвёл обрезание подписей. Device/installed30/real GPX20wpt/NGW import/restart/native map acceptance не выполнялись.
Исторические message89 и selector37 сохранены с их прежними source pins; они не
перезапускались и не сертифицируют новые Core cache bytes. Caption/layout — отдельный
срез. Wiki, APK/release/deploy и paused native stages не затронуты.

Адресная Core source recipe (из Android root): `& '.\gradlew.bat' '--offline' '--no-daemon' '--continue' '-Pkotlin.incremental=false' '-PciReliabilityChecks=true' '--console=plain' ':maplib:testDebugUnitTest' '--tests' 'com.nextgis.maplib.datasource.GeometryRTreeRecoveryTest' '--tests' 'com.nextgis.maplib.map.LocalVectorBulkCacheReadinessTest' '--tests' 'com.nextgis.maplib.datasource.GeometryRTreePersistenceTest' '--tests' 'com.nextgis.maplib.datasource.GeometryRTreeConcurrencyTest' '--tests' 'com.nextgis.maplib.map.SqliteVectorSpatialQueryTest'`

Фактический Core29 включает23 новые проверки и6 прежних; команда выбирает ровно эти пять классов. Source/JVM результат не заменяет device/post-fix приёмку. Свидетельства: Root source55-baseline/partial-Core29-App0-setup-classification.json и core29-green/final-result-source-stop.json; в историческом Core29 checkpoint consumer compile ещё ожидалась; затем принята отдельная финальная source compile Belka, указанная ниже.

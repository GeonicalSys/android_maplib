# maplib — инструкции для ИИ-агентов

Перед изменением прочитай `docs/README.md` и `docs/manifest.yaml`. Если репозиторий
открыт внутри `android_gisapp`, также прочитай `../docs/START-HERE.md`,
`../docs/registry/change-impact.yaml` и связанные invariants/upstream hotspots.

Если parent central docs отсутствуют, локальный pack остаётся обязательным;
сообщи, что cross-repository registry нельзя обновить атомарно, и используй
central docs из root-репозитория GeonicalSystem как отдельный источник.

`maplib` — нижний слой. Не добавляй зависимости на `maplibui` или `app`.
Изменения `MaplibreMapInteraction`, `IGISApplication`, `MapDrawable`, storage,
NGW или Collector metadata имеют consumers в верхних репозиториях.

`BuildConfig.VERSION_NAME` maplib сопряжён с версией consuming app отдельно для
debug и release. На AGP 9.x не задавай `versionName` в library `buildTypes`;
debug override делается через `buildConfigField`, затем проверяется совместно с
APK командой `..\tools\verify-apk-version-matrix.ps1` из root workspace.

Сохраняй layer order, hot-add consistency, no-track-flags, Collector resource и
backup-related contracts. После изменения выполняй релевантные unit tests и
проверяй compile consumers. Обновляй local docs и central registry по DoD.

Collector snapshot поддерживает только уже известные `Connection` типы:
vector/PostGIS и `qgis_vector_style`/`qgis_raster_style`. Style resource имеет
собственный remote id для tile URL/identity, parent id только для extent и
никогда не становится редактируемым. Не добавляй в `Connection.java` новые
resource classes ради Collector без отдельного продуктового решения.

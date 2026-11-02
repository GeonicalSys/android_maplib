---
id: maplib-20261102-remove-loopback-tiles
area: Мобильное приложение
kind: user
at: '2026-11-02T03:00:00+04:00'
title: Удалить прежний HTTP механизм локальных векторных тайлов
why: Владелец поручил использовать новую прямую SQLite систему независимо от старых render flags
covers:
- src/main/java/com/nextgis/maplib/map/LayerIdentifyPolicy.java
- src/main/java/com/nextgis/maplib/map/LocalVectorTileEncoder.java
- src/main/java/com/nextgis/maplib/map/LocalVectorTileLabelAnchor.java
- src/main/java/com/nextgis/maplib/map/LocalVectorTileProvider.java
- src/main/java/com/nextgis/maplib/map/LocalVectorTileRenderMode.java
- src/main/java/com/nextgis/maplib/map/LocalVectorTileRequestPolicy.java
- src/main/java/com/nextgis/maplib/map/LocalVectorTileServer.java
- src/main/java/com/nextgis/maplib/map/MPLFeaturesUtils.java
- src/main/java/com/nextgis/maplib/map/MapDrawable.java
- src/main/java/com/nextgis/maplib/util/Constants.java
- src/test/java/com/nextgis/maplib/map/LayerIdentifyPolicyTest.java
- src/test/java/com/nextgis/maplib/map/LocalVectorTileEncoderTest.java
- src/test/java/com/nextgis/maplib/map/LocalVectorTileFullContentTest.java
- src/test/java/com/nextgis/maplib/map/LocalVectorTileLabelsTest.java
- src/test/java/com/nextgis/maplib/map/LocalVectorTileProviderTest.java
- src/test/java/com/nextgis/maplib/map/LocalVectorTileRequestPolicyTest.java
- src/test/java/com/nextgis/maplib/map/PolygonStyleTileSupportTest.java
---

MOB-REL-01. Удалены obsolete HTTP server/provider/encoder/request policy и
вызовы их регистрации/очистки/URL factories. Обычные Simple/Field Rule
слои остаются на direct SQLite CustomVectorSource; old render metadata
сохраняется инертно. Classic converter нужен preview/overlay/unknown extension,
но старый сервер больше не запускает. Shared LocalVectorTileDiskCache и
native admission/epoch/original contracts сохранены. LayerIdentifyPolicy
теперь visible-only, включая old-config layers; права/схема/SQL rows/sync прежние.

Maplibui снимает только forced connected override и старый clear на switch;
closeMapObj/direct retirement, lease/preferences/order прежние. Consumers всех
трёх app variants и изменённые unit/native test sources компилируются.
Новые tests execution NOT_RUN по поручению владельца. Исторические native
FAIL не закрыты. Related/Full и реальный offline/project-switch остаются gate.

Порядок доставки: maplib Merge Commit → maplibui Merge Commit → fetched merged
pins в app PR89 → app Squash → финальные проверки и выпуск отдельно. Эти
Draft PR не разрешают merge или публикацию. Канонические LISA registry refs
на удалённый provider требуют отдельного owner follow-up; чужой checkout не менялся.

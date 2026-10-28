---
id: maplib-20261028-sqlite-edit-provider-current
area: Редактор геометрии и актуальность SQLite источника
kind: internal
at: 2026-10-28T06:48:00+01:00
title: Актуальный provider после Canvas hide и до восстановления фонового слоя
why: Native05 подтвердил устаревший contentVersion provider после edit activation, а поздний show мог снова инвалидировать уже перестроенный источник
covers:
  - src/main/java/com/nextgis/maplib/map/MapDrawable.java
  - src/main/java/com/nextgis/maplib/map/VectorLayer.java
  - src/test/java/com/nextgis/maplib/map/VectorLayerVisibilityEpochTest.java
  - docs/sqlite-vector-source-bridge.md
---

MOB-04, самостоятельный подтверждённый edit-start defect после checkpoint
4d67e428. В software-sqlite-related05 existingSqlitePolygonEditKeepAndCancelAndTemporaryHideUndoPreserveAllOriginalRows
failure JSON provider_current=false при closed=false/owner_job_active=true,
requested7/IOsuccessful4/returned4/cancelled3; manual original/PBF чтения
возвращали CancellationException. Независимая ошибка test oracle accessory
direction LineString безFID исправляется consuming app отдельно и не объясняет
эти metadata. Canvas EditLayerOverlay.setMode(MODE_EDIT) сначала вызывал
VectorLayer.hideFeature, менял renderContentVersion, затем MapDrawable только
добавлял native exclusion. Pending/new-camera/line-label reads прежнего provider
оставались stale, даже если прежние cached128 native objects пока отображались.

После exclusion MapDrawable проверяет зарегистрированный provider и пересоздаёт
его только при !isCurrent, сохраняя model hide для Canvas и native exclusion.
Отдельно запоминается фактически скрытый model FID: ID remap не теряет пару
hide/show. Actual finish/Cancel восстанавливает только этот owned mask ДО
снятия exclusion и rebuild; keepEditObj маску сохраняет. Это предотвращает
захват provider, который всё ещё видит скрытый original, и последующую
инвалидизацию от обычного overlay MODE_HIGHLIGHT/MODE_NONE show. При удалении
активного edit объекта owned mask тоже освобождается до завершения source update.

VectorLayer.hideFeature теперь меняет epoch только для нового validFID
(NOT_FOUND/duplicate — no-op), сохраняя прежний hide-without-notify contract.
showFeature уведомляет только при фактическом remove по значению. Поэтому
F1 temporary hide + F2 edit/finish + повторный showF2 не создаёт ложное изменение
версии при оставшемся F1. Реальный show/Undo/showAll сохраняет штатный notify.
Геометрия, SQLite rows/schema, style JSON, saved-state и sync не меняются;
это process-local mask/version/lifecycle contract, не скрытие слоёв из-за RAM.

Новые4Robolectric28 tests используют настоящие VectorLayer/contentVersion и
SqliteVectorTileProvider с owning empty technical SQLite map: valid/new hide,
NOT_FOUND/duplicate, two hidden IDs с повторным show и сохранением current
provider, hide→replacement→show→current provider, showAll idempotence. Business
rows/геометрии не создаются, SDK/GPU не эмулируется. CountingLayer считает реальные
notify вызовы, super сохраняет настоящее обновление версии.

Consuming NativeSQL editor regression policy agent теперь требует
provider.isCurrent, rich full65original и complete Polygon PBF на этапах
beforeEdit/start128/keep128/Cancel129/temporaryHide128/Undo129. Полные nativeIDs,
overlay geometry и whole-row hash expectations не ослаблены; fake data
notifications/model repair отсутствуют. На source freeze tests/build/native
этим агентом NOT RUN, root запускает combined checks и Related. Native delete,
new-draft/editor matrices и2GiB acceptance отдельно, полного PASS здесь нет.

Перед edits fresh workspace preflight Fetch OK, всеown ahead0/behind0, maplib
checkpoint4d67e428; текущие dirty manifest принадлежат parent и сохранены.
Whole-contour plans sqlite-edit-provider-current-dependency-plan01.json и
expanded sqlite-edit-visibility-epoch-dependency-plan02.json: UNKNOWN,
REFRESHED_IN_MEMORY. Прямой путь Overlay.hide→model version→provider current→
native edit/background и обратный Cancel/show→replacement прослежен в source.
Semantic idempotence/mask-order и source links описаны здесь/local doc; parent
обновляет canonical/app/maplibui registries, foreign desktop не менялся.
Delivery следует maplib→maplibui/app pointers, release/publication не выполнялись.

Root verification: sqlite-full-units-build09 PASS77s, 713maplib/93maplibui/235app
tests (1041 total, zero failures/skips). software-sqlite-related06 completed all9
cases on actual SwiftShader, source fingerprint b174994b5fe56abbfe2c97af371d7a52626b034544e4eb673b0667786a0180e8.
Editor original/current-provider and style/content/visibility cases PASS; overall
run remains FAIL8/9 because the unrelated custom-PNG pixel case failed. Full-start
verification UID logs are now nonempty and retained for every case. Cleanup
confirmed owned process/console stopped and temporary settings restored. This
does not establish physical787 performance or complete publication acceptance.

Style lifecycle PASS в05 не объясняет intermittent03/04: bridge delta был
диагностическими counters, а05UID capture у всех9case завершился NativeLogStartError.
Поэтому ранних SDK errors/окончательной причины нет; parent повторяет style
после текущего fix и исправленного full-start UID capture. Semiliteral валиден
по точному13.7.1, offset logic/SDK fork/render barrier здесь не менялись.
Два новых discovery plan удерживаются в Q incident evidence (около66MiB) до
общего closure; новых SDK/runtime/download/bulk database copies не создано.

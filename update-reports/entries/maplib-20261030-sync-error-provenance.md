---
id: maplib-20261030-sync-error-provenance
area: Сообщения мобильного приложения
kind: user
at: '2026-10-30T12:45:34.018344+00:00'
title: Причина сбоя ВебГИС доходит до пользовательского сообщения
why: Общий результат очереди терял known server cause, а отдельные SYNC_NONE HTTP failures могли выглядеть успешным проходом.
covers:
- src/main/java/com/nextgis/maplib/util/SyncResultUtil.java
- src/main/java/com/nextgis/maplib/datasource/ngw/SyncAdapter.java
- src/main/java/com/nextgis/maplib/map/NGWVectorLayer.java
- src/main/java/com/nextgis/maplib/map/NGWLookupTable.java
- src/main/java/com/nextgis/maplib/util/NGWLayerSchemaCompat.java
- src/main/java/com/nextgis/maplib/datasource/ngw/ResourceGroup.java
- src/main/java/com/nextgis/maplib/datasource/ngw/Connection.java
- src/main/java/com/nextgis/maplib/util/NgwHttpException.java
- src/main/java/com/nextgis/maplib/util/NgwHttpResponseException.java
- src/main/java/com/nextgis/maplib/util/NetworkUtil.java
- src/main/res/values/strings.xml
- src/main/res/values-ru/strings.xml
- src/test/java/com/nextgis/maplib/util/SyncFailureSnapshotTest.java
- src/test/java/com/nextgis/maplib/map/NGWHttpFailureReasonProducerTest.java
- src/test/java/com/nextgis/maplib/map/NGWSyncNoneServerFailureTest.java
- src/test/java/com/nextgis/maplib/util/NgwHttpExceptionTest.java
- src/test/java/com/nextgis/maplib/util/NgwHttpResponseExceptionTest.java
- AGENTS.md
- docs/README.md
- docs/manifest.yaml
---

Подготовлена передача известной причины сбоя ВебГИС от запроса до сообщения в
приложении. Серверная перегрузка или возможные технические работы получают общий
понятный RU/EN текст с предложением повторить позже. Это не утверждение о доказанном
плановом обслуживании. Интернет, авторизация, права, локальная запись, отмена и
смешанные причины не превращаются в сообщение «техработы».

SyncResultUtil хранит metadata отдельно от существующих IO/Parse counters. В local
account Bundle передаются version/flags/IO/Parse плюс exact result Parcelable token,
не raw body/URL/Throwable. Scope очищается до/после прохода; malformed/foreign/
parcel-copy/late counters дают generic. PUSH и lookup reason-only, без doublecount;
known server SYNC_NONE metadata/count/fallback добавляют1IO за failed request,
сохраняя null/UNKNOWN/zero→KEEP. Full/deferred optional schema wrappers прежние.

ResourceGroup/Connection различают failed child list и successful empty, сохраняя
void API/sort/cache/parcel. Initial feature typed IOException и initial metadata
typed NGException сохраняют точные старые diagnostic/localized messages и разные
catch/retry boundaries. Новый Core body используется App dialog/notification,
resource browser и LayerFill без расшифровки технических текстов.

Actual89 PASS/0fail/errors/skips: Core46 и App43,11 XML10.995с, Gradle0/BUILD50с.
Core46 состоит из37 Robolectric API28 и9 plain JUnit, App43 — модели API28;
не42+4 и не85моделей+4plain. Исходный Root receipt с ошибочной type label сохранён,
итоги/выходы/source hashes не меняются и проверка не повторяется ради подписи.
Same literal4/6eb9 GREEN0.434с включены в89; отдельный old-source baseline имеет
3 meaningful comparisonFAIL/1 control/0errors3.180с/native1.

Первый общий85:81PASS/4assertFAIL/0errors/skips/native1, остаётся NOT_PASS.
Три failures показали реальный408+externalDB false-maintenance classifier bug;
NetworkUtil получил3-line body-only422 guard после numeric429/5xx. Transient/retry
predicates inverse-exact. Четвёртая ошибка — противоречащий исходному контракту
Producer stale-capture assert8; Root явно скорректировал только его наSERVER|OTHER12.
SyncResultUtil7ec/Core16/App13 и другие fixture bodies прежние; mixed12 всё равно
даёт generic UI, поскольку maintenance допускает только pure4. Это не устранение
ошибки изменением API/ослаблением UI ожиданий и не all-tests-unchanged claim.

BelkaRelease Java/Kotlin source compile0/BUILD59с (61.275с observed),100taskSET exact
при другом порядке; CoreJava/UIJava/AppJava+Kotlin executed. Java JAR28/535/670/45
classes без dex/so/APK/AAB/AAR.68 inputs exact во время89/compile, все15 unit и13
compile процессов завершились естественно. Missing new API/setup compilation
не называется baseline RED.
Actual network/SQL/map/device/native/GUI fitting, restart и APK acceptance не
выполнялись. Cookie/capability/TUS/HTTP200/unknown remote outcomes остаются открытыми.

### Воспроизведение технических source-проверок

Из обычного Android root с versioned gradlew.bat и11 versioned test classes.
Установлены JBR21.0.9+-14787801-b1163.94, Gradle9.3.1/AGP9.1.0,
Robolectric4.16.1/JUnit4.13.2, API28 android-all-instrumented9-robolectric-4913185-2-i7
(SHA256 dbb1c88987b54cef3132695a1b4d2f4851ba06286a6fa7d15772f98fd1f0cdf7).
Shared Q offline caches/runtime переиспользуются; task Scratch helper/fixture не
нужен для исполнения. Actual observer использовал собственный phase-specific Q
java.io.tmpdir; ниже выбран обычный Q Temp. Его имя не является test input.
Argument arrays точны: unit30 с11filters/--continue и compile7 без--continue.

Перед отдельно разрешённым запуском тот же graph с --dry-run должен содержать
только source/unit resources и reviewed Java JAR roles, без product APK/assemble/
sign/install/device/CMake/NDK/new downloads. --continue собирает только отфильтрованные
assertion outcomes после prelaunch gates, а не разрешает обход failed prerequisite.
После unit nonzero к compile не переходить. Эти команды не возобновляют native scope.

```powershell
$env:TEMP='Q:\Codex\Temp'
$env:TMP='Q:\Codex\Temp'
$env:TMPDIR='Q:\Codex\Temp'
$env:PYTHONDONTWRITEBYTECODE='1'
$env:PYTHONIOENCODING='utf-8'
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$env:GRADLE_USER_HOME='Q:\Codex\Cache\gradle'
$env:JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=Q:/Codex/Temp -Drobolectric.offline=true -Drobolectric.dependency.dir=Q:/Codex/Cache/maven/org/robolectric/android-all-instrumented/9-robolectric-4913185-2-i7'
& '.\gradlew.bat' '--offline' '--no-daemon' '--continue' '-Pkotlin.incremental=false' '-PciReliabilityChecks=true' '--console=plain' ':maplib:testDebugUnitTest' '--tests' 'com.nextgis.maplib.util.SyncFailureSnapshotTest' '--tests' 'com.nextgis.maplib.map.NGWHttpFailureReasonProducerTest' '--tests' 'com.nextgis.maplib.map.NGWSyncNoneServerFailureTest' '--tests' 'com.nextgis.maplib.util.NgwHttpExceptionTest' '--tests' 'com.nextgis.maplib.util.NgwHttpResponseExceptionTest' '--tests' 'com.nextgis.maplib.util.NetworkUtilTransientNgwTest' ':app:testLisaDebugUnitTest' '--tests' 'com.nextgis.mobile.util.SyncFailurePresentationTest' '--tests' 'com.nextgis.mobile.util.ProjectSyncLateFailureTest' '--tests' 'com.nextgis.maplib.datasource.ngw.ResourceChildrenMessagesTest' '--tests' 'com.nextgis.maplibui.util.LayerFillErrorMessageTest' '--tests' 'com.nextgis.maplibui.activity.FormLegacyListMessagesTest'
$taskUnitExit=$LASTEXITCODE
if($taskUnitExit -ne 0){throw "User-message source tests failed with exit $taskUnitExit; compile was not started."}
& '.\gradlew.bat' '--offline' '--no-daemon' '-Pkotlin.incremental=false' '-PciReliabilityChecks=true' '--console=plain' ':app:compileBelkaReleaseJavaWithJavac' ':app:compileBelkaReleaseKotlin'
$taskCompileExit=$LASTEXITCODE
if($taskCompileExit -ne 0){throw "Source compilation failed with exit $taskCompileExit."}
```

Exact current sources/tests/resources версионируются в owning repositories;
App held F61/E66 pointers отдельно от live-library source validation. Core63/UI46
Merge Commit должен предшествовать App pointers/clock semantic review/docs checks.
Source Draft delivery не означает merge, installed APK, publication или acceptance
телефона/реального сервера; новые runtime stages этим recipe не разрешены.

Фактическая квалификация моделей без повторного запуска: Core46 состоит из37 Robolectric/API28 и9 plainJUnit (NgwHttpExceptionTest5 + existing NetworkUtil4), App43 — Robolectric/API28. Итого80 моделей и9 plainJUnit;89PASS/Gradle0/все exact inputs и stop receipts неизменны. Первичный immutable Root receipt описал5 plain как модели; его JSON не переписан, эта пометка исправляет только подпись охвата.

### Закрытие source-среза сообщений — 30 октября2026

После semantic review документов global dependency index проверен CURRENT:
5761 files / 78853 edges, source SHA256
`3742037ad3e46bdbb2183c026b451b4abfa69a0d81ccc6c9aa8fa4b6207d71c1`.
Basis: own Root/App/Core/UI working trees, foreign easypicker/publisher committed
HEAD snapshots; 188 historical observations сохранены побайтно. Полнота связей
остаётся UNKNOWN. Final impact сопоставляет все36 changed source/test/resource
paths; это план, не исполнение дополнительных сценариев.

App docs-check:26 policy tests PASS; local UI validator оставляет ровно прежний
WF-SYNC-CLOCK-STORE. Отдельные57 docs tests:53PASS/4fail того же F61 clock contract.
Его ожидание не подгонялось под live unmerged library. Root docs validator:
ровно одна прежняя ошибка frontmatter в excluded Wiki backup REVERT.md; Wiki
не менялась. Поэтому полные docs suites не объявляются PASS.

После metadata review из68 inputs выполненных89/compile проверок неизменны67
behavioral/source/runtime inputs; единственное намеренное изменение — reviewed
UI contract catalogue. Код/тесты после89PASS и Belka source compile не менялись.
Fresh fetch всех6 repositories успешен, owned branches ahead/behind0/0 перед
коммитом. App gitlinks F61/E66 сохраняются до library Merge Commit; source Draft
не является готовым APK. Report guards пройдены отдельно от docs limitations.

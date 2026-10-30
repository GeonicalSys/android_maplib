---
id: maplib-20261030-full-snapshot-http206
area: Надёжность синхронизации NGW
kind: user
at: '2026-10-30T07:59:34.596633+00:00'
title: Явно частичный ответ не применяется как полный набор данных
why: Полный потоковый pull принимал206 за авторитетный список без доказательства полноты.
covers:
- src/main/java/com/nextgis/maplib/map/NGWVectorLayer.java
- src/test/java/com/nextgis/maplib/map/NGWVectorLayerFullSnapshotHttpStatusTest.java
- AGENTS.md
- docs/README.md
- docs/manifest.yaml
---

Явно частичный ответ сервера больше не используется как полный набор объектов
в одном потоковом NGW pull. Ранее route допускал любой2xx, в том числе206, хотя
не посылал Range и не собирал/проверял диапазоны. Изменение подготовлено в исходниках
Draft PR #63; установленное приложение получит его после обычного выпуска.

Только downloadFullSnapshot добавляет один IO и failed206 сразу после response code,
до error/input stream, Content-Length и нового stage. Предыдущий local push и
prefix cleanup до GET, другие2xx/404/transient/tracked routes прежние. Outer file-null
не допускает применения этого полного снимка через scan/backup/SQL; это не отказ
до всех SQL/cleanup. RFC9110§15.3.7 допускает диапазон, покрывающий всё представление,
но этот route не доказывает такой полноты. Actual tenant206/потеря данных не наблюдались.

Core4 actual-private-downloader SDK28: baseline1 AssertionFAIL/3controls/0technical,
затем4PASS/0fail/error/skips, XML1.905с. Baseline206 остановился на первом assertNull
nonnullstage; прочие stream/length/error/IO guards исполнились в GREEN. Controls200,
203 и404 сохранены. Technical []/connection/account не используют HTTP socket,
SQLite/map/native/full apply или бизнес-данные. App15 и source-level ACK/clock цепочка
описаны отдельной новой App internal карточкой; helper не выдаётся за их исполнение.

Shared19 baseline4 named assertions/15controls →19PASS/0errors/skips, Core1.905с/
App2.298с, Gradle0/35с. Старые keyset9/clock19/journal22/storage21/prefetch12 и
1117/1192 не запускались. Belka source compile0/39с:100 unique task SET равен
reviewed100, порядок иной;87actionable6executed81up-to-date. App Java/Kotlin и
changed Maplib Java исполнились. Release4 Java JAR28/530/669/45 classes без dex/so/
APK/AAB/AAR;7 owned PID завершились естественно, source24/runtime12/UI3981 прежние.

Первый compile-dry observer потерял native exit из-за Get-FileHash postdiagnostic
сбоя: эта попытка UNKNOWN/NOT_PASS,9 процессов завершились. Root исправил только
owned Q helper (native-exit-first/local.NETSHA), повторил только dry0/11с и затем
actual compile один раз0/39с. Нет retroactive/allgatesPASS, global module/permission
правок. Старый prefetch sequence NOT_PASS остаётся историческим.

Источники: vector ca59e5a0771b9798f5a1e7fabea6ea938c97de8136272ab03dffa463bbf9d19f;
Core test7ae829c25e4465dac18b18f1d65771d538d59e01839f82d14c008f1796e78867.
Inverse четырёх строк восстанавливает f20a33f6 целиком. Baseline55bf/counter46b,
GREEN07cf и final compile2aa45 имеют компактные audit receipts; источник и тест
версионируются здесь, эти scratch receipts не являются runtime dependency.

Partial200/tracked/initial-import/count races остаются в общем
AUDIT-TODO-NGW-PARTIAL-SNAPSHOT; disk/retry/samehash/account27 отдельно. UI39/142/19/F61
не перепривязан. Metadata closure ниже сохраняет прежние App clock
и excluded Root wiki failures. Source Draft доставка разрешена,
но Merge Commit Core до App pointer и обычный APK release/native acceptance —
отдельные этапы; merge/release/deploy здесь не исполнялись.

### Воспроизведение технического source-среза

Из Android root с gradlew.bat. Установлены JBR21.0.9+-14787801-b1163.94,
Gradle9.3.1/AGP9.1.0, Robolectric4.16.1, JUnit4.13.2, API28
android-all-instrumented9-robolectric-4913185-2-i7 (SHA256
`dbb1c88987b54cef3132695a1b4d2f4851ba06286a6fa7d15772f98fd1f0cdf7`).
Переиспользуются установленные JBR и Q caches; новых SDK/dependency/runtime копий нет.
Перед отдельно разрешённым запуском проверяется тот же graph с --dry-run:
только source/unit resources и exact Java JAR; без CMake/NDK/product APK/assemble/
sign/install/device/newdeps. Unit-resource packages не являются сборкой product APK.
--continue предназначен только для сбора результатов двух фильтров после всех
prelaunch gates, не для обхода nonzero/неизвестного native exit.

Эта public recipe предназначена для текущего исправленного source и ожидает exit0.
Intentional baselineRED с ожидаемым exit1 — отдельный bounded stage; он не должен
переходить к компиляции через эту recipe или обходить проверку результата.

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
& '.\gradlew.bat' '--offline' '--no-daemon' '--continue' '-Pkotlin.incremental=false' '-PciReliabilityChecks=true' '--console=plain' ':maplib:testDebugUnitTest' '--tests' 'com.nextgis.maplib.map.NGWVectorLayerFullSnapshotHttpStatusTest' ':app:testLisaDebugUnitTest' '--tests' 'com.nextgis.mobile.util.NgwLookupCacheSyncTest'
$taskUnitExit=$LASTEXITCODE
if($taskUnitExit -ne 0){throw "Shared19 failed with exit $taskUnitExit; no compile was started."}
& '.\gradlew.bat' '--offline' '--no-daemon' '-Pkotlin.incremental=false' '-PciReliabilityChecks=true' '--console=plain' ':app:compileBelkaReleaseJavaWithJavac' ':app:compileBelkaReleaseKotlin'
$taskCompileExit=$LASTEXITCODE
if($taskCompileExit -ne 0){throw "Source compile failed with exit $taskCompileExit."}
```

Это точные два actual argument arrays: shared unit12 args (два фильтра/--continue)
и compile7 args без --continue/dry-run. Команды не требуют Q-only fixture/script:
тесты находятся в обычных owner source paths. LisaDebug compile был частью shared19;
Belka команды принимают только current source Java/Kotlin, не whole APK/release.

Source/docs closure: Core report guard56cards/5source paths PASS. App report
guard128/10 и26 policy tests PASS19.097с; полный App docs gate NOT_PASS только
на прежнем WF-SYNC-CLOCK-STORE (held F61), отдельные57 docs tests53PASS/4same
clock failures48.191с. Root docs NOT_PASS только на исключённом пользователем
Wiki REVERT.md frontmatter. Wiki/clock expectations не менялись. Plan26 exit0,
source/test4 MAPPED, semantic UNKNOWN. Shared index --write0/80.673с и --check0/
77.941с:5742files/source2cd5db27, четыре own live repos + два foreign committed
HEAD, все188 observations byte-exact. Это не новое runtime/full acceptance.

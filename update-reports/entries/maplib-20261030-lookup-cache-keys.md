---
id: maplib-20261030-lookup-cache-keys
area: Синхронизация справочников
kind: user
at: '2026-10-30T03:19:38.383994Z'
title: Замена ключей справочника обновляет кеш без ошибки
why: Одинаковое число строк скрывало изменившийся набор ключей и прерывало синхронизацию
covers:
- src/main/java/com/nextgis/maplib/map/NGWLookupTable.java
- AGENTS.md
- docs/README.md
- docs/manifest.yaml
---

Разобранный текущим read path набор с новым ключом теперь обновляет прежний кеш
при том же количестве пар. Ненулевое remote значение Hashtable сравнивается
с локальным, поэтому отсутствующий ключ означает различие. API, остальные
bytes/порядок/identity/read-failure/savefalse/версия/clock сохранены.

Сопутствующий docs-only аудит owner pack уточняет source-контракт geometry
выноса (3857 nearest facet и явный sphere fallback), не меняя продукт или
политику восстановления. Подробности — отдельная App internal stakeout card;
этот docs контекст не объявляется новым пользовательским поведением.

Actual public sync: baseline9, один meaningful AssertionFAIL из missing-key
NPE и8 PASS, errors/skips0. После одной expression-замены те же9 PASS,
errors/skips0, XML2.066с, Gradle0/32с. BelkaRelease Java/Kotlin0/37с, graph100
без запрещённых задач; LisaDebug compile входит в unit graph133. Независимый
actual-source/XML/compile review CLEAR. Тестовые fill/save seams используют
технические String maps; partial-read control явно выбрасывает IOException.
Успешный valid-but-incomplete/silent partial ответ не проверен на полноту
(AUDIT-TODO-NGW-PARTIAL-SNAPSHOT UNKNOWN). HTTP/tenant/device/AVD/SQLite/GIS/business data не
используются. False-save control фиксирует существующую memory/result policy,
а не disk ACK. Старые19/22/21 suites не запускались; desktop1117/1192 не изменены.

Current-source basis: own live maplib8ca и App testdcc7, не acceptance App с
committed F61 gitlink. Merge Commit библиотеки → scoped App pointer после merge
и отдельная device/NGW приёмка остаются зависимостями доставки. UI clock F61 не
перепривязан. Доставка source/docs в собственные Draft PR library63/App86 разрешена в
этой задаче и выполняется штатно после необходимых source/docs checks.
Commit/push/Draft не являются merge, выпуском APK или deploy и не принимают
App с parent F61 как исправленную поставку. Merge/release/deploy/native
acceptance не выполнялись. После C-интеграции report guards: App126 cards/9
source paths, maplib54/3, Root377/35 — PASS. Policy26 PASS18.495с. Полный
App validator NOT_PASS: ровно сохранённый WF-SYNC-CLOCK-STORE drift;
UI39/142/19/11 groups принят. Docs57:53 PASS/4 FAIL46.177с, все четыре —
тот же clock guard. Root docs NOT_PASS: ровно прежний excluded wiki frontmatter.
Source index write/check CURRENT: own root/App/maplib live, остальные три —
committed HEAD basis; semantic completeness UNKNOWN. Общего validation PASS
и native/disk/device/release приёмки эти результаты не заявляют.

Из корня Android, с установленными JBR21.0.9+-14787801-b1163.94, Gradle9.3.1,
AGP9.1.0, Robolectric4.16.1, JUnit4.13.2/API28, сначала проверить --dry-run
тех же аргументов: нет CMake/NDK/native/assemble/install/sign/minify/device/
network/download tasks. Java library JAR и необходимые AGP unit-resource
archives разрешены, product APK не собирался. LisaDebug current-source compile
входит в unit run; BelkaRelease ниже только Java/Kotlin source compile.

```powershell
$env:TEMP='Q:\Codex\Temp'
$env:TMP='Q:\Codex\Temp'
$env:TMPDIR='Q:\Codex\Temp'
$env:PYTHONDONTWRITEBYTECODE='1'
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$env:GRADLE_USER_HOME='Q:\Codex\Cache\gradle'
$env:JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=Q:/Codex/Temp -Drobolectric.offline=true -Drobolectric.dependency.dir=Q:/Codex/Cache/maven/org/robolectric/android-all-instrumented/9-robolectric-4913185-2-i7'
& '.\gradlew.bat' '--offline' '--no-daemon' '-Pkotlin.incremental=false' '-PciReliabilityChecks=true' '--console=plain' ':app:testLisaDebugUnitTest' '--tests' 'com.nextgis.mobile.util.NgwLookupCacheSyncTest'
& '.\gradlew.bat' '--offline' '--no-daemon' '-Pkotlin.incremental=false' '-PciReliabilityChecks=true' '--console=plain' ':app:compileBelkaReleaseJavaWithJavac' ':app:compileBelkaReleaseKotlin'
```
Переиспользован SDK28 android-all-instrumented9-robolectric-4913185-2-i7.jar,
SHA256 dbb1c88987b54cef3132695a1b4d2f4851ba06286a6fa7d15772f98fd1f0cdf7.
Новых runtime/dependency copies/downloads нет; Q caches/JBR reuse проверены
по12 exact inputs. Собственные процессы остановились естественно, stop readback
PASS; компактные logs/XML/hash/stop receipts удерживаются до PR review, bulk0.

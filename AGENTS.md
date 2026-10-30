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

## Git-доставка

Запрос изменить/исправить/добавить содержимое репозитория разрешает агенту
создать `codex/*` ветку, выполнить проверки, scoped commits, push и открыть
Draft PR без отдельных подтверждений каждого шага. Read-only запросы этого не
разрешают. Direct push в `master`, force push, tag/release и merge запрещены без
явного намерения пользователя завершить выпуск. PR библиотеки сливается через
Merge Commit; связанный root PR обновляет submodule pointer после merge.

## Реестр обновлений для отчёта

Доработка, которую нужно объяснить в отчёте заказчику или во внутреннем
списке для оплаты, получает карточку `update-reports/entries/<id>.md` в том
же PR. Префикс id: `maplib-`. Срезы живут в монорепозитории `lisa`
(`docs/update-reports/`). Граница — номера карточек, не дата мержа.

## Project scripts

`forms/ConditionalRequiredRules` — чистый типизированный parser/evaluator
`meta.json.lisa_form_rules`; читать `../docs/architecture/conditional-form-rules.md`.
Не смешивать с JS, каскадами, GIS-запросами или UI. Соблюдать пределы формата,
missing-семантику и числовые 0/1 для checkbox; static required сохранять для видимых полей. V2 visible не меняет
значения/схему; hidden scope и Save eligibility определяет UI по тем же conditions.
V1 остаётся совместимым. Обе коллекции делят budget, лишние ключи и ошибки
типов отклоняются. Element ID — переносимое буквенное имя без цифр.

Зависимые списки: `forms/CascadingLists` владеет чистым parser/state engine;
читать `../docs/architecture/cascading-form-lists.md`. Не добавлять UI/JS или
Access-specific правила в parser; циклы/неверные варианты проверяются нативно.

Владеет форматом пакета/ссылки, private project cache, isolated QuickJS JNI/service и read-only host broker. `gis.query` и `time.monthWindow` встроены; spatial/расчётные/table API пока не реализованы.

Перед доработкой читать `../docs/architecture/project-scripts.md` и пользовательское
руководство. Новый host API добавлять с capability/grants/типами/бюджетами/тестами
сначала в APK. Не поставлять arbitrary SQL, Java reflection, сеть или GIS-движок
внешним JS. Старые пакеты сохранять для pin черновиков. Cross-repo schema/API
обновлять одновременно с stand_project и central registries.

## Общая синхронизация проектов

Читать consuming root docs/architecture/ngw-sync-and-storage.md и
docs/guides/project-synchronization-user-guide.md. sync_all_projects по умолчанию true;
ручной и scheduled account запуск используют ProjectSyncRunner. Не переключать
mMap/active prefs ради фонового проекта. Владельца переносить через
SyncWorkspaceSession во все async callbacks, service tickets и provider URI;
untagged UI URI всегда относится к активной карте, expired token не имеет fallback.
Очередь держит глобальный lease до завершения дочерних работ и mutating HTTP.
Полный pending project/account план сохраняется до первого прохода; Collector
journals разделены по canonical map path. Проверять cancellation, equal layer/group
IDs, сохранность draft и реальный fill на изолированном эмуляторе.
Подписи разделённых double_combobox брать из meta.fields keyname/display_name,
затем layer alias; field key используется только при отсутствии обоих.



`ProjectSyncState` публикует новое время только после `commit() == true` для
точного объекта SharedPreferences и canonical project key. Сохранять подтверждённое
значение до сборки владельца GC; identity registry не удерживает prefs/context/editor.
Короткий lock состояния не вызывает preferences/editor/context; отдельный writer
monitor не используется читателями. Вложенные вызовы выбирают последнюю успешную
invocation, а не максимальный timestamp и не последний завершившийся callback.
Не менять API, имя файла, UUID-ключи и long-формат; не добавлять компенсирующие
записи. `false` может уже изменить cache и оставить на диске старое либо новое
значение: защита чтения действует только в текущем процессе для этого владельца,
не обещает межпроцессный rollback. Все записи этого namespace вести через API.


## Обязательный контроль реестра перед push

Новая самостоятельная доработка получает новый ID карточки и `covers` с
точными путями всех её изменённых исходников/тестов/скриптов. Старый ID
не дописывать новым поведением. Для служебного исключения нужен новый
`exemptions/*.yaml` с точными путями и конкретной причиной.
Перед commit и push выполнить `py -3.13 -B tools/check_update_reports.py`.
В начале каждой задачи агент сам проверяет, подключён ли `.githooks/pre-push`
в этом клоне. Если hook ещё не подключён, агент без отдельного запроса
пользователю выполняет `py -3.13 tools/check_update_reports.py --install-hook`.
Если выбранному Python не хватает PyYAML, агент сам устанавливает зависимость
из `tools/requirements-update-reports.txt` и повторяет подключение. Штатный
installer сохраняет чужую конфигурацию hooks; её нельзя перезаписывать вслепую.
Не перекладывать эту настройку на пользователя или агента другого компьютера.
Обычный push блокируется hook при пропуске или ошибке. Запрещено обходить
его `--no-verify`, менять политику либо выдавать фиктивное исключение ради
зелёной проверки. Проверка путей не заменяет содержательный review карточек.


## Запрошенные пользователем отчёты

Каждый запрошенный отчёт, включая черновик, аудит и сверку, агент сохраняет
в Git и отправляет в свою ветку/PR в рамках той же задачи. Результат не должен
оставаться только в чате, `outputs/` или `.local/`. Отчёты о разработках
экосистемы и их приложения хранятся в `lisa/docs/update-reports/drafts/<id>/`
с оглавлением; прочие отчёты — в репозитории-владельце по его установленному
формату. В финале обязательны прямые ссылки на файлы в Git и статус PR.
Сохранение черновика не закрывает ID карточек: `reported/` и `letters/`
создаются отдельно по команде «зафиксируй отчёт». Правила источников и секретов
применяются и к приложениям отчёта.

## Lookup-кеш и полный NGW snapshot

Перед изменением NGWLookupTable.sync или NGWVectorLayer.downloadFullSnapshot читать
`../docs/architecture/ngw-sync-and-storage.md`,
`../docs/guides/project-synchronization-user-guide.md` и связанные invariants.
Equal size не означает equal key set; сохранять nonnull remote Hashtable comparison,
тот же map owner, no-write для равного содержимого, SYNC_NONE и старый кеш при
обработанных IOException/NGException/JSONException. В двух ветвях save()==false
добавляет один numIoExceptions к текущему результату: память уже новая, один save,
исключения и пропуск записи при следующем равном ответе прежние. Не называть это
rollback, disk ACK или исправленным retry. Политика остаётся в центральном
AUDIT-TODO-NGW-LOOKUP-CACHE-PERSISTENCE.

Только полный streamed downloader отклоняет явный HTTP206 до error/input stream,
Content-Length и нового stage. Его предшествующая очистка prefix-файлов до GET,
предыдущая отправка правок и остальные response/404/transient/track routes прежние.
Не утверждать отказ до всех SQL/cleanup или полноту успешного200. Сохранять
AUDIT-TODO-NGW-PARTIAL-SNAPSHOT для остальных неизвестных ветвей.

Исторические App9/8ca проверки отделены от текущих App15 и Core4 в shared19.
Core4 вызывают actual private downloader без SQLite/map/network; App15 — actual
public sync с technical fill/save seams. Проверять compile consumers; source/JVM
не заменяют device/NGW/restart acceptance. Точная recipe в новой Core карточке
`update-reports/entries/maplib-20261030-full-snapshot-http206.md`.


## Причина сбоя и пользовательское сообщение

Читать consuming docs/architecture/ngw-sync-and-storage.md и UI contracts.
Передавать только известную причину HTTP в текущем точном account/result owner;
не определять её по локализованному сообщению. beginSync(result) и финальный
account Bundle относятся к одному вызову. Четыре primitive поля и пятый local
SyncResult Parcelable owner token не являются форматом IPC/Intent/хранения:
parcel copy, чужой result, поздний IO/Parse или неизвестная причина дают generic.
Очищать весь scope и output keys; не возвращать raw body, URL или exception в UI.

PUSH и lookup добавляют только metadata после прежнего IO/Parse increment;
SYNC_NONE известный server HTTP failure добавляет один IO за failed request,
сохраняя null/UNKNOWN/zero→KEEP. Full/deferred optional schema wrappers прежние.
ChildrenLoadResult отличает failed child list от successful empty и не меняет
legacy void API/Parcelable/sort/cache. NgwHttpException остаётся IOException,
NgwHttpResponseException — NGException: сохранять исходный diagnostic/localized
message и прежние catch/retry boundaries. Общий RU/EN server body принадлежит
Core error_server_temporarily_unavailable; возможные техработы не доказаны фактом5xx.
Source/JVM проверки не заменяют device, живой NGW или импорт/SQLite acceptance.

Temporary-server text recognition ограничена HTTP422; numeric429/5xx отдельны.
HTTP408 остаётся transient для retry, но слова external database в его body
не доказывают техработы. Не исправлять такой classifier ослаблением UI oracle.
